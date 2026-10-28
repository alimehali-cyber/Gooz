#version 300 es
// TEMPORARY GPU pipeline diagnostics (only used when the in-app DIAG view is not OFF).
// Each output texel reduces one block of the source texture(s) with texelFetch and returns
// exact float bits / counts in an RGBA32UI target that is read back with GL_RGBA_INTEGER.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;

uniform int u_Mode;      // 0 stats(A)  1 diff(A,B)  2 records(R)  3 higher-order fraction(A)  4 black-pixel classes(A=final,B=HDR,C=pre-tonemap)
                         // 5 record decode r/phi units(R)  6 record decode g/counts(R)  7 raw min/max x,y(R)  8 raw min/max z,w(R)
                         // 9 crossing classes(R): nonzero-but-invalid, all-zero  10 first nonzero / first valid raw crossing words(R)
uniform sampler2D u_A;
uniform sampler2D u_B;
uniform sampler2D u_C;
uniform usampler2D u_R;
uniform ivec2 u_Size;    // source size in texels (all sources of one mode have this size)
uniform ivec2 u_Grid;    // output grid size

layout(location = 0) out uvec4 o;

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
bool isShadowTexel(vec4 v) { return v.a <= 0.5 && dot(v.rgb, v.rgb) <= 1.0e-7; }

// Crossing decode identical to gargantuaUnpackCrossing (gargantua_geodesic.frag): the g half's sign bit
// carries the tier flag; a crossing is present when the tau half is non-zero.
bool crossingValid(uvec2 c) { return ((c.y & 0xFFFF7FFFu) & 0xFFFF0000u) != 0u; }

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 lo = cell * u_Size / u_Grid;
    ivec2 hi = (cell + 1) * u_Size / u_Grid;
    float fMin = 1.0e30;
    float fMax = 0.0;
    float fSum = 0.0;
    uint c0 = 0u; uint c1 = 0u; uint c2 = 0u; uint c3 = 0u; uint c4 = 0u; uint c5 = 0u;
    uint lastPos = 0u;
    float gMin = 1.0e30; float gMax = -1.0e30; float pMin = 1.0e30; float pMax = -1.0e30;
    uvec4 rawMin = uvec4(0xFFFFFFFFu); uvec4 rawMax = uvec4(0u);
    uvec2 firstNonzero = uvec2(0u); uvec2 firstValid = uvec2(0u);
    for (int y = lo.y; y < hi.y; y++) {
        for (int x = lo.x; x < hi.x; x++) {
            ivec2 p = ivec2(x, y);
            if (u_Mode == 0) {
                vec4 v = texelFetch(u_A, p, 0);
                float l = luma(v.rgb);
                fMin = min(fMin, l); fMax = max(fMax, l); fSum += l;
                if (any(notEqual(v.rgb, vec3(0.0)))) c0++;
            } else if (u_Mode == 1) {
                vec4 a = texelFetch(u_A, p, 0);
                vec4 b = texelFetch(u_B, p, 0);
                float d = luma(a.rgb) - luma(b.rgb);
                fSum += d * d; fMax = max(fMax, abs(d));
                bool changed = any(notEqual(a, b));
                if (changed) c0++;
                if (changed && isShadowTexel(b)) c1++;
                if (b.a >= 1.0 && luma(b.rgb) > 0.01) { c2++; fMin = min(fMin, 0.0); if (changed) c3++; }
            } else if (u_Mode == 2) {
                uvec4 r = texelFetch(u_R, p, 0);
                bool nonzero = any(notEqual(r, uvec4(0u)));
                bool hasHigh = ((r.y | r.w) & 0xFFFF0000u) != 0u;
                // Tier flags only (bit 15 of y = tier-1, of w = tier-2; no crossing recorded): not truncated.
                bool flagOnly = r.x == 0u && r.z == 0u && ((r.y | r.w) & 0xFFFF7FFFu) == 0u;
                if (nonzero) c0++;
                if (hasHigh) c1++;
                if (nonzero && !hasHigh && !flagOnly) c2++;
                if ((r.y & 0x8000u) != 0u) c3++;
            } else if (u_Mode == 3) {
                vec4 v = texelFetch(u_A, p, 0);
                float l = luma(v.rgb);
                if (v.a >= 1.0 && l > 1.0e-4) {
                    float f = clamp((v.a - 1.0) / l, 0.0, 1.0);
                    fSum += f; fMax = max(fMax, f); c0++;
                    if (f > 0.5) c1++;
                }
            } else if (u_Mode == 9 || u_Mode == 10) {
                uvec4 r = texelFetch(u_R, p, 0);
                for (int k = 0; k < 2; k++) {
                    uvec2 c = (k == 0) ? r.xy : r.zw;
                    bool nonzero = (c.x | c.y) != 0u;
                    bool valid = crossingValid(c);
                    if (nonzero && !valid) c0++;
                    if (!nonzero) c1++;
                    if (c.x == 0u && (c.y & 0xFFFF7FFFu) == 0u && c.y != 0u) c2++;
                    if (nonzero && all(equal(firstNonzero, uvec2(0u)))) firstNonzero = c;
                    if (valid && all(equal(firstValid, uvec2(0u)))) firstValid = c;
                }
            } else if (u_Mode >= 5) {
                uvec4 r = texelFetch(u_R, p, 0);
                rawMin = min(rawMin, r); rawMax = max(rawMax, r);
                for (int k = 0; k < 2; k++) {
                    uvec2 c = (k == 0) ? r.xy : r.zw;
                    if (!crossingValid(c)) continue;
                    vec2 rPhi = unpackUnorm2x16(c.x);
                    vec2 gTau = unpackHalf2x16(c.y & 0xFFFF7FFFu);
                    fMin = min(fMin, rPhi.x); fMax = max(fMax, rPhi.x);
                    pMin = min(pMin, rPhi.y); pMax = max(pMax, rPhi.y);
                    gMin = min(gMin, gTau.x); gMax = max(gMax, gTau.x);
                    c0++;
                    if (gTau.y < 0.0) c1++;
                    if (!(gTau.x > 0.0) || gTau.x > 65000.0) c2++;
                }
                if ((r.w & 0x8000u) != 0u) c3++;
            } else {
                vec4 fin = texelFetch(u_A, p, 0);
                if (max(fin.r, max(fin.g, fin.b)) < 3.0 / 255.0) {
                    vec4 h = texelFetch(u_B, p, 0);
                    vec4 pre = texelFetch(u_C, p, 0);
                    bool shadow = isShadowTexel(h);
                    bool isolated = false;
                    if (shadow && x > 0 && y > 0 && x + 1 < u_Size.x && y + 1 < u_Size.y) {
                        isolated = !isShadowTexel(texelFetch(u_B, p + ivec2(1, 0), 0)) &&
                            !isShadowTexel(texelFetch(u_B, p - ivec2(1, 0), 0)) &&
                            !isShadowTexel(texelFetch(u_B, p + ivec2(0, 1), 0)) &&
                            !isShadowTexel(texelFetch(u_B, p - ivec2(0, 1), 0));
                    }
                    if (isolated) { c3++; lastPos = uint(x) | (uint(y) << 16); }             // D sampling hole
                    else if (shadow && h.a < 0.25) { c0++; }                                   // A captured
                    else if (shadow) { c1++; lastPos = uint(x) | (uint(y) << 16); }            // B unresolved
                    else if (luma(h.rgb) < 1.0e-3) { c2++; }                                   // C empty space
                    else {                                                                      // E post-processing
                        c4++; lastPos = uint(x) | (uint(y) << 16);
                        if (max(pre.r, max(pre.g, pre.b)) <= 0.0) c5++;                        // zeroed before ACES
                    }
                }
            }
        }
    }
    if (u_Mode == 0 || u_Mode == 3) {
        o = uvec4(floatBitsToUint(fMin), floatBitsToUint(fMax), floatBitsToUint(fSum), (u_Mode == 0) ? c0 : (c0 | (c1 << 16)));
    } else if (u_Mode == 1) {
        o = uvec4(floatBitsToUint(fSum), floatBitsToUint(fMax), c0 | (c1 << 16), c2 | (c3 << 16));
    } else if (u_Mode == 2) {
        o = uvec4(c0, c1, c2, c3);
    } else if (u_Mode == 5) {
        o = uvec4(floatBitsToUint(fMin), floatBitsToUint(fMax), floatBitsToUint(pMin), floatBitsToUint(pMax));
    } else if (u_Mode == 6) {
        o = uvec4(floatBitsToUint(gMin), floatBitsToUint(gMax), c0 | (c1 << 16), c2 | (c3 << 16));
    } else if (u_Mode == 7) {
        o = uvec4(rawMin.x, rawMax.x, rawMin.y, rawMax.y);
    } else if (u_Mode == 8) {
        o = uvec4(rawMin.z, rawMax.z, rawMin.w, rawMax.w);
    } else if (u_Mode == 9) {
        o = uvec4(c0, c1, c2, 0u);
    } else if (u_Mode == 10) {
        o = uvec4(firstNonzero, firstValid);
    } else {
        o = uvec4(c0 | (c1 << 16), c2 | (c3 << 16), c4 | (c5 << 16), lastPos);
    }
}

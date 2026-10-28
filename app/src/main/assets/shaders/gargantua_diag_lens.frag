#version 300 es
// TEMPORARY lensing diagnostics: one RGBA32UI word set per geodesic ray pixel, read back once.
// u_NoDisk is the production trace program redrawn with u_EnableDisk = 0 (same camera, same rays), so
// its alpha is the pure geodesic classification: captured 0, escaped (sky) 1, unresolved 0.5 (M7
// averages give fractions at the boundary). The records are the production r0 cache (disk ON).
//   x: bits 0-1 class (0 captured, 1 escaped, 2 mixed)  2-3 valid crossings in r0  4 HO crossing 1
//      5 HO crossing 2  6 D0 dark  7 D5 dark  8 D6 dark  9-10 D0 alpha class  16-31 noDisk alpha*1000
//   y: r0.x (crossing 1 packUnorm2x16 r/phi)  z: r0.y (crossing 1 half g/tau)  w: r0.z (crossing 2 r/phi)
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;

uniform sampler2D u_NoDisk;
uniform sampler2D u_Hdr;
uniform sampler2D u_Pre;
uniform sampler2D u_Final;
uniform usampler2D u_R0;
uniform int u_HasRecords;
uniform ivec2 u_Res;

layout(location = 0) out uvec4 o;

ivec2 at(ivec2 sz, ivec2 p) {
    return clamp(ivec2((vec2(p) + 0.5) * vec2(sz) / vec2(u_Res)), ivec2(0), sz - 1);
}

uint alphaClass(float a) {
    return a < 0.25 ? 0u : (a >= 0.75 ? 1u : 2u);
}

void main() {
    const vec3 W = vec3(0.2126, 0.7152, 0.0722);
    ivec2 p = ivec2(gl_FragCoord.xy);
    float a = texelFetch(u_NoDisk, p, 0).a;
    uvec4 r = (u_HasRecords != 0) ? texelFetch(u_R0, p, 0) : uvec4(0u);
    bool v1 = (r.y & 0xFFFF0000u) != 0u;
    bool v2 = (r.w & 0xFFFF0000u) != 0u;
    uint n = (v1 ? 1u : 0u) + (v2 ? 1u : 0u);
    uint ho1 = (v1 && (r.y & 0x80000000u) != 0u) ? 1u : 0u;
    uint ho2 = (v2 && (r.w & 0x80000000u) != 0u) ? 1u : 0u;
    vec4 d0 = texelFetch(u_Hdr, at(textureSize(u_Hdr, 0), p), 0);
    vec3 d5 = texelFetch(u_Pre, at(textureSize(u_Pre, 0), p), 0).rgb;
    vec3 d6 = texelFetch(u_Final, at(textureSize(u_Final, 0), p), 0).rgb;
    uint dark0 = dot(d0.rgb, W) < 1.0e-3 ? 1u : 0u;
    uint dark5 = dot(d5, W) < 1.0e-3 ? 1u : 0u;
    uint dark6 = max(d6.r, max(d6.g, d6.b)) < 3.0 / 255.0 ? 1u : 0u;
    uint aq = uint(clamp(a, 0.0, 65.0) * 1000.0 + 0.5);
    o.x = alphaClass(a) | (n << 2) | (ho1 << 4) | (ho2 << 5) | (dark0 << 6) | (dark5 << 7) | (dark6 << 8) |
        (alphaClass(d0.a) << 9) | (aq << 16);
    o.y = r.x;
    o.z = r.y;
    o.w = r.z;
}

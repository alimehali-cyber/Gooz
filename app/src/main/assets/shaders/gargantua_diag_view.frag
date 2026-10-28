#version 300 es
// TEMPORARY GPU pipeline diagnostics: fullscreen display of one intermediate texture.
// No tone mapping, bloom, palette or animation is applied here.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;

uniform int u_Mode;          // 0 linear RGB clamp(v*scale)  1 ray-record false colour  2 higher-order/total grayscale
                             // 3 lensing views L1..L6 of the lens words in u_R (gargantua_diag_lens.frag)
uniform int u_LensMode;      // 1 capture  2 disk hit  3 r_hit  4 phi_hit  5 HO count  6 shadow boundary
uniform sampler2D u_A;
uniform usampler2D u_R;
uniform float u_Scale;
uniform ivec2 u_Size;
uniform int u_MarkerCount;
uniform vec2 u_Markers[32];  // marker positions in [0,1] texture coordinates
uniform vec2 u_Screen;       // destination size in pixels

in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    vec3 c;
    if (u_Mode == 3) {
        ivec2 p = clamp(ivec2(v_TexCoord * vec2(u_Size)), ivec2(0), u_Size - 1);
        uvec4 l = texelFetch(u_R, p, 0);
        uint cls = l.x & 3u;
        uint n = (l.x >> 2) & 3u;
        float rU = float(l.y & 0xFFFFu) / 65535.0;
        float phi = (float(l.y >> 16) / 65535.0 - 0.5) * 6.2831853;
        uint ho = ((l.x >> 4) & 1u) + ((l.x >> 5) & 1u);
        if (u_LensMode == 1) {
            c = cls == 0u ? vec3(0.0) : (cls == 1u ? vec3(0.2, 0.35, 0.9) : vec3(1.0, 0.85, 0.1));
        } else if (u_LensMode == 2) {
            c = n == 0u ? (cls == 0u ? vec3(0.25, 0.0, 0.0) : vec3(0.08)) : (n == 1u ? vec3(0.9) : vec3(0.3, 1.0, 0.3));
        } else if (u_LensMode == 3) {
            c = n == 0u ? vec3(0.0) : vec3(rU);
        } else if (u_LensMode == 4) {
            c = n == 0u ? vec3(0.0) : 0.5 + 0.5 * vec3(cos(phi), cos(phi - 2.0944), cos(phi + 2.0944));
        } else if (u_LensMode == 5) {
            c = ho == 0u ? (n == 0u ? vec3(0.0) : vec3(0.18)) : (ho == 1u ? vec3(1.0, 0.55, 0.0) : vec3(1.0, 0.0, 0.0));
        } else {
            bool edge = false;
            bool edge0 = false;
            bool edge6 = false;
            uint dark0 = (l.x >> 6) & 1u;
            uint dark6 = (l.x >> 8) & 1u;
            for (int k = 0; k < 4; k++) {
                ivec2 q = clamp(p + ivec2(k == 0 ? 1 : (k == 1 ? -1 : 0), k == 2 ? 1 : (k == 3 ? -1 : 0)), ivec2(0), u_Size - 1);
                uint m = texelFetch(u_R, q, 0).x;
                edge = edge || ((m & 3u) == 0u) != (cls == 0u);
                edge0 = edge0 || ((m >> 6) & 1u) != dark0;
                edge6 = edge6 || ((m >> 8) & 1u) != dark6;
            }
            c = cls == 0u ? vec3(0.12) : vec3(0.0);
            if (edge6) c = vec3(1.0, 0.0, 1.0);
            if (edge0) c = vec3(0.0, 1.0, 0.0);
            if (edge) c = vec3(1.0);
        }
    } else if (u_Mode == 1) {
        ivec2 p = clamp(ivec2(v_TexCoord * vec2(u_Size)), ivec2(0), u_Size - 1);
        uvec4 r = texelFetch(u_R, p, 0);
        bool nonzero = any(notEqual(r, uvec4(0u)));
        bool hasHigh = ((r.y | r.w) & 0xFFFF0000u) != 0u;
        // R = hit radius (unorm16), G = hit azimuth (unorm16), B = valid crossing (high halves set).
        c = vec3(float(r.x & 0xFFFFu) / 65535.0, float(r.x >> 16) / 65535.0, hasHigh ? 1.0 : 0.0);
        bool flagOnly = r.x == 0u && r.z == 0u && ((r.y | r.w) & 0xFFFF7FFFu) == 0u;
        if (nonzero && !hasHigh && !flagOnly) c = vec3(1.0, 0.0, 1.0); // truncated record signature
    } else {
        vec4 v = texture(u_A, v_TexCoord);
        if (u_Mode == 2) {
            float l = dot(v.rgb, vec3(0.2126, 0.7152, 0.0722));
            c = vec3((v.a >= 1.0 && l > 1.0e-4) ? clamp((v.a - 1.0) / l, 0.0, 1.0) : 0.0);
        } else {
            c = clamp(v.rgb * u_Scale, 0.0, 1.0);
        }
    }
    vec2 px = v_TexCoord * u_Screen;
    for (int i = 0; i < 32; i++) {
        if (i >= u_MarkerCount) break;
        vec2 d = abs(px - u_Markers[i] * u_Screen);
        if ((d.x < 1.0 && d.y < 9.0) || (d.y < 1.0 && d.x < 9.0)) {
            c = (i < 10) ? vec3(0.0, 1.0, 1.0) : ((i < 12) ? vec3(1.0, 0.2, 0.2) : vec3(0.3, 1.0, 0.3));
        }
    }
    fragColor = vec4(c, 1.0);
}

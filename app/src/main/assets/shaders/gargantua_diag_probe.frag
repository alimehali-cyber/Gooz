#version 300 es
// TEMPORARY GPU pipeline diagnostics: exact texel values at probe points (row 0) or along the
// fixed radial scan lines of the photon-ring probe (rows 1..12), written as raw bits.
precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;

uniform int u_Kind;          // 0 float texture u_A, 1 unsigned record texture u_R
uniform sampler2D u_A;
uniform usampler2D u_R;
uniform ivec2 u_Size;        // source size in texels
uniform int u_PointCount;
uniform vec2 u_Points[48];   // probe points in source texels
uniform vec2 u_ScanCenterSt; // shader st coordinates of the scan centre
uniform float u_ScanR0;
uniform float u_ScanDr;
uniform vec2 u_Res;          // the geodesic ray/render resolution the st coordinates refer to

layout(location = 0) out uvec4 o;

void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 p;
    if (cell.y == 0) {
        if (cell.x >= u_PointCount) { o = uvec4(0u); return; }
        p = ivec2(u_Points[cell.x]);
    } else {
        float angle = radians(15.0 + 30.0 * float(cell.y - 1));
        vec2 st = u_ScanCenterSt + vec2(cos(angle), sin(angle)) * (u_ScanR0 + u_ScanDr * float(cell.x));
        vec2 texel = (st * min(u_Res.x, u_Res.y) + u_Res) * 0.5;
        p = ivec2(floor(texel * vec2(u_Size) / u_Res));
    }
    p = clamp(p, ivec2(0), u_Size - 1);
    if (u_Kind == 1) {
        o = texelFetch(u_R, p, 0);
    } else {
        o = floatBitsToUint(texelFetch(u_A, p, 0));
    }
}

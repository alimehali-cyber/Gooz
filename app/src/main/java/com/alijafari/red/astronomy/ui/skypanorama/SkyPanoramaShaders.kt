package com.alijafari.red.astronomy.ui.skypanorama

/**
 * GLSL ES 3.00 sources for the panorama pass. Written from first principles for this renderer.
 *
 * The vertex shader emits one oversized triangle that covers the viewport, so no vertex buffer is
 * needed. The fragment shader converts each pixel to an equatorial view direction using the
 * uniform camera basis, converts that direction to NASA celestial-map texture coordinates, and
 * samples the panorama with an explicit, screen-uniform LOD.
 *
 * Mapping contract (see [SkyPanoramaMath]):
 *   u = fract(0.5 - RA / 2pi), v = 0.5 - Dec / pi
 * The horizontal wrap is handled by GL_REPEAT on S; the seam at RA = +/-pi therefore samples
 * neighbouring texels without a derivative discontinuity, because the LOD is a uniform.
 */
object SkyPanoramaShaders {

    const val VERTEX_SHADER = """#version 300 es
precision highp float;
precision highp int;

out vec2 vNdc;

void main() {
    // Vertices (-1,-1), (3,-1), (-1,3) form a triangle that covers the whole clip square.
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vNdc = p * 2.0 - 1.0;
    gl_Position = vec4(vNdc, 0.0, 1.0);
}
"""

    const val FRAGMENT_SHADER = """#version 300 es
precision highp float;
precision highp int;

precision highp sampler2D;

in vec2 vNdc;
out vec4 fragColor;

uniform sampler2D uPanorama;
uniform vec3 uForward;
uniform vec3 uRight;
uniform vec3 uUp;
uniform vec2 uTanHalf;
uniform float uLod;
uniform float uExposure;
uniform float uSaturation;
uniform float uContrast;
uniform float uVisibility;
// Daytime palette (sRGB 0..1) and night blend. uNightWeight 0 = daytime gradient only,
// 1 = photographic panorama only (exactly the Phase 1 output).
uniform vec3 uSkyZenith;
uniform vec3 uSkyMid;
uniform vec3 uSkyHorizon;
uniform float uNightWeight;

const float PI = 3.14159265358979323846;
const float MID_STOP = 0.55;
const float HORIZON_STOP = 0.86;

void main() {
    // Equatorial direction of this pixel. +right is west, +up is north (see SkyPanoramaMath).
    vec3 dir = normalize(uForward + vNdc.x * uTanHalf.x * uRight + vNdc.y * uTanHalf.y * uUp);

    // RA is eastward-positive in -PI..PI. The branch cut at +/-PI lands on the GL_REPEAT seam.
    float ra = atan(dir.y, dir.x);
    float dec = asin(clamp(dir.z, -1.0, 1.0));

    // NASA celestial plate-carree: RA 0h at the centre column, RA increasing to the left,
    // north at the first row (t = 0 for an unflipped upload).
    vec2 uv = vec2(fract(0.5 - ra / (2.0 * PI)), 0.5 - dec / PI);

    vec3 c = textureLod(uPanorama, uv, uLod).rgb;

    // Restrained, display-referred adjustments. All values are uniforms, not constants.
    c *= uExposure;
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c = mix(vec3(luma), c, uSaturation);
    c = (c - 0.5) * uContrast + 0.5;
    c *= uVisibility;

    // Daytime sky: the same vertical stops as the Compose Real Sky gradient (top = zenith). The top of
    // the viewport is NDC y = +1, so the fraction from the top is 0.5 - 0.5 * y.
    float t = 0.5 - 0.5 * vNdc.y;
    vec3 day = t < MID_STOP
        ? mix(uSkyZenith, uSkyMid, t / MID_STOP)
        : (t < HORIZON_STOP
            ? mix(uSkyMid, uSkyHorizon, (t - MID_STOP) / (HORIZON_STOP - MID_STOP))
            : uSkyHorizon);

    vec3 sky = mix(day, clamp(c, 0.0, 1.0), uNightWeight);
    fragColor = vec4(clamp(sky, 0.0, 1.0), 1.0);
}
"""
}

package com.alijafari.red.astronomy.ar.android

/**
 * GLSL ES 3.00 sources of the AR "camera off" sky. Every formula mirrors the CPU reference in
 * `ar.core` (SkyAtmosphere sky-view LUT lookup, SkyRenderModel night background, Milky Way mapping and
 * tone mapping, ArRefraction.refractionFromApparentDeg), so the GL sky and the Canvas glyphs are exposed
 * by the same "eye". See ArSkyShaderParityTest for the source-level parity checks.
 */
object ArSkyShaders {

    const val VERTEX = """#version 300 es
precision highp float;
out vec2 vNdc;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vNdc = p * 2.0 - 1.0;
    gl_Position = vec4(vNdc, 0.0, 1.0);
}
"""

    const val FRAGMENT = """#version 300 es
precision highp float;
precision highp sampler2D;

in vec2 vNdc;
out vec4 fragColor;

uniform mat3 uCamToEnu;       // columns: camera right, camera down, boresight (ENU)
uniform vec2 uHalfTan;        // (W/2)/f, (H/2)/f
uniform sampler2D uSunLut;    // sky-view LUT lit by the Sun, linear sRGB cd/m2
uniform sampler2D uMoonLut;   // sky-view LUT for a unit-strength source at the Moon's elevation
uniform float uMoonScale;     // lunar/solar illuminance ratio
uniform float uLutScale;      // LUT texels are stored divided by this (half-float range)
uniform float uSunAz;         // radians, from north through east
uniform float uMoonAz;
uniform sampler2D uMilkyWay;  // NASA SVS Deep Star Maps 2020 Milky Way (sRGB, equirectangular, ICRS)
uniform float uMwReady;
uniform float uMwLod;
uniform mat3 uEnuToGcrs;
uniform float uMwPeak;        // cd/m2 for a linear texel of 1.0
uniform float uMwGamma;
uniform vec3 uTau;            // zenith optical depth per channel (Milky Way extinction)
uniform float uNightNatural;  // natural airglow at the zenith, cd/m2
uniform float uNightArtificial; // light pollution at the zenith, cd/m2
uniform float uZenithTv;      // zenith transmittance (photopic) for the airglow term
uniform float uExposure;
uniform float uScotopic;
uniform float uRefrScale;     // (P/1010) * (283/T)
uniform float uGroundGain;
uniform float uDitherSeed;

const float PI = 3.14159265358979;

float elevToV(float e) {
    float l = clamp(e, -0.5 * PI, 0.5 * PI);
    return 0.5 + 0.5 * sign(l) * sqrt(abs(l) / (0.5 * PI));
}

vec3 lutSample(sampler2D t, float elev, float dAz) {
    float a = abs(atan(sin(dAz), cos(dAz)));
    return texture(t, vec2(a / PI, elevToV(elev))).rgb * uLutScale;
}

float airmass(float altDeg) {
    float h = max(altDeg, -0.9);
    return 1.0 / (sin(radians(h)) + 0.50572 * pow(h + 6.07995, -1.6364));
}

float nightBackground(float altDeg) {
    float alt = max(altDeg, 0.5);
    float z = radians(90.0 - alt);
    float s = 6371.0 / (6371.0 + 90.0) * sin(z);
    float vanRhijn = 1.0 / sqrt(max(1e-3, 1.0 - s * s));
    float airglow = uNightNatural * vanRhijn * clamp(uZenithTv, 0.2, 1.0);
    float dome = uNightArtificial * (1.0 + 4.0 * exp(-alt / 9.0));
    return airglow + dome;
}

float acesCurve(float x) {
    float v = max(x, 0.0);
    return clamp((v * (2.51 * v + 0.03)) / (v * (2.43 * v + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec3 dc = normalize(vec3(vNdc.x * uHalfTan.x, -vNdc.y * uHalfTan.y, 1.0));
    vec3 d = normalize(uCamToEnu * dc);
    float elev = asin(clamp(d.z, -1.0, 1.0));
    float az = atan(d.x, d.y);
    vec3 col;
    if (elev >= 0.0) {
        col = lutSample(uSunLut, elev, az - uSunAz) + lutSample(uMoonLut, elev, az - uMoonAz) * uMoonScale;
        float altDeg = degrees(elev);
        col += nightBackground(altDeg) * vec3(1.0, 1.0, 1.08);
        if (uMwReady > 0.5) {
            // Apparent -> true altitude (Bennett 1982, scaled by pressure and temperature).
            float r = 0.0;
            if (altDeg > -1.0 && altDeg < 90.0) {
                r = max(0.0, (1.0 / tan(radians(altDeg + 7.31 / (altDeg + 4.4)))) * uRefrScale / 60.0);
            }
            float trueAlt = radians(altDeg - r);
            float hl = length(d.xy);
            vec2 hd = hl > 1e-6 ? d.xy / hl : vec2(0.0, 1.0);
            vec3 dt = vec3(hd * cos(trueAlt), sin(trueAlt));
            vec3 g = uEnuToGcrs * dt;
            float ra = atan(g.y, g.x);
            float dec = asin(clamp(g.z, -1.0, 1.0));
            vec2 uv = vec2(fract(0.5 - ra / (2.0 * PI)), 0.5 - dec / PI);
            vec3 mw = textureLod(uMilkyWay, uv, uMwLod).rgb;
            mw = pow(max(mw, vec3(0.0)), vec3(2.2));
            vec3 mwL = uMwPeak * pow(mw, vec3(uMwGamma));
            col += mwL * exp(-uTau * airmass(altDeg));
        }
    } else {
        // Dark landscape: the sky colour just above the horizon in this azimuth, strongly dimmed and
        // darkening further below the horizon.
        float h0 = radians(0.5);
        vec3 skyH = lutSample(uSunLut, h0, az - uSunAz) + lutSample(uMoonLut, h0, az - uMoonAz) * uMoonScale;
        float depth = clamp(-elev / radians(25.0), 0.0, 1.0);
        col = (skyH + vec3(nightBackground(0.5))) * uGroundGain * (1.0 - 0.75 * depth);
    }

    // ---- Tone mapping (identical to SkyRenderModel.toneMap) ----
    vec3 c = max(col, vec3(0.0)) * uExposure;
    if (uScotopic > 0.0) {
        float scot = 0.36 * c.g + 0.62 * c.b + 0.02 * c.r;
        c = c * (1.0 - uScotopic) + scot * vec3(0.62, 0.80, 1.18) * uScotopic;
    }
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    vec3 o = vec3(0.0);
    if (y > 1e-12) {
        float yd = acesCurve(y);
        c *= yd / y;
        float m = max(c.r, max(c.g, c.b));
        if (m > 1.0) {
            float sat = clamp((1.0 - yd) / max(m - yd, 1e-9), 0.0, 1.0);
            c = vec3(yd) + (c - vec3(yd)) * sat;
        }
        o = clamp(c, 0.0, 1.0);
    }
    vec3 lo = o * 12.92;
    vec3 hi = 1.055 * pow(o, vec3(1.0 / 2.4)) - 0.055;
    vec3 srgb = mix(lo, hi, step(vec3(0.0031308), o));
    // Triangular dither against banding in the dark gradients.
    float n1 = fract(sin(dot(gl_FragCoord.xy + uDitherSeed, vec2(12.9898, 78.233))) * 43758.5453);
    float n2 = fract(sin(dot(gl_FragCoord.yx + uDitherSeed * 1.7, vec2(39.3468, 11.135))) * 24634.6345);
    srgb += (n1 + n2 - 1.0) / 255.0;
    fragColor = vec4(clamp(srgb, 0.0, 1.0), 1.0);
}
"""
}

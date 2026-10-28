#version 300 es
precision mediump float;

uniform sampler2D u_HdrTexture;
uniform sampler2D u_BloomTexture;
uniform float u_Exposure;
uniform float u_BloomIntensity;
uniform int u_EnableBloom;
uniform vec2 u_TexelSize;

in vec2 v_TexCoord;
out vec4 fragColor;

// ACES filmic tone mapping operator (Krzysztof Narkowicz fit)
vec3 aces_filmic(vec3 x) {
    float a = 2.51;
    float b = 0.03;
    float c = 2.42;
    float d = 0.59;
    float e = 0.14;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

void main() {
    vec4 hdr = texture(u_HdrTexture, v_TexCoord);

    // Guaranteed razor-sharp black hole shadow
    if (hdr.a <= 0.5) {
        if (dot(hdr.rgb, hdr.rgb) <= 1.0e-7) {
            fragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    }

    vec3 color = hdr.rgb;

    // Edge-protected unsharp mask: strictly disabled near the shadow boundary
    vec4 n1 = texture(u_HdrTexture, v_TexCoord + vec2(u_TexelSize.x, 0.0));
    vec4 n2 = texture(u_HdrTexture, v_TexCoord - vec2(u_TexelSize.x, 0.0));
    vec4 n3 = texture(u_HdrTexture, v_TexCoord + vec2(0.0, u_TexelSize.y));
    vec4 n4 = texture(u_HdrTexture, v_TexCoord - vec2(0.0, u_TexelSize.y));

    if (n1.a > 0.5 && n2.a > 0.5 && n3.a > 0.5 && n4.a > 0.5) {
        vec3 neighborAvg = (n1.rgb + n2.rgb + n3.rgb + n4.rgb) * 0.25;
        color += (color - neighborAvg) * 0.45;
        color = max(color, vec3(0.0));
    }

    // Additive bloom
    if (u_EnableBloom == 1) {
        vec3 bloom = texture(u_BloomTexture, v_TexCoord).rgb;
        color += bloom * u_BloomIntensity;
    }

    // Exposure adjustment
    vec3 exposed = color * u_Exposure;

    // Luminance-preserving ACES: the ACES curve is applied to Rec.709 luminance only and RGB is
    // rescaled by mappedLuma / inputLuma, so the HDR chromaticity (e.g. orange G/R ~ 0.54) is kept
    // instead of the per-channel shoulder pulling green up toward red.
    float inputLuma = dot(exposed, vec3(0.2126, 0.7152, 0.0722));
    float mappedLuma = aces_filmic(vec3(inputLuma)).r;
    // The 1e-4 floor keeps the ratio finite at mediump precision; below it the output is < 1/255.
    vec3 ldr = clamp(exposed * (mappedLuma / max(inputLuma, 1.0e-4)), 0.0, 1.0);

    // CRITICAL: Display OETF Gamma 2.2 correction
    // Narkowicz ACES outputs linear display space. Gamma expansion is mandatory.
    ldr = pow(ldr, vec3(1.0 / 2.2));

    fragColor = vec4(ldr, 1.0);
}

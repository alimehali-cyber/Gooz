#version 300 es
precision mediump float;

uniform sampler2D u_HdrTexture;
uniform float u_BloomThreshold;

in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    vec4 hdr = texture(u_HdrTexture, v_TexCoord);
    // Black hole shadow or unresolved rays — strictly exclude from bloom
    if (hdr.a <= 0.5) {
        if (dot(hdr.rgb, hdr.rgb) <= 1.0e-7) {
            fragColor = vec4(0.0, 0.0, 0.0, 0.0);
            return;
        }
    }

    vec3 c = hdr.rgb;
    // Standard perceptual luminance weights (Rec. 709 / sRGB)
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float k2Lum = max(0.0, hdr.a - 1.0);
    float ordLum = max(0.0, lum - k2Lum);
    float excess = max(0.0, ordLum - u_BloomThreshold);
    float factor = (lum > 1.0e-5) ? excess / lum : 0.0;
    fragColor = vec4(c * factor, 1.0);
}

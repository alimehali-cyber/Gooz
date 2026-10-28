#version 300 es
precision highp float;
precision highp sampler2D;

uniform sampler2D u_Texture;
uniform vec2 u_SourceSize;
uniform int u_MaxChannel;

in vec2 v_TexCoord;
out vec4 fragColor;

vec4 sampleSource(ivec2 p, ivec2 sourceSize) {
    if (any(lessThan(p, ivec2(0))) || any(greaterThanEqual(p, sourceSize))) {
        return vec4(0.0);
    }
    return texelFetch(u_Texture, p, 0);
}

float max4(float a, float b, float c, float d) {
    return max(max(a, b), max(c, d));
}

void main() {
    ivec2 sourceSize = max(ivec2(1), ivec2(u_SourceSize));
    ivec2 base = ivec2(gl_FragCoord.xy) * 2;

    vec4 s0 = sampleSource(base, sourceSize);
    vec4 s1 = sampleSource(base + ivec2(1, 0), sourceSize);
    vec4 s2 = sampleSource(base + ivec2(0, 1), sourceSize);
    vec4 s3 = sampleSource(base + ivec2(1, 1), sourceSize);

    vec4 reduced = s0 + s1 + s2 + s3;
    if (u_MaxChannel == 0) reduced.r = max4(s0.r, s1.r, s2.r, s3.r);
    if (u_MaxChannel == 1) reduced.g = max4(s0.g, s1.g, s2.g, s3.g);
    if (u_MaxChannel == 2) reduced.b = max4(s0.b, s1.b, s2.b, s3.b);
    if (u_MaxChannel == 3) reduced.a = max4(s0.a, s1.a, s2.a, s3.a);

    fragColor = reduced;
}

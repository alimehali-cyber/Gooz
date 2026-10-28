#version 300 es
precision mediump float;

uniform sampler2D u_Texture;
uniform vec2 u_Direction; // (1.0 / width, 0.0) or (0.0, 1.0 / height)

in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    // 5-tap hardware-bilinear sampling approximating a 9-tap Gaussian distribution
    vec2 off1 = 1.3846153846 * u_Direction;
    vec2 off2 = 3.2307692308 * u_Direction;

    vec3 sum = texture(u_Texture, v_TexCoord).rgb * 0.2270270270;
    sum += texture(u_Texture, v_TexCoord + off1).rgb * 0.3162162162;
    sum += texture(u_Texture, v_TexCoord - off1).rgb * 0.3162162162;
    sum += texture(u_Texture, v_TexCoord + off2).rgb * 0.0702702703;
    sum += texture(u_Texture, v_TexCoord - off2).rgb * 0.0702702703;

    fragColor = vec4(sum, 1.0);
}

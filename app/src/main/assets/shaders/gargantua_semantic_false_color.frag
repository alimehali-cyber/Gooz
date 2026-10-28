#version 300 es
precision highp float;

uniform sampler2D u_Texture;
in vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    vec4 semantic = texture(u_Texture, v_TexCoord);
    float state = semantic.a;
    vec3 stateColor =
        state < 0.5 ? vec3(0.10, 0.10, 0.10) :
        state < 1.5 ? vec3(0.15, 0.20, 1.00) :
        state < 2.5 ? vec3(0.10, 0.90, 0.20) :
        state < 3.5 ? vec3(
            semantic.r,
            semantic.g,
            clamp(semantic.b / 8.0, 0.0, 1.0)
        ) :
        vec3(1.00, 0.20, 0.85);
    fragColor = vec4(stateColor, 1.0);
}

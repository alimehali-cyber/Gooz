#version 300 es
precision highp float;

in vec2 v_TexCoord;
out vec4 fragColor;

uniform vec2 u_Resolution; // Viewport width and height in pixels
uniform float u_Time;      // Elapsed time in seconds

void main() {
    // Aspect-ratio-corrected normalized device coordinates in [-1, 1]
    vec2 st = (gl_FragCoord.xy * 2.0 - u_Resolution.xy) / min(u_Resolution.x, u_Resolution.y);

    // Subtle time-based procedural test gradient (M1 GPU health test only)
    float dist = length(st);
    float angle = atan(st.y, st.x);

    // Deep slate and subtle violet procedural color wash
    vec3 colA = vec3(0.04, 0.05, 0.09); // Deep space slate
    vec3 colB = vec3(0.12, 0.08, 0.20); // Deep violet
    vec3 colC = vec3(0.18, 0.22, 0.35); // Cool azure

    float t1 = 0.5 + 0.5 * sin(u_Time * 0.8 + dist * 2.0);
    float t2 = 0.5 + 0.5 * cos(u_Time * 0.5 + angle);

    vec3 color = mix(colA, colB, clamp(dist * 0.7, 0.0, 1.0));
    color = mix(color, colC, t1 * 0.35 * (1.0 - smoothstep(0.0, 1.4, dist)));

    // Concentric calibration test shapes: reference rings
    float ring1 = abs(dist - 0.50) - 0.003;
    float ring2 = abs(dist - 0.75) - 0.002;
    float ringPulse = sin(u_Time * 1.5) * 0.02;
    float ring3 = abs(dist - (0.30 + ringPulse)) - 0.002;

    float gridAlpha = 1.0 - smoothstep(0.0, 0.004, min(min(ring1, ring2), ring3));
    color += vec3(0.25, 0.45, 0.75) * gridAlpha * 0.45;

    // Crosshair alignment test marker at origin
    float crosshair = min(abs(st.x), abs(st.y));
    if (dist < 0.08 && crosshair < 0.002) {
        color += vec3(0.40, 0.60, 0.90) * 0.6;
    }

    fragColor = vec4(color, 1.0);
}

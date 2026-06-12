#version 450

layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec3 vViewPos;

layout(set = 0, binding = 0) uniform sampler2D tex;

layout(set = 0, binding = 1) uniform FogUBO {
    vec4 color;   // rgb = fog color, a unused
    float start;
    float end;
    float enabled;   // >0.5 = on
    float brightness; // global day/night light multiplier (1.0 = full daylight)
} fog;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 c = texture(tex, vUV) * vColor;
    if (c.a < (1.0 / 255.0)) discard;

    // global day/night darkening (applied to lit geometry; UI uses brightness=1)
    c.rgb *= fog.brightness;

    if (fog.enabled > 0.5) {
        float dist = length(vViewPos);
        float f = clamp((fog.end - dist) / (fog.end - fog.start), 0.0, 1.0);
        c.rgb = mix(fog.color.rgb, c.rgb, f);
    }
    outColor = c;
}

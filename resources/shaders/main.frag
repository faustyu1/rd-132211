#version 450

layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec3 vViewPos;
layout(location = 3) in vec2 vLight;   // x = sky light, y = block light

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

    // Sky light follows the day/night cycle; block light does not, so a torch keeps its
    // room lit at midnight. Whichever source is stronger wins, as in the original game.
    // Geometry with no light attribute defaults to (1, 0) and so just tracks brightness,
    // which is what the UI, particles and entities want.
    c.rgb *= max(vLight.x * fog.brightness, vLight.y);

    if (fog.enabled > 0.5) {
        float dist = length(vViewPos);
        float f = clamp((fog.end - dist) / (fog.end - fog.start), 0.0, 1.0);
        c.rgb = mix(fog.color.rgb, c.rgb, f);
    }
    outColor = c;
}

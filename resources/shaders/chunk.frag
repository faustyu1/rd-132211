#version 450

layout(location = 0) in vec2 vUV;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec3 vViewPos;
layout(location = 3) in vec2 vLight;   // x = sky light, y = block light
layout(location = 4) flat in uint vLayer;

// One array layer per terrain tile: uv stays inside its own tile at every mip level, so a
// distant block cannot pick up the colour of whatever tile sat next to it in the sheet.
layout(set = 0, binding = 0) uniform sampler2DArray tex;

layout(set = 0, binding = 1) uniform FogUBO {
    vec4 color;
    float start;
    float end;
    float enabled;
    float brightness;
} fog;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 c = texture(tex, vec3(vUV, float(vLayer))) * vColor;
    if (c.a < (1.0 / 255.0)) discard;

    // Sky light follows the day/night cycle; block light does not, so a torch keeps its
    // room lit at midnight. Whichever source is stronger wins, as in the original game.
    c.rgb *= max(vLight.x * fog.brightness, vLight.y);

    if (fog.enabled > 0.5) {
        float dist = length(vViewPos);
        float f = clamp((fog.end - dist) / (fog.end - fog.start), 0.0, 1.0);
        c.rgb = mix(fog.color.rgb, c.rgb, f);
    }
    outColor = c;
}

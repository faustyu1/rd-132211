#version 450

layout(location = 0) in vec3 inPos;
layout(location = 1) in vec2 inUV;
layout(location = 2) in vec4 inColor;
// x = sky light, y = block light. Kept apart so the fragment stage can dim only the sky
// half with the day/night multiplier — a torch has to stay bright at midnight.
layout(location = 3) in vec2 inLight;

layout(push_constant) uniform PushConstants {
    mat4 proj;
    mat4 modelView;
} pc;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec3 vViewPos;
layout(location = 3) out vec2 vLight;

void main() {
    vec4 viewPos = pc.modelView * vec4(inPos, 1.0);
    vViewPos = viewPos.xyz;
    vUV = inUV;
    vColor = inColor;
    vLight = inLight;
    gl_Position = pc.proj * viewPos;
}

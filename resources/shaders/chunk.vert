#version 450

// Packed chunk vertex, 16 bytes. See ChunkTesselator for the byte layout.
layout(location = 0) in uvec4 inPacked;   // xyz = section-local position in 1/2048 block, w = texture layer
layout(location = 1) in vec4 inColor;     // unorm8 RGBA
layout(location = 2) in vec4 inUvLight;   // xy = uv inside the tile, z = sky light, w = block light
// Per-instance: the world-space origin of the section this draw belongs to. Keeping it out
// of the vertex is what lets every section share one vertex buffer and one indirect draw.
layout(location = 3) in vec3 inOrigin;

layout(push_constant) uniform PushConstants {
    mat4 proj;
    mat4 modelView;
} pc;

layout(location = 0) out vec2 vUV;
layout(location = 1) out vec4 vColor;
layout(location = 2) out vec3 vViewPos;
layout(location = 3) out vec2 vLight;
layout(location = 4) flat out uint vLayer;

void main() {
    vec3 world = inOrigin + vec3(inPacked.xyz) * (1.0 / 2048.0);
    vec4 viewPos = pc.modelView * vec4(world, 1.0);
    vViewPos = viewPos.xyz;
    vUV = inUvLight.xy;
    vLight = inUvLight.zw;
    vColor = inColor;
    vLayer = inPacked.w;
    gl_Position = pc.proj * viewPos;
}

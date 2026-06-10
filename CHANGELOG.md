# Changelog

All notable changes to RubyDung are documented in this file.

## [0.3.0] - 2026-06-10

### Changed
- **Renderer migrated from OpenGL 2.1 fixed-function to Vulkan 1.x** (MoltenVK on macOS).
  The entire rendering pipeline now runs through a new Vulkan backend under
  `com.mojang.rubydung.render.vk`, exposed via a `GameRenderer` facade that mimics
  the old immediate-mode GL API (push/pop/translate/rotate/scale/setColor/bindTexture/
  setFog/setPipeline/draw).
- `Tesselator`, `WorldChunk`, `LevelRenderer`, `Frustum`, `FontRenderer`,
  `ParticleSystem`, and `Textures` reworked to feed the Vulkan renderer.
- UI code now calls the immediate-mode shim (`render.GL` / `render.Imm`) instead of
  LWJGL `GL11`.

### Added
- Vulkan backend: `VkContext`, `Swapchain`, `Pipelines`, `FrameSync`,
  `DescriptorAllocator`, `StreamingBuffer`, `QuadIndexBuffer`, `VkBuf`, `VkTexture`,
  `ShaderCompiler`, `DeferredDeleter`.
- GLSL 450 shaders (`resources/shaders/main.vert`, `main.frag`) compiled at runtime
  via shaderc.
- `lwjgl-vulkan` and `lwjgl-shaderc` dependencies.
- Dynamic rendering (`VK_KHR_dynamic_rendering`), per-frame depth buffers,
  push-constant matrices, and a fog UBO.

### Fixed
- See-through-blocks / cave bleed: per-frame depth images prevent cross-frame depth
  corruption; the translucent pipeline now writes depth so shaded opaque faces occlude
  correctly.

### Removed
- Dead `level/Chunk.java` (superseded by `WorldChunk`).

## [0.2.0]

### Added
- Water transparency, player spawn, loading screen, and block interaction.
- Unicode chat with an AWT font atlas, input blocking, and message fade.

### Changed
- Multiplayer overhaul, server list, and UI/UX fixes.

## [0.1.0]

### Added
- Initial RubyDung release: Minecraft-classic-style procedural voxel world and
  core gameplay.
- Maven shade fat-jar build; bundled Windows + Linux LWJGL natives for a universal jar.

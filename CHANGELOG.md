# Changelog

All notable changes to RubyDung are documented in this file.

This project follows [Semantic Versioning](https://semver.org/) while in `0.x`:
- **MINOR** (`0.X.0`) — new features or breaking/architectural changes.
- **PATCH** (`0.x.X`) — bug fixes and small tweaks, no new features.

Do not bump versions for every commit; group changes into a release and tag once.

## [0.4.0] - 2026-06-13

### Added
- **Survival mode**: player health (0–20 half-hearts), fall damage, void damage,
  invulnerability frames, passive regeneration, respawn, and a blocky heart HUD.
- **Dropped items** (`DroppedItems`): broken blocks spawn spinning, gravity-affected,
  collidable item entities with magnet pickup, pickup delay, and despawn timer.
- **Creative inventory** (screen 6): block palette grid with cursor-carry, hotbar
  editing, item counts, and block-name tooltips. Replaces the old crafting screen.
- **Day/night cycle**: animated sky colour, warm dawn/dusk horizon tint, and a global
  brightness multiplier driven through the fog UBO and fragment shader.
- **Chunk sections (16³)**: meshing and rendering split into 8 vertical slices per
  chunk with per-section frustum culling and dirty/urgent tracking.
- **Player persistence** (`player.dat`): position, rotation, spawn point, health, game
  mode, hotbar, and inventory saved per world. `B` sets the spawn point.
- **Sky-light flood-fill**: per-chunk BFS sky lighting (0–15) with smooth face shading.
- **Configurable render distance**: TINY/SHORT/NORMAL/FAR/EXTREME presets that load and
  unload chunks live, not just adjust fog.

### Changed
- **Parallel chunk streaming**: chunk generation moved off the main thread to a worker
  pool (nearest-first), eliminating walk microfreezes.
- **Fast world preload**: `Level.preloadRegion` generates a 1-ring-wider area in parallel
  so every mesh sees real neighbours — seamless borders with no re-mesh.
- **Cave generation** rewritten with a deterministic sphere cache for seamless,
  reproducible tunnels; biome borders now meander instead of forming straight lines.
- Block edits rebuild affected sections synchronously for same-frame feedback.

### Fixed
- **Chunk seams**: neighbours are re-meshed when a new chunk loads, removing the light
  band that appeared every 16 blocks at chunk borders.
- **Water vs caves**: still water adjacent to a cave mouth now wakes the fluid sim and
  flows in on chunk load instead of staying frozen.
- **Item pickup**: picked-up blocks are no longer silently consumed; hotbar slots show a
  block-colour swatch and count.

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

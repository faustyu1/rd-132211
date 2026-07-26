# Changelog

All notable changes to RubyDung are documented in this file.

This project follows [Semantic Versioning](https://semver.org/) while in `0.x`:
- **MINOR** (`0.X.0`) — new features or breaking/architectural changes.
- **PATCH** (`0.x.X`) — bug fixes and small tweaks, no new features.

Do not bump versions for every commit; group changes into a release and tag once.

## [0.5.0] - 2026-07-26

Everything below is new gameplay or a protocol change, so this is a MINOR bump rather than
a patch. Multiplayer is not compatible with 0.4.x: the handshake now carries a protocol
version and a mismatched peer is told so instead of misreading the stream.

### Added
- **Torches and block light.** A second light channel, propagated by the same flood fill as
  sky light but seeded from emitters, and — unlike sky light — not dimmed by the day/night
  cycle, so a lit cave stays lit at midnight. Light crosses chunk borders, is blocked by
  solid blocks, and disappears when the torch does. A torch needs something to stand on and
  drops when that support is broken.
- **Tools and crafting** (`Items`, screen 7, key `C`). Sticks, and pickaxes/axes/shovels in
  wood, stone and iron. The right tool mines its material up to seven times faster, and
  stone and ore now yield nothing without a pickaxe good enough for them — bare hands get
  wood and dirt, a wooden pickaxe gets stone and coal, stone gets iron, iron gets gold and
  diamond. That chain is what finally gives the ore in the ground a purpose. Recipes are
  shapeless and listed with their cost and your stock; tools have no durability.
- **Drowning.** Fifteen seconds of breath under water, then half a heart a second, with a
  bubble row above the hearts. Refills instantly at the surface.
- **Number keys 1-9** select hotbar slots.
- **Autosave** every 30 seconds. A crash used to cost everything since launch.
- **Survival inventory** shows what you have actually collected, including crafted items,
  instead of the creative palette.

### Changed
- **Multiplayer sends the world seed, not the world.** The handshake shrank from tens of
  megabytes to seventeen bytes: both sides generate identical terrain from the seed, and
  only chunks somebody edited travel, streamed a few per tick around each client as it
  moves. Before this, the client generated its own terrain outside the snapshot it was
  sent, so players standing in the same place could be in different worlds.
- **One font.** The hand-built 5x7 bitmap font is gone; menus, HUD, chat and the 3D name
  tags all draw through the same texture atlas. Menus can therefore show lowercase and
  Cyrillic, which the old font silently dropped, and a label that would not fit its button
  is scaled down instead of drawn past the edge.
- **Saves only store what the seed cannot reproduce.** A world folder used to accumulate
  every chunk the player had ever seen (~14 MB at the default render distance); now it
  holds only edited chunks, and untouched terrain is regenerated.
- Spectator mode moves properly fast, which is the only thing it is for.
- `Level.save()`/`load()` without arguments (the pre-`saves/` single-folder API) removed.

### Fixed
- Placing a block no longer relights the whole chunk when there is no torch light near it.

## [0.4.1] - 2026-07-26

A full line-by-line review of the codebase, and the fixes for everything it found.
No new features.

### Fixed
- **Crash when pressing Escape during world generation**: the key fell through to the
  pause screen, whose render path dereferences a player that does not exist until
  loading finishes. The 3D path is now unreachable without a live world.
- **Buildings vanished when you walked away**: chunks that streamed out of range were
  dropped without being written, and `save()` only ever wrote resident chunks. Edited
  chunks are now flushed on unload and read back from disk when they stream in again;
  if the write fails, the chunk stays in memory rather than taking your work with it.
- **Inventory leaked between worlds**: the hotbar and item counts live on the game
  object, not on the player, and were never reset — a brand-new world started with the
  previous world's blocks.
- **Multiplayer joins dropped or split packets**: the client read `WELCOME` through a
  `BufferedInputStream` it then threw away, losing whatever that buffer had read ahead;
  a half-buffered packet left the reader mid-stream, reading a garbage length and
  closing the connection. One buffered stream now lives for the whole session.
- **Joining a server corrupted the open single-player world**: the session reused that
  world's `Level`, so the host's snapshot overwrote it in memory (and, with the new
  unload flush, on disk). A joined session now gets its own `Level`.
- **World snapshot raced chunk streaming**: `getRawBlocks` sized its buffer from
  `chunks.size()` and then iterated the live map, overflowing it if a chunk arrived in
  between — which is exactly what happens while the host is walking around.
- **`HOST GAME` from the main menu** passed a null level to `GameServer`, because
  `startWorld` publishes the world from a background thread.
- **Unvalidated network input**: the join handshake allocated `byte[len]` straight from
  the wire, and block ids arrived unchecked; both are now bounded and validated.
- **Leaves could not be hit**: the picking ray tested solidity, which deliberately
  excludes leaves, so the breaking code for them was unreachable. Their collision and
  lighting are unchanged — they are still walk-through and still let light past.
- **Survival had no economy**: placing never consumed anything and the creative palette
  handed out infinite stacks in any mode.
- **Water checks were off by one at negative coordinates** (truncation instead of floor),
  so swim drag and fall-damage cancelling misfired across half the world.
- **Only one of several simultaneously collected drops was credited.**
- **Water rendered as a magenta checkerboard**: it sampled atlas tile 14, which is the
  missing-texture placeholder — the atlas only ships two real tiles.
- **Ambient occlusion on walls** sampled voxels outside the face plane and never sampled
  vertical neighbours, so overhangs cast no shading. Top and bottom faces are unchanged.
- **The crosshair's inverse blend was silently ignored** (`glBlendFunc` was a no-op), so
  it disappeared against light terrain.
- **Caves stopped dead** at the generator's search radius, which was smaller than a
  tunnel's reach; **trees never crossed chunk borders**, leaving bald strips on a
  16-block grid. Trunk spacing now spans borders and stays a pure function of seed and
  chunk coordinates, so parallel generation still agrees on every block.
- **Silent holes in the world**: an exception in a background section build left that
  section permanently unbuilt, with no log.
- **Durations were three times too short**: constants written for Minecraft's 20 tps ran
  on a 60 Hz tick — regeneration, break speed, drop despawn, damage flash and the
  day/night cycle now mean what they say. Movement and physics constants are untouched.
- **Clicks made during world generation** were replayed into the world the moment it
  appeared, breaking or placing blocks at spawn.
- **Vulkan**: resources were freed a frame before the fence proved the GPU was done with
  them; the present semaphore was reused per frame-in-flight although `vkQueuePresentKHR`
  waits on it (now one per swapchain image); the first device with graphics+present was
  taken rather than preferring a discrete GPU; scratch buffers leaked on shutdown.
- **The font atlas** packed every glyph into one unchecked 4096px row, and text
  measurement disagreed with what was drawn for unsupported characters.
- **`./build.sh` and `./run.sh` were broken**: both `cd`'d to a directory that no longer
  exists, so the documented build command failed instantly. They now resolve their own
  location and fall back to `$JAVA_HOME` or `PATH`; `run2.sh` is no longer pre-Vulkan.

### Changed
- Flowing water no longer forces synchronous mesh rebuilds on the render thread; the
  "rebuild this frame" path is reserved for player edits, as it was meant to be.
- Marking geometry dirty no longer scans every loaded chunk per changed block, a block
  edit no longer re-runs a whole-chunk light flood-fill with a 128 KB allocation, and
  meshing no longer evaluates faces for the render layer it does not emit into.
- UI text is drawn once per string instead of once per glyph.
- `player.dat` is now version 3: the unused 36-byte slot array is gone. Version 2 files
  still load.
- `CLAUDE.md` and `README.md` corrected — both still described an OpenGL 2.1 renderer, a
  `level.dat` save file and a five-screen menu, none of which have been true since 0.3.0.
- Dead code removed (`Tile.rock`, `renderFace`, `Level.getOrLoadChunk`/`Biome`/
  `animalSpawns`, `WorldChunk.hasMesh`, `LevelListener.lightColumnChanged`, and the three
  duplicate copies of the block-colour table).

### Known limitations
- Back-face culling stays disabled and vertex buffers stay host-visible. Both are
  worthwhile optimisations, but the first needs consistent winding across every geometry
  path and the second buys nothing on unified-memory hardware.

## [0.4.0] - 2026-06-13

### Added
- **Survival mode**: player health (0–20 half-hearts), fall damage, void damage,
  invulnerability frames, passive regeneration, respawn, and a blocky heart HUD.
- **Dropped items** (`DroppedItems`): broken blocks spawn spinning, gravity-affected,
  collidable item entities with proximity pickup, pickup delay, and despawn timer.
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

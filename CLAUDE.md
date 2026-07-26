# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**Build** (JDK 21+ from `$JAVA_HOME` or `PATH`, jars straight from `~/.m2`):
```bash
./build.sh
```

**Run** (after build):
```bash
./run.sh
```

**Second client** for local multiplayer testing (runs from a scratch directory so it gets its own settings/saves):
```bash
./run2.sh
```

**Build + run via Maven** (unpacks natives to `target/natives/` automatically):
```bash
mvn compile exec:java
```
`exec:java` does not pass `-XstartOnFirstThread`, which GLFW needs on macOS — use `./run.sh` there.

## Architecture

RubyDung is a Minecraft-classic-style voxel game. Entry point: `sources/com/mojang/rubydung/RubyDung.java` — implements `Runnable`, owns the game loop, the GLFW window (created with `GLFW_NO_API`: the renderer is Vulkan, not GL), and all UI screens.

**Packages:**

- `com.mojang.rubydung` — top-level: `RubyDung` (main loop, HUD, menus), `Player` (movement, survival health, breath, game modes), `Items` (non-block items, tools, recipes, mining rules), `RemotePlayer`, `Input` (GLFW event buffering), `DroppedItems` (block-drop entities), `ParticleSystem`, `FontRenderer` (AWT-generated Unicode atlas — the only text renderer), `Textures`, `Timer` (60 Hz fixed tick + interpolation alpha), `HitResult`, `Settings`
- `com.mojang.rubydung.level` — `Level` (chunk map, fluid simulation, per-world save/load), `WorldChunk` (a 16×128×16 column split into 8 vertical 16³ sections, each with its own mesh and dirty/urgent state, plus per-chunk sky-light BFS and a block-light channel for torches, whose fill is driven from `Level` in world space so it crosses chunk borders), `ChunkGenerator` + `PerlinNoise` (terrain, biomes, caves, ores, trees), `LevelRenderer` (frustum-culled chunk rendering), `Tesselator` (interleaved pos3+uv2+color4 vertex builder), `Tile`, `Frustum`, `LevelListener`
- `com.mojang.rubydung.render` — `GL` / `Imm`: a fixed-function-style shim (`glBegin`/`glVertex`/`glColor`) so UI code still reads like the old GL 2.1 code while feeding the Vulkan renderer
- `com.mojang.rubydung.render.vk` — Vulkan backend: `VkContext`, `Swapchain`, `FrameSync`, `Pipelines`, `DescriptorAllocator`, `StreamingBuffer`, `QuadIndexBuffer`, `VkBuf`, `VkTexture`, `DeferredDeleter`, `ShaderCompiler`, and the `GameRenderer` facade (push/pop/translate/rotate/scale/setColor/bindTexture/setFog/setPipeline/draw)
- `com.mojang.rubydung.phys` — `AABB` (collision)
- `com.mojang.rubydung.net` — TCP multiplayer: `GameServer`, `GameClient`, `Connection`, `Packet`, `PacketWriter`

**Rendering pipeline:** `RubyDung.render()` → `GameRenderer.beginFrame()` → `setupCamera()` → `LevelRenderer.render()` (pass 0 = opaque, pass 1 = translucent) → particles/drops → HUD/menus via `beginOrtho()`/`endOrtho()` → `endFrame()`. Vulkan uses `VK_KHR_dynamic_rendering`, push-constant matrices and a fog UBO; the GLSL 450 shaders in `resources/shaders/` are compiled at runtime by shaderc. All text — menus, HUD, chat and the 3D name tags — goes through `FontRenderer`'s AWT-generated atlas (ASCII + Cyrillic); `RubyDung.drawText`/`drawTextCentered`/`drawTextFitCentered` are the only entry points, and labels that would overflow their button are scaled down. Vertices are `pos3+uv2+color4+light2`: the light pair is (sky, block), and the fragment shader dims only the sky half with the day/night multiplier, which is what keeps torchlight alive at night.

**Persistence** (all paths relative to the working directory): settings → `settings.properties`; server list → `servers.properties`; worlds → `saves/<name>/` holding `seed.dat`, one `<cx>_<cz>.dat` per **edited** chunk (untouched terrain is regenerated from the seed), and `player.dat` (position, spawn, health, mode, hotbar, item counts) — every file gzip'd.

**Multiplayer:** host calls `GameServer`, client calls `GameClient`. The handshake carries `Packet.PROTOCOL_VERSION`, the assigned id and the **world seed** — terrain is regenerated on both sides rather than transmitted, and only chunks somebody edited are streamed (`Packet.CHUNK`, a few per tick around each client). Both sides sync player positions each tick; block changes go through `sendSetTile`/`broadcastTile` and are validated against `Packet.isPlaceable`. A joined session always gets its own `Level` with no save directory, so it can never write over a single-player world. Screen state machine: -1=main menu, 0=game, 1=pause, 2=settings, 3=mp host, 4=direct connect, 5=server list, 6=inventory, 7=crafting, 8=world select, 9=create world, 10=add/edit server, 11=loading.

**Dependencies:** LWJGL 3.4.1 (GLFW + Vulkan + shaderc + natives), JOML 1.10.7 (matrix math). Maven unpacks macOS natives to `target/natives/`; `build.sh`/`run.sh` put the `~/.m2` jars on the classpath and let LWJGL extract the natives it needs.

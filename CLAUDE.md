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

- `com.mojang.rubydung` — top-level: `RubyDung` (main loop, HUD, menus), `Player` (movement, survival health, breath, game modes), `Items` (non-block items, tools, recipes, mining rules, stack limits), `Inventory` (36 stack slots: 0–8 the hotbar, 9–35 the storage rows), `RemotePlayer`, `Input` (GLFW event buffering), `DroppedItems` (block-drop entities), `ParticleSystem`, `FontRenderer` (stb_truetype-generated Unicode atlas — the only text renderer), `Textures`, `Timer` (60 Hz fixed tick + interpolation alpha), `HitResult`, `Settings`
- `com.mojang.rubydung.level` — `Level` (chunk map, fluid simulation, per-world save/load), `WorldChunk` (a 16×128×16 column split into 8 vertical 16³ sections, each with its own mesh, dirty/urgent state and visibility graph, plus per-chunk sky-light BFS and a block-light channel for torches, whose fill is driven from `Level` in world space so it crosses chunk borders), `ChunkGenerator` + `PerlinNoise` (terrain, biomes, caves, ores, trees), `LevelRenderer` (occlusion + frustum culling, mesh scheduling), `Tesselator` (interleaved pos3+uv2+color4 vertex builder), `Tile`, `Frustum`, `LevelListener`
- `com.mojang.rubydung.render` — `GL` / `Imm`: a fixed-function-style shim (`glBegin`/`glVertex`/`glColor`) so UI code still reads like the old GL 2.1 code while feeding the Vulkan renderer
- `com.mojang.rubydung.ui` — `Ui`: the one place the interface's look lives (palette + `panel`/`slot`/`button`/`field`/`rect`/`border`). Every screen draws through it, so the menus, the inventory and the HUD stay one style; changing a colour here changes the whole game
- `com.mojang.rubydung.render.vk` — Vulkan backend: `VkContext`, `Swapchain`, `FrameSync`, `Pipelines`, `DescriptorAllocator`, `StreamingBuffer`, `MeshArena`, `QuadIndexBuffer`, `VkBuf`, `VkTexture`, `DeferredDeleter`, `ShaderCompiler`, and the `GameRenderer` facade (push/pop/translate/rotate/scale/setColor/bindTexture/setFog/setPipeline/draw)
- `com.mojang.rubydung.phys` — `AABB` (collision)
- `com.mojang.rubydung.net` — TCP multiplayer: `GameServer`, `GameClient`, `Connection`, `Packet`, `PacketWriter`

**Rendering pipeline:** `RubyDung.render()` → `GameRenderer.beginFrame()` → `setupCamera()` → `LevelRenderer.render()` (pass 0 = opaque, pass 1 = translucent) → particles/drops → HUD/menus via `beginOrtho()`/`endOrtho()` → `endFrame()`. Vulkan uses `VK_KHR_dynamic_rendering`, push-constant matrices and a fog UBO; the GLSL 450 shaders are compiled ahead of time by `tools/compile-shaders.sh` into `resources/shaders/*.spv` and only loaded at runtime (`ShaderCompiler`), so shaderc is a dev-profile dependency rather than a shipped one. **The UI has its own coordinate space:** `beginOrtho()` divides the framebuffer by the GUI scale (`settings.guiScale`, 0 = AUTO — the largest whole factor still leaving 1024x600 units) and stores the result in `guiW`/`guiH`. Every screen lays itself out in those units, never in framebuffer pixels, and `mouseScreenX/Y` map the cursor into the same space; without that the whole interface shrinks to a corner on a HiDPI display. All text — menus, HUD, chat and the 3D name tags — goes through `FontRenderer`'s stb_truetype atlas (ASCII + Cyrillic); `RubyDung.drawText`/`drawTextCentered`/`drawTextFitCentered` are the only entry points, and labels that would overflow their button are scaled down. Vertices are `pos3+uv2+color4+light2`: the light pair is (sky, block), and the fragment shader dims only the sky half with the day/night multiplier, which is what keeps torchlight alive at night.

**Chunk visibility and streaming:** `Level.update` generates a *disc* of chunk radius `renderDist + 1` around the player, nearest first, and unloads past `renderDist + 3`; the extra ring is a data-only skirt, because a chunk is meshed only once all four of its neighbours exist (`Level.neighborsLoaded`) so its border faces are culled right the first time instead of being re-meshed once per neighbour that arrives later. Meshing itself is driven by `LevelRenderer` through `WorldChunk.pump()`, never by the generator: visible chunks get the per-frame upload/build budget first, then the hidden ones nearest the player, so a chunk that goes dirty out of sight still converges. Which sections are drawn comes from a flood fill (`LevelRenderer.computeVisibility`) that starts in the camera's own open pocket and steps into a neighbouring 16³ section only through a face pair that section's visibility graph connects (`WorldChunk.visGraph`, built with the mesh — a section of solid rock connects nothing), never doubling back along an axis it already used. Sealed cave systems are therefore never drawn: standing in a cave it culls ~98% of the geometry a plain frustum test would submit, on the surface ~15-20%, and the flood order is front-to-back so the depth test rejects overdraw early.

**Streaming without hitches:** section meshes do not own a `VkBuf` each — they take slices from `MeshArena`, a handful of 32 MB buffers with a coalescing free list, so uploading a mesh is a memcpy into already-mapped memory instead of a vkAllocateMemory + vkMapMemory on the render thread (a full render distance is thousands of section meshes). A slice is only returned through `DeferredDeleter`, never straight away, or the next upload would overwrite geometry a frame in flight is still drawing. Uploads are paced by bytes (3 MB/frame) rather than by count, since section meshes differ by two orders of magnitude in size. The generator and mesher share a fixed core budget (`Threads`: cores minus two, split 3:2, below normal priority) instead of each sizing itself at all-cores-but-one and starving the render thread between them. Edited chunks reach disk in one `LinearRegionCache.writeBatch` per region rather than one region rewrite per chunk — that is what an autosave costs, and batching 40 edited chunks in a region takes 14 ms instead of 900. `Level.dispose()` gives a torn-down world's slices and generator threads back.

**Persistence** (all paths relative to the working directory): settings → `settings.properties`; server list → `servers.properties`; worlds → `saves/<name>/` holding `seed.dat`, `player.dat` (v4: position, spawn, health, mode, selected slot, and the 36 inventory stacks; v≤3 files stored a hotbar id list plus a flat per-id tally and are converted into stacks on load) and the **edited** chunks (untouched terrain is regenerated from the seed) in Linear region files `r.<rx>.<rz>.linear` — 32×32 chunks per file, whole body zstd-compressed, written by `LinearRegionFile`. `LinearRegionCache` gives `Level` the chunk-at-a-time access the streaming paths need (an LRU of decompressed regions, write-through so a flushed chunk really is on disk; `writeBatch` groups a flush by region so one zstd pass covers every chunk of it, and reports which regions failed so their chunks stay resident). `seed.dat`/`player.dat` stay gzip'd. Pre-Linear saves with one `<cx>_<cz>.dat` per chunk are still read, and each such chunk moves into a region file the next time it is written.

**Multiplayer:** host calls `GameServer`, client calls `GameClient`. The handshake carries `Packet.PROTOCOL_VERSION`, the assigned id and the **world seed** — terrain is regenerated on both sides rather than transmitted, and only chunks somebody edited are streamed (`Packet.CHUNK`, zstd-compressed, a few per tick out to the render distance the client reports in `Packet.CLIENT_INFO`). Both sides sync player positions each tick — a client sends only when it has actually moved — and block changes go through `sendSetTile`/`broadcastTile`, validated against `Packet.isPlaceable`. A joined session always gets its own `Level` with no save directory, so it can never write over a single-player world.

Neither direction blocks the game thread: `Connection` owns a reader thread and a writer thread with a bounded queue each, because a blocking socket write has no timeout in Java and one client on bad wifi would otherwise freeze the host for as long as its TCP window stayed full. A queue that stops draining is a peer that cannot keep up, and the connection is dropped. The host pings every second and drops a peer silent for 30 s — a half-open socket (unplugged cable) stays writable forever, so silence is the only evidence there is — and tells everybody else with `Packet.PLAYER_LEAVE`, or they keep rendering a player who quit. Peers are not trusted: per-client rate limits on edits and chat, positions checked for NaN and world bounds, and chat lines are attributed by the host from its own name table rather than by text the sender supplied.

**Who owns what:** the host runs the only fluid simulation (`Level.setSimulateFluids(false)` on a client) and broadcasts the cells it moves, batched into one `Packet.SET_FLUID` per tick; two independent simulations drift apart within seconds. Finding chunks to stream happens on a background thread, since a chunk that is not resident costs a region decompression, but the **snapshot and the send happen on the game thread** (`Level.getResidentModifiedBlocks`): a chunk copied a moment earlier is older than the updates already queued behind it, and the client would apply those and then have them wiped by the stale snapshot. Screen state machine: -1=main menu, 0=game, 1=pause, 2=settings, 3=mp host, 4=direct connect, 5=server list, 6=inventory, 7=crafting, 8=world select, 9=create world, 10=add/edit server, 11=loading.

**Dependencies:** LWJGL 3.4.1 (GLFW + Vulkan + stb + natives; shaderc only in the dev profile that rebuilds the SPIR-V), JOML 1.10.7 (matrix math), zstd-jni 1.5.7-6 (Linear region compression; natives bundled in the jar). Maven unpacks macOS natives to `target/natives/`; `build.sh`/`run.sh` put the `~/.m2` jars on the classpath and let LWJGL extract the natives it needs.

# RubyDung

A Minecraft-classic-style voxel game written in Java, with a custom **Vulkan** renderer (MoltenVK on macOS) migrated from the original OpenGL 2.1 fixed-function pipeline.

## Features

- Procedural voxel world with block placement and destruction
- Frustum-culled chunk rendering with multithreaded mesh building
- Water transparency, fog, day/night sky, and particle effects
- TCP multiplayer: host a server or join via a server list, with synced player positions and block changes
- Unicode chat with an AWT-generated font atlas (Cyrillic + ASCII)
- Persistent worlds (`saves/<name>/`) and settings (`settings.properties`)

## Requirements

- Java 21+ (`build.sh`/`run.sh` use `$JAVA_HOME`, else the `javac`/`java` on your `PATH`)
- macOS, Windows, or Linux. On macOS the Vulkan backend runs through MoltenVK.

## Build & Run

**Build + run via Maven** (handles native unpacking automatically):

```bash
mvn compile exec:java
```

> On macOS the game requires `-XstartOnFirstThread`, which `exec:java` does not pass — use the
> custom scripts below there.

**Custom build script** (JDK 21+ from `$JAVA_HOME` or `PATH`, jars from `~/.m2`):

```bash
./build.sh
./run.sh
./run2.sh   # second client, run from a scratch dir, for local multiplayer testing
```

**Fat jar** (all-platform natives + shaders bundled):

```bash
mvn package
java -XstartOnFirstThread -jar target/rubydung.jar   # macOS
java -jar target/rubydung.jar                         # Windows / Linux
```

## Architecture

Entry point: `sources/com/mojang/rubydung/RubyDung.java` — implements `Runnable`, owns the game loop and all UI screens.

**Packages:**

- `com.mojang.rubydung` — `RubyDung` (main loop, rendering, menus), `Player`, `RemotePlayer`, `Input`, `DroppedItems`, `ParticleSystem`, `FontRenderer`, `Textures`, `Timer`, `HitResult`, `Settings`
- `com.mojang.rubydung.level` — `Level`, `LevelRenderer`, `LevelListener`, `WorldChunk`, `ChunkGenerator`, `PerlinNoise`, `Tesselator`, `Tile`, `Frustum`
- `com.mojang.rubydung.render` — `GL` / `Imm`: fixed-function-style shim used by the UI code
- `com.mojang.rubydung.phys` — `AABB` (collision)
- `com.mojang.rubydung.net` — TCP multiplayer: `GameServer`, `GameClient`, `Connection`, `Packet`, `PacketWriter`
- `com.mojang.rubydung.render.vk` — Vulkan backend: `VkContext`, `Swapchain`, `Pipelines`, `FrameSync`, `GameRenderer` (facade), `DescriptorAllocator`, `StreamingBuffer`, `QuadIndexBuffer`, `VkBuf`, `VkTexture`, `ShaderCompiler`, `DeferredDeleter`

**Rendering:** the `GameRenderer` facade mimics the old immediate-mode GL API (push/pop/translate/rotate/scale/setColor/bindTexture/setFog/setPipeline/draw). The pipeline uses `VK_KHR_dynamic_rendering`, per-frame depth buffers, push-constant matrices, and a fog UBO. GLSL 450 shaders (`resources/shaders/`) are compiled at runtime via shaderc.

## Dependencies

- LWJGL 3.4.1 (GLFW + Vulkan + shaderc + natives)
- JOML 1.10.7 (matrix math)

## Versioning

This project follows [Semantic Versioning](https://semver.org/) while in `0.x`. See [CHANGELOG.md](CHANGELOG.md) for release history.

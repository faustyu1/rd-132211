# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

**Build** (custom script using bundled JDK 21):
```bash
./build.sh
```

**Run** (after build):
```bash
./run.sh
```

**Build + run via Maven** (handles native unpacking automatically):
```bash
mvn compile exec:java
```

## Architecture

RubyDung is a Minecraft-classic-style voxel game. Entry point: `sources/com/mojang/rubydung/RubyDung.java` — implements `Runnable`, owns the game loop, OpenGL context (LWJGL3 + OpenGL 2.1 fixed-function), and all UI screens.

**Packages:**

- `com.mojang.rubydung` — top-level: `RubyDung` (main loop, rendering, menus), `Player`, `RemotePlayer`, `Input` (GLFW event buffering), `Textures`, `Timer`, `HitResult`, `Settings`
- `com.mojang.rubydung.level` — `Level` (block data, saves to `level.dat`), `LevelRenderer` (frustum-culled chunk rendering), `Chunk` (geometry tesselation), `Tesselator` (immediate-mode GL geometry builder), `Tile`, `Frustum`
- `com.mojang.rubydung.phys` — `AABB` (collision)
- `com.mojang.rubydung.net` — TCP multiplayer: `GameServer`, `GameClient`, `Connection`, `Packet`, `PacketWriter`

**Rendering pipeline:** `RubyDung.render()` → `setupCamera()` → `LevelRenderer.render()` (pass 0 = opaque, pass 1 = translucent) → HUD/menus via `beginOrtho()`/`endOrtho()`. All text is drawn via a built-in 5×7 bitmap font (`drawChar`).

**Settings** are persisted to `settings.properties`. Level is saved to `level.dat` (custom binary format in `Level.save()`/`Level.load()`).

**Multiplayer:** host calls `GameServer`, client calls `GameClient`. Both sync player positions each tick; block changes are broadcast via `sendSetTile`/`broadcastTile`. Screen state machine: 0=game, 1=pause, 2=settings, 3=multiplayer lobby, 4=join screen.

**Dependencies:** LWJGL 3.4.1 (GLFW + OpenGL + natives), JOML 1.10.7 (matrix math). Maven unpacks macOS natives to `target/natives/`; the custom `build.sh` uses the older LWJGL 2.9.3 jars from `~/.m2`.

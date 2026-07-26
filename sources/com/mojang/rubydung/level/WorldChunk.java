package com.mojang.rubydung.level;

import com.mojang.rubydung.phys.AABB;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.VkBuf;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

public class WorldChunk {
    public static final int SIZE = 16;
    public static final int HEIGHT = 128;
    // vertical mesh sections of 16x16x16: a block edit rebuilds one section, not the
    // whole 16x128x16 column, and empty (sky) sections cost nothing to build or draw.
    public static final int SECTION = 16;
    public static final int SECTIONS = HEIGHT / SECTION; // 8

    public final int cx, cz;
    public final AABB aabb;

    // blocks[y * SIZE * SIZE + z * SIZE + x] — storage stays monolithic (gen/save/light)
    final byte[] blocks = new byte[SIZE * HEIGHT * SIZE];
    final int[] lightDepths = new int[SIZE * SIZE];
    // propagated sky light 0..15 per block (BFS): full overhead sky = 15, decays 1 per block
    final byte[] skyLight = new byte[SIZE * HEIGHT * SIZE];
    // propagated block light 0..15 from emitters (torches). Kept separate from sky light
    // because only the sky half follows the day/night cycle.
    final byte[] blockLight = new byte[SIZE * HEIGHT * SIZE];
    private static final int MAX_LIGHT = 15;
    // BFS scratch queue: relighting runs on the chunk-gen threads, the mesh pool and the
    // main thread, so it is per-thread — a shared buffer would race, and a per-chunk one
    // would cost 128 KB times every loaded chunk.
    private static final ThreadLocal<int[]> lightQueue =
        ThreadLocal.withInitial(() -> new int[SIZE * HEIGHT * SIZE]);

    /** One renderable vertical slice (16^3). Owns its own mesh + dirty state. */
    private final class Section {
        final int sy;                 // section index 0..SECTIONS-1 (y0 = sy*SECTION)
        volatile boolean dirty = true;
        volatile boolean urgent = false;
        final AtomicBoolean rebuilding = new AtomicBoolean(false);
        final AtomicReference<MeshData> pendingMesh = new AtomicReference<>(null);
        final VkBuf[] buf = new VkBuf[2];
        final int[] vertexCount = new int[2];
        final float ay0, ay1;         // world-space Y bounds for frustum test
        Section(int sy) {
            this.sy = sy;
            this.ay0 = sy * SECTION;
            this.ay1 = ay0 + SECTION;
        }
    }

    // interleaved vertex array per layer (pos3+uv2+color4+light2 = 11 floats/vertex)
    private record MeshData(float[][] verts, int[] floatCount, int[] count) {}

    private final Section[] sections = new Section[SECTIONS];

    // true once a player/network edit touched this chunk: generated-but-untouched chunks
    // can be regenerated from the seed, edited ones must be flushed to disk before unloading
    volatile boolean modified = false;

    /** Flag this chunk as edited. Only Level's own mutators may call this — the generator must not. */
    public void markModified() { modified = true; }

    public static int rebuiltThisFrame = 0;
    public static int updates = 0;

    private final Level level;

    public WorldChunk(int cx, int cz, Level level) {
        this.cx = cx;
        this.cz = cz;
        this.level = level;
        int x0 = cx * SIZE, z0 = cz * SIZE;
        this.aabb = new AABB(x0, 0, z0, x0 + SIZE, HEIGHT, z0 + SIZE);
        for (int i = 0; i < SECTIONS; i++) sections[i] = new Section(i);
    }

    public byte getBlock(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return 0;
        return blocks[y * SIZE * SIZE + lz * SIZE + lx];
    }

    public void setBlock(int lx, int y, int lz, byte type) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return;
        int idx = y * SIZE * SIZE + lz * SIZE + lx;
        if (Tile.lightEmission(blocks[idx]) > 0 || Tile.lightEmission(type) > 0) emitters = null;
        blocks[idx] = type;
        markSectionDirty(y, false);
    }

    /** Mark the section owning world-y dirty; also the vertical neighbour if on a boundary. */
    private void markSectionDirty(int y, boolean urgent) {
        int sy = y / SECTION;
        if (sy < 0 || sy >= SECTIONS) return;
        if (urgent) sections[sy].urgent = true;
        sections[sy].dirty = true;
        int local = y - sy * SECTION;
        if (local == 0 && sy > 0)            { sections[sy - 1].dirty = true; if (urgent) sections[sy - 1].urgent = true; }
        if (local == SECTION - 1 && sy < SECTIONS - 1) { sections[sy + 1].dirty = true; if (urgent) sections[sy + 1].urgent = true; }
    }

    public void calcLightDepths() {
        for (int lx = 0; lx < SIZE; lx++) {
            for (int lz = 0; lz < SIZE; lz++) {
                lightDepths[lz * SIZE + lx] = surfaceDepth(lx, lz);
            }
        }
        calcSkyLight();
        calcBlockLight();
    }

    /**
     * Relight after a single block edit: only the edited column's depth can have
     * changed, so rescanning all 256 columns per placed block is wasted work.
     */
    public void updateLightAt(int lx, int lz) {
        if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE) return;
        lightDepths[lz * SIZE + lx] = surfaceDepth(lx, lz);
        calcSkyLight();
        calcBlockLight();
    }

    /** Highest y in this column that still blocks light (0 if the column is fully open). */
    private int surfaceDepth(int lx, int lz) {
        int y = HEIGHT - 1;
        while (y > 0) {
            if (blocksLight(getBlock(lx, y, lz))) break;
            y--;
        }
        return y;
    }

    private static boolean blocksLight(byte b) {
        // air, leaves, water and torches let light through (leaves give dappled, not full block)
        return Tile.isSolid(b);
    }

    /**
     * Sky-light BFS confined to this chunk: every column above its surface is full
     * sky (15); light then floods down and sideways losing 1 per step. This softens
     * cave mouths and shadow edges (smooth 0..15 falloff) instead of a hard 0.8/1.0
     * step. Kept per-chunk so parallel streaming never produces order-dependent seams.
     */
    private void calcSkyLight() {
        java.util.Arrays.fill(skyLight, (byte) 0);
        int[] queue = lightQueue.get();
        int head = 0, tail = 0;
        // seed: open columns from the top down to the first light blocker get full sky
        for (int lx = 0; lx < SIZE; lx++) {
            for (int lz = 0; lz < SIZE; lz++) {
                for (int y = HEIGHT - 1; y >= 0; y--) {
                    if (blocksLight(getBlock(lx, y, lz))) break;
                    int idx = y * SIZE * SIZE + lz * SIZE + lx;
                    skyLight[idx] = (byte) MAX_LIGHT;
                    queue[tail++] = idx;
                }
            }
        }
        // BFS spread of decreasing light into non-opaque neighbours
        final int SS = SIZE * SIZE;
        while (head < tail) {
            int idx = queue[head++];
            int y = idx / SS;
            int rem = idx - y * SS;
            int lz = rem / SIZE;
            int lx = rem - lz * SIZE;
            int next = skyLight[idx] - 1;
            if (next <= 0) continue;
            // unrolled 6-neighbour push; returns updated tail
            tail = pushLight(lx - 1, y, lz, next, queue, tail);
            tail = pushLight(lx + 1, y, lz, next, queue, tail);
            tail = pushLight(lx, y - 1, lz, next, queue, tail);
            tail = pushLight(lx, y + 1, lz, next, queue, tail);
            tail = pushLight(lx, y, lz - 1, next, queue, tail);
            tail = pushLight(lx, y, lz + 1, next, queue, tail);
        }
    }

    private int pushLight(int lx, int y, int lz, int level, int[] queue, int tail) {
        return push(skyLight, lx, y, lz, level, queue, tail);
    }

    /** Raise one cell of a light map to {@code level} and queue it, if that is an increase. */
    private int push(byte[] map, int lx, int y, int lz, int level, int[] queue, int tail) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return tail;
        if (blocksLight(getBlock(lx, y, lz))) return tail;
        int idx = y * SIZE * SIZE + lz * SIZE + lx;
        if (map[idx] >= level) return tail;
        map[idx] = (byte) level;
        queue[tail++] = idx;
        return tail;
    }

    /**
     * Block light from this chunk's own emitters only. Light that crosses a chunk border is
     * added afterwards by Level's world-space reflood: seeding a chunk from its neighbours'
     * light instead would be circular — two chunks would keep feeding each other the light of
     * a torch that has already been removed.
     */
    public void calcBlockLight() {
        java.util.Arrays.fill(blockLight, (byte) 0);
        final int SS = SIZE * SIZE;
        int[] queue = lightQueue.get();
        int head = 0, tail = 0;
        for (int idx : emitters()) {
            blockLight[idx] = (byte) Tile.lightEmission(blocks[idx]);
            queue[tail++] = idx;
        }
        while (head < tail) {
            int idx = queue[head++];
            int y = idx / SS;
            int rem = idx - y * SS;
            int lz = rem / SIZE;
            int lx = rem - lz * SIZE;
            int next = blockLight[idx] - 1;
            if (next <= 0) continue;
            tail = push(blockLight, lx - 1, y, lz, next, queue, tail);
            tail = push(blockLight, lx + 1, y, lz, next, queue, tail);
            tail = push(blockLight, lx, y - 1, lz, next, queue, tail);
            tail = push(blockLight, lx, y + 1, lz, next, queue, tail);
            tail = push(blockLight, lx, y, lz - 1, next, queue, tail);
            tail = push(blockLight, lx, y, lz + 1, next, queue, tail);
        }
    }

    // Emitting blocks are rare and only ever appear through Level.setTile, so their positions
    // are scanned once and cached rather than re-found on every relight.
    private static final int[] NO_EMITTERS = new int[0];
    private volatile int[] emitters;

    int[] emitters() {
        int[] e = emitters;
        if (e == null) {
            int n = 0;
            int[] found = null;
            for (int idx = 0; idx < blocks.length; idx++) {
                if (Tile.lightEmission(blocks[idx]) <= 0) continue;
                if (found == null) found = new int[16];
                else if (n == found.length) found = java.util.Arrays.copyOf(found, n * 2);
                found[n++] = idx;
            }
            e = (n == 0) ? NO_EMITTERS : java.util.Arrays.copyOf(found, n);
            emitters = e;
        }
        return e;
    }

    void invalidateEmitters() { emitters = null; }

    /** Raise one cell of block light, in chunk-local coords. Returns true if it changed. */
    boolean raiseBlockLight(int lx, int y, int lz, int level) {
        if (level <= 0 || lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return false;
        if (blocksLight(getBlock(lx, y, lz))) return false;
        int idx = y * SIZE * SIZE + lz * SIZE + lx;
        if (blockLight[idx] >= level) return false;
        blockLight[idx] = (byte) level;
        return true;
    }

    void clearBlockLight(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return;
        blockLight[y * SIZE * SIZE + lz * SIZE + lx] = 0;
    }

    int blockLightAt(int lx, int y, int lz) {
        return blockLight[y * SIZE * SIZE + lz * SIZE + lx];
    }

    public float getBrightness(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE || y < 0 || y >= HEIGHT) return 1.0f;
        int light = skyLight[y * SIZE * SIZE + lz * SIZE + lx];
        // map 0..15 -> 0.35..1.0 so deep dark stays moody but never pitch black
        return 0.35f + (light / (float) MAX_LIGHT) * 0.65f;
    }

    /** Block light 0..1 at a cell; 0 outside the chunk, since darkness is the default. */
    public float getBlockBrightness(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE || y < 0 || y >= HEIGHT) return 0f;
        return blockLight[y * SIZE * SIZE + lz * SIZE + lx] / (float) MAX_LIGHT;
    }

    public void setDirty() { for (Section s : sections) s.dirty = true; }
    /** Mark dirty AND request an immediate rebuild this frame (player edits). */
    public void setDirtyUrgent() { for (Section s : sections) { s.dirty = true; s.urgent = true; } }

    /** Mark only the sections covering world-y range [y0,y1] dirty (optionally urgent). */
    public void setDirtyRange(int y0, int y1, boolean urgent) {
        int s0 = Math.max(0, y0 / SECTION);
        int s1 = Math.min(SECTIONS - 1, y1 / SECTION);
        for (int sy = s0; sy <= s1; sy++) { sections[sy].dirty = true; if (urgent) sections[sy].urgent = true; }
    }

    /** Schedule a background mesh build for every dirty section (call after generation). */
    public void scheduleBuild() {
        for (Section s : sections) {
            if (!s.dirty) continue;
            if (s.rebuilding.getAndSet(true)) continue;
            s.dirty = false;
            final Section sec = s;
            java.util.concurrent.ForkJoinPool.commonPool().execute(() -> buildSectionSafe(sec));
        }
    }

    /** Build all sections synchronously on the calling thread (bulk parallel preload). */
    public void buildNow() {
        for (Section s : sections) {
            s.rebuilding.set(true);
            s.dirty = false;
            buildSectionSafe(s);
        }
    }

    /**
     * A section whose build throws would stay dirty=false/rebuilding=true forever, i.e.
     * a silent 16^3 hole in the world, so hand it back to the retry path. Only the first
     * failure is logged: a systematic bug would otherwise spam the console every frame.
     */
    private void buildSectionSafe(Section sec) {
        try {
            buildSection(sec);
        } catch (Throwable e) {
            if (!buildFailureLogged) {
                buildFailureLogged = true;
                System.err.println("chunk section build failed at chunk " + cx + "," + cz + " section " + sec.sy);
                e.printStackTrace();
            }
            sec.dirty = true;
            sec.rebuilding.set(false);
        }
    }

    private static volatile boolean buildFailureLogged = false;

    private void buildSection(Section sec) {
        float[][] verts = new float[2][];
        int[] floatCount = new int[2];
        int[] count = new int[2];
        int bx0 = cx * SIZE, bz0 = cz * SIZE;
        int y0 = sec.sy * SECTION, y1 = y0 + SECTION;

        for (int layer = 0; layer < 2; layer++) {
            Tesselator t = new Tesselator();
            t.init();
            for (int lx = 0; lx < SIZE; lx++) {
                for (int y = y0; y < y1; y++) {
                    for (int lz = 0; lz < SIZE; lz++) {
                        byte blockType = getBlock(lx, y, lz);
                        if (blockType == 0) continue;
                        int wx = bx0 + lx, wz = bz0 + lz;
                        boolean water = Tile.isWater(blockType);
                        Tile tile = water ? Tile.forWater(blockType) : getTile(blockType);
                        if (water) {
                            if (layer == 1) tile.render(t, level, layer, wx, y, wz);
                        } else {
                            tile.render(t, level, layer, wx, y, wz);
                        }
                    }
                }
            }
            verts[layer] = t.getBackingArray();
            count[layer] = t.getVertexCount();
            floatCount[layer] = count[layer] * Tesselator.FLOATS_PER_VERTEX;
        }
        sec.pendingMesh.set(new MeshData(verts, floatCount, count));
    }

    private Tile getTile(byte blockType) {
        return switch (blockType) {
            case 1  -> Tile.stone;
            case 2  -> Tile.wood;
            case 3  -> Tile.leaves;
            case 4  -> Tile.water;
            case 5  -> Tile.bedrock;
            case 6  -> Tile.coalOre;
            case 7  -> Tile.ironOre;
            case 8  -> Tile.goldOre;
            case 9  -> Tile.diamondOre;
            case 10 -> Tile.sand;
            case 11 -> Tile.gravel;
            case 12 -> Tile.grass;
            case 13 -> Tile.dirt;
            case 14 -> Tile.snow;
            case Tile.TORCH -> Tile.torch;
            default -> Tile.stone;
        };
    }

    private void uploadPending(Section sec) {
        MeshData mesh = sec.pendingMesh.getAndSet(null);
        if (mesh == null) return;
        GameRenderer r = GameRenderer.instance;
        for (int layer = 0; layer < 2; layer++) {
            sec.vertexCount[layer] = mesh.count()[layer];
            final VkBuf old = sec.buf[layer];
            if (old != null) r.deleter.enqueue(old::free);
            sec.buf[layer] = null;
            if (mesh.count()[layer] == 0) continue;
            float[] data = mesh.verts()[layer];
            int floats = mesh.floatCount()[layer];
            VkBuf vb = new VkBuf(r.ctx, (long) floats * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            vb.upload2(data, 0, floats);
            sec.buf[layer] = vb;
        }
        updates++;
        rebuiltThisFrame++;
        sec.rebuilding.set(false);
    }

    /** Render this chunk's sections for a layer, skipping sections outside the frustum. */
    public void render(int layer, Frustum frustum) {
        float x0 = cx * SIZE, z0 = cz * SIZE, x1 = x0 + SIZE, z1 = z0 + SIZE;
        for (Section sec : sections) {
            if (sec.pendingMesh.get() != null) uploadPending(sec);

            if (sec.dirty && sec.urgent && !sec.rebuilding.get()) {
                // player edit: rebuild now on this thread and upload immediately (no 1-frame lag)
                sec.urgent = false;
                sec.dirty = false;
                sec.rebuilding.set(true);
                buildSectionSafe(sec);
                uploadPending(sec);
            } else if (sec.dirty && !sec.rebuilding.get() && rebuiltThisFrame < 16) {
                sec.dirty = false;
                sec.rebuilding.set(true);
                final Section s = sec;
                java.util.concurrent.ForkJoinPool.commonPool().execute(() -> buildSectionSafe(s));
            }

            if (sec.vertexCount[layer] == 0 || sec.buf[layer] == null) continue;
            // per-section frustum cull (vertical slices behind you / above-below are skipped)
            if (frustum != null && !frustum.cubeInFrustum(x0, sec.ay0, z0, x1, sec.ay1, z1)) continue;
            GameRenderer.instance.draw(sec.buf[layer], sec.vertexCount[layer]);
        }
    }

    public void freeGL() {
        GameRenderer r = GameRenderer.instance;
        for (Section sec : sections) {
            for (int layer = 0; layer < 2; layer++) {
                final VkBuf b = sec.buf[layer];
                if (b != null && r != null) r.deleter.enqueue(b::free);
                sec.buf[layer] = null;
            }
        }
    }
}

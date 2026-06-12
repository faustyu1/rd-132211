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
    private static final int MAX_LIGHT = 15;

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

    // interleaved vertex array per layer (pos3+uv2+color4 = 9 floats/vertex)
    private record MeshData(float[][] verts, int[] floatCount, int[] count) {}

    private final Section[] sections = new Section[SECTIONS];

    public boolean hasMesh() {
        for (Section s : sections) if (s.vertexCount[0] > 0 || s.vertexCount[1] > 0) return true;
        return false;
    }

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
        blocks[y * SIZE * SIZE + lz * SIZE + lx] = type;
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
                int y = HEIGHT - 1;
                while (y > 0) {
                    byte b = getBlock(lx, y, lz);
                    if (b != 0 && b != 3 && !Tile.isWater(b)) break;
                    y--;
                }
                lightDepths[lz * SIZE + lx] = y;
            }
        }
        calcSkyLight();
    }

    private static boolean blocksLight(byte b) {
        // air, leaves and water let light through (leaves give dappled, not full block)
        return b != 0 && b != 3 && !Tile.isWater(b);
    }

    /**
     * Sky-light BFS confined to this chunk: every column above its surface is full
     * sky (15); light then floods down and sideways losing 1 per step. This softens
     * cave mouths and shadow edges (smooth 0..15 falloff) instead of a hard 0.8/1.0
     * step. Kept per-chunk so parallel streaming never produces order-dependent seams.
     */
    private void calcSkyLight() {
        java.util.Arrays.fill(skyLight, (byte) 0);
        int cap = SIZE * HEIGHT * SIZE;
        int[] queue = new int[cap];
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
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return tail;
        if (blocksLight(getBlock(lx, y, lz))) return tail;
        int idx = y * SIZE * SIZE + lz * SIZE + lx;
        if (skyLight[idx] >= level) return tail;
        skyLight[idx] = (byte) level;
        queue[tail++] = idx;
        return tail;
    }

    public float getBrightness(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE || y < 0 || y >= HEIGHT) return 1.0f;
        int light = skyLight[y * SIZE * SIZE + lz * SIZE + lx];
        // map 0..15 -> 0.35..1.0 so deep dark stays moody but never pitch black
        return 0.35f + (light / (float) MAX_LIGHT) * 0.65f;
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
            java.util.concurrent.ForkJoinPool.commonPool().execute(() -> buildSection(sec));
        }
    }

    /** Build all sections synchronously on the calling thread (bulk parallel preload). */
    public void buildNow() {
        for (Section s : sections) {
            s.rebuilding.set(true);
            s.dirty = false;
            buildSection(s);
        }
    }

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
            floatCount[layer] = count[layer] * 9;
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
                buildSection(sec);
                uploadPending(sec);
            } else if (sec.dirty && !sec.rebuilding.get() && rebuiltThisFrame < 16) {
                sec.dirty = false;
                sec.rebuilding.set(true);
                final Section s = sec;
                java.util.concurrent.ForkJoinPool.commonPool().execute(() -> buildSection(s));
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

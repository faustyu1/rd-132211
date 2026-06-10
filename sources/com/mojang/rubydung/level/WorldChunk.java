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

    public final int cx, cz;
    public final AABB aabb;

    // blocks[y * SIZE * SIZE + z * SIZE + x]
    final byte[] blocks = new byte[SIZE * HEIGHT * SIZE];
    final int[] lightDepths = new int[SIZE * SIZE];

    private volatile boolean dirty = true;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    // one interleaved vertex array per layer (pos3+uv2+color4 = 9 floats/vertex)
    private record MeshData(float[][] verts, int[] floatCount, int[] count) {}
    private final AtomicReference<MeshData> pendingMesh = new AtomicReference<>(null);

    private final VkBuf[] buf = new VkBuf[2];
    volatile int[] vertexCount = new int[2];
    public boolean hasMesh() { return vertexCount[0] > 0 || vertexCount[1] > 0; }

    public static int rebuiltThisFrame = 0;
    public static int updates = 0;

    private final Level level;

    public WorldChunk(int cx, int cz, Level level) {
        this.cx = cx;
        this.cz = cz;
        this.level = level;
        int x0 = cx * SIZE, z0 = cz * SIZE;
        this.aabb = new AABB(x0, 0, z0, x0 + SIZE, HEIGHT, z0 + SIZE);
    }

    public byte getBlock(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return 0;
        return blocks[y * SIZE * SIZE + lz * SIZE + lx];
    }

    public void setBlock(int lx, int y, int lz, byte type) {
        if (lx < 0 || lx >= SIZE || y < 0 || y >= HEIGHT || lz < 0 || lz >= SIZE) return;
        blocks[y * SIZE * SIZE + lz * SIZE + lx] = type;
        dirty = true;
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
    }

    public float getBrightness(int lx, int y, int lz) {
        if (lx < 0 || lx >= SIZE || lz < 0 || lz >= SIZE) return 1.0f;
        return y < lightDepths[lz * SIZE + lx] ? 0.8f : 1.0f;
    }

    public void setDirty() { dirty = true; }

    /** Schedule a background mesh build immediately (call right after generation). */
    public void scheduleBuild() {
        if (rebuilding.getAndSet(true)) return;
        dirty = false;
        java.util.concurrent.ForkJoinPool.commonPool().execute(this::buildMesh);
    }

    private void buildMesh() {
        float[][] verts = new float[2][];
        int[]     floatCount = new int[2];
        int[]     count = new int[2];
        int bx0 = cx * SIZE, bz0 = cz * SIZE;

        for (int layer = 0; layer < 2; layer++) {
            Tesselator t = new Tesselator();
            t.init();
            for (int lx = 0; lx < SIZE; lx++) {
                for (int y = 0; y < HEIGHT; y++) {
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
            // throwaway build Tesselator → take its backing array directly (no copy)
            verts[layer] = t.getBackingArray();
            count[layer] = t.getVertexCount();
            floatCount[layer] = count[layer] * 9;
        }
        pendingMesh.set(new MeshData(verts, floatCount, count));
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

    private void uploadPending() {
        MeshData mesh = pendingMesh.getAndSet(null);
        if (mesh == null) return;
        GameRenderer r = GameRenderer.instance;
        for (int layer = 0; layer < 2; layer++) {
            vertexCount[layer] = mesh.count()[layer];
            // defer-delete the old buffer (GPU may still be reading it)
            final VkBuf old = buf[layer];
            if (old != null) r.deleter.enqueue(old::free);
            buf[layer] = null;
            if (mesh.count()[layer] == 0) continue;
            float[] data = mesh.verts()[layer];
            int floats = mesh.floatCount()[layer];
            VkBuf vb = new VkBuf(r.ctx, (long) floats * 4, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            vb.upload2(data, 0, floats);
            buf[layer] = vb;
        }
        updates++;
        rebuiltThisFrame++;
        rebuilding.set(false);
    }

    public void render(int layer) {
        if (pendingMesh.get() != null) uploadPending();
        if (dirty && !rebuilding.get() && rebuiltThisFrame < 16) {
            dirty = false;
            rebuilding.set(true);
            java.util.concurrent.ForkJoinPool.commonPool().execute(this::buildMesh);
        }
        if (vertexCount[layer] == 0 || buf[layer] == null) return;
        GameRenderer.instance.draw(buf[layer], vertexCount[layer]);
    }

    public void freeGL() {
        GameRenderer r = GameRenderer.instance;
        for (int layer = 0; layer < 2; layer++) {
            final VkBuf b = buf[layer];
            if (b != null && r != null) r.deleter.enqueue(b::free);
            buf[layer] = null;
        }
    }
}

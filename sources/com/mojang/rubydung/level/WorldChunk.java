package com.mojang.rubydung.level;

import com.mojang.rubydung.Textures;
import com.mojang.rubydung.phys.AABB;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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

    private record MeshData(float[][] verts, float[][] tex, float[][] color, int[] count) {}
    private final AtomicReference<MeshData> pendingMesh = new AtomicReference<>(null);

    private final int[][] vbo = new int[2][3];
    volatile int[] vertexCount = new int[2];
    public boolean hasMesh() { return vertexCount[0] > 0 || vertexCount[1] > 0; }
    private volatile boolean vboReady = false; // VBOs allocated on main thread

    public static int rebuiltThisFrame = 0;
    public static int updates = 0;
    private static int texture = -1;

    private final Level level;

    public WorldChunk(int cx, int cz, Level level) {
        this.cx = cx;
        this.cz = cz;
        this.level = level;
        int x0 = cx * SIZE, z0 = cz * SIZE;
        this.aabb = new AABB(x0, 0, z0, x0 + SIZE, HEIGHT, z0 + SIZE);
        // VBOs are NOT created here — constructor may be called from background threads.
        // They are created lazily on the main thread in ensureVBOs().
    }

    /** Must be called on the GL (main) thread before any upload/render. */
    private void ensureVBOs() {
        if (vboReady) return;
        for (int layer = 0; layer < 2; layer++) {
            vbo[layer][0] = GL15.glGenBuffers();
            vbo[layer][1] = GL15.glGenBuffers();
            vbo[layer][2] = GL15.glGenBuffers();
        }
        if (texture == -1) texture = Textures.loadTexture("/terrain.png", GL11.GL_NEAREST);
        vboReady = true;
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
        float[][] tex   = new float[2][];
        float[][] color = new float[2][];
        int[]     count = new int[2];
        int bx0 = cx * SIZE, bz0 = cz * SIZE;

        for (int layer = 0; layer < 2; layer++) {
            Tesselator t = new Tesselator();
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
            verts[layer] = t.getVertexArray();
            tex[layer]   = t.getTexArray();
            color[layer] = t.getColorArray();
            count[layer] = t.getVertexCount();
        }
        pendingMesh.set(new MeshData(verts, tex, color, count));
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
        ensureVBOs();
        for (int layer = 0; layer < 2; layer++) {
            vertexCount[layer] = mesh.count()[layer];
            if (mesh.count()[layer] == 0) continue;
            FloatBuffer vb = MemoryUtil.memAllocFloat(mesh.verts()[layer].length);
            vb.put(mesh.verts()[layer]).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[layer][0]);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_DYNAMIC_DRAW);
            MemoryUtil.memFree(vb);
            FloatBuffer tb = MemoryUtil.memAllocFloat(mesh.tex()[layer].length);
            tb.put(mesh.tex()[layer]).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[layer][1]);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, tb, GL15.GL_DYNAMIC_DRAW);
            MemoryUtil.memFree(tb);
            FloatBuffer cb = MemoryUtil.memAllocFloat(mesh.color()[layer].length);
            cb.put(mesh.color()[layer]).flip();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[layer][2]);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, cb, GL15.GL_DYNAMIC_DRAW);
            MemoryUtil.memFree(cb);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
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
        if (vertexCount[layer] == 0) return;
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        Textures.bind(texture);
        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[layer][0]);
        GL11.glVertexPointer(3, GL11.GL_FLOAT, 0, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[layer][1]);
        GL11.glTexCoordPointer(2, GL11.GL_FLOAT, 0, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[layer][2]);
        GL11.glColorPointer(4, GL11.GL_FLOAT, 0, 0);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, vertexCount[layer]);
        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    public void freeGL() {
        if (!vboReady) return;
        for (int layer = 0; layer < 2; layer++)
            for (int i = 0; i < 3; i++) GL15.glDeleteBuffers(vbo[layer][i]);
    }
}

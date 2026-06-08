package com.mojang.rubydung.level;

import com.mojang.rubydung.Textures;
import com.mojang.rubydung.phys.AABB;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Chunk {
    public final AABB aabb;
    public final Level level;
    public final int x0, y0, z0;
    public final int x1, y1, z1;

    private volatile boolean dirty = true;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    // per-layer VBOs: [layer][0]=vertex, [layer][1]=texcoord, [layer][2]=color
    private final int[][] vbo = new int[2][3];
    private volatile int[] vertexCount = new int[2];

    /** Holds all mesh data for one completed build, published atomically. */
    private record MeshData(float[][] verts, float[][] tex, float[][] color, int[] count) {}

    // Single volatile reference — guarantees all fields visible atomically
    private final AtomicReference<MeshData> pendingMesh = new AtomicReference<>(null);

    private static final int texture = Textures.loadTexture("/terrain.png", GL11.GL_NEAREST);

    public static int rebuiltThisFrame = 0;
    public static int updates = 0;

    public Chunk(Level level, int x0, int y0, int z0, int x1, int y1, int z1) {
        this.level = level;
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.x1 = x1; this.y1 = y1; this.z1 = z1;
        this.aabb = new AABB(x0, y0, z0, x1, y1, z1);

        for (int layer = 0; layer < 2; layer++) {
            vbo[layer][0] = GL15.glGenBuffers();
            vbo[layer][1] = GL15.glGenBuffers();
            vbo[layer][2] = GL15.glGenBuffers();
        }
    }

    /** Called from worker thread — builds mesh data into plain float arrays (no GL calls). */
    private void buildMesh() {
        float[][] verts  = new float[2][];
        float[][] tex    = new float[2][];
        float[][] color  = new float[2][];
        int[]     count  = new int[2];

        for (int layer = 0; layer < 2; layer++) {
            Tesselator t = new Tesselator();
            for (int x = x0; x < x1; x++) {
                for (int y = y0; y < y1; y++) {
                    for (int z = z0; z < z1; z++) {
                        byte blockType = level.getBlock(x, y, z);
                        if (blockType == 0) continue;
                        Tile tile;
                        if (blockType == 2) tile = Tile.wood;
                        else if (blockType == 3) tile = Tile.leaves;
                        else if (blockType == 4) tile = Tile.water;
                        else if (blockType == 5) tile = Tile.bedrock;
                        else if (blockType == 6) tile = Tile.coalOre;
                        else if (blockType == 7) tile = Tile.ironOre;
                        else if (blockType == 8) tile = Tile.goldOre;
                        else if (blockType == 9) tile = Tile.diamondOre;
                        else if (blockType == 10) tile = Tile.sand;
                        else if (blockType == 11) tile = Tile.gravel;
                        else {
                            int surf = level.getSurfaceY(x, z);
                            Level.Biome biome = Level.Biome.values()[level.getBiome(x, z) & 0xFF];
                            if (y >= surf) {
                                if (biome == Level.Biome.DESERT) tile = Tile.dirt;
                                else if (biome == Level.Biome.MOUNTAINS && y > 35) tile = Tile.stone;
                                else tile = Tile.grass;
                            } else if (y >= surf - 3) tile = Tile.dirt;
                            else tile = Tile.stone;
                        }
                        // water renders in layer 1; everything else uses normal layer logic
                        if (blockType == 4) {
                            if (layer == 1) tile.render(t, level, layer, x, y, z);
                        } else {
                            tile.render(t, level, layer, x, y, z);
                        }
                    }
                }
            }
            verts[layer] = t.getVertexArray();
            tex[layer]   = t.getTexArray();
            color[layer] = t.getColorArray();
            count[layer] = t.getVertexCount();
        }

        // Publish all four arrays atomically via a single reference
        pendingMesh.set(new MeshData(verts, tex, color, count));
    }

    /** Called from main thread — uploads pending mesh data to GPU. */
    private void uploadPending() {
        MeshData mesh = pendingMesh.getAndSet(null);
        if (mesh == null) return;

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
        // upload if worker finished
        if (pendingMesh.get() != null) uploadPending();

        // kick off async rebuild if needed
        if (dirty && !rebuilding.get() && rebuiltThisFrame < 4) {
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
        GL11.glColorPointer(3, GL11.GL_FLOAT, 0, 0);

        GL11.glDrawArrays(GL11.GL_QUADS, 0, vertexCount[layer]);

        GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
        GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    }

    public void setDirty() {
        dirty = true;
    }
}

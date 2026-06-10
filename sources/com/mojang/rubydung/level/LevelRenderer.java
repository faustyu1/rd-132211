package com.mojang.rubydung.level;

import com.mojang.rubydung.HitResult;
import com.mojang.rubydung.Player;
import com.mojang.rubydung.Textures;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.Pipelines;
import com.mojang.rubydung.render.vk.VkTexture;

public class LevelRenderer implements LevelListener {
    private static final int CHUNK_SIZE = WorldChunk.SIZE;

    private final Level level;
    private final Tesselator t = new Tesselator();
    private VkTexture terrain;

    public LevelRenderer(Level level) {
        this.level = level;
        level.addListener(this);
    }

    private VkTexture terrain() {
        if (terrain == null) terrain = Textures.loadTexture("/terrain.png", false);
        return terrain;
    }

    public void render(Player player, int layer) {
        WorldChunk.rebuiltThisFrame = 0;
        GameRenderer r = GameRenderer.instance;
        r.setPipeline(layer == 0 ? Pipelines.Pipeline.WORLD_OPAQUE : Pipelines.Pipeline.WORLD_TRANSLUCENT);
        r.bindTexture(terrain());
        var frustum = Frustum.getFrustum();
        for (var chunk : level.getLoadedChunks()) {
            if (frustum.cubeInFrustum(chunk.aabb)) chunk.render(layer);
        }
    }

    public void renderHit(HitResult h) {
        GameRenderer r = GameRenderer.instance;
        r.setPipeline(Pipelines.Pipeline.LINES);
        r.bindWhite();
        float alpha = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f;
        r.setColor(1.0f, 1.0f, 1.0f, alpha);
        t.init();
        // wireframe box outline of the hit face as line pairs
        addFaceWireframe(h.x(), h.y(), h.z(), h.f());
        t.flushLines();
        r.setColor(1f, 1f, 1f, 1f);
    }

    /** Emit a face outline (4 edges = 8 line vertices) for the given block face. */
    private void addFaceWireframe(int x, int y, int z, int face) {
        float x0 = x, x1 = x + 1f, y0 = y, y1 = y + 1f, z0 = z, z1 = z + 1f;
        float[][] corners;
        switch (face) {
            case 0 -> corners = new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y0,z1},{x0,y0,z1}}; // bottom (y-)
            case 1 -> corners = new float[][]{{x0,y1,z0},{x1,y1,z0},{x1,y1,z1},{x0,y1,z1}}; // top (y+)
            case 2 -> corners = new float[][]{{x0,y0,z0},{x1,y0,z0},{x1,y1,z0},{x0,y1,z0}}; // z-
            case 3 -> corners = new float[][]{{x0,y0,z1},{x1,y0,z1},{x1,y1,z1},{x0,y1,z1}}; // z+
            case 4 -> corners = new float[][]{{x0,y0,z0},{x0,y0,z1},{x0,y1,z1},{x0,y1,z0}}; // x-
            default -> corners = new float[][]{{x1,y0,z0},{x1,y0,z1},{x1,y1,z1},{x1,y1,z0}}; // x+
        }
        for (int i = 0; i < 4; i++) {
            float[] a = corners[i];
            float[] b = corners[(i + 1) % 4];
            t.vertex(a[0], a[1], a[2]);
            t.vertex(b[0], b[1], b[2]);
        }
    }

    private void setDirty(int x0, int y0, int z0, int x1, int y1, int z1) {
        int cx0 = Math.floorDiv(x0, CHUNK_SIZE);
        int cx1 = Math.floorDiv(x1, CHUNK_SIZE);
        int cz0 = Math.floorDiv(z0, CHUNK_SIZE);
        int cz1 = Math.floorDiv(z1, CHUNK_SIZE);
        for (var chunk : level.getLoadedChunks()) {
            if (chunk.cx >= cx0 && chunk.cx <= cx1 && chunk.cz >= cz0 && chunk.cz <= cz1)
                chunk.setDirty();
        }
    }

    @Override
    public void tileChanged(int x, int y, int z) {
        setDirty(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    }

    @Override
    public void lightColumnChanged(int x, int z, int y0, int y1) {
        setDirty(x - 1, y0 - 1, z - 1, x + 1, y1 + 1, z + 1);
    }

    @Override
    public void allChanged() {
        for (var chunk : level.getLoadedChunks()) chunk.setDirty();
    }
}

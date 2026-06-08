package com.mojang.rubydung.level;

import com.mojang.rubydung.HitResult;
import com.mojang.rubydung.Player;
import org.lwjgl.opengl.GL11;

public class LevelRenderer implements LevelListener {
    private static final int CHUNK_SIZE = WorldChunk.SIZE;

    private final Level level;
    private final Tesselator t = new Tesselator();

    public LevelRenderer(Level level) {
        this.level = level;
        level.addListener(this);
    }

    public void render(Player player, int layer) {
        WorldChunk.rebuiltThisFrame = 0;
        var frustum = Frustum.getFrustum();
        for (var chunk : level.getLoadedChunks()) {
            if (frustum.cubeInFrustum(chunk.aabb)) chunk.render(layer);
        }
    }

    public void pick(Player player) {
        var box = player.bb.grow(3.0f, 3.0f, 3.0f);
        int x0 = (int) box.x0, x1 = (int) (box.x1 + 1.0f);
        int y0 = (int) box.y0, y1 = (int) (box.y1 + 1.0f);
        int z0 = (int) box.z0, z1 = (int) (box.z1 + 1.0f);

        GL11.glInitNames();
        for (int x = x0; x < x1; x++) {
            GL11.glPushName(x);
            for (int y = y0; y < y1; y++) {
                GL11.glPushName(y);
                for (int z = z0; z < z1; z++) {
                    GL11.glPushName(z);
                    if (level.isSolidTile(x, y, z)) {
                        GL11.glPushName(0);
                        for (int i = 0; i < 6; i++) {
                            GL11.glPushName(i);
                            t.init();
                            Tile.rock.renderFace(t, x, y, z, i);
                            t.flush();
                            GL11.glPopName();
                        }
                        GL11.glPopName();
                    }
                    GL11.glPopName();
                }
                GL11.glPopName();
            }
            GL11.glPopName();
        }
    }

    public void renderHit(HitResult h) {
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        float alpha = (float) Math.sin(System.currentTimeMillis() / 100.0) * 0.2f + 0.4f;
        GL11.glColor4f(1.0f, 1.0f, 1.0f, alpha);
        t.init();
        Tile.rock.renderFace(t, h.x(), h.y(), h.z(), h.f());
        t.flush();
        GL11.glDisable(GL11.GL_BLEND);
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

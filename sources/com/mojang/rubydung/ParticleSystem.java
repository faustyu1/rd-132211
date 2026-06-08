package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.level.Tile;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Lightweight block-break particles: small gravity-affected coloured cubes. */
public class ParticleSystem {
    private static final class Particle {
        float x, y, z, xd, yd, zd;
        float r, g, b;
        float life, maxLife;
        float size;
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Random rng = new Random();
    private static final int MAX = 4000;

    /** Spawn a burst of particles for a broken block. */
    public void spawnBlockBreak(int bx, int by, int bz, byte blockType) {
        float[] c = colorFor(blockType);
        int count = 24;
        for (int i = 0; i < count && particles.size() < MAX; i++) {
            Particle p = new Particle();
            p.x = bx + rng.nextFloat();
            p.y = by + rng.nextFloat();
            p.z = bz + rng.nextFloat();
            p.xd = (rng.nextFloat() - 0.5f) * 0.2f;
            p.yd = rng.nextFloat() * 0.2f + 0.05f;
            p.zd = (rng.nextFloat() - 0.5f) * 0.2f;
            float shade = 0.7f + rng.nextFloat() * 0.3f;
            p.r = c[0] * shade; p.g = c[1] * shade; p.b = c[2] * shade;
            p.maxLife = p.life = 0.6f + rng.nextFloat() * 0.6f;
            p.size = 0.08f + rng.nextFloat() * 0.06f;
            particles.add(p);
        }
    }

    public void tick() {
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= 0.05f;
            if (p.life <= 0) {
                // swap-remove: O(1) instead of O(n) shift
                particles.set(i, particles.get(particles.size() - 1));
                particles.remove(particles.size() - 1);
                continue;
            }
            p.yd -= 0.04f;
            p.x += p.xd; p.y += p.yd; p.z += p.zd;
            p.xd *= 0.92f; p.zd *= 0.92f; p.yd *= 0.98f;
        }
    }

    public void render(float a) {
        if (particles.isEmpty()) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBegin(GL11.GL_QUADS);
        for (Particle p : particles) {
            float s = p.size;
            GL11.glColor3f(p.r, p.g, p.b);
            float x0 = p.x - s, x1 = p.x + s, y0 = p.y - s, y1 = p.y + s, z0 = p.z - s, z1 = p.z + s;
            // a tiny camera-agnostic cube (cheap; few faces visible anyway)
            GL11.glVertex3f(x0,y0,z1); GL11.glVertex3f(x1,y0,z1); GL11.glVertex3f(x1,y1,z1); GL11.glVertex3f(x0,y1,z1);
            GL11.glVertex3f(x1,y0,z0); GL11.glVertex3f(x0,y0,z0); GL11.glVertex3f(x0,y1,z0); GL11.glVertex3f(x1,y1,z0);
            GL11.glVertex3f(x0,y1,z1); GL11.glVertex3f(x1,y1,z1); GL11.glVertex3f(x1,y1,z0); GL11.glVertex3f(x0,y1,z0);
            GL11.glVertex3f(x0,y0,z0); GL11.glVertex3f(x1,y0,z0); GL11.glVertex3f(x1,y0,z1); GL11.glVertex3f(x0,y0,z1);
        }
        GL11.glEnd();
        GL11.glColor3f(1f, 1f, 1f);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    public void clear() { particles.clear(); }

    /** Approximate tint colour for a block id (mirrors Tile tints). */
    private static float[] colorFor(byte b) {
        return switch (b) {
            case Tile.GRASS  -> new float[]{0.35f, 0.65f, 0.25f};
            case Tile.DIRT   -> new float[]{0.52f, 0.38f, 0.24f};
            case Tile.SAND   -> new float[]{0.86f, 0.80f, 0.56f};
            case Tile.SNOW   -> new float[]{0.95f, 0.97f, 1.00f};
            case Tile.WOOD   -> new float[]{0.45f, 0.31f, 0.16f};
            case Tile.LEAVES -> new float[]{0.42f, 0.62f, 0.30f};
            case Tile.COAL   -> new float[]{0.28f, 0.28f, 0.30f};
            case Tile.IRON   -> new float[]{0.78f, 0.66f, 0.52f};
            case Tile.GOLD   -> new float[]{0.92f, 0.80f, 0.30f};
            case Tile.DIAMOND-> new float[]{0.45f, 0.85f, 0.88f};
            case Tile.GRAVEL -> new float[]{0.52f, 0.52f, 0.55f};
            case Tile.BEDROCK-> new float[]{0.22f, 0.22f, 0.24f};
            default          -> new float[]{0.58f, 0.58f, 0.60f}; // stone
        };
    }
}

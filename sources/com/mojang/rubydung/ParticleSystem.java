package com.mojang.rubydung;

import com.mojang.rubydung.level.Tesselator;
import com.mojang.rubydung.level.Tile;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.Pipelines;

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
        float[] c = Tile.swatch(blockType);
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

    private final Tesselator t = new Tesselator();

    public void render(float a) {
        if (particles.isEmpty()) return;
        GameRenderer r = GameRenderer.instance;
        r.setPipeline(Pipelines.Pipeline.WORLD_OPAQUE);
        r.bindWhite();
        t.init();
        for (Particle p : particles) {
            float s = p.size;
            t.color(p.r, p.g, p.b, 1f);
            float x0 = p.x - s, x1 = p.x + s, y0 = p.y - s, y1 = p.y + s, z0 = p.z - s, z1 = p.z + s;
            // a tiny camera-agnostic cube (cheap; few faces visible anyway)
            t.vertex(x0,y0,z1); t.vertex(x1,y0,z1); t.vertex(x1,y1,z1); t.vertex(x0,y1,z1);
            t.vertex(x1,y0,z0); t.vertex(x0,y0,z0); t.vertex(x0,y1,z0); t.vertex(x1,y1,z0);
            t.vertex(x0,y1,z1); t.vertex(x1,y1,z1); t.vertex(x1,y1,z0); t.vertex(x0,y1,z0);
            t.vertex(x0,y0,z0); t.vertex(x1,y0,z0); t.vertex(x1,y0,z1); t.vertex(x0,y0,z1);
        }
        t.flush();
    }

    public void clear() { particles.clear(); }
}

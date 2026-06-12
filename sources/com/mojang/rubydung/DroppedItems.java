package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.level.Tesselator;
import com.mojang.rubydung.level.Tile;
import com.mojang.rubydung.phys.AABB;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.Pipelines;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Block drops: a small spinning coloured cube spawned when a block breaks.
 * Falls under gravity, collides with the world, is drawn into the world, and
 * is collected when the player walks close (bumping the matching hotbar slot).
 */
public class DroppedItems {

    private static final class Drop {
        float x, y, z, xd, yd, zd;
        byte type;
        int age;          // ticks since spawn
        int pickupDelay;  // ticks before it can be collected
        float spin;       // render rotation, radians
    }

    private static final float SIZE = 0.18f;       // half-extent of the cube
    private static final int   DESPAWN = 20 * 300; // ~5 min at 20 tps
    private static final float PICKUP_RANGE = 1.1f;

    private final List<Drop> drops = new ArrayList<>();
    private final Random rng = new Random();
    private final Level level;

    public DroppedItems(Level level) {
        this.level = level;
    }

    /** Spawn a drop for a broken block at the block's centre with a small pop. */
    public void spawn(int bx, int by, int bz, byte type) {
        if (type == 0 || Tile.isWater(type)) return;
        Drop d = new Drop();
        d.x = bx + 0.5f;
        d.y = by + 0.5f;
        d.z = bz + 0.5f;
        d.xd = (rng.nextFloat() - 0.5f) * 0.1f;
        d.yd = 0.12f + rng.nextFloat() * 0.04f;
        d.zd = (rng.nextFloat() - 0.5f) * 0.1f;
        d.type = type;
        d.pickupDelay = 10;
        d.spin = rng.nextFloat() * (float) (Math.PI * 2);
        drops.add(d);
    }

    /** Advance physics + handle pickup. Returns the block id collected this tick, or 0. */
    public byte tick(Player player) {
        byte collected = 0;
        for (int i = drops.size() - 1; i >= 0; i--) {
            Drop d = drops.get(i);
            d.age++;
            if (d.pickupDelay > 0) d.pickupDelay--;
            d.spin += 0.12f;
            if (d.age > DESPAWN) { swapRemove(i); continue; }

            // gravity + integrate with simple axis-separated world collision
            d.yd -= 0.04f;
            moveAxis(d, d.xd, 0, 0);
            moveAxis(d, 0, d.yd, 0);
            moveAxis(d, 0, 0, d.zd);
            d.xd *= 0.92f; d.zd *= 0.92f; d.yd *= 0.98f;

            // pickup: pull toward and collect when the player is close (survival/creative)
            if (d.pickupDelay == 0 && player != null && player.mode != Player.GameMode.SPECTATOR) {
                float dx = player.x - d.x, dy = (player.y - 0.9f) - d.y, dz = player.z - d.z;
                float dist2 = dx * dx + dy * dy + dz * dz;
                if (dist2 < PICKUP_RANGE * PICKUP_RANGE) {
                    collected = d.type;
                    swapRemove(i);
                }
            }
        }
        return collected;
    }

    /** Move one drop along a single axis, stopping against solid blocks. */
    private void moveAxis(Drop d, float ax, float ay, float az) {
        AABB box = new AABB(d.x - SIZE, d.y - SIZE, d.z - SIZE, d.x + SIZE, d.y + SIZE, d.z + SIZE);
        var cubes = level.getCubes(box.expand(ax, ay, az));
        float my = ay, mx = ax, mz = az;
        for (var c : cubes) my = c.clipYCollide(box, my);
        box.move(0, my, 0);
        for (var c : cubes) mx = c.clipXCollide(box, mx);
        box.move(mx, 0, 0);
        for (var c : cubes) mz = c.clipZCollide(box, mz);
        box.move(0, 0, mz);
        d.x = (box.x0 + box.x1) / 2f;
        d.y = (box.y0 + box.y1) / 2f;
        d.z = (box.z0 + box.z1) / 2f;
        if (my != ay) d.yd = 0;
        if (mx != ax) d.xd = 0;
        if (mz != az) d.zd = 0;
    }

    private void swapRemove(int i) {
        drops.set(i, drops.get(drops.size() - 1));
        drops.remove(drops.size() - 1);
    }

    public void clear() { drops.clear(); }

    private final Tesselator t = new Tesselator();

    public void render(float a) {
        if (drops.isEmpty()) return;
        GameRenderer r = GameRenderer.instance;
        r.setPipeline(Pipelines.Pipeline.WORLD_OPAQUE);
        r.bindWhite();
        for (Drop d : drops) {
            float[] col = colorFor(d.type);
            // gentle bob so drops read as floating items
            float bob = (float) Math.sin((d.age + a) * 0.15f) * 0.05f;
            float cx = d.x, cy = d.y + bob, cz = d.z;
            float c = (float) Math.cos(d.spin), s = (float) Math.sin(d.spin);
            t.init();
            // build a cube rotated about Y so it spins in place
            emitCube(cx, cy, cz, c, s, col);
            t.flush();
        }
    }

    /** Emit a SIZE-cube centred at (cx,cy,cz), rotated yaw (c=cos,s=sin), tinted col. */
    private void emitCube(float cx, float cy, float cz, float c, float s, float[] col) {
        // 8 corners in local space, rotated around Y then translated
        float e = SIZE;
        float[][] local = {
            {-e,-e,-e},{ e,-e,-e},{ e,-e, e},{-e,-e, e},
            {-e, e,-e},{ e, e,-e},{ e, e, e},{-e, e, e},
        };
        float[][] w = new float[8][3];
        for (int i = 0; i < 8; i++) {
            float lx = local[i][0], ly = local[i][1], lz = local[i][2];
            w[i][0] = cx + (lx * c - lz * s);
            w[i][1] = cy + ly;
            w[i][2] = cz + (lx * s + lz * c);
        }
        // face shading to fake lighting (top brightest, sides dimmer)
        quad(w, 4, 5, 6, 7, col, 1.0f);   // top
        quad(w, 3, 2, 1, 0, col, 0.5f);   // bottom
        quad(w, 0, 1, 5, 4, col, 0.8f);   // -z
        quad(w, 2, 3, 7, 6, col, 0.8f);   // +z
        quad(w, 3, 0, 4, 7, col, 0.6f);   // -x
        quad(w, 1, 2, 6, 5, col, 0.6f);   // +x
    }

    private void quad(float[][] w, int a, int b, int c, int d, float[] col, float shade) {
        t.color(col[0] * shade, col[1] * shade, col[2] * shade, 1f);
        t.vertex(w[a][0], w[a][1], w[a][2]);
        t.vertex(w[b][0], w[b][1], w[b][2]);
        t.vertex(w[c][0], w[c][1], w[c][2]);
        t.vertex(w[d][0], w[d][1], w[d][2]);
    }

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

package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.phys.AABB;

import static org.lwjgl.glfw.GLFW.*;

public class Player {

    public enum GameMode { SURVIVAL, CREATIVE, SPECTATOR }

    private final Level level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public float yRot, xRot;
    public AABB bb;
    public boolean onGround = false;
    public boolean sprinting = false;
    public boolean sneaking  = false;
    public boolean flying    = false;
    public GameMode mode = GameMode.CREATIVE;
    public float fallDistance = 0;

    // survival health, measured in half-hearts (0..20, i.e. 10 full hearts)
    public static final int MAX_HEALTH = 20;
    public int health = MAX_HEALTH;
    public int hurtTime = 0;          // ticks of red flash after taking damage
    public int invulnTime = 0;        // ticks of damage immunity
    private int regenTimer = 0;       // ticks until next natural regen point

    // double-tap W detection
    private long lastWTap  = 0;
    private boolean wWasUp = true;
    // double-tap Space detection for fly toggle
    private long lastSpaceTap  = 0;
    private boolean spaceWasUp = true;

    public Player(Level level) {
        this.level = level;
        resetPos();
    }

    // persistent spawn point (defaults to world origin column)
    public boolean hasSpawn = false;
    public float spawnX = 0.5f, spawnY, spawnZ = 0.5f;

    /** Set the respawn/reset point to a world position. */
    public void setSpawn(float sx, float sy, float sz) {
        spawnX = sx; spawnY = sy; spawnZ = sz; hasSpawn = true;
    }

    private void resetPos() {
        if (hasSpawn) {
            setPos(spawnX, spawnY, spawnZ);
        } else {
            int sy = level.getSurfaceY(0, 0);
            setPos(0.5f, sy + 2 + 1.62f, 0.5f);
        }
    }

    /** Teleport to an explicit position (used when restoring a saved game). */
    public void teleport(float px, float py, float pz, float yaw, float pitch) {
        setPos(px, py, pz);
        yRot = yaw; xRot = pitch;
        xd = yd = zd = 0; fallDistance = 0;
    }

    /** Apply damage in half-hearts; ignored in creative/spectator or while invulnerable. */
    public void hurt(int amount) {
        if (mode != GameMode.SURVIVAL || amount <= 0) return;
        if (invulnTime > 0) return;
        health = Math.max(0, health - amount);
        hurtTime = 10;
        invulnTime = 10;
        regenTimer = 0;
        if (health <= 0) respawn();
    }

    public void heal(int amount) {
        health = Math.min(MAX_HEALTH, health + amount);
    }

    public boolean isDead() { return mode == GameMode.SURVIVAL && health <= 0; }

    private void respawn() {
        health = MAX_HEALTH;
        hurtTime = 0;
        invulnTime = 20;
        fallDistance = 0;
        xd = yd = zd = 0;
        resetPos();
    }

    private void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.bb = new AABB(
            x - 0.3f,
            y - 0.9f,
            z - 0.3f,
            x + 0.3f,
            y + 0.9f,
            z + 0.3f
        );
    }

    public void turn(float xo, float yo) {
        yRot += xo * 0.15f;
        xRot -= yo * 0.15f;
        xRot = Math.clamp(xRot, -90.0f, 90.0f);
    }

    public void tick() {
        xo = x;
        yo = y;
        zo = z;

        if (hurtTime > 0) hurtTime--;
        if (invulnTime > 0) invulnTime--;
        // slow natural regen in survival: 1 half-heart per ~4s while not at full
        if (mode == GameMode.SURVIVAL && health > 0 && health < MAX_HEALTH) {
            if (++regenTimer >= 80) { regenTimer = 0; health++; }
        }
        // void damage instead of the old free teleport-on-fall
        if (y < -40) hurt(MAX_HEALTH);

        float xa = 0.0f;
        float ya = 0.0f;

        if (Input.isKeyDown(GLFW_KEY_R)) resetPos();
        // non-survival: free teleport back up if you fall out. survival takes void damage (above).
        if (y < -20 && mode != GameMode.SURVIVAL) resetPos();

        boolean wDown = Input.isKeyDown(GLFW_KEY_UP) || Input.isKeyDown(GLFW_KEY_W);
        if (Input.isKeyDown(GLFW_KEY_DOWN) || Input.isKeyDown(GLFW_KEY_S)) ya += 1.0f;
        if (Input.isKeyDown(GLFW_KEY_LEFT) || Input.isKeyDown(GLFW_KEY_A)) xa -= 1.0f;
        if (Input.isKeyDown(GLFW_KEY_RIGHT) || Input.isKeyDown(GLFW_KEY_D)) xa += 1.0f;
        if (wDown) ya -= 1.0f;

        // Shift = sneak
        sneaking = Input.isKeyDown(GLFW_KEY_LEFT_SHIFT) || Input.isKeyDown(GLFW_KEY_RIGHT_SHIFT);

        // double-tap W = sprint (cancel on sneak or not moving forward)
        if (wDown) {
            if (wWasUp) {
                long now = System.currentTimeMillis();
                if (now - lastWTap < 250) sprinting = true;
                lastWTap = now;
                wWasUp = false;
            }
        } else {
            wWasUp = true;
        }
        if (!wDown || sneaking) sprinting = false;

        boolean spaceDown = Input.isKeyDown(GLFW_KEY_SPACE) || Input.isKeyDown(GLFW_KEY_LEFT_ALT);

        // SPECTATOR: always flying, direct position update (noclip)
        if (mode == GameMode.SPECTATOR) {
            flying = true;
            yd = 0;
            if (spaceDown) yd = 0.1f;
            if (sneaking)  yd = -0.1f;
            moveRelative(xa, ya, 0.08f);
            x += xd; y += yd; z += zd;
            bb = new AABB(x-0.3f, y-0.9f, z-0.3f, x+0.3f, y+0.9f, z+0.3f);
            xd *= 0.8f; yd = 0; zd *= 0.8f;
            return;
        }

        // SURVIVAL: no flying
        if (mode == GameMode.SURVIVAL) {
            flying = false;
        }

        // double-tap Space = toggle fly (CREATIVE only)
        if (spaceDown) {
            if (spaceWasUp) {
                long now = System.currentTimeMillis();
                if (now - lastSpaceTap < 250 && mode == GameMode.CREATIVE) flying = !flying;
                lastSpaceTap = now;
                spaceWasUp = false;
            }
        } else {
            spaceWasUp = true;
        }

        if (flying) {
            yd = 0;
            if (spaceDown) yd =  0.1f;
            if (sneaking)  yd = -0.1f;
            moveRelative(xa, ya, 0.04f);
            move(xd, yd, zd);
            xd *= 0.8f; yd = 0; zd *= 0.8f;
            fallDistance = 0;
            return;
        }

        if (spaceDown && onGround) {
            yd = 0.14f;
        }

        float groundSpeed = sneaking ? 0.01f : (sprinting ? 0.04f : 0.02f);
        moveRelative(xa, ya, onGround ? groundSpeed : 0.005f);
        yd -= 0.005f;

        // track fall distance (SURVIVAL)
        if (!onGround && !inWater() && yd < 0) {
            fallDistance -= yd;
        } else if (onGround) {
            if (mode == GameMode.SURVIVAL && fallDistance > 3.0f) {
                // 1 half-heart per block fallen beyond 3 (water/landing already excluded)
                hurt((int) Math.floor(fallDistance - 3.0f));
            }
            fallDistance = 0;
        }
        if (inWater()) fallDistance = 0;

        // sneak: prevent walking off edges
        if (sneaking && onGround) {
            float below = bb.y0 - 0.5f;
            // check full new footprint after X move
            if (xd != 0 && level.getCubes(new AABB(bb.x0 + xd, below, bb.z0, bb.x1 + xd, bb.y0 - 0.01f, bb.z1)).isEmpty()) xd = 0;
            // check full new footprint after Z move
            if (zd != 0 && level.getCubes(new AABB(bb.x0, below, bb.z0 + zd, bb.x1, bb.y0 - 0.01f, bb.z1 + zd)).isEmpty()) zd = 0;
            // combined diagonal
            if (xd != 0 && zd != 0 && level.getCubes(new AABB(bb.x0 + xd, below, bb.z0 + zd, bb.x1 + xd, bb.y0 - 0.01f, bb.z1 + zd)).isEmpty()) { xd = 0; zd = 0; }
        }

        move(xd, yd, zd);
        if (inWater()) {
            xd *= 0.6f;
            yd *= 0.8f;
            zd *= 0.6f;
        } else {
            xd *= 0.91f;
            yd *= 0.98f;
            zd *= 0.91f;
            if (onGround) {
                xd *= 0.8f;
                zd *= 0.8f;
            }
        }
    }

    public boolean inWater() {
        return com.mojang.rubydung.level.Tile.isWater(level.getBlock((int) x, (int) (y - 0.5f), (int) z));
    }

    public void move(float xa, float ya, float za) {
        float yaOrig = ya;
        float xaOrig = xa;
        float zaOrig = za;

        // expand query region a bit extra so nearby blocks are never missed
        var aabbs = level.getCubes(
            bb.expand(xa, ya, za).grow(0.01f, 0.01f, 0.01f)
        );
        for (var aabb : aabbs) ya = aabb.clipYCollide(bb, ya);
        bb.move(0.0f, ya, 0.0f);
        for (var aabb : aabbs) xa = aabb.clipXCollide(bb, xa);
        bb.move(xa, 0.0f, 0.0f);
        for (var aabb : aabbs) za = aabb.clipZCollide(bb, za);
        bb.move(0.0f, 0.0f, za);

        onGround = ya != yaOrig && yaOrig < 0.0f;
        if (xa != xaOrig) xd = 0.0f;
        if (ya != yaOrig) yd = 0.0f;
        if (za != zaOrig) zd = 0.0f;

        x = (bb.x0 + bb.x1) / 2.0f;
        y = bb.y0 + 1.62f;
        z = (bb.z0 + bb.z1) / 2.0f;
    }

    public void moveRelative(float xa, float za, float speed) {
        float dist = xa * xa + za * za;
        if (dist < 0.01f) return;

        float scale = speed / (float) Math.sqrt(dist);
        float xa2 = xa * scale;
        float za2 = za * scale;
        double yRadians = Math.toRadians(yRot);
        float sin = (float) Math.sin(yRadians);
        float cos = (float) Math.cos(yRadians);
        xd += xa2 * cos - za2 * sin;
        zd += za2 * cos + xa2 * sin;
    }
}

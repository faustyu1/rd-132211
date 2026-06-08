package com.mojang.rubydung.phys;

public class AABB {
    public float x0, y0, z0;
    public float x1, y1, z1;

    public AABB(float x0, float y0, float z0, float x1, float y1, float z1) {
        this.x0 = x0; this.y0 = y0; this.z0 = z0;
        this.x1 = x1; this.y1 = y1; this.z1 = z1;
    }

    public AABB expand(float xa, float ya, float za) {
        float _x0 = x0, _y0 = y0, _z0 = z0;
        float _x1 = x1, _y1 = y1, _z1 = z1;
        if (xa < 0) _x0 += xa; else _x1 += xa;
        if (ya < 0) _y0 += ya; else _y1 += ya;
        if (za < 0) _z0 += za; else _z1 += za;
        return new AABB(_x0, _y0, _z0, _x1, _y1, _z1);
    }

    public AABB grow(float xa, float ya, float za) {
        return new AABB(x0 - xa, y0 - ya, z0 - za, x1 + xa, y1 + ya, z1 + za);
    }

    public float clipXCollide(AABB c, float xa) {
        if (c.y1 <= y0 || c.y0 >= y1 || c.z1 <= z0 || c.z0 >= z1) return xa;
        if (xa > 0 && c.x1 <= x0) xa = Math.min(xa, x0 - c.x1);
        if (xa < 0 && c.x0 >= x1) xa = Math.max(xa, x1 - c.x0);
        return xa;
    }

    public float clipYCollide(AABB c, float ya) {
        if (c.x1 <= x0 || c.x0 >= x1 || c.z1 <= z0 || c.z0 >= z1) return ya;
        if (ya > 0 && c.y1 <= y0) ya = Math.min(ya, y0 - c.y1);
        if (ya < 0 && c.y0 >= y1) ya = Math.max(ya, y1 - c.y0);
        return ya;
    }

    public float clipZCollide(AABB c, float za) {
        if (c.x1 <= x0 || c.x0 >= x1 || c.y1 <= y0 || c.y0 >= y1) return za;
        if (za > 0 && c.z1 <= z0) za = Math.min(za, z0 - c.z1);
        if (za < 0 && c.z0 >= z1) za = Math.max(za, z1 - c.z0);
        return za;
    }

    public boolean intersects(AABB c) {
        return c.x1 > x0 && c.x0 < x1 && c.y1 > y0 && c.y0 < y1 && c.z1 > z0 && c.z0 < z1;
    }

    public void move(float xa, float ya, float za) {
        x0 += xa; y0 += ya; z0 += za;
        x1 += xa; y1 += ya; z1 += za;
    }

}

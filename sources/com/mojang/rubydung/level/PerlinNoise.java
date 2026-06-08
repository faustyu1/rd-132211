package com.mojang.rubydung.level;

import java.util.Random;

/**
 * Classic Perlin noise with both 2D and 3D variants.
 * - {@link #octave}/{@link #octave3} return fractal noise normalised to 0..1.
 * - {@link #noise}/{@link #noise3} return raw noise roughly in -1..1 (used for ridged/tunnel caves).
 */
public class PerlinNoise {
    private final int[] p = new int[512];

    public PerlinNoise(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        Random rng = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = perm[i]; perm[i] = perm[j]; perm[j] = tmp;
        }
        for (int i = 0; i < 256; i++) p[i] = p[i + 256] = perm[i];
    }

    // ---- 2D ----
    public double noise(double x, double y) {
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255;
        x -= Math.floor(x); y -= Math.floor(y);
        double u = fade(x), v = fade(y);
        int a = p[X] + Y, b = p[X + 1] + Y;
        return lerp(v, lerp(u, grad(p[a], x, y), grad(p[b], x - 1, y)),
                       lerp(u, grad(p[a + 1], x, y - 1), grad(p[b + 1], x - 1, y - 1)));
    }

    public double octave(double x, double y, int octaves) {
        double result = 0, amp = 1, freq = 1, max = 0;
        for (int i = 0; i < octaves; i++) {
            result += noise(x * freq, y * freq) * amp;
            max += amp; amp *= 0.5; freq *= 2;
        }
        return (result / max + 1) * 0.5;
    }

    // ---- 3D ----
    public double noise3(double x, double y, double z) {
        int X = (int) Math.floor(x) & 255, Y = (int) Math.floor(y) & 255, Z = (int) Math.floor(z) & 255;
        x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
        double u = fade(x), v = fade(y), w = fade(z);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z;
        int B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        return lerp(w,
            lerp(v, lerp(u, grad3(p[AA], x, y, z),     grad3(p[BA], x - 1, y, z)),
                    lerp(u, grad3(p[AB], x, y - 1, z), grad3(p[BB], x - 1, y - 1, z))),
            lerp(v, lerp(u, grad3(p[AA + 1], x, y, z - 1),     grad3(p[BA + 1], x - 1, y, z - 1)),
                    lerp(u, grad3(p[AB + 1], x, y - 1, z - 1), grad3(p[BB + 1], x - 1, y - 1, z - 1))));
    }

    public double octave3(double x, double y, double z, int octaves) {
        double result = 0, amp = 1, freq = 1, max = 0;
        for (int i = 0; i < octaves; i++) {
            result += noise3(x * freq, y * freq, z * freq) * amp;
            max += amp; amp *= 0.5; freq *= 2;
        }
        return (result / max + 1) * 0.5;
    }

    private double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private double lerp(double t, double a, double b) { return a + t * (b - a); }

    private double grad(int hash, double x, double y) {
        int h = hash & 3;
        double u = h < 2 ? x : y, v = h < 2 ? y : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private double grad3(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
}

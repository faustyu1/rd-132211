package com.mojang.rubydung.render;

import com.mojang.rubydung.level.Tesselator;

/**
 * Immediate-mode emulation for UI and simple 3D primitives, replacing the old
 * glBegin/glColor/glVertex/glEnd calls. Geometry is accumulated in a Tesselator and
 * flushed through the Vulkan GameRenderer using whatever pipeline/texture is currently set.
 *
 * Modes:
 *   QUADS      — vertices in groups of 4, drawn as two triangles each.
 *   LINE_LOOP  — N vertices forming a closed outline (emitted as N line segments).
 *   LINES      — vertices in pairs.
 */
public final class Imm {
    public static final int QUADS = 0;
    public static final int LINE_LOOP = 1;
    public static final int LINES = 2;

    private final Tesselator t = new Tesselator();
    private int mode;
    private float r = 1, g = 1, b = 1, a = 1;

    // buffered vertices for LINE_LOOP closing
    private float[] lx = new float[256], ly = new float[256], lz = new float[256];
    private int lineCount = 0;

    public void color(float r, float g, float b, float a) {
        this.r = r; this.g = g; this.b = b; this.a = a;
    }

    public void begin(int mode) {
        this.mode = mode;
        t.init();
        lineCount = 0;
    }

    public void vertex2(float x, float y) { vertex3(x, y, 0f); }

    public void vertex3(float x, float y, float z) {
        if (mode == QUADS) {
            t.color(r, g, b, a);
            t.vertex(x, y, z);
        } else {
            if (lineCount == lx.length) grow();
            lx[lineCount] = x; ly[lineCount] = y; lz[lineCount] = z;
            lineCount++;
        }
    }

    /** Set texture coordinate for the next vertex (QUADS, textured draws). */
    public void tex(float u, float v) { t.tex(u, v); }

    public void end() {        if (mode == QUADS) {
            t.flush();
            return;
        }
        // build line list
        t.init();
        if (mode == LINE_LOOP) {
            for (int i = 0; i < lineCount; i++) {
                int j = (i + 1) % lineCount;
                emitLineVert(i);
                emitLineVert(j);
            }
        } else { // LINES — already in pairs
            for (int i = 0; i < lineCount; i++) emitLineVert(i);
        }
        t.flushLines();
    }

    private void emitLineVert(int i) {
        t.color(r, g, b, a);
        t.vertex(lx[i], ly[i], lz[i]);
    }

    private void grow() {
        int n = lx.length * 2;
        lx = java.util.Arrays.copyOf(lx, n);
        ly = java.util.Arrays.copyOf(ly, n);
        lz = java.util.Arrays.copyOf(lz, n);
    }
}

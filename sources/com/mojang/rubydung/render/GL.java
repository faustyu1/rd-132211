package com.mojang.rubydung.render;

import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.Pipelines;

/**
 * Thin fixed-function GL compatibility shim used by the UI / simple-primitive code in RubyDung.
 * It delegates immediate-mode geometry to {@link Imm} and the Vulkan {@link GameRenderer},
 * so the legacy {@code GL11.glColor/glBegin/glVertex/glEnd} call sites convert almost 1:1
 * (just {@code GL11.} -> {@code GL.}).
 *
 * State machine:
 *  - beginOrtho()/endOrtho() toggle UI (ortho) vs 3D mode; this decides the pipeline.
 *  - All shim draws are untextured (white texture); textured text goes through FontRenderer.
 *  - glEnable/Disable/glBlendFunc/glLineWidth are mostly no-ops (handled by pipeline objects).
 */
public final class GL {
    private GL() {}

    // primitive modes
    public static final int GL_QUADS     = 0x0007;
    public static final int GL_LINE_LOOP = 0x0002;
    public static final int GL_LINES     = 0x0001;

    // state enum constants (consumed only by no-op enable/disable/blend)
    public static final int GL_BLEND = 1, GL_TEXTURE_2D = 2;
    public static final int GL_SRC_ALPHA = 10, GL_ONE_MINUS_SRC_ALPHA = 11,
        GL_ONE_MINUS_DST_COLOR = 13, GL_ZERO = 14;

    private static final Imm imm = new Imm();
    private static boolean ortho = false;
    private static Pipelines.Pipeline pipeline3D = Pipelines.Pipeline.WORLD_TRANSLUCENT;

    // ── mode control ──
    public static void setOrtho(boolean o) { ortho = o; }
    public static void set3DQuadPipeline(Pipelines.Pipeline p) { pipeline3D = p; }

    // ── color ──
    public static void glColor3f(float r, float g, float b) { imm.color(r, g, b, 1f); }
    public static void glColor4f(float r, float g, float b, float a) { imm.color(r, g, b, a); }

    // ── immediate mode ──
    public static void glBegin(int mode) {
        GameRenderer r = GameRenderer.instance;
        boolean lines = (mode == GL_LINE_LOOP || mode == GL_LINES);
        if (ortho) {
            r.setPipeline(lines ? Pipelines.Pipeline.UI_LINES : Pipelines.Pipeline.UI);
        } else {
            r.setPipeline(lines ? Pipelines.Pipeline.LINES : pipeline3D);
        }
        r.bindWhite();
        switch (mode) {
            case GL_QUADS -> imm.begin(Imm.QUADS);
            case GL_LINE_LOOP -> imm.begin(Imm.LINE_LOOP);
            default -> imm.begin(Imm.LINES);
        }
    }

    public static void glVertex2f(float x, float y) { imm.vertex2(x, y); }
    public static void glVertex3f(float x, float y, float z) { imm.vertex3(x, y, z); }
    public static void glEnd() { imm.end(); }

    // ── no-op state (pipelines encode this) ──
    public static void glEnable(int cap) {}
    public static void glDisable(int cap) {}
    public static void glBlendFunc(int src, int dst) {}
    public static void glLineWidth(float w) {}
}

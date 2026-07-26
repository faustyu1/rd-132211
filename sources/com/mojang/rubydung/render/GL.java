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
 *  - glEnable/Disable are no-ops (the pipelines encode that state); glBlendFunc only
 *    distinguishes the classic inverse blend from normal alpha blending.
 */
public final class GL {
    private GL() {}

    // primitive modes
    public static final int GL_QUADS     = 0x0007;
    public static final int GL_LINE_LOOP = 0x0002;
    public static final int GL_LINES     = 0x0001;

    // state enum constants (consumed by the no-op enable/disable and by glBlendFunc)
    public static final int GL_BLEND = 1, GL_TEXTURE_2D = 2;
    public static final int GL_SRC_ALPHA = 10, GL_ONE_MINUS_SRC_ALPHA = 11,
        GL_ONE_MINUS_DST_COLOR = 13, GL_ZERO = 14;

    private static final Imm imm = new Imm();
    private static boolean ortho = false;
    private static Pipelines.Pipeline pipeline3D = Pipelines.Pipeline.WORLD_TRANSLUCENT;
    private static boolean invertBlend = false;

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
            Pipelines.Pipeline quads = invertBlend ? Pipelines.Pipeline.UI_INVERT : Pipelines.Pipeline.UI;
            r.setPipeline(lines ? Pipelines.Pipeline.UI_LINES : quads);
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

    /** Reads back the colour last set with glColor — FontRenderer takes colour as an argument. */
    public static void color(float[] out) { imm.getColor(out); }

    public static void glVertex2f(float x, float y) { imm.vertex2(x, y); }
    public static void glVertex3f(float x, float y, float z) { imm.vertex3(x, y, z); }
    public static void glEnd() { imm.end(); }

    // ── no-op state (pipelines encode this) ──
    public static void glEnable(int cap) {}

    /**
     * No-op except for GL_BLEND, which resets the remembered blend function the way real GL
     * would: without this the inverse blend requested by the crosshair would stay latched and
     * silently apply to the next ortho quad drawn by a call site that forgot its glBlendFunc.
     */
    public static void glDisable(int cap) {
        if (cap == GL_BLEND) invertBlend = false;
    }

    /**
     * Remembers the requested blend function so the next glBegin can pick a pipeline. Only the
     * classic inverse blend (ONE_MINUS_DST_COLOR/ZERO, used by the crosshair) has its own
     * pipeline; every other combination maps to the standard SRC_ALPHA/ONE_MINUS_SRC_ALPHA one.
     */
    public static void glBlendFunc(int src, int dst) {
        invertBlend = (src == GL_ONE_MINUS_DST_COLOR && dst == GL_ZERO);
    }

    /**
     * No-op, and it cannot be otherwise: MoltenVK does not support the wideLines feature, so all
     * pipelines are built with lineWidth 1.0 and any other width would be a validation error.
     * Call sites asking for 2f get a 1px line.
     */
    public static void glLineWidth(float w) {}
}

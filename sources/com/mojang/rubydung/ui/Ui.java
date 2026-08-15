package com.mojang.rubydung.ui;

import com.mojang.rubydung.render.GL;

/**
 * The one place the interface's look is decided: a dark slate surface, hairline edges and a
 * single amber accent picked up from the logo. Every screen — menus, inventory, HUD — draws
 * through these, so a change here changes the whole game rather than one screen.
 *
 * Only flat rectangles: the renderer behind this is an immediate-mode shim over Vulkan, and
 * keeping the vocabulary that small is what lets a panel, a button and a slot read as parts of
 * the same object instead of three separately invented widgets.
 *
 * Coordinates are GUI units, not framebuffer pixels — {@code RubyDung.beginOrtho} scales them.
 */
public final class Ui {
    private Ui() {}

    // surfaces
    public static final float[] SCREEN_DIM = {0.03f, 0.04f, 0.06f, 0.72f};
    public static final float[] PANEL      = {0.09f, 0.10f, 0.13f, 0.94f};
    public static final float[] PANEL_HEAD = {0.13f, 0.14f, 0.18f, 0.96f};
    public static final float[] SUNKEN     = {0.05f, 0.055f, 0.075f, 0.92f};
    public static final float[] RAISED     = {1f, 1f, 1f, 0.06f};
    public static final float[] EDGE       = {1f, 1f, 1f, 0.10f};
    public static final float[] EDGE_SOFT  = {1f, 1f, 1f, 0.05f};

    // accent + text
    public static final float[] ACCENT     = {0.98f, 0.76f, 0.32f, 1f};
    public static final float[] ACCENT_DIM = {0.98f, 0.76f, 0.32f, 0.18f};
    public static final float[] TEXT       = {0.92f, 0.93f, 0.96f, 1f};
    public static final float[] TEXT_DIM   = {0.62f, 0.65f, 0.72f, 1f};
    public static final float[] TEXT_DARK  = {0.05f, 0.05f, 0.07f, 1f};

    public static void color(float[] c) { GL.glColor4f(c[0], c[1], c[2], c[3]); }

    public static void color(float[] c, float alpha) { GL.glColor4f(c[0], c[1], c[2], c[3] * alpha); }

    public static void rect(int x, int y, int w, int h, float[] c) {
        color(c);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(x, y); GL.glVertex2f(x + w, y);
        GL.glVertex2f(x + w, y + h); GL.glVertex2f(x, y + h);
        GL.glEnd();
    }

    public static void rect(int x, int y, int w, int h, float[] c, float alpha) {
        color(c, alpha);
        GL.glBegin(GL.GL_QUADS);
        GL.glVertex2f(x, y); GL.glVertex2f(x + w, y);
        GL.glVertex2f(x + w, y + h); GL.glVertex2f(x, y + h);
        GL.glEnd();
    }

    /** A frame drawn as four rectangles — line width is unreliable across drivers. */
    public static void border(int x, int y, int w, int h, int t, float[] c) {
        rect(x, y, w, t, c);
        rect(x, y + h - t, w, t, c);
        rect(x, y, t, h, c);
        rect(x + w - t, y, t, h, c);
    }

    /**
     * A window: dark body, hairline edge, and an accent rule under the title strip. The rule
     * is the whole signature of the style — panels, list rows and the hotbar all repeat it.
     */
    public static void panel(int x, int y, int w, int h, int headerH) {
        rect(x + 2, y + 3, w, h, new float[]{0f, 0f, 0f, 0.35f});   // drop shadow
        rect(x, y, w, h, PANEL);
        if (headerH > 0) {
            rect(x, y, w, headerH, PANEL_HEAD);
            rect(x, y + headerH - 1, w, 1, ACCENT, 0.55f);
        }
        border(x, y, w, h, 1, EDGE);
    }

    /** A container for one item: sunk into the panel, lit when the mouse is over it. */
    public static void slot(int x, int y, int s, boolean hovered, boolean selected) {
        rect(x, y, s, s, SUNKEN);
        if (hovered) rect(x, y, s, s, ACCENT_DIM);
        border(x, y, s, s, 1, hovered || selected ? ACCENT : EDGE);
        if (selected) {
            border(x - 1, y - 1, s + 2, s + 2, 1, ACCENT);
            rect(x, y + s - 2, s, 2, ACCENT);
        }
    }

    /** A clickable surface: flat fill plus an accent spine on the left while hovered. */
    public static void button(int x, int y, int w, int h, boolean hovered, boolean enabled) {
        rect(x, y, w, h, hovered ? ACCENT_DIM : RAISED);
        border(x, y, w, h, 1, hovered ? ACCENT : EDGE);
        if (hovered) rect(x, y, 2, h, ACCENT);
        if (!enabled) rect(x, y, w, h, SCREEN_DIM, 0.6f);
    }

    /** A text field: darker than a button so a form reads as inputs, not as more buttons. */
    public static void field(int x, int y, int w, int h, boolean focused) {
        rect(x, y, w, h, SUNKEN);
        border(x, y, w, h, 1, focused ? ACCENT : EDGE);
        if (focused) rect(x, y + h - 2, w, 2, ACCENT);
    }
}

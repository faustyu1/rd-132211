package com.mojang.rubydung;

import com.mojang.rubydung.render.GL;

/**
 * The original 5x7 block font, kept for the logo and the screen titles.
 *
 * Everything else in the game draws through {@link FontRenderer}'s texture atlas, which has
 * lowercase and Cyrillic and looks right at small sizes. But a smooth sans-serif "RUBYDUNG"
 * reads as a word processor, not as a voxel game — the headline type is part of the game's
 * identity, and blocky letters made of actual squares are the point. So the two live side by
 * side: this one for headlines, the atlas for text.
 *
 * Only uppercase ASCII, digits and a little punctuation exist here. {@link #canRender} says
 * whether a string is fully covered, so a caller can fall back to the atlas instead of
 * dropping characters silently, which is what the old code did.
 */
public final class PixelFont {
    private PixelFont() {}

    private static final int[][] GLYPHS = {
        {0b11111, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11111}, // 0
        {0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}, // 1
        {0b11111, 0b00001, 0b00001, 0b11111, 0b10000, 0b10000, 0b11111}, // 2
        {0b11111, 0b00001, 0b00001, 0b11111, 0b00001, 0b00001, 0b11111}, // 3
        {0b10001, 0b10001, 0b10001, 0b11111, 0b00001, 0b00001, 0b00001}, // 4
        {0b11111, 0b10000, 0b10000, 0b11111, 0b00001, 0b00001, 0b11111}, // 5
        {0b11111, 0b10000, 0b10000, 0b11111, 0b10001, 0b10001, 0b11111}, // 6
        {0b11111, 0b00001, 0b00001, 0b00011, 0b00010, 0b00100, 0b00100}, // 7
        {0b11111, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b11111}, // 8
        {0b11111, 0b10001, 0b10001, 0b11111, 0b00001, 0b00001, 0b11111}, // 9
        {0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}, // A
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110}, // B
        {0b01111, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b01111}, // C
        {0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110}, // D
        {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111}, // E
        {0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000}, // F
        {0b01111, 0b10000, 0b10000, 0b10011, 0b10001, 0b10001, 0b01111}, // G
        {0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001}, // H
        {0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110}, // I
        {0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100}, // J
        {0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001}, // K
        {0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111}, // L
        {0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001}, // M
        {0b10001, 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001}, // N
        {0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}, // O
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000}, // P
        {0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101}, // Q
        {0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001}, // R
        {0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110}, // S
        {0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100}, // T
        {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110}, // U
        {0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100}, // V
        {0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b10101, 0b01010}, // W
        {0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001}, // X
        {0b10001, 0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100}, // Y
        {0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111}, // Z
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000}, // ' '
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100}, // '.'
        {0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00000, 0b00100}, // '!'
        {0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000}, // '-'
        {0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000}, // ':'
        {0b00001, 0b00010, 0b00100, 0b00100, 0b01000, 0b10000, 0b00000}, // '/'
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00100, 0b00100}, // ',' (approx)
        {0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b11111}, // '_'
    };
    // Direct char→glyph index lookup. -1 = unsupported character.
    private static final int[] GLYPH_INDEX;
    static {
        char[] keys = {
            '0','1','2','3','4','5','6','7','8','9',
            'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S','T','U','V','W','X','Y','Z',
            ' ','.','!','-',':','/',',','_'
        };
        int maxChar = 0;
        for (char k : keys) if (k > maxChar) maxChar = k;
        GLYPH_INDEX = new int[maxChar + 1];
        java.util.Arrays.fill(GLYPH_INDEX, -1);
        for (int i = 0; i < keys.length; i++) GLYPH_INDEX[keys[i]] = i;
    }

    /** True if every character has a glyph — otherwise the caller should use FontRenderer. */
    public static boolean canRender(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') c -= 32;
            if (c >= GLYPH_INDEX.length || GLYPH_INDEX[c] < 0) return false;
        }
        return true;
    }

    /** Width of a string drawn with the given cell width and letter pitch. */
    public static int width(String s, int charW, int pitch) {
        return s.isEmpty() ? 0 : (s.length() - 1) * pitch + charW;
    }

    /**
     * Draw a string as filled squares, one batch for the whole string. Colour comes from the
     * current GL shim colour, like every other primitive here.
     */
    public static void draw(String s, int x, int y, int charW, int charH, int pitch) {
        int pixW = Math.max(charW / 5, 1);
        int pixH = Math.max(charH / 7, 1);
        GL.glBegin(GL.GL_QUADS);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') c -= 32;
            int glyph = (c < GLYPH_INDEX.length) ? GLYPH_INDEX[c] : -1;
            if (glyph < 0) continue;
            int cx = x + i * pitch;
            for (int row = 0; row < 7; row++) {
                for (int col = 0; col < 5; col++) {
                    if ((GLYPHS[glyph][row] & (1 << (4 - col))) == 0) continue;
                    float px = cx + col * pixW, py = y + row * pixH;
                    GL.glVertex2f(px,        py);
                    GL.glVertex2f(px + pixW, py);
                    GL.glVertex2f(px + pixW, py + pixH);
                    GL.glVertex2f(px,        py + pixH);
                }
            }
        }
        GL.glEnd();
    }
}

package com.mojang.rubydung;

import org.lwjgl.opengl.GL11;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Bitmap font atlas built from a system font at init time (GL thread).
 * Covers ASCII printable + Cyrillic (U+0400–U+04FF).
 * Call init() once after GL context is created.
 */
public class FontRenderer {
    private int texId;
    private final int[] charX   = new int[512];
    private final int[] charW   = new int[512];
    public final int glyphH;
    private final int atlasW = 4096, atlasH;

    private static final String RANGES =
        " !\"#$%&'()*+,-./0123456789:;<=>?@" +
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`" +
        "abcdefghijklmnopqrstuvwxyz{|}~" +
        "\u0400\u0401\u0402\u0403\u0404\u0405\u0406\u0407\u0408\u0409\u040A\u040B\u040C\u040D\u040E\u040F" +
        "\u0410\u0411\u0412\u0413\u0414\u0415\u0416\u0417\u0418\u0419\u041A\u041B\u041C\u041D\u041E\u041F" +
        "\u0420\u0421\u0422\u0423\u0424\u0425\u0426\u0427\u0428\u0429\u042A\u042B\u042C\u042D\u042E\u042F" +
        "\u0430\u0431\u0432\u0433\u0434\u0435\u0436\u0437\u0438\u0439\u043A\u043B\u043C\u043D\u043E\u043F" +
        "\u0440\u0441\u0442\u0443\u0444\u0445\u0446\u0447\u0448\u0449\u044A\u044B\u044C\u044D\u044E\u044F" +
        "\u0451"; // ё

    // char → atlas index
    private final java.util.HashMap<Character, Integer> charIndex = new java.util.HashMap<>();

    public FontRenderer(int fontSize) {
        System.setProperty("java.awt.headless", "true");
        Font font = new Font("SansSerif", Font.PLAIN, fontSize);
        // measure glyphs
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gtmp = tmp.createGraphics();
        gtmp.setFont(font);
        FontMetrics fm = gtmp.getFontMetrics();
        glyphH = fm.getHeight();
        int x = 0;
        for (int i = 0; i < RANGES.length(); i++) {
            char c = RANGES.charAt(i);
            int w = fm.charWidth(c);
            charIndex.put(c, i);
            charX[i] = x;
            charW[i] = w;
            x += w + 1;
        }
        gtmp.dispose();
        atlasH = nextPow2(glyphH);
        int atlasH2 = nextPow2(glyphH);

        // draw atlas
        BufferedImage atlas = new BufferedImage(atlasW, atlasH2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(font);
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, atlasW, atlasH2);
        g.setColor(Color.WHITE);
        for (int i = 0; i < RANGES.length(); i++) {
            g.drawString(String.valueOf(RANGES.charAt(i)), charX[i], fm.getAscent());
        }
        g.dispose();

        // upload to GL
        texId = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        int[] px = new int[atlasW * atlasH2];
        atlas.getRGB(0, 0, atlasW, atlasH2, px, 0, atlasW);
        ByteBuffer buf = MemoryUtil.memAlloc(atlasW * atlasH2 * 4);
        for (int p : px) {
            buf.put((byte)((p >> 16) & 0xFF));
            buf.put((byte)((p >>  8) & 0xFF));
            buf.put((byte)( p        & 0xFF));
            buf.put((byte)((p >> 24) & 0xFF));
        }
        buf.flip();
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, atlasW, atlasH2, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        MemoryUtil.memFree(buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    public int stringWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            Integer idx = charIndex.get(s.charAt(i));
            w += idx != null ? charW[idx] + 1 : charW[0];
        }
        return w;
    }

    public void drawString(String s, int x, int y, float r, float g, float b, float a) {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        float ah = atlasH;
        for (int i = 0; i < s.length(); i++) {
            Integer idx = charIndex.get(s.charAt(i));
            if (idx == null) idx = charIndex.getOrDefault('?', 0);
            int gx = charX[idx], gw = charW[idx];
            float u0 = (float) gx / atlasW, u1 = (float)(gx + gw) / atlasW;
            float v1 = (float) glyphH / ah;
            GL11.glTexCoord2f(u0, 0);  GL11.glVertex2f(x,    y);
            GL11.glTexCoord2f(u1, 0);  GL11.glVertex2f(x+gw, y);
            GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x+gw, y+glyphH);
            GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(x,    y+glyphH);
            x += gw + 1;
        }
        GL11.glEnd();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        if (prevTex == 0) GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    private static int nextPow2(int v) {
        int p = 1; while (p < v) p <<= 1; return p;
    }
}

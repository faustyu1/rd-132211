package com.mojang.rubydung;

import com.mojang.rubydung.level.Tesselator;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.Pipelines;
import com.mojang.rubydung.render.vk.VkTexture;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Bitmap font atlas built from a system font at init time.
 * Covers ASCII printable + Cyrillic (U+0400–U+04FF).
 */
public class FontRenderer {
    private VkTexture texture;
    private final int[] charX   = new int[512];
    private final int[] charW   = new int[512];
    public final int glyphH;
    private final int atlasW = 4096, atlasH;

    private final Tesselator t = new Tesselator();

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

    private final java.util.HashMap<Character, Integer> charIndex = new java.util.HashMap<>();

    public FontRenderer(int fontSize) {
        System.setProperty("java.awt.headless", "true");
        Font font = new Font("SansSerif", Font.PLAIN, fontSize);
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

        int[] px = new int[atlasW * atlasH2];
        atlas.getRGB(0, 0, atlasW, atlasH2, px, 0, atlasW);
        ByteBuffer buf = MemoryUtil.memAlloc(atlasW * atlasH2 * 4);
        for (int p : px) {
            buf.put((byte)((p >> 16) & 0xFF)); // R
            buf.put((byte)((p >>  8) & 0xFF)); // G
            buf.put((byte)( p        & 0xFF)); // B
            buf.put((byte)((p >> 24) & 0xFF)); // A
        }
        buf.flip();
        texture = GameRenderer.instance.createTexture(atlasW, atlasH2, buf, true);
        MemoryUtil.memFree(buf);
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
        GameRenderer gr = GameRenderer.instance;
        gr.setPipeline(Pipelines.Pipeline.UI);
        gr.bindTexture(texture);
        t.init();
        t.color(r, g, b, a);
        float ah = atlasH;
        for (int i = 0; i < s.length(); i++) {
            Integer idx = charIndex.get(s.charAt(i));
            if (idx == null) idx = charIndex.getOrDefault('?', 0);
            int gx = charX[idx], gw = charW[idx];
            float u0 = (float) gx / atlasW, u1 = (float)(gx + gw) / atlasW;
            float v1 = (float) glyphH / ah;
            t.tex(u0, 0);  t.vertex(x,    y,        0);
            t.tex(u1, 0);  t.vertex(x+gw, y,        0);
            t.tex(u1, v1); t.vertex(x+gw, y+glyphH, 0);
            t.tex(u0, v1); t.vertex(x,    y+glyphH, 0);
            x += gw + 1;
        }
        t.flush();
    }

    private static int nextPow2(int v) {
        int p = 1; while (p < v) p <<= 1; return p;
    }
}

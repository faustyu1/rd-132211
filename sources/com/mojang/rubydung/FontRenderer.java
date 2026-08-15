package com.mojang.rubydung;

import com.mojang.rubydung.level.Tesselator;
import com.mojang.rubydung.render.vk.GameRenderer;
import com.mojang.rubydung.render.vk.Pipelines;
import com.mojang.rubydung.render.vk.VkTexture;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Bitmap font atlas rasterised from a TrueType file at init time, covering ASCII printable
 * plus Cyrillic (U+0400–U+04FF).
 *
 * The glyphs used to come from {@code java.awt.Font("SansSerif")}, which dragged the whole
 * AWT stack into a Vulkan game and — worse — resolved to a different typeface with different
 * metrics on every operating system, so a layout tuned on one machine drifted on the next.
 * A font file is rasterised with stb_truetype instead: ship {@code resources/font.ttf} and
 * every platform gets identical text. Failing that a known system font is used, which keeps
 * the game running but gives up that guarantee.
 */
public class FontRenderer {
    private VkTexture texture;
    private final int[] charX   = new int[512];
    private final int[] charY   = new int[512];
    private final int[] charW   = new int[512];
    public final int glyphH;
    private final int atlasW = 4096, atlasH;
    private final int fallbackIndex;   // glyph substituted for characters outside RANGES

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

    /**
     * Glyphs are separated by this much transparent padding. One pixel is not enough: the UI
     * scales text up (a 52px logo out of this atlas), and a magnifying bilinear sample at the
     * edge of a glyph reaches past its rectangle, dragging a sliver of the neighbouring letter
     * in with it. That fringe is what makes big text look misshapen.
     */
    private static final int PAD = 4;

    /** Bold faces first: this atlas is the game's only font and reads better heavy. */
    private static final String[] SYSTEM_FONTS = {
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "C:\\Windows\\Fonts\\arialbd.ttf",
        "C:\\Windows\\Fonts\\segoeuib.ttf",
    };

    public FontRenderer(int fontSize) {
        ByteBuffer ttf = loadFont();
        STBTTFontinfo info = STBTTFontinfo.create();
        if (!STBTruetype.stbtt_InitFont(info, ttf))
            throw new RuntimeException("stb_truetype could not parse the font");

        float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, fontSize);
        int ascentPx, rowHeight;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var ascent = stack.mallocInt(1);
            var descent = stack.mallocInt(1);
            var lineGap = stack.mallocInt(1);
            STBTruetype.stbtt_GetFontVMetrics(info, ascent, descent, lineGap);
            ascentPx = Math.round(ascent.get(0) * scale);
            // same definition AWT's FontMetrics.getHeight() used, so the UI's line spacing
            // and every box sized from glyphH keep their proportions
            rowHeight = Math.round((ascent.get(0) - descent.get(0) + lineGap.get(0)) * scale);
        }
        glyphH = rowHeight;

        // lay the glyphs out in rows, exactly as the atlas was packed before
        int rowH = glyphH + PAD;
        int x = 0, row = 0;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var advance = stack.mallocInt(1);
            var bearing = stack.mallocInt(1);
            for (int i = 0; i < RANGES.length(); i++) {
                char c = RANGES.charAt(i);
                STBTruetype.stbtt_GetCodepointHMetrics(info, c, advance, bearing);
                int w = Math.round(advance.get(0) * scale);
                if (x + w + PAD > atlasW) { x = 0; row++; }
                charIndex.put(c, i);
                charX[i] = x;
                charY[i] = row * rowH;
                charW[i] = w;
                x += w + PAD;
            }
        }
        fallbackIndex = charIndex.getOrDefault('?', 0);
        atlasH = nextPow2((row + 1) * rowH);

        // Rasterise coverage into an 8-bit atlas, then expand to white-with-alpha. Drawing
        // straight into the RGBA buffer would mean stb writing every fourth byte.
        byte[] coverage = new byte[atlasW * atlasH];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var x0 = stack.mallocInt(1);
            var y0 = stack.mallocInt(1);
            var x1 = stack.mallocInt(1);
            var y1 = stack.mallocInt(1);
            for (int i = 0; i < RANGES.length(); i++) {
                char c = RANGES.charAt(i);
                STBTruetype.stbtt_GetCodepointBitmapBox(info, c, scale, scale, x0, y0, x1, y1);
                int gw = x1.get(0) - x0.get(0), gh = y1.get(0) - y0.get(0);
                if (gw <= 0 || gh <= 0) continue;   // space and friends have no ink

                ByteBuffer glyph = MemoryUtil.memAlloc(gw * gh);
                STBTruetype.stbtt_MakeCodepointBitmap(info, glyph, gw, gh, gw, scale, scale, c);
                int dstX = charX[i] + Math.max(0, x0.get(0));
                int dstY = charY[i] + ascentPx + y0.get(0);
                for (int gy = 0; gy < gh; gy++) {
                    int ay = dstY + gy;
                    if (ay < 0 || ay >= atlasH) continue;
                    for (int gx = 0; gx < gw; gx++) {
                        int ax = dstX + gx;
                        if (ax < 0 || ax >= atlasW) continue;
                        coverage[ay * atlasW + ax] = glyph.get(gy * gw + gx);
                    }
                }
                MemoryUtil.memFree(glyph);
            }
        }
        MemoryUtil.memFree(ttf);

        ByteBuffer buf = MemoryUtil.memAlloc(atlasW * atlasH * 4);
        for (byte cov : coverage) {
            buf.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put(cov);
        }
        buf.flip();
        texture = GameRenderer.instance.createTexture(atlasW, atlasH, buf, true);
        MemoryUtil.memFree(buf);
    }

    /** Prefer the bundled font, so text is identical on every platform. */
    private static ByteBuffer loadFont() {
        try (InputStream in = FontRenderer.class.getResourceAsStream("/font.ttf")) {
            if (in != null) return toBuffer(in.readAllBytes());
        } catch (Exception ignored) {}

        for (String path : SYSTEM_FONTS) {
            try {
                Path p = Path.of(path);
                if (Files.isReadable(p)) {
                    System.out.println("[font] resources/font.ttf missing, falling back to " + path);
                    return toBuffer(Files.readAllBytes(p));
                }
            } catch (Exception ignored) {}
        }
        throw new RuntimeException("no font available: put a TrueType file at resources/font.ttf");
    }

    private static ByteBuffer toBuffer(byte[] bytes) {
        ByteBuffer buf = MemoryUtil.memAlloc(bytes.length);
        buf.put(bytes).flip();
        return buf;
    }

    /** Width of a string at a scale factor, in screen pixels. */
    public float width(String s, float scale) {
        return stringWidth(s) * scale;
    }

    /** Height of a line at a scale factor, in screen pixels. */
    public float height(float scale) {
        return glyphH * scale;
    }

    public int stringWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            Integer idx = charIndex.get(s.charAt(i));
            // must match the substitution drawString makes, or boxes sized from this mismatch the text
            if (idx == null) idx = fallbackIndex;
            w += charW[idx] + 1;
        }
        return w;
    }

    public void drawString(String s, int x, int y, float r, float g, float b, float a) {
        drawString(s, x, y, 1f, r, g, b, a, Pipelines.Pipeline.UI);
    }

    /** Draw at an arbitrary scale; 1 renders the atlas at its native size. */
    public void drawString(String s, float x, float y, float scale, float r, float g, float b, float a) {
        drawString(s, x, y, scale, r, g, b, a, Pipelines.Pipeline.UI);
    }

    /**
     * The one text primitive. Everything the game draws — menus, HUD, chat and the 3D name
     * tags — goes through here, so there is a single font with a single set of glyphs
     * (ASCII plus Cyrillic) rather than a second hand-built uppercase-only bitmap font.
     */
    public void drawString(String s, float x, float y, float scale,
                           float r, float g, float b, float a, Pipelines.Pipeline pipeline) {
        if (s.isEmpty()) return;
        GameRenderer gr = GameRenderer.instance;
        gr.setPipeline(pipeline);
        gr.bindTexture(texture);
        t.init();
        t.color(r, g, b, a);
        float gh = glyphH * scale;
        for (int i = 0; i < s.length(); i++) {
            Integer idx = charIndex.get(s.charAt(i));
            if (idx == null) idx = fallbackIndex;
            int gx = charX[idx], gy = charY[idx], gw = charW[idx];
            float u0 = (float) gx / atlasW, u1 = (float)(gx + gw) / atlasW;
            float v0 = (float) gy / atlasH, v1 = (float)(gy + glyphH) / atlasH;
            float w = gw * scale;
            t.tex(u0, v0); t.vertex(x,     y,      0);
            t.tex(u1, v0); t.vertex(x + w, y,      0);
            t.tex(u1, v1); t.vertex(x + w, y + gh, 0);
            t.tex(u0, v1); t.vertex(x,     y + gh, 0);
            x += w + scale;
        }
        t.flush();
    }

    private static int nextPow2(int v) {
        int p = 1; while (p < v) p <<= 1; return p;
    }
}

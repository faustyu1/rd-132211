package com.mojang.rubydung.level;

public class Tile {

    // --- Block ids (stored in WorldChunk.blocks) ---
    public static final byte AIR = 0, STONE = 1, WOOD = 2, LEAVES = 3, WATER = 4,
        BEDROCK = 5, COAL = 6, IRON = 7, GOLD = 8, DIAMOND = 9, SAND = 10,
        GRAVEL = 11, GRASS = 12, DIRT = 13, SNOW = 14;

    // Flowing water: 7 decreasing levels (1 = nearly full ... 7 = thin edge).
    // WATER (id 4) is the permanent full "source"; flowing ids reuse the water tile.
    public static final byte WATER_FLOW1 = 15, WATER_FLOW7 = 21;

    public static boolean isWater(int b) { return b == WATER || (b >= WATER_FLOW1 && b <= WATER_FLOW7); }
    /** 0 for a source, 1..7 for flowing water. */
    public static int waterLevel(int b) { return b == WATER ? 0 : (b - 14); }
    /** level<=0 -> source, otherwise flowing id for level 1..7. */
    public static byte waterBlock(int level) { return level <= 0 ? WATER : (byte) (14 + Math.min(level, 7)); }

    /** Seconds to break a block by hand in survival. 0 = instant, <0 = unbreakable. */
    public static float hardness(int b) {
        return switch (b) {
            case BEDROCK -> -1f;
            case LEAVES  -> 0.2f;
            case GRASS, DIRT, SAND, SNOW, GRAVEL -> 0.5f;
            case WOOD    -> 1.5f;
            case COAL, IRON, GOLD -> 3.0f;
            case DIAMOND -> 3.75f;
            case STONE   -> 1.5f;
            default      -> 1.0f;
        };
    }

    // The terrain atlas only ships two real tiles: 0 = grass (green), 1 = stone (gray).
    // Every material therefore reuses one of those two textures and is differentiated by a colour tint.
    private static final int TEX_GRASS = 0, TEX_STONE = 1;

    public static final Tile rock       = tinted(TEX_STONE, 0.58f, 0.58f, 0.60f);
    public static final Tile grass      = tinted(TEX_GRASS, 1.00f, 1.00f, 1.00f);
    public static final Tile dirt       = tinted(TEX_STONE, 0.52f, 0.38f, 0.24f);
    public static final Tile stone      = tinted(TEX_STONE, 0.58f, 0.58f, 0.60f);
    public static final Tile wood       = tinted(TEX_STONE, 0.45f, 0.31f, 0.16f);
    public static final Tile leaves     = tinted(TEX_GRASS, 0.42f, 0.62f, 0.30f);
    public static final Tile bedrock    = tinted(TEX_STONE, 0.22f, 0.22f, 0.24f);
    public static final Tile coalOre    = tinted(TEX_STONE, 0.28f, 0.28f, 0.30f);
    public static final Tile ironOre    = tinted(TEX_STONE, 0.78f, 0.66f, 0.52f);
    public static final Tile goldOre    = tinted(TEX_STONE, 0.92f, 0.80f, 0.30f);
    public static final Tile diamondOre = tinted(TEX_STONE, 0.45f, 0.85f, 0.88f);
    public static final Tile sand       = tinted(TEX_STONE, 0.86f, 0.80f, 0.56f);
    public static final Tile gravel     = tinted(TEX_STONE, 0.52f, 0.52f, 0.55f);
    public static final Tile snow       = tinted(TEX_STONE, 0.95f, 0.97f, 1.00f);
    public static final Tile water      = tinted(14, 0.30f, 0.50f, 1.00f);
    /** Flowing water tiles for levels 1..7 (index 0..6); lower fill = thinner. */
    public static final Tile[] waterFlow = new Tile[7];
    static {
        for (int lvl = 1; lvl <= 7; lvl++) {
            Tile t = tinted(14, 0.30f, 0.50f, 1.00f);
            t.fill = 1.0f - lvl / 8.0f;   // level 1 -> 0.875 ... level 7 -> 0.125
            waterFlow[lvl - 1] = t;
        }
    }

    /** Tile for a water block id (source or flow). */
    public static Tile forWater(int blockId) {
        if (blockId == WATER) return water;
        int lvl = waterLevel(blockId);
        return (lvl >= 1 && lvl <= 7) ? waterFlow[lvl - 1] : water;
    }

    private static Tile tinted(int tex, float r, float g, float b) {
        Tile t = new Tile(tex, tex, tex);
        t.tr = r; t.tg = g; t.tb = b;
        return t;
    }

    // texTop, texBottom, texSide
    private final int texTop, texBottom, texSide;
    // color tint (default white = no tint)
    float tr = 1f,
        tg = 1f,
        tb = 1f;
    // vertical fill fraction (1 = full cube). <1 lowers the top surface (flowing water).
    float fill = 1f;

    private Tile(int texTop, int texBottom, int texSide) {
        this.texTop = texTop;
        this.texBottom = texBottom;
        this.texSide = texSide;
    }

    private static float[] uv(int tex) {
        float u0 = tex / 16.0f;
        return new float[] { u0, u0 + 0.0624375f, 0.0624375f };
    }

    private float ao(
        Level level,
        int x,
        int y,
        int z,
        int dx1,
        int dz1,
        int dx2,
        int dz2
    ) {
        boolean s1 = level.isSolidTile(x + dx1, y, z + dz1);
        boolean s2 = level.isSolidTile(x + dx2, y, z + dz2);
        boolean c =
            level.isSolidTile(x + dx1, y, z + dz2) ||
            level.isSolidTile(x + dx2, y, z + dz1);
        int occ = (s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0);
        return 1.0f - occ * 0.2f;
    }

    public void render(
        Tesselator t,
        Level level,
        int layer,
        int x,
        int y,
        int z
    ) {
        float x0 = x,
            x1 = x + 1.0f;
        float y0 = y,
            y1 = y + fill;
        float z0 = z,
            z1 = z + 1.0f;

        // bottom face
        if (!level.isSolidTile(x, y - 1, z)) {
            float[] c = uv(texBottom);
            float br = level.getBrightness(x, y - 1, z);
            boolean lit = br >= 1.0f;
            if (lit ^ (layer == 1)) {
                float a00 = ao(level, x, y - 1, z, -1, 0, 0, 1) * br,
                    a10 = ao(level, x, y - 1, z, 1, 0, 0, 1) * br;
                float a01 = ao(level, x, y - 1, z, -1, 0, 0, -1) * br,
                    a11 = ao(level, x, y - 1, z, 1, 0, 0, -1) * br;
                t.color(a00 * tr, a00 * tg, a00 * tb);
                t.tex(c[0], c[2]);
                t.vertex(x0, y0, z1);
                t.color(a01 * tr, a01 * tg, a01 * tb);
                t.tex(c[0], 0);
                t.vertex(x0, y0, z0);
                t.color(a11 * tr, a11 * tg, a11 * tb);
                t.tex(c[1], 0);
                t.vertex(x1, y0, z0);
                t.color(a10 * tr, a10 * tg, a10 * tb);
                t.tex(c[1], c[2]);
                t.vertex(x1, y0, z1);
            }
        }
        // top face
        if (!level.isSolidTile(x, y + 1, z)) {
            float[] c = uv(texTop);
            float br = level.getBrightness(x, y, z);
            boolean lit = br >= 1.0f;
            if (lit ^ (layer == 1)) {
                float a11 = ao(level, x, y + 1, z, 1, 0, 0, 1) * br,
                    a10 = ao(level, x, y + 1, z, 1, 0, 0, -1) * br;
                float a00 = ao(level, x, y + 1, z, -1, 0, 0, -1) * br,
                    a01 = ao(level, x, y + 1, z, -1, 0, 0, 1) * br;
                t.color(a11 * tr, a11 * tg, a11 * tb);
                t.tex(c[1], c[2]);
                t.vertex(x1, y1, z1);
                t.color(a10 * tr, a10 * tg, a10 * tb);
                t.tex(c[1], 0);
                t.vertex(x1, y1, z0);
                t.color(a00 * tr, a00 * tg, a00 * tb);
                t.tex(c[0], 0);
                t.vertex(x0, y1, z0);
                t.color(a01 * tr, a01 * tg, a01 * tb);
                t.tex(c[0], c[2]);
                t.vertex(x0, y1, z1);
            }
        }
        // south face (z-)
        if (!level.isSolidTile(x, y, z - 1)) {
            float[] c = uv(texSide);
            float rawBr = level.getBrightness(x, y, z - 1);
            float br = rawBr * 0.8f;
            boolean lit = rawBr >= 1.0f;
            if (lit ^ (layer == 1)) {
                float a00 = ao(level, x, y, z - 1, -1, 0, 0, -1) * br,
                    a10 = ao(level, x, y, z - 1, 1, 0, 0, -1) * br;
                float a01 = ao(level, x, y, z - 1, -1, 0, 0, 1) * br,
                    a11 = ao(level, x, y, z - 1, 1, 0, 0, 1) * br;
                t.color(a00 * tr, a00 * tg, a00 * tb);
                t.tex(c[1], 0);
                t.vertex(x0, y1, z0);
                t.color(a10 * tr, a10 * tg, a10 * tb);
                t.tex(c[0], 0);
                t.vertex(x1, y1, z0);
                t.color(a11 * tr, a11 * tg, a11 * tb);
                t.tex(c[0], c[2]);
                t.vertex(x1, y0, z0);
                t.color(a01 * tr, a01 * tg, a01 * tb);
                t.tex(c[1], c[2]);
                t.vertex(x0, y0, z0);
            }
        }
        // north face (z+)
        if (!level.isSolidTile(x, y, z + 1)) {
            float[] c = uv(texSide);
            float rawBr = level.getBrightness(x, y, z + 1);
            float br = rawBr * 0.8f;
            boolean lit = rawBr >= 1.0f;
            if (lit ^ (layer == 1)) {
                float a00 = ao(level, x, y, z + 1, -1, 0, 0, 1) * br,
                    a10 = ao(level, x, y, z + 1, 1, 0, 0, 1) * br;
                float a01 = ao(level, x, y, z + 1, -1, 0, 0, -1) * br,
                    a11 = ao(level, x, y, z + 1, 1, 0, 0, -1) * br;
                t.color(a00 * tr, a00 * tg, a00 * tb);
                t.tex(c[0], 0);
                t.vertex(x0, y1, z1);
                t.color(a01 * tr, a01 * tg, a01 * tb);
                t.tex(c[0], c[2]);
                t.vertex(x0, y0, z1);
                t.color(a11 * tr, a11 * tg, a11 * tb);
                t.tex(c[1], c[2]);
                t.vertex(x1, y0, z1);
                t.color(a10 * tr, a10 * tg, a10 * tb);
                t.tex(c[1], 0);
                t.vertex(x1, y1, z1);
            }
        }
        // west face (x-)
        if (!level.isSolidTile(x - 1, y, z)) {
            float[] c = uv(texSide);
            float rawBr = level.getBrightness(x - 1, y, z);
            float br = rawBr * 0.6f;
            boolean lit = rawBr >= 1.0f;
            if (lit ^ (layer == 1)) {
                float a00 = ao(level, x - 1, y, z, 0, -1, -1, 0) * br,
                    a10 = ao(level, x - 1, y, z, 0, 1, -1, 0) * br;
                float a01 = ao(level, x - 1, y, z, 0, -1, 1, 0) * br,
                    a11 = ao(level, x - 1, y, z, 0, 1, 1, 0) * br;
                t.color(a10 * tr, a10 * tg, a10 * tb);
                t.tex(c[1], 0);
                t.vertex(x0, y1, z1);
                t.color(a00 * tr, a00 * tg, a00 * tb);
                t.tex(c[0], 0);
                t.vertex(x0, y1, z0);
                t.color(a01 * tr, a01 * tg, a01 * tb);
                t.tex(c[0], c[2]);
                t.vertex(x0, y0, z0);
                t.color(a11 * tr, a11 * tg, a11 * tb);
                t.tex(c[1], c[2]);
                t.vertex(x0, y0, z1);
            }
        }
        // east face (x+)
        if (!level.isSolidTile(x + 1, y, z)) {
            float[] c = uv(texSide);
            float rawBr = level.getBrightness(x + 1, y, z);
            float br = rawBr * 0.6f;
            boolean lit = rawBr >= 1.0f;
            if (lit ^ (layer == 1)) {
                float a00 = ao(level, x + 1, y, z, 0, -1, 1, 0) * br,
                    a10 = ao(level, x + 1, y, z, 0, 1, 1, 0) * br;
                float a01 = ao(level, x + 1, y, z, 0, -1, -1, 0) * br,
                    a11 = ao(level, x + 1, y, z, 0, 1, -1, 0) * br;
                t.color(a00 * tr, a00 * tg, a00 * tb);
                t.tex(c[0], c[2]);
                t.vertex(x1, y0, z1);
                t.color(a01 * tr, a01 * tg, a01 * tb);
                t.tex(c[1], c[2]);
                t.vertex(x1, y0, z0);
                t.color(a11 * tr, a11 * tg, a11 * tb);
                t.tex(c[1], 0);
                t.vertex(x1, y1, z0);
                t.color(a10 * tr, a10 * tg, a10 * tb);
                t.tex(c[0], 0);
                t.vertex(x1, y1, z1);
            }
        }
    }

    public void renderFace(Tesselator t, int x, int y, int z, int face) {
        float x0 = x,
            x1 = x + 1f,
            y0 = y,
            y1 = y + 1f,
            z0 = z,
            z1 = z + 1f;
        switch (face) {
            case 0 -> {
                t.vertex(x0, y0, z1);
                t.vertex(x0, y0, z0);
                t.vertex(x1, y0, z0);
                t.vertex(x1, y0, z1);
            }
            case 1 -> {
                t.vertex(x1, y1, z1);
                t.vertex(x1, y1, z0);
                t.vertex(x0, y1, z0);
                t.vertex(x0, y1, z1);
            }
            case 2 -> {
                t.vertex(x0, y1, z0);
                t.vertex(x1, y1, z0);
                t.vertex(x1, y0, z0);
                t.vertex(x0, y0, z0);
            }
            case 3 -> {
                t.vertex(x0, y1, z1);
                t.vertex(x0, y0, z1);
                t.vertex(x1, y0, z1);
                t.vertex(x1, y1, z1);
            }
            case 4 -> {
                t.vertex(x0, y1, z1);
                t.vertex(x0, y1, z0);
                t.vertex(x0, y0, z0);
                t.vertex(x0, y0, z1);
            }
            case 5 -> {
                t.vertex(x1, y0, z1);
                t.vertex(x1, y0, z0);
                t.vertex(x1, y1, z0);
                t.vertex(x1, y1, z1);
            }
        }
    }
}

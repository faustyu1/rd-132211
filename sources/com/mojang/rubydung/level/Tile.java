package com.mojang.rubydung.level;

public class Tile {

    // --- Block ids (stored in WorldChunk.blocks) ---
    public static final byte AIR = 0, STONE = 1, WOOD = 2, LEAVES = 3, WATER = 4,
        BEDROCK = 5, COAL = 6, IRON = 7, GOLD = 8, DIAMOND = 9, SAND = 10,
        GRAVEL = 11, GRASS = 12, DIRT = 13, SNOW = 14, TORCH = 22;

    // Flowing water: 7 decreasing levels (1 = nearly full ... 7 = thin edge).
    // WATER (id 4) is the permanent full "source"; flowing ids reuse the water tile.
    public static final byte WATER_FLOW1 = 15, WATER_FLOW7 = 21;

    public static boolean isWater(int b) { return b == WATER || (b >= WATER_FLOW1 && b <= WATER_FLOW7); }

    /**
     * A full solid cube: blocks movement, blocks light, and lets a neighbouring face be
     * culled. Air, leaves, water and torches are all see-through and walk-through, so the
     * collision, lighting and meshing code all ask this one question.
     */
    public static boolean isSolid(int b) {
        return b != AIR && b != LEAVES && b != TORCH && !isWater(b);
    }

    /** A real, placeable block id — excludes AIR and the internal flowing-water states. */
    public static boolean isKnownBlock(byte b) {
        return (b >= STONE && b <= SNOW) || b == TORCH;
    }

    /** Human-readable block name, shared by the inventory, tooltips and the crafting list. */
    public static String name(byte b) {
        return switch (b) {
            case AIR     -> "EMPTY";
            case GRASS   -> "GRASS";
            case DIRT    -> "DIRT";
            case STONE   -> "STONE";
            case GRAVEL  -> "GRAVEL";
            case SAND    -> "SAND";
            case SNOW    -> "SNOW";
            case WOOD    -> "WOOD";
            case LEAVES  -> "LEAVES";
            case COAL    -> "COAL ORE";
            case IRON    -> "IRON ORE";
            case GOLD    -> "GOLD ORE";
            case DIAMOND -> "DIAMOND ORE";
            case BEDROCK -> "BEDROCK";
            case WATER   -> "WATER";
            case TORCH   -> "TORCH";
            default      -> "BLOCK " + (b & 0xFF);
        };
    }

    /** Light a block emits by itself, 0..15. Only torches glow for now. */
    public static int lightEmission(int b) { return b == TORCH ? 14 : 0; }

    /** A torch needs something under it; it pops off when that support goes away. */
    public static boolean needsSupport(int b) { return b == TORCH; }
    /** 0 for a source, 1..7 for flowing water. */
    public static int waterLevel(int b) { return b == WATER ? 0 : (b - 14); }
    /** level<=0 -> source, otherwise flowing id for level 1..7. */
    public static byte waterBlock(int level) { return level <= 0 ? WATER : (byte) (14 + Math.min(level, 7)); }

    /** Seconds to break a block by hand in survival. 0 = instant, <0 = unbreakable. */
    public static float hardness(int b) {
        return switch (b) {
            case BEDROCK -> -1f;
            case TORCH   -> 0f;
            case LEAVES  -> 0.2f;
            case GRASS, DIRT, SAND, SNOW, GRAVEL -> 0.5f;
            case WOOD    -> 1.5f;
            case COAL, IRON, GOLD -> 3.0f;
            case DIAMOND -> 3.75f;
            case STONE   -> 1.5f;
            default      -> 1.0f;
        };
    }

    // Shared block colours for everything that draws a block outside the world mesh
    // (break particles, dropped items, inventory swatches). Cached instances, so
    // callers must treat the returned array as read-only.
    private static final float[] SW_GRASS   = { 0.35f, 0.65f, 0.25f };
    private static final float[] SW_DIRT    = { 0.52f, 0.38f, 0.24f };
    private static final float[] SW_SAND    = { 0.86f, 0.80f, 0.56f };
    private static final float[] SW_SNOW    = { 0.95f, 0.97f, 1.00f };
    private static final float[] SW_WOOD    = { 0.45f, 0.31f, 0.16f };
    private static final float[] SW_LEAVES  = { 0.42f, 0.62f, 0.30f };
    private static final float[] SW_COAL    = { 0.28f, 0.28f, 0.30f };
    private static final float[] SW_IRON    = { 0.78f, 0.66f, 0.52f };
    private static final float[] SW_GOLD    = { 0.92f, 0.80f, 0.30f };
    private static final float[] SW_DIAMOND = { 0.45f, 0.85f, 0.88f };
    private static final float[] SW_GRAVEL  = { 0.52f, 0.52f, 0.55f };
    private static final float[] SW_BEDROCK = { 0.22f, 0.22f, 0.24f };
    private static final float[] SW_WATER   = { 0.30f, 0.50f, 1.00f };
    private static final float[] SW_STONE   = { 0.58f, 0.58f, 0.60f };
    private static final float[] SW_TORCH   = { 1.00f, 0.86f, 0.45f };

    /** Canonical RGB swatch for a block id. The array is shared - do not mutate it. */
    public static float[] swatch(byte id) {
        return switch (id) {
            case GRASS   -> SW_GRASS;
            case DIRT    -> SW_DIRT;
            case SAND    -> SW_SAND;
            case SNOW    -> SW_SNOW;
            case WOOD    -> SW_WOOD;
            case LEAVES  -> SW_LEAVES;
            case COAL    -> SW_COAL;
            case IRON    -> SW_IRON;
            case GOLD    -> SW_GOLD;
            case DIAMOND -> SW_DIAMOND;
            case GRAVEL  -> SW_GRAVEL;
            case BEDROCK -> SW_BEDROCK;
            case WATER   -> SW_WATER;
            case TORCH   -> SW_TORCH;
            default      -> SW_STONE;
        };
    }

    // The terrain atlas only ships two real tiles: 0 = grass (green), 1 = stone (gray).
    // Every material therefore reuses one of those two textures and is differentiated by a colour tint.
    private static final int TEX_GRASS = 0, TEX_STONE = 1;

    // Width/height of one atlas tile in uv space, slightly under 1/16 so neighbours do not bleed in.
    private static final float UV_SIZE = 0.0624375f;

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
    public static final Tile water      = tinted(TEX_STONE, 0.30f, 0.50f, 1.00f);
    public static final Tile torch      = tinted(TEX_STONE, 1.00f, 0.86f, 0.45f);
    public static final Tile[] waterFlow = new Tile[7];
    static {
        // a thin, short, self-lit post: not a cube, so its faces must never be culled
        torch.inset = 0.42f;
        torch.fill = 0.6f;
        torch.glow = 1.0f;
        torch.noCull = true;
        water.translucent = true;
        water.ta = 0.65f;
        for (int lvl = 1; lvl <= 7; lvl++) {
            Tile t = tinted(TEX_STONE, 0.30f, 0.50f, 1.00f);
            t.fill = 1.0f - lvl / 8.0f;
            t.translucent = true;
            t.ta = 0.65f;
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

    // Atlas u-range per face. Meshing asks for these once per emitted face, so they are
    // resolved here instead of building a fresh coordinate array every time.
    private final float uTop0, uTop1, uBottom0, uBottom1, uSide0, uSide1;
    // color tint (default white = no tint)
    float tr = 1f, tg = 1f, tb = 1f, ta = 1f;
    // vertical fill fraction (1 = full cube). <1 lowers the top surface (flowing water).
    float fill = 1f;
    // horizontal inset per side (0 = full cube). >0 makes a thin post, e.g. a torch.
    float inset = 0f;
    // self-illumination 0..1: the block lights its own faces regardless of its surroundings.
    float glow = 0f;
    // skip neighbour face culling — required for anything that is not a full cube, whose
    // faces stay visible even when the neighbouring block is solid.
    boolean noCull = false;
    boolean translucent = false;

    private Tile(int texTop, int texBottom, int texSide) {
        this.uTop0 = texTop / 16.0f;
        this.uTop1 = this.uTop0 + UV_SIZE;
        this.uBottom0 = texBottom / 16.0f;
        this.uBottom1 = this.uBottom0 + UV_SIZE;
        this.uSide0 = texSide / 16.0f;
        this.uSide1 = this.uSide0 + UV_SIZE;
    }

    /**
     * Ambient occlusion for one face corner. s1 and s2 are the two neighbours of the
     * face-adjacent voxel that lie in the plane of the face, the corner between them is
     * their sum; sampling anything off that plane picks up blocks that cannot occlude it.
     */
    private float ao(
        Level level,
        int x,
        int y,
        int z,
        int s1x,
        int s1y,
        int s1z,
        int s2x,
        int s2y,
        int s2z
    ) {
        boolean s1 = level.isSolidTile(x + s1x, y + s1y, z + s1z);
        boolean s2 = level.isSolidTile(x + s2x, y + s2y, z + s2z);
        boolean c = level.isSolidTile(
            x + s1x + s2x,
            y + s1y + s2y,
            z + s1z + s2z
        );
        int occ = (s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0);
        return 1.0f - occ * 0.2f;
    }

    /**
     * Set the light of the face about to be emitted from the voxel it faces, and return the
     * directional shade that belongs in the vertex colour.
     *
     * Light is deliberately NOT folded into the colour any more: the fragment shader dims sky
     * light with the day/night multiplier and leaves block light alone, which is what keeps a
     * torch-lit cave bright at midnight. The 0.8/0.6 side shading is a property of the face,
     * not of the light, so it stays multiplicative in the colour and survives both.
     */
    private float faceLight(Tesselator t, Level level, int x, int y, int z, float shade) {
        t.light(level.getBrightness(x, y, z), Math.max(level.getBlockBrightness(x, y, z), glow));
        return shade;
    }

    public void render(
        Tesselator t,
        Level level,
        int layer,
        int x,
        int y,
        int z
    ) {
        // Opaque tiles belong to layer 0, translucent ones to layer 1; bail out before doing
        // any face-visibility or lighting work for the pass that would emit nothing.
        if (translucent ? layer != 1 : layer != 0) return;

        float x0 = x + inset,
            x1 = x + 1.0f - inset;
        float y0 = y,
            y1 = y + fill;
        float z0 = z + inset,
            z1 = z + 1.0f - inset;
        boolean w = translucent; // water: cull faces against same fluid

        // bottom face
        if (noCull || !level.isSolidOrSameFluid(x, y - 1, z, w)) {
            float br = faceLight(t, level, x, y - 1, z, 1.0f);
            float a00 = ao(level, x, y - 1, z, -1, 0, 0, 0, 0, 1) * br,
                a10 = ao(level, x, y - 1, z, 1, 0, 0, 0, 0, 1) * br;
            float a01 = ao(level, x, y - 1, z, -1, 0, 0, 0, 0, -1) * br,
                a11 = ao(level, x, y - 1, z, 1, 0, 0, 0, 0, -1) * br;
            t.color(a00 * tr, a00 * tg, a00 * tb, ta);
            t.tex(uBottom0, UV_SIZE);
            t.vertex(x0, y0, z1);
            t.color(a01 * tr, a01 * tg, a01 * tb, ta);
            t.tex(uBottom0, 0);
            t.vertex(x0, y0, z0);
            t.color(a11 * tr, a11 * tg, a11 * tb, ta);
            t.tex(uBottom1, 0);
            t.vertex(x1, y0, z0);
            t.color(a10 * tr, a10 * tg, a10 * tb, ta);
            t.tex(uBottom1, UV_SIZE);
            t.vertex(x1, y0, z1);
        }
        // top face
        if (noCull || !level.isSolidOrSameFluid(x, y + 1, z, w)) {
            float br = faceLight(t, level, x, y + 1, z, 1.0f);
            float a11 = ao(level, x, y + 1, z, 1, 0, 0, 0, 0, 1) * br,
                a10 = ao(level, x, y + 1, z, 1, 0, 0, 0, 0, -1) * br;
            float a00 = ao(level, x, y + 1, z, -1, 0, 0, 0, 0, -1) * br,
                a01 = ao(level, x, y + 1, z, -1, 0, 0, 0, 0, 1) * br;
            t.color(a11 * tr, a11 * tg, a11 * tb, ta);
            t.tex(uTop1, UV_SIZE);
            t.vertex(x1, y1, z1);
            t.color(a10 * tr, a10 * tg, a10 * tb, ta);
            t.tex(uTop1, 0);
            t.vertex(x1, y1, z0);
            t.color(a00 * tr, a00 * tg, a00 * tb, ta);
            t.tex(uTop0, 0);
            t.vertex(x0, y1, z0);
            t.color(a01 * tr, a01 * tg, a01 * tb, ta);
            t.tex(uTop0, UV_SIZE);
            t.vertex(x0, y1, z1);
        }
        // south face (z-)
        if (noCull || !level.isSolidOrSameFluid(x, y, z - 1, w)) {
            float br = faceLight(t, level, x, y, z - 1, 0.8f);
            float a00 = ao(level, x, y, z - 1, -1, 0, 0, 0, 1, 0) * br,
                a10 = ao(level, x, y, z - 1, 1, 0, 0, 0, 1, 0) * br;
            float a01 = ao(level, x, y, z - 1, -1, 0, 0, 0, -1, 0) * br,
                a11 = ao(level, x, y, z - 1, 1, 0, 0, 0, -1, 0) * br;
            t.color(a00 * tr, a00 * tg, a00 * tb, ta);
            t.tex(uSide1, 0);
            t.vertex(x0, y1, z0);
            t.color(a10 * tr, a10 * tg, a10 * tb, ta);
            t.tex(uSide0, 0);
            t.vertex(x1, y1, z0);
            t.color(a11 * tr, a11 * tg, a11 * tb, ta);
            t.tex(uSide0, UV_SIZE);
            t.vertex(x1, y0, z0);
            t.color(a01 * tr, a01 * tg, a01 * tb, ta);
            t.tex(uSide1, UV_SIZE);
            t.vertex(x0, y0, z0);
        }
        // north face (z+)
        if (noCull || !level.isSolidOrSameFluid(x, y, z + 1, w)) {
            float br = faceLight(t, level, x, y, z + 1, 0.8f);
            float a00 = ao(level, x, y, z + 1, -1, 0, 0, 0, 1, 0) * br,
                a10 = ao(level, x, y, z + 1, 1, 0, 0, 0, 1, 0) * br;
            float a01 = ao(level, x, y, z + 1, -1, 0, 0, 0, -1, 0) * br,
                a11 = ao(level, x, y, z + 1, 1, 0, 0, 0, -1, 0) * br;
            t.color(a00 * tr, a00 * tg, a00 * tb, ta);
            t.tex(uSide0, 0);
            t.vertex(x0, y1, z1);
            t.color(a01 * tr, a01 * tg, a01 * tb, ta);
            t.tex(uSide0, UV_SIZE);
            t.vertex(x0, y0, z1);
            t.color(a11 * tr, a11 * tg, a11 * tb, ta);
            t.tex(uSide1, UV_SIZE);
            t.vertex(x1, y0, z1);
            t.color(a10 * tr, a10 * tg, a10 * tb, ta);
            t.tex(uSide1, 0);
            t.vertex(x1, y1, z1);
        }
        // west face (x-)
        if (noCull || !level.isSolidOrSameFluid(x - 1, y, z, w)) {
            float br = faceLight(t, level, x - 1, y, z, 0.6f);
            float a00 = ao(level, x - 1, y, z, 0, 0, -1, 0, 1, 0) * br,
                a10 = ao(level, x - 1, y, z, 0, 0, 1, 0, 1, 0) * br;
            float a01 = ao(level, x - 1, y, z, 0, 0, -1, 0, -1, 0) * br,
                a11 = ao(level, x - 1, y, z, 0, 0, 1, 0, -1, 0) * br;
            t.color(a10 * tr, a10 * tg, a10 * tb, ta);
            t.tex(uSide1, 0);
            t.vertex(x0, y1, z1);
            t.color(a00 * tr, a00 * tg, a00 * tb, ta);
            t.tex(uSide0, 0);
            t.vertex(x0, y1, z0);
            t.color(a01 * tr, a01 * tg, a01 * tb, ta);
            t.tex(uSide0, UV_SIZE);
            t.vertex(x0, y0, z0);
            t.color(a11 * tr, a11 * tg, a11 * tb, ta);
            t.tex(uSide1, UV_SIZE);
            t.vertex(x0, y0, z1);
        }
        // east face (x+)
        if (noCull || !level.isSolidOrSameFluid(x + 1, y, z, w)) {
            float br = faceLight(t, level, x + 1, y, z, 0.6f);
            float a00 = ao(level, x + 1, y, z, 0, 0, 1, 0, -1, 0) * br,
                a10 = ao(level, x + 1, y, z, 0, 0, 1, 0, 1, 0) * br;
            float a01 = ao(level, x + 1, y, z, 0, 0, -1, 0, -1, 0) * br,
                a11 = ao(level, x + 1, y, z, 0, 0, -1, 0, 1, 0) * br;
            t.color(a00 * tr, a00 * tg, a00 * tb, ta);
            t.tex(uSide0, UV_SIZE);
            t.vertex(x1, y0, z1);
            t.color(a01 * tr, a01 * tg, a01 * tb, ta);
            t.tex(uSide1, UV_SIZE);
            t.vertex(x1, y0, z0);
            t.color(a11 * tr, a11 * tg, a11 * tb, ta);
            t.tex(uSide1, 0);
            t.vertex(x1, y1, z0);
            t.color(a10 * tr, a10 * tg, a10 * tb, ta);
            t.tex(uSide0, 0);
            t.vertex(x1, y1, z1);
        }
    }
}

package com.mojang.rubydung;

import com.mojang.rubydung.level.Tile;

/**
 * Everything that is carried but not placed: sticks and tools, the recipes that make them,
 * and how a held tool changes mining.
 *
 * Ids 0..127 are blocks (see {@link Tile}); 128 and up are items. That split is what
 * {@link #isItem} tests, and it is why the hotbar and the collected-item tally are indexed
 * with {@code id & 0xFF} — a byte block id and an int item id have to land in the same table.
 *
 * Tools have no durability. Adding it would mean per-slot state, and the inventory is a flat
 * count per id, so a tool here is simply owned or not owned.
 */
public final class Items {
    private Items() {}

    public static final int STICK = 128;
    public static final int WOOD_PICKAXE = 129, STONE_PICKAXE = 130, IRON_PICKAXE = 131;
    public static final int WOOD_AXE = 132, STONE_AXE = 133, IRON_AXE = 134;
    public static final int WOOD_SHOVEL = 135, STONE_SHOVEL = 136, IRON_SHOVEL = 137;

    public static boolean isItem(int id) { return (id & 0xFF) >= 128; }

    /** Only blocks can be put back into the world. */
    public static boolean isPlaceable(int id) {
        int b = id & 0xFF;
        return !isItem(b) && b != Tile.AIR && Tile.isKnownBlock((byte) b);
    }

    // ── tools ───────────────────────────────────────────────────────────────────────

    /** 0 = bare hands, 1 = wood, 2 = stone, 3 = iron. */
    public static int tier(int id) {
        return switch (id) {
            case WOOD_PICKAXE, WOOD_AXE, WOOD_SHOVEL -> 1;
            case STONE_PICKAXE, STONE_AXE, STONE_SHOVEL -> 2;
            case IRON_PICKAXE, IRON_AXE, IRON_SHOVEL -> 3;
            default -> 0;
        };
    }

    private static boolean isPickaxe(int id) { return id == WOOD_PICKAXE || id == STONE_PICKAXE || id == IRON_PICKAXE; }
    private static boolean isAxe(int id)     { return id == WOOD_AXE || id == STONE_AXE || id == IRON_AXE; }
    private static boolean isShovel(int id)  { return id == WOOD_SHOVEL || id == STONE_SHOVEL || id == IRON_SHOVEL; }

    /** Blocks a pickaxe is for — the same set that refuses to drop without one. */
    private static boolean needsPickaxe(byte block) {
        return switch (block) {
            case Tile.STONE, Tile.COAL, Tile.IRON, Tile.GOLD, Tile.DIAMOND -> true;
            default -> false;
        };
    }

    private static boolean isDiggable(byte block) {
        return switch (block) {
            case Tile.DIRT, Tile.GRASS, Tile.SAND, Tile.GRAVEL, Tile.SNOW -> true;
            default -> false;
        };
    }

    /** How much faster the held tool breaks this block. 1 = no help. */
    public static float miningSpeed(int held, byte block) {
        int t = tier(held);
        if (t == 0) return 1f;
        boolean right = (isPickaxe(held) && needsPickaxe(block))
            || (isAxe(held) && block == Tile.WOOD)
            || (isShovel(held) && isDiggable(block));
        return right ? 1f + t * 2f : 1f;   // wood 3x, stone 5x, iron 7x
    }

    /**
     * Whether breaking this block with what is held actually yields anything. Stone and ores
     * need a pickaxe, and the richer the ore the better it has to be — that is the whole
     * reason to go looking for iron.
     */
    public static boolean canHarvest(int held, byte block) {
        if (!needsPickaxe(block)) return true;
        if (!isPickaxe(held)) return false;
        int need = switch (block) {
            case Tile.GOLD, Tile.DIAMOND -> 3;
            case Tile.IRON -> 2;
            default -> 1;
        };
        return tier(held) >= need;
    }

    // ── recipes ─────────────────────────────────────────────────────────────────────

    /** {@code need} holds id/count pairs. Shapeless: only the totals matter. */
    public record Recipe(int result, int count, int[] need) {}

    public static final Recipe[] RECIPES = {
        new Recipe(STICK, 4, new int[]{Tile.WOOD, 1}),
        new Recipe(Tile.TORCH, 4, new int[]{STICK, 1, Tile.COAL, 1}),
        new Recipe(WOOD_PICKAXE, 1, new int[]{Tile.WOOD, 3, STICK, 2}),
        new Recipe(STONE_PICKAXE, 1, new int[]{Tile.STONE, 3, STICK, 2}),
        new Recipe(IRON_PICKAXE, 1, new int[]{Tile.IRON, 3, STICK, 2}),
        new Recipe(WOOD_AXE, 1, new int[]{Tile.WOOD, 3, STICK, 2}),
        new Recipe(STONE_AXE, 1, new int[]{Tile.STONE, 3, STICK, 2}),
        new Recipe(IRON_AXE, 1, new int[]{Tile.IRON, 3, STICK, 2}),
        new Recipe(WOOD_SHOVEL, 1, new int[]{Tile.WOOD, 1, STICK, 2}),
        new Recipe(STONE_SHOVEL, 1, new int[]{Tile.STONE, 1, STICK, 2}),
        new Recipe(IRON_SHOVEL, 1, new int[]{Tile.IRON, 1, STICK, 2}),
    };

    /** True if {@code counts} (indexed by id & 0xFF) covers every ingredient. */
    public static boolean canCraft(Recipe r, int[] counts) {
        for (int i = 0; i < r.need().length; i += 2)
            if (counts[r.need()[i] & 0xFF] < r.need()[i + 1]) return false;
        return true;
    }

    /** Spend the ingredients and add the result. Caller must have checked {@link #canCraft}. */
    public static void craft(Recipe r, int[] counts) {
        for (int i = 0; i < r.need().length; i += 2) counts[r.need()[i] & 0xFF] -= r.need()[i + 1];
        counts[r.result() & 0xFF] += r.count();
    }

    // ── presentation ────────────────────────────────────────────────────────────────

    private static final float[] SW_STICK = { 0.62f, 0.45f, 0.24f };
    private static final float[] SW_WOOD_TOOL  = { 0.55f, 0.40f, 0.22f };
    private static final float[] SW_STONE_TOOL = { 0.62f, 0.62f, 0.64f };
    private static final float[] SW_IRON_TOOL  = { 0.85f, 0.82f, 0.78f };

    /** Shared, read-only. Falls through to the block table for anything that is not an item. */
    public static float[] swatch(int id) {
        if (!isItem(id)) return Tile.swatch((byte) (id & 0xFF));
        if (id == STICK) return SW_STICK;
        return switch (tier(id)) {
            case 1 -> SW_WOOD_TOOL;
            case 2 -> SW_STONE_TOOL;
            default -> SW_IRON_TOOL;
        };
    }

    public static String name(int id) {
        return switch (id) {
            case STICK -> "STICK";
            case WOOD_PICKAXE -> "WOOD PICKAXE";
            case STONE_PICKAXE -> "STONE PICKAXE";
            case IRON_PICKAXE -> "IRON PICKAXE";
            case WOOD_AXE -> "WOOD AXE";
            case STONE_AXE -> "STONE AXE";
            case IRON_AXE -> "IRON AXE";
            case WOOD_SHOVEL -> "WOOD SHOVEL";
            case STONE_SHOVEL -> "STONE SHOVEL";
            case IRON_SHOVEL -> "IRON SHOVEL";
            default -> Tile.name((byte) (id & 0xFF));
        };
    }
}

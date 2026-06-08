package com.mojang.rubydung.level;

import java.util.Random;

/**
 * Layered, Minecraft-style procedural world generator.
 *
 * Every feature is sampled purely from world coordinates + seed, so chunks
 * generated independently still stitch together seamlessly at their borders.
 *
 * Pipeline (per chunk):
 *   1. climate maps  -> per-column surface height + biome
 *   2. solid fill    -> stone columns, jagged bedrock floor (y 0..4)
 *   3. surface cover -> biome topsoil (grass / sand / snow / rock)
 *   4. caves         -> 3D worm tunnels + deep cheese caverns
 *   5. ravines       -> deep sinuous canyons
 *   6. mineshafts    -> gridded corridors with wooden support frames
 *   7. ores          -> depth-banded veins
 *   8. water         -> oceans + inland lakes (column-fill, never floods caves)
 *   9. vegetation    -> biome trees
 */
public class ChunkGenerator {

    public static final int SEA_LEVEL = 62;

    // biome ids (mirror Level.Biome ordinal order)
    public static final int OCEAN = 0, BEACH = 1, PLAINS = 2, FOREST = 3,
        DESERT = 4, SAVANNA = 5, MOUNTAINS = 6, SNOWY = 7;

    private static final int S = WorldChunk.SIZE;
    private static final int H = WorldChunk.HEIGHT;

    private final long seed;
    private final PerlinNoise continent, erosion, peaks, detail;
    private final PerlinNoise temperature, humidity;
    private final PerlinNoise ravine, lake, mine;

    public ChunkGenerator(long seed) {
        this.seed = seed;
        this.continent   = new PerlinNoise(seed);
        this.erosion     = new PerlinNoise(seed ^ 0x1234ABCDL);
        this.peaks       = new PerlinNoise(seed ^ 0xA1B2C3D4L);
        this.detail      = new PerlinNoise(seed ^ 0x0FEEL);
        this.temperature = new PerlinNoise(seed ^ 0x7E3127AAL);
        this.humidity    = new PerlinNoise(seed ^ 0x5150C0DEL);
        this.ravine      = new PerlinNoise(seed ^ 0x4A71E5L);
        this.lake        = new PerlinNoise(seed ^ 0x1A4E5L);
        this.mine        = new PerlinNoise(seed ^ 0x312E54L);
    }

    // ===================================================================== //
    //  Public queries                                                        //
    // ===================================================================== //

    /** Top solid ground height of a column (terrain, before water), seamless everywhere. */
    public int surfaceHeight(int wx, int wz) {
        double cont = continent.octave(wx / 480.0, wz / 480.0, 4);
        double ero  = erosion.octave(wx / 320.0, wz / 320.0, 3);
        double pk   = peaks.octave(wx / 160.0, wz / 160.0, 4);
        double det  = detail.octave(wx / 70.0, wz / 70.0, 5);

        // continentalness: ~30% ocean, rest land that rises gently inland
        double land = cont - 0.40;
        double h;
        if (land < 0) {
            h = SEA_LEVEL - 2 + land * 80.0;                 // shelf -> deep ocean floor
        } else {
            double base  = SEA_LEVEL + 1 + land * 22.0;      // gentle inland rise
            double roll  = (det - 0.5) * 16.0;               // rolling surface bumps
            double hills = land * 26.0 * (1.0 - ero * 0.5);  // eroded zones flatten out
            double mtn = 0.0;
            double m = pk - 0.42;
            if (m > 0) mtn = m * m * 520.0 * (1.0 - ero * 0.6); // sharp peaks in low-erosion zones
            h = base + roll + hills + mtn;
        }
        int surf = (int) Math.round(h);

        // inland lakes: carve gentle basins that water then fills to sea level
        if (land >= 0) {
            double lk = lake.octave(wx / 95.0, wz / 95.0, 2);
            if (lk > 0.74 && surf >= SEA_LEVEL - 1 && surf <= SEA_LEVEL + 5) {
                int depth = 2 + (int) ((lk - 0.74) / 0.26 * 4.0); // 2..6
                surf = SEA_LEVEL - depth;
            }
        }
        return clamp(surf, 4, H - 5);
    }

    public int biomeId(int wx, int wz) {
        return biomeFrom(surfaceHeight(wx, wz), wx, wz);
    }

    private int biomeFrom(int surf, int wx, int wz) {
        // octave noise clusters near 0.5; stretch it so climate bands are reachable
        double t  = stretch(temperature.octave(wx / 340.0, wz / 340.0, 3));
        double hu = stretch(humidity.octave(wx / 300.0, wz / 300.0, 3));
        if (surf <= SEA_LEVEL - 2) return OCEAN;
        if (surf <= SEA_LEVEL + 1) return BEACH;
        if (surf >= 88)            return MOUNTAINS;
        if (t < 0.35)              return SNOWY;
        if (t > 0.62 && hu < 0.45) return DESERT;
        if (t > 0.50 && hu < 0.45) return SAVANNA;
        if (hu > 0.50)             return FOREST;
        return PLAINS;
    }

    private static double stretch(double v) {
        return clamp01(0.5 + (v - 0.5) * 2.1);
    }

    // ===================================================================== //
    //  Main generation                                                       //
    // ===================================================================== //

    public void generate(WorldChunk chunk) {
        int bx0 = chunk.cx * S, bz0 = chunk.cz * S;

        int[] surf  = new int[S * S];
        int[] biome = new int[S * S];
        for (int lx = 0; lx < S; lx++) {
            for (int lz = 0; lz < S; lz++) {
                int wx = bx0 + lx, wz = bz0 + lz;
                int s = surfaceHeight(wx, wz);
                surf[lz * S + lx] = s;
                biome[lz * S + lx] = biomeFrom(s, wx, wz);
            }
        }

        fillTerrain(chunk, bx0, bz0, surf, biome);
        carveCaves(chunk, bx0, bz0, surf);
        carveRavines(chunk, bx0, bz0, surf);
        genMineshafts(chunk, bx0, bz0, surf);
        genOres(chunk);
        fillWater(chunk, surf);
        genTrees(chunk, bx0, bz0, surf, biome);

        chunk.calcLightDepths();
    }

    // ---- 2. solid fill + bedrock + 3. surface cover ----
    private void fillTerrain(WorldChunk chunk, int bx0, int bz0, int[] surf, int[] biome) {
        for (int lx = 0; lx < S; lx++) {
            for (int lz = 0; lz < S; lz++) {
                int wx = bx0 + lx, wz = bz0 + lz;
                int s = surf[lz * S + lx];
                int bm = biome[lz * S + lx];
                for (int y = 0; y <= s; y++) {
                    chunk.setBlock(lx, y, lz, isBedrock(wx, y, wz) ? Tile.BEDROCK : Tile.STONE);
                }
                applySurface(chunk, lx, lz, s, bm);
            }
        }
    }

    private void applySurface(WorldChunk chunk, int lx, int lz, int s, int bm) {
        byte top;
        byte filler;
        int fillerDepth;
        switch (bm) {
            case OCEAN     -> { top = Tile.SAND;  filler = Tile.SAND; fillerDepth = 2; }
            case BEACH     -> { top = Tile.SAND;  filler = Tile.SAND; fillerDepth = 3; }
            case DESERT    -> { top = Tile.SAND;  filler = Tile.SAND; fillerDepth = 4; }
            case SNOWY     -> { top = Tile.SNOW;  filler = Tile.DIRT; fillerDepth = 3; }
            case MOUNTAINS -> {
                if (s >= 102) { top = Tile.SNOW; filler = Tile.STONE; fillerDepth = 0; }
                else          { top = Tile.STONE; filler = Tile.STONE; fillerDepth = 0; }
            }
            default        -> { top = Tile.GRASS; filler = Tile.DIRT; fillerDepth = 4; }
        }
        chunk.setBlock(lx, s, lz, top);
        for (int d = 1; d <= fillerDepth; d++) {
            int y = s - d;
            if (y > 4 && chunk.getBlock(lx, y, lz) == Tile.STONE) chunk.setBlock(lx, y, lz, filler);
        }
    }

    private boolean isBedrock(int wx, int y, int wz) {
        if (y == 0) return true;
        if (y >= 5) return false;
        return hash01(wx, y, wz) < (5 - y) / 5.0;
    }

    // ---- 4. caves (MC-style worm carver) ----
    // Each chunk seeds its own tunnels; tunnels wander across chunk borders so
    // we also process neighbouring chunks' tunnels that could reach into ours.
    private static final int CAVE_SEARCH_RADIUS = 8; // chunks to search around current chunk

    private void carveCaves(WorldChunk chunk, int bx0, int bz0, int[] surf) {
        for (int ox = -CAVE_SEARCH_RADIUS; ox <= CAVE_SEARCH_RADIUS; ox++) {
            for (int oz = -CAVE_SEARCH_RADIUS; oz <= CAVE_SEARCH_RADIUS; oz++) {
                int ncx = chunk.cx + ox, ncz = chunk.cz + oz;
                long rseed = regionSeed(ncx, ncz);
                Random rng = new Random(rseed);
                // ~1 tunnel per 2 chunks (matching MC Alpha density)
                int tunnels = rng.nextInt(6) == 0 ? 2 : (rng.nextInt(5) == 0 ? 1 : 0);
                // room caves very rarely
                if (rng.nextInt(16) == 0) {
                    float rx = ncx * S + rng.nextFloat() * S;
                    float ry = 8 + rng.nextFloat() * 40f;
                    float rz = ncz * S + rng.nextFloat() * S;
                    carveTunnel(chunk, bx0, bz0, surf, new Random(rng.nextLong()),
                        rx, ry, rz,
                        rng.nextFloat() * (float)(Math.PI * 2),
                        (rng.nextFloat() - 0.5f) * 0.25f,
                        0, 2.5f + rng.nextFloat(), 0);
                }
                for (int t = 0; t < tunnels; t++) {
                    float sx = ncx * S + rng.nextFloat() * S;
                    float sy = 8 + rng.nextFloat() * 48f;
                    float sz = ncz * S + rng.nextFloat() * S;
                    float yaw   = rng.nextFloat() * (float)(Math.PI * 2);
                    float pitch = (rng.nextFloat() - 0.5f) * 0.25f;
                    int len = 80 + rng.nextInt(80);
                    carveTunnel(chunk, bx0, bz0, surf, new Random(rng.nextLong()),
                        sx, sy, sz, yaw, pitch, len, 1.0f, 0);
                }
            }
        }
    }

    private void carveTunnel(WorldChunk chunk, int bx0, int bz0, int[] surf,
                              Random rng, float ox, float oy, float oz,
                              float yaw, float pitch, int maxLen, float radiusMul, int depth) {
        float ddyaw = 0f, ddpitch = 0f;
        for (int step = 0; step < (maxLen == 0 ? 1 : maxLen); step++) {
            float dy = (float) Math.sin(pitch);
            float hLen = (float) Math.cos(pitch);
            float dx = (float) Math.cos(yaw) * hLen;
            float dz = (float) Math.sin(yaw) * hLen;
            ox += dx; oy += dy; oz += dz;

            if (oy < 5 || oy > H - 8) return;

            // radius oscillates sinusoidally (narrow → wide → narrow)
            float r = radiusMul * (1.5f + (float) Math.sin(step * 0.18f) * 0.8f);
            if (r < 0.5f) r = 0.5f;

            // early-out: bounding box doesn't intersect this chunk
            if (ox + r < bx0 || ox - r >= bx0 + S) { pitch *= 0.85f; continue; }
            if (oz + r < bz0 || oz - r >= bz0 + S) { pitch *= 0.85f; continue; }

            float r2 = r * r;
            int x0 = Math.max((int)(ox - r) - 1, bx0), x1 = Math.min((int)(ox + r) + 1, bx0 + S - 1);
            int z0 = Math.max((int)(oz - r) - 1, bz0), z1 = Math.min((int)(oz + r) + 1, bz0 + S - 1);
            int y0c = Math.max(5, (int)(oy - r) - 1), y1c = (int)(oy + r) + 1;
            for (int wx = x0; wx <= x1; wx++) {
                int lx = wx - bx0;
                float fx = wx + 0.5f - ox; float fx2 = fx * fx;
                for (int wz = z0; wz <= z1; wz++) {
                    int lz = wz - bz0;
                    float fz = wz + 0.5f - oz;
                    if (fx2 + fz * fz > r2) continue;
                    int surfY = surf[lz * S + lx];
                    for (int wy = y0c; wy < Math.min(y1c, surfY); wy++) {
                        float fy = wy + 0.5f - oy;
                        if (fx2 + fy * fy + fz * fz < r2) carve(chunk, lx, wy, lz);
                    }
                }
            }

            ddyaw   += (rng.nextFloat() - 0.5f) * 0.12f;
            ddpitch += (rng.nextFloat() - 0.5f) * 0.08f;
            yaw   += (rng.nextFloat() - 0.5f) * 0.25f + ddyaw;
            pitch += (rng.nextFloat() - 0.5f) * 0.06f + ddpitch;
            pitch *= 0.85f;
            if (radiusMul > 1.0f) radiusMul = Math.max(1.0f, radiusMul - 0.06f);

            if (depth == 0 && rng.nextInt(10) == 0 && step > 10) {
                carveTunnel(chunk, bx0, bz0, surf, new Random(rng.nextLong()),
                    ox, oy, oz, yaw + (rng.nextFloat() - 0.5f) * 1.6f,
                    pitch * 0.5f, maxLen - step, 1.0f, 1);
            }
        }
    }

    private long regionSeed(int cx, int cz) {
        return seed ^ ((long) cx * 341873128712L) ^ ((long) cz * 132897987541L);
    }

    // ---- 5. ravines / canyons ----
    private void carveRavines(WorldChunk chunk, int bx0, int bz0, int[] surf) {
        for (int lx = 0; lx < S; lx++) {
            for (int lz = 0; lz < S; lz++) {
                int wx = bx0 + lx, wz = bz0 + lz;
                double mask = ravine.octave(wx / 700.0 + 100.0, wz / 700.0 + 100.0, 2);
                if (mask <= 0.60) continue;
                double rv = ravine.noise(wx / 180.0, wz / 180.0);
                double a = Math.abs(rv);
                if (a >= 0.030) continue;
                int floor = 11 + (int) (a / 0.030 * 6.0);   // walls shallower, centre deeper
                int top = surf[lz * S + lx] - 1;
                for (int y = floor; y <= top; y++) carve(chunk, lx, y, lz);
            }
        }
    }

    // ---- 6. mineshafts ----
    private void genMineshafts(WorldChunk chunk, int bx0, int bz0, int[] surf) {
        final int spacing = 24;
        for (int lx = 0; lx < S; lx++) {
            for (int lz = 0; lz < S; lz++) {
                int wx = bx0 + lx, wz = bz0 + lz;
                double region = mine.octave(wx / 280.0, wz / 280.0, 2);
                if (region < 0.62) continue;
                int level = 18 + (int) ((region - 0.62) / 0.38 * 14.0); // 18..32
                int mx = Math.floorMod(wx, spacing);
                int mz = Math.floorMod(wz, spacing);
                boolean alongX = mz <= 2;
                boolean alongZ = mx <= 2;
                if (!alongX && !alongZ) continue;
                int s = surf[lz * S + lx];
                if (level + 2 >= s - 1) continue;

                for (int y = level; y <= level + 2; y++) carve(chunk, lx, y, lz);

                // wooden support frames every 5 blocks along a corridor
                if (alongX && Math.floorMod(wx, 5) == 0) {
                    if (mz == 0 || mz == 2) { chunk.setBlock(lx, level, lz, Tile.WOOD); chunk.setBlock(lx, level + 1, lz, Tile.WOOD); }
                    chunk.setBlock(lx, level + 2, lz, Tile.WOOD);
                }
                if (alongZ && Math.floorMod(wz, 5) == 0) {
                    if (mx == 0 || mx == 2) { chunk.setBlock(lx, level, lz, Tile.WOOD); chunk.setBlock(lx, level + 1, lz, Tile.WOOD); }
                    chunk.setBlock(lx, level + 2, lz, Tile.WOOD);
                }
            }
        }
    }

    private void carve(WorldChunk chunk, int lx, int y, int lz) {
        byte b = chunk.getBlock(lx, y, lz);
        if (b != Tile.AIR && b != Tile.BEDROCK && b != Tile.WATER) chunk.setBlock(lx, y, lz, Tile.AIR);
    }

    // ---- 7. ores ----
    private void genOres(WorldChunk chunk) {
        Random r = new Random(seed ^ ((long) chunk.cx * 341873128712L) ^ ((long) chunk.cz * 132897987541L));
        oreVeins(chunk, r, Tile.COAL,    18, 9,  H - 6, 9);
        oreVeins(chunk, r, Tile.IRON,    12, 6,  54,    7);
        oreVeins(chunk, r, Tile.GRAVEL,  6,  4,  56,    14);
        oreVeins(chunk, r, Tile.GOLD,    5,  4,  30,    6);
        oreVeins(chunk, r, Tile.DIAMOND, 3,  2,  16,    6);
    }

    private void oreVeins(WorldChunk chunk, Random r, byte type, int count, int minY, int maxY, int size) {
        for (int i = 0; i < count; i++) {
            int ox = r.nextInt(S), oz = r.nextInt(S);
            int oy = minY + r.nextInt(Math.max(1, maxY - minY));
            for (int v = 0; v < size; v++) {
                int vx = ox + r.nextInt(3) - 1;
                int vy = oy + r.nextInt(3) - 1;
                int vz = oz + r.nextInt(3) - 1;
                if (chunk.getBlock(vx, vy, vz) == Tile.STONE) chunk.setBlock(vx, vy, vz, type);
            }
        }
    }

    // ---- 8. water (column fill; never floods enclosed caves) ----
    private void fillWater(WorldChunk chunk, int[] surf) {
        for (int lx = 0; lx < S; lx++) {
            for (int lz = 0; lz < S; lz++) {
                int s = surf[lz * S + lx];
                if (s >= SEA_LEVEL) continue;
                for (int y = s + 1; y <= SEA_LEVEL; y++)
                    if (chunk.getBlock(lx, y, lz) == Tile.AIR) chunk.setBlock(lx, y, lz, Tile.WATER);
            }
        }
    }

    // ---- 9. vegetation ----
    private void genTrees(WorldChunk chunk, int bx0, int bz0, int[] surf, int[] biome) {
        Random r = new Random(seed ^ ((long) chunk.cx * 987654321L) ^ ((long) chunk.cz * 123456789L));
        for (int lx = 2; lx < S - 2; lx++) {
            for (int lz = 2; lz < S - 2; lz++) {
                int s = surf[lz * S + lx];
                if (s < SEA_LEVEL) continue;
                byte topBlock = chunk.getBlock(lx, s, lz);
                if (topBlock != Tile.GRASS && topBlock != Tile.SNOW) continue;
                int chance = switch (biome[lz * S + lx]) {
                    case FOREST  -> 22;
                    case SNOWY   -> 55;
                    case PLAINS  -> 130;
                    case SAVANNA -> 200;
                    default      -> 0;
                };
                if (chance == 0 || r.nextInt(chance) != 0) continue;
                if (hasTreeNearby(chunk, lx, s, lz)) continue;
                plantTree(chunk, r, lx, s, lz);
            }
        }
    }

    /** Keep trunks apart so forests don't fuse into a solid wood wall. */
    private boolean hasTreeNearby(WorldChunk chunk, int lx, int s, int lz) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 1; dy <= 3; dy++)
                    if (chunk.getBlock(lx + dx, s + dy, lz + dz) == Tile.WOOD) return true;
        return false;
    }

    private void plantTree(WorldChunk chunk, Random r, int lx, int s, int lz) {
        int trunkH = 4 + r.nextInt(3);
        int topY = s + trunkH;
        for (int y = s + 1; y <= topY && y < H; y++) chunk.setBlock(lx, y, lz, Tile.WOOD);
        for (int ly = topY - 2; ly <= topY + 1 && ly < H; ly++) {
            int rad = ly >= topY ? 1 : 2;
            for (int dx = -rad; dx <= rad; dx++) {
                for (int dz = -rad; dz <= rad; dz++) {
                    if (Math.abs(dx) == rad && Math.abs(dz) == rad && r.nextInt(2) == 0) continue;
                    int nx = lx + dx, nz = lz + dz;
                    if (chunk.getBlock(nx, ly, nz) == Tile.AIR) chunk.setBlock(nx, ly, nz, Tile.LEAVES);
                }
            }
        }
    }

    // ===================================================================== //
    //  Helpers                                                               //
    // ===================================================================== //

    private double hash01(int x, int y, int z) {
        long h = seed * 6364136223846793005L + 1442695040888963407L;
        h ^= x * 0x9E3779B97F4A7C15L;
        h ^= y * 0xC2B2AE3D27D4EB4FL;
        h ^= z * 0x165667B19E3779F9L;
        h ^= h >>> 29; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 32;
        return (h >>> 11) * (1.0 / (1L << 53));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}

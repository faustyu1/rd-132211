package com.mojang.rubydung.level;

import com.mojang.rubydung.phys.AABB;

import java.io.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class Level {
    public enum Biome { OCEAN, BEACH, PLAINS, FOREST, DESERT, SAVANNA, MOUNTAINS, SNOWY }

    public static final int sizeY = WorldChunk.HEIGHT;

    private final ConcurrentHashMap<Long, WorldChunk> chunks = new ConcurrentHashMap<>();
    private final ChunkGenerator generator;
    private long seed;
    private final List<LevelListener> levelListeners = new ArrayList<>();
    public final java.util.List<int[]> animalSpawns = new java.util.ArrayList<>();

    public Level(long seed) {
        this.seed = seed;
        this.generator = new ChunkGenerator(seed);
    }

    public long getSeed() { return seed; }

    private static long chunkKey(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    public WorldChunk getOrLoadChunk(int cx, int cz) {
        return chunks.computeIfAbsent(chunkKey(cx, cz), k -> {
            WorldChunk chunk = new WorldChunk(cx, cz, this);
            generator.generate(chunk);
            chunk.scheduleBuild();
            // wake all water blocks so fluid simulation starts immediately on load
            int bx0 = cx * WorldChunk.SIZE, bz0 = cz * WorldChunk.SIZE;
            for (int lx = 0; lx < WorldChunk.SIZE; lx++)
                for (int lz = 0; lz < WorldChunk.SIZE; lz++)
                    for (int y = 0; y < WorldChunk.HEIGHT; y++) {
                        byte b = chunk.getBlock(lx, y, lz);
                        if (Tile.isWater(b)) fluidPending.add(packPos(bx0 + lx, y, bz0 + lz));
                    }
            return chunk;
        });
    }

    public void update(float playerX, float playerZ, int renderDist) {
        int pcx = (int) Math.floor(playerX / WorldChunk.SIZE);
        int pcz = (int) Math.floor(playerZ / WorldChunk.SIZE);
        // load chunks in range
        for (int dx = -renderDist; dx <= renderDist; dx++)
            for (int dz = -renderDist; dz <= renderDist; dz++)
                getOrLoadChunk(pcx + dx, pcz + dz);
        // unload far chunks
        int unloadDist = renderDist + 2;
        chunks.entrySet().removeIf(e -> {
            WorldChunk c = e.getValue();
            if (Math.abs(c.cx - pcx) > unloadDist || Math.abs(c.cz - pcz) > unloadDist) {
                c.freeGL();
                return true;
            }
            return false;
        });
    }

    public Collection<WorldChunk> getLoadedChunks() {
        return chunks.values();
    }

    public byte getBlock(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return 0;
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk == null) return 0;
        return chunk.getBlock(x - cx * WorldChunk.SIZE, y, z - cz * WorldChunk.SIZE);
    }

    public void setTile(int x, int y, int z, int type) {
        if (y < 0 || y >= sizeY) return;
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk == null) return;
        chunk.setBlock(x - cx * WorldChunk.SIZE, y, z - cz * WorldChunk.SIZE, (byte) type);
        // recompute light for this column
        chunk.calcLightDepths();
        for (var listener : levelListeners) listener.tileChanged(x, y, z);
        // wake fluid simulation around the change
        scheduleFluid(x, y, z);
        scheduleFluidNeighbors(x, y, z);
    }

    // ===================================================================== //
    //  Fluid simulation (Minecraft-style water: source + 7 flow levels)      //
    // ===================================================================== //

    private final java.util.Set<Long> fluidPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_FLOW = 7;

    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    public void scheduleFluid(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return;
        if (Tile.isWater(getBlock(x, y, z)) || getBlock(x, y, z) == 0)
            fluidPending.add(packPos(x, y, z));
    }

    private void scheduleFluidNeighbors(int x, int y, int z) {
        scheduleFluid(x + 1, y, z); scheduleFluid(x - 1, y, z);
        scheduleFluid(x, y, z + 1); scheduleFluid(x, y, z - 1);
        scheduleFluid(x, y + 1, z); scheduleFluid(x, y - 1, z);
    }

    /** Internal block write that wakes fluids but skips light recompute (water never affects light). */
    private void setBlockFluid(int x, int y, int z, byte type) {
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk == null) return;
        chunk.setBlock(x - cx * WorldChunk.SIZE, y, z - cz * WorldChunk.SIZE, type);
        for (var listener : levelListeners) listener.tileChanged(x, y, z);
        if (Tile.isWater(type) || type == 0) fluidPending.add(packPos(x, y, z));
        scheduleFluidNeighbors(x, y, z);
    }

    /** Advance water flow. Called a few times per second from the game tick. */
    public void tickFluids() {
        if (fluidPending.isEmpty()) return;
        // snapshot this round so newly-scheduled cells settle next round (stable animation)
        Long[] batch = fluidPending.toArray(new Long[0]);
        fluidPending.clear();
        for (long packed : batch) {
            int x = (int) (packed >> 38);
            int y = (int) ((packed >> 26) & 0xFFF);
            int z = (int) (packed & 0x3FFFFFF);
            // sign-extend 26-bit x/z
            if ((x & 0x2000000) != 0) x |= ~0x3FFFFFF;
            if ((z & 0x2000000) != 0) z |= ~0x3FFFFFF;
            updateFluid(x, y, z);
        }
    }

    private void updateFluid(int x, int y, int z) {
        byte cur = getBlock(x, y, z);
        boolean isWater = Tile.isWater(cur);
        if (!isWater && cur != 0) return;

        if (isWater && cur != Tile.WATER) {
            // flowing water: must be fed, else it dries up / decreases
            int fed = inflowLevel(x, y, z);
            if (fed < 0) { setBlockFluid(x, y, z, (byte) 0); return; }
            int curLvl = Tile.waterLevel(cur);
            if (fed != curLvl) { setBlockFluid(x, y, z, Tile.waterBlock(fed)); return; }
        }

        int level = isWater ? Tile.waterLevel(cur) : -1;
        if (!isWater) return; // empty cell: nothing to push out (handled by neighbors feeding it)

        // 1) flow straight down
        if (y > 0) {
            byte below = getBlock(x, y - 1, z);
            if (below == 0 || (Tile.isWater(below) && below != Tile.WATER)) {
                if (below != Tile.WATER_FLOW1)
                    setBlockFluid(x, y - 1, z, Tile.WATER_FLOW1);
                return; // water that can fall does not spread sideways
            }
        }

        // 2) spread horizontally, losing one level per block
        int nextLvl = level + 1;
        if (nextLvl > MAX_FLOW) return;
        spreadTo(x + 1, y, z, nextLvl);
        spreadTo(x - 1, y, z, nextLvl);
        spreadTo(x, y, z + 1, nextLvl);
        spreadTo(x, y, z - 1, nextLvl);
    }

    private void spreadTo(int x, int y, int z, int level) {
        if (level > MAX_FLOW) return;
        byte b = getBlock(x, y, z);
        if (b == 0) {
            setBlockFluid(x, y, z, Tile.waterBlock(level));
        } else if (Tile.isWater(b) && b != Tile.WATER) {
            if (Tile.waterLevel(b) > level) setBlockFluid(x, y, z, Tile.waterBlock(level));
        }
    }

    /** Best (lowest) level this flowing cell can sustain from neighbors, or -1 if unfed. */
    private int inflowLevel(int x, int y, int z) {
        // fed from directly above (any water) -> level 1 (falling column stays strong)
        byte above = getBlock(x, y + 1, z);
        if (Tile.isWater(above)) return 1;
        int best = MAX_FLOW + 1;
        best = Math.min(best, sideFeed(x + 1, y, z));
        best = Math.min(best, sideFeed(x - 1, y, z));
        best = Math.min(best, sideFeed(x, y, z + 1));
        best = Math.min(best, sideFeed(x, y, z - 1));
        return best > MAX_FLOW ? -1 : best;
    }

    private int sideFeed(int x, int y, int z) {
        byte b = getBlock(x, y, z);
        if (b == Tile.WATER) return 1;
        if (Tile.isWater(b)) return Tile.waterLevel(b) + 1;
        return MAX_FLOW + 1;
    }


    public boolean isTile(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return false;
        return getBlock(x, y, z) != 0;
    }

    public boolean isSolidTile(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return false;
        byte b = getBlock(x, y, z);
        return b != 0 && b != 3 && !Tile.isWater(b);
    }

    public boolean isLightBlocker(int x, int y, int z) {
        return isSolidTile(x, y, z);
    }

    public float getBrightness(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return 1.0f;
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk == null) return 1.0f;
        return chunk.getBrightness(x - cx * WorldChunk.SIZE, y, z - cz * WorldChunk.SIZE);
    }

    public int getSurfaceY(int x, int z) {
        // Use generator for deterministic surface, or scan loaded chunk
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk != null) {
            int lx = x - cx * WorldChunk.SIZE;
            int lz2 = z - cz * WorldChunk.SIZE;
            return chunk.lightDepths[lz2 * WorldChunk.SIZE + lx];
        }
        // fallback: ask the generator directly
        return generator.surfaceHeight(x, z);
    }

    public List<AABB> getCubes(AABB aabb) {
        List<AABB> result = new ArrayList<>();
        int x0 = (int) Math.floor(aabb.x0);
        int x1 = (int) Math.floor(aabb.x1 + 1.0f);
        int y0 = Math.max((int) Math.floor(aabb.y0), 0);
        int y1 = Math.min((int) Math.floor(aabb.y1 + 1.0f), sizeY);
        int z0 = (int) Math.floor(aabb.z0);
        int z1 = (int) Math.floor(aabb.z1 + 1.0f);
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                for (int z = z0; z < z1; z++) {
                    if (isSolidTile(x, y, z)) {
                        result.add(new AABB(x, y, z, x + 1, y + 1, z + 1));
                    }
                }
            }
        }
        return result;
    }

    public byte getBiome(int x, int z) {
        return (byte) generator.biomeId(x, z);
    }

    public void addListener(LevelListener listener) {
        levelListeners.add(listener);
    }

    public void removeListener(LevelListener listener) {
        levelListeners.remove(listener);
    }

    public void save(File dir) {
        dir.mkdirs();
        // Save seed
        try (var dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(new File(dir, "seed.dat"))))) {
            dos.writeLong(seed);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Save each loaded chunk
        for (var chunk : chunks.values()) {
            File f = new File(dir, chunk.cx + "_" + chunk.cz + ".dat");
            try (var dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) {
                dos.write(chunk.blocks);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void load(File dir) {
        File seedFile = new File(dir, "seed.dat");
        if (seedFile.exists()) {
            try (var dis = new DataInputStream(new GZIPInputStream(new FileInputStream(seedFile)))) {
                seed = dis.readLong();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        File[] files = dir.listFiles((d, name) -> name.matches("-?\\d+_-?\\d+\\.dat"));
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().replace(".dat", "");
            String[] parts = name.split("_");
            // handle negative coords: split on last _ before second number
            try {
                int cx, cz;
                // find split point: second number may start with -
                int sep = name.lastIndexOf('_');
                cx = Integer.parseInt(name.substring(0, sep));
                cz = Integer.parseInt(name.substring(sep + 1));
                WorldChunk chunk = new WorldChunk(cx, cz, this);
                byte[] buf = chunk.blocks;
                int off = 0;
                try (var dis = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
                    int n;
                    while (off < buf.length && (n = dis.read(buf, off, buf.length - off)) > 0) off += n;
                }
                if (off != buf.length) continue; // incompatible save (e.g. old world height) -> regenerate
                chunk.calcLightDepths();
                chunk.setDirty();
                chunks.put(chunkKey(cx, cz), chunk);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        for (var listener : levelListeners) listener.allChanged();
    }

    // Legacy save/load (single file) kept for compatibility
    public void save() {
        save(new File("world"));
    }

    public void load() {
        load(new File("world"));
    }

    /** Returns a compact byte[] of all loaded chunks for network sync (legacy). */
    public byte[] getRawBlocks() {
        // Serialize as: [count(int)][cx(int)][cz(int)][blocks(SIZE*HEIGHT*SIZE)]...
        int count = chunks.size();
        int blockLen = WorldChunk.SIZE * WorldChunk.HEIGHT * WorldChunk.SIZE;
        byte[] out = new byte[4 + count * (8 + blockLen)];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(out);
        buf.putInt(count);
        for (var chunk : chunks.values()) {
            buf.putInt(chunk.cx);
            buf.putInt(chunk.cz);
            buf.put(chunk.blocks);
        }
        return out;
    }

    /** Replaces block data from network. */
    public void setRawBlocks(byte[] data) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(data);
        int count = buf.getInt();
        int blockLen = WorldChunk.SIZE * WorldChunk.HEIGHT * WorldChunk.SIZE;
        for (int i = 0; i < count; i++) {
            int cx = buf.getInt(), cz = buf.getInt();
            WorldChunk chunk = chunks.computeIfAbsent(chunkKey(cx, cz), k -> new WorldChunk(cx, cz, this));
            buf.get(chunk.blocks);
            chunk.calcLightDepths();
            chunk.setDirty();
        }
        for (var listener : levelListeners) listener.allChanged();
    }
}

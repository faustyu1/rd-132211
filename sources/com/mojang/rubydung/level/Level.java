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
    public static final int sizeY = WorldChunk.HEIGHT;

    private final ConcurrentHashMap<Long, WorldChunk> chunks = new ConcurrentHashMap<>();
    // chunk keys currently being generated on the background pool (avoid duplicate work)
    private final java.util.Set<Long> generating = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService genPool =
        java.util.concurrent.Executors.newFixedThreadPool(Threads.GEN, Threads.factory("chunk-gen"));
    // not final: load() replaces it when a saved world turns out to have a different seed.
    // volatile because the chunk-generation pool reads it.
    private volatile ChunkGenerator generator;
    private long seed;
    private final List<LevelListener> levelListeners = new ArrayList<>();
    // world folder to flush edited chunks into when they stream out; null = nowhere to write yet
    private volatile File saveDir;
    // chunk-granular access to the world's Linear region files (32x32 chunks per file)
    private final LinearRegionCache regions = new LinearRegionCache();

    public Level(long seed) {
        this.seed = seed;
        this.generator = new ChunkGenerator(seed);
    }

    public long getSeed() { return seed; }

    /**
     * Adopt a seed read from disk. The generator has to be rebuilt with it: callers open an
     * existing world by constructing a Level with a placeholder seed and then loading, and a
     * generator still holding the placeholder quietly produces a different world for every
     * chunk that is not on disk — which, now that only edited chunks are stored, is nearly
     * all of them.
     */
    private void setSeed(long seed) {
        if (this.seed == seed && generator != null) return;
        this.seed = seed;
        this.generator = new ChunkGenerator(seed);
    }

    private static long chunkKey(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    /** The loaded chunk at these chunk coords, or null — never generates. */
    public WorldChunk getChunk(int cx, int cz) {
        return chunks.get(chunkKey(cx, cz));
    }

    /** Generate a chunk's block data only — no mesh build, no neighbour invalidation. */
    private WorldChunk loadChunkData(int cx, int cz) {
        return chunks.computeIfAbsent(chunkKey(cx, cz), k -> {
            WorldChunk c = new WorldChunk(cx, cz, this);
            if (!readChunk(c)) generator.generate(c);
            wakeBorderWater(c);
            return c;
        });
    }

    private void markNeighborDirty(int cx, int cz) {
        WorldChunk c = chunks.get(chunkKey(cx, cz));
        if (c != null) c.setDirty();
    }

    /**
     * Wake water blocks that can flow on chunk load:
     *  - blocks on a chunk border (water may flow to/from a neighbour), and
     *  - any water exposed to air on a side or below (e.g. a lake/ocean next to a
     *    carved cave mouth) so the fluid sim floods the opening instead of leaving
     *    a frozen wall of static water beside the cave.
     */
    private void wakeBorderWater(WorldChunk chunk) {
        int bx0 = chunk.cx * WorldChunk.SIZE, bz0 = chunk.cz * WorldChunk.SIZE;
        int S = WorldChunk.SIZE;
        for (int y = 0; y < WorldChunk.HEIGHT; y++) {
            for (int lx = 0; lx < S; lx++) {
                for (int lz = 0; lz < S; lz++) {
                    if (!Tile.isWater(chunk.getBlock(lx, y, lz))) continue;
                    boolean border = lx == 0 || lx == S - 1 || lz == 0 || lz == S - 1;
                    if (border || touchesAir(chunk, lx, y, lz))
                        fluidPending.add(packPos(bx0 + lx, y, bz0 + lz));
                }
            }
        }
    }

    /** True if any side/below neighbour of a water cell is open air it could flow into. */
    private static boolean touchesAir(WorldChunk c, int lx, int y, int lz) {
        return c.getBlock(lx, y - 1, lz) == Tile.AIR
            || c.getBlock(lx + 1, y, lz) == Tile.AIR
            || c.getBlock(lx - 1, y, lz) == Tile.AIR
            || c.getBlock(lx, y, lz + 1) == Tile.AIR
            || c.getBlock(lx, y, lz - 1) == Tile.AIR;
    }

    /**
     * Generate and mesh a whole square region up front, in parallel.
     * Block data is generated one ring wider than the build area so that every
     * built mesh sees real neighbours (seamless borders, no later re-mesh), then
     * all meshes are built concurrently. Blocks until meshes are ready to upload.
     */
    public void preloadRegion(int cx0, int cx1, int cz0, int cz1) {
        List<int[]> dataCoords = new ArrayList<>();
        for (int cx = cx0 - 1; cx <= cx1 + 1; cx++)
            for (int cz = cz0 - 1; cz <= cz1 + 1; cz++)
                dataCoords.add(new int[]{cx, cz});
        dataCoords.parallelStream().forEach(c -> loadChunkData(c[0], c[1]));

        List<int[]> buildCoords = new ArrayList<>();
        for (int cx = cx0; cx <= cx1; cx++)
            for (int cz = cz0; cz <= cz1; cz++)
                buildCoords.add(new int[]{cx, cz});
        buildCoords.parallelStream().forEach(c -> {
            WorldChunk ch = chunks.get(chunkKey(c[0], c[1]));
            if (ch != null) ch.buildNow();
        });
    }

    /** True once every 4-neighbour of a chunk has block data, i.e. it can be meshed seamlessly. */
    boolean neighborsLoaded(int cx, int cz) {
        return chunks.containsKey(chunkKey(cx - 1, cz)) && chunks.containsKey(chunkKey(cx + 1, cz))
            && chunks.containsKey(chunkKey(cx, cz - 1)) && chunks.containsKey(chunkKey(cx, cz + 1));
    }

    // the scan below is (2r+3)^2 map lookups; it only has to run when the player crosses a
    // chunk border, plus a slow heartbeat so a chunk whose generation failed is retried
    private int lastScanCx = Integer.MIN_VALUE, lastScanCz = Integer.MIN_VALUE, scanTicks = 0;

    public void update(float playerX, float playerZ, int renderDist) {
        int pcx = (int) Math.floor(playerX / WorldChunk.SIZE);
        int pcz = (int) Math.floor(playerZ / WorldChunk.SIZE);
        // block data reaches one ring past the meshed area: a chunk is only meshed once its
        // four neighbours exist, so without the skirt the outermost ring would never build
        int genDist = renderDist + 1;
        long genDistSq = (long) genDist * genDist;
        if (pcx != lastScanCx || pcz != lastScanCz || ++scanTicks >= 20) {
            lastScanCx = pcx;
            lastScanCz = pcz;
            scanTicks = 0;
            // queue missing chunks for background generation, nearest first
            List<long[]> wanted = new ArrayList<>();
            for (int dx = -genDist; dx <= genDist; dx++)
                for (int dz = -genDist; dz <= genDist; dz++) {
                    long d2 = (long) dx * dx + (long) dz * dz;
                    // a disc, not a square: the corners of a square are past the fog anyway
                    if (d2 > genDistSq) continue;
                    int cx = pcx + dx, cz = pcz + dz;
                    long key = chunkKey(cx, cz);
                    if (chunks.containsKey(key) || generating.contains(key)) continue;
                    wanted.add(new long[]{d2, cx, cz});
                }
            wanted.sort((a, b) -> Long.compare(a[0], b[0]));
            for (long[] w : wanted) {
                int cx = (int) w[1], cz = (int) w[2];
                long key = chunkKey(cx, cz);
                if (!generating.add(key)) continue;
                genPool.execute(() -> {
                    try {
                        streamChunk(cx, cz);
                    } catch (Throwable e) {
                        // an exception here used to disappear into the executor and leave a
                        // permanent hole in the world; log it once and let the scan retry
                        if (!streamFailureLogged) {
                            streamFailureLogged = true;
                            System.err.println("chunk generation failed at " + cx + "," + cz);
                            e.printStackTrace();
                        }
                    } finally {
                        generating.remove(key);
                    }
                });
            }
        }
        // unload far chunks
        long unloadDistSq = (long) (genDist + 2) * (genDist + 2);
        List<WorldChunk> far = null;
        for (WorldChunk c : chunks.values()) {
            long ddx = c.cx - pcx, ddz = c.cz - pcz;
            if (ddx * ddx + ddz * ddz <= unloadDistSq) continue;
            if (far == null) far = new ArrayList<>();
            far.add(c);
        }
        if (far == null) return;
        // an edited chunk that is only dropped is lost for good: save() writes resident
        // chunks, so it must reach disk before it leaves memory. One batch means one region
        // rewrite even when a whole edited area streams out at once; a region that fails to
        // write (full/read-only disk) keeps its chunks loaded rather than taking the
        // player's building down with it.
        java.util.Set<Long> failed = flushModified(far);
        for (WorldChunk c : far) {
            if (c.modified && failed.contains(LinearRegionCache.regionKeyOf(c.cx, c.cz))) continue;
            chunks.remove(chunkKey(c.cx, c.cz));
            c.freeGL();
        }
    }

    /**
     * Write every edited chunk in the list to its region file, batched by region. Returns
     * the region keys that failed, or an empty set when there is nothing (or nowhere) to write.
     */
    private java.util.Set<Long> flushModified(Collection<WorldChunk> candidates) {
        File dir = saveDir;
        if (dir == null) return java.util.Set.of();
        List<LinearRegionCache.Entry> batch = null;
        for (WorldChunk c : candidates) {
            if (!c.modified) continue;
            if (batch == null) batch = new ArrayList<>();
            batch.add(new LinearRegionCache.Entry(c.cx, c.cz, c.blocks.clone()));
        }
        if (batch == null) return java.util.Set.of();
        java.util.Set<Long> failed = regions.writeBatch(dir, batch, (int) (System.currentTimeMillis() / 1000L));
        for (WorldChunk c : candidates)
            if (c.modified && !failed.contains(LinearRegionCache.regionKeyOf(c.cx, c.cz))) c.modified = false;
        return failed;
    }

    private static volatile boolean streamFailureLogged = false;

    /**
     * Background-thread chunk generation: create, generate, publish. Meshing is left to the
     * renderer's pump, which waits until all four neighbours have arrived — the old code
     * meshed immediately and then re-meshed the whole column once per neighbour that showed
     * up afterwards, i.e. up to five builds of every chunk in the world just to settle the
     * seams.
     */
    private void streamChunk(int cx, int cz) {
        long key = chunkKey(cx, cz);
        if (chunks.containsKey(key)) return;
        WorldChunk c = new WorldChunk(cx, cz, this);
        if (!readChunk(c)) generator.generate(c);
        wakeBorderWater(c);
        chunks.putIfAbsent(key, c);
    }

    public Collection<WorldChunk> getLoadedChunks() {
        return chunks.values();
    }

    /**
     * Tear a world down: stop generating for it and give every chunk's vertex memory back.
     * A level that is merely dropped keeps its generator threads alive and its meshes
     * reserved in the arena, so entering worlds in one session would leak both.
     */
    public void dispose() {
        genPool.shutdownNow();
        for (WorldChunk c : chunks.values()) c.freeGL();
        chunks.clear();
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
        int lx = x - cx * WorldChunk.SIZE, lz = z - cz * WorldChunk.SIZE;
        byte old = chunk.getBlock(lx, y, lz);
        chunk.setBlock(lx, y, lz, (byte) type);
        chunk.markModified();
        // recompute light for this column
        chunk.updateLightAt(lx, lz);
        for (var listener : levelListeners) listener.tileChanged(x, y, z, true);
        // Emitters change what is lit; ordinary blocks change what the light can reach, but
        // only matter where there is light to block, which is nowhere in most of the world.
        if (Tile.lightEmission(old) > 0 || Tile.lightEmission(type) > 0 || litNearby(x, y, z))
            relightBlockLightAround(x, y, z);
        // a torch cannot hang in the air: removing its support takes it with it
        if (Tile.isSolid(old) && !Tile.isSolid((byte) type) && Tile.needsSupport(getBlock(x, y + 1, z)))
            setTile(x, y + 1, z, Tile.AIR);
        // wake fluid simulation around the change
        scheduleFluid(x, y, z);
        scheduleFluidNeighbors(x, y, z);
    }

    // ===================================================================== //
    //  Block light (torches): world-space reflood of a bounded box            //
    // ===================================================================== //

    private static final int LIGHT_RADIUS = 15;

    /**
     * Recompute block light around a change. Everything within the light radius is cleared
     * and then re-flooded from every emitter that could reach it, walking world coordinates
     * so light crosses chunk borders exactly as it does anywhere else.
     *
     * Re-flooding from the emitters themselves is what makes removal work: a torch that is
     * gone simply is not a seed any more, and because the fill only ever raises a cell, the
     * emitters outside the cleared box re-light their share of it without disturbing
     * anything beyond it.
     */
    public void relightBlockLightAround(int x, int y, int z) {
        final int r = LIGHT_RADIUS;
        int y0 = Math.max(0, y - r), y1 = Math.min(sizeY - 1, y + r);
        int x0 = x - r, x1 = x + r, z0 = z - r, z1 = z + r;

        for (int bx = x0; bx <= x1; bx++)
            for (int bz = z0; bz <= z1; bz++) {
                WorldChunk c = chunkAt(bx, bz);
                if (c == null) continue;
                int lx = bx - Math.floorDiv(bx, WorldChunk.SIZE) * WorldChunk.SIZE;
                int lz = bz - Math.floorDiv(bz, WorldChunk.SIZE) * WorldChunk.SIZE;
                for (int by = y0; by <= y1; by++) c.clearBlockLight(lx, by, lz);
            }

        // seeds: every emitter whose own radius overlaps the cleared box
        LongQueue queue = new LongQueue();
        int ecx0 = Math.floorDiv(x0 - r, WorldChunk.SIZE), ecx1 = Math.floorDiv(x1 + r, WorldChunk.SIZE);
        int ecz0 = Math.floorDiv(z0 - r, WorldChunk.SIZE), ecz1 = Math.floorDiv(z1 + r, WorldChunk.SIZE);
        for (int cx = ecx0; cx <= ecx1; cx++) {
            for (int cz = ecz0; cz <= ecz1; cz++) {
                WorldChunk c = chunks.get(chunkKey(cx, cz));
                if (c == null) continue;
                for (int idx : c.emitters()) {
                    int ey = idx / (WorldChunk.SIZE * WorldChunk.SIZE);
                    int rem = idx - ey * WorldChunk.SIZE * WorldChunk.SIZE;
                    int elz = rem / WorldChunk.SIZE;
                    int elx = rem - elz * WorldChunk.SIZE;
                    int ex = cx * WorldChunk.SIZE + elx, ez = cz * WorldChunk.SIZE + elz;
                    int emit = Tile.lightEmission(c.getBlock(elx, ey, elz));
                    if (emit <= 0) continue;
                    c.raiseBlockLight(elx, ey, elz, emit);
                    queue.add(packPos(ex, ey, ez));
                }
            }
        }

        while (!queue.isEmpty()) {
            long p = queue.poll();
            int px = unpackX(p), py = unpackY(p), pz = unpackZ(p);
            int next = blockLightRaw(px, py, pz) - 1;
            if (next <= 0) continue;
            spreadBlockLight(px - 1, py, pz, next, queue);
            spreadBlockLight(px + 1, py, pz, next, queue);
            spreadBlockLight(px, py - 1, pz, next, queue);
            spreadBlockLight(px, py + 1, pz, next, queue);
            spreadBlockLight(px, py, pz - 1, next, queue);
            spreadBlockLight(px, py, pz + 1, next, queue);
        }

        // anything whose light may have moved has to be re-meshed
        for (int cx = Math.floorDiv(x0, WorldChunk.SIZE); cx <= Math.floorDiv(x1, WorldChunk.SIZE); cx++)
            for (int cz = Math.floorDiv(z0, WorldChunk.SIZE); cz <= Math.floorDiv(z1, WorldChunk.SIZE); cz++) {
                WorldChunk c = chunks.get(chunkKey(cx, cz));
                if (c != null) c.setDirtyRange(y0, y1, false);
            }
    }

    private void spreadBlockLight(int x, int y, int z, int level, LongQueue queue) {
        WorldChunk c = chunkAt(x, z);
        if (c == null) return;
        int lx = x - Math.floorDiv(x, WorldChunk.SIZE) * WorldChunk.SIZE;
        int lz = z - Math.floorDiv(z, WorldChunk.SIZE) * WorldChunk.SIZE;
        if (c.raiseBlockLight(lx, y, lz, level)) queue.add(packPos(x, y, z));
    }

    private int blockLightRaw(int x, int y, int z) {
        WorldChunk c = chunkAt(x, z);
        if (c == null) return 0;
        int lx = x - Math.floorDiv(x, WorldChunk.SIZE) * WorldChunk.SIZE;
        int lz = z - Math.floorDiv(z, WorldChunk.SIZE) * WorldChunk.SIZE;
        return c.blockLightAt(lx, y, lz);
    }

    private WorldChunk chunkAt(int x, int z) {
        return chunks.get(chunkKey(Math.floorDiv(x, WorldChunk.SIZE), Math.floorDiv(z, WorldChunk.SIZE)));
    }

    private static int unpackX(long p) { int v = (int) (p >> 38); return (v & 0x2000000) != 0 ? v | ~0x3FFFFFF : v; }
    private static int unpackY(long p) { return (int) ((p >> 26) & 0xFFF); }
    private static int unpackZ(long p) { int v = (int) (p & 0x3FFFFFF); return (v & 0x2000000) != 0 ? v | ~0x3FFFFFF : v; }

    /** Minimal growable long FIFO — the light fill would otherwise box every position. */
    private static final class LongQueue {
        private long[] a = new long[1024];
        private int head = 0, tail = 0;
        void add(long v) {
            if (tail == a.length) {
                if (head > 0) { System.arraycopy(a, head, a, 0, tail - head); tail -= head; head = 0; }
                else a = java.util.Arrays.copyOf(a, a.length * 2);
            }
            a[tail++] = v;
        }
        boolean isEmpty() { return head == tail; }
        long poll() { return a[head++]; }
    }

    // ===================================================================== //
    //  Fluid simulation (Minecraft-style water: source + 7 flow levels)      //
    // ===================================================================== //

    private final java.util.Set<Long> fluidPending = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final int MAX_FLOW = 7;

    /** Told about every cell the simulation moves; the host uses it to broadcast the flow. */
    public interface FluidSink { void fluidChanged(int x, int y, int z, int type); }

    /**
     * Off on a multiplayer client. Two copies of the simulation drift apart within seconds —
     * they process cells in different orders on different tick counts — so the host runs the
     * only one there is and the client just applies what it is told (see
     * {@link #applyNetworkFluid}).
     */
    private volatile boolean simulateFluids = true;
    private volatile FluidSink fluidSink;

    public void setSimulateFluids(boolean on) {
        simulateFluids = on;
        if (!on) fluidPending.clear();   // nothing will ever drain it again
    }

    public void setFluidSink(FluidSink sink) { this.fluidSink = sink; }

    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    public void scheduleFluid(int x, int y, int z) {
        // a client never drains this set, so filling it would just leak
        if (!simulateFluids) return;
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
        // deliberately NOT marked modified: flow levels are derived state that
        // wakeBorderWater + the sim rebuild on load, and every ocean would otherwise
        // count as edited and get flushed to disk when it streams out
        // not urgent: a flowing cell settles over several ticks, so meshing it inline on
        // the render thread would stall for up to 64 cells per fluid tick
        for (var listener : levelListeners) listener.tileChanged(x, y, z, false);
        if (Tile.isWater(type) || type == 0) fluidPending.add(packPos(x, y, z));
        scheduleFluidNeighbors(x, y, z);
        FluidSink sink = fluidSink;
        if (sink != null) sink.fluidChanged(x, y, z, type);
    }

    /**
     * Apply one fluid cell the host's simulation moved. No scheduling and no modified flag:
     * a client neither simulates water nor owns the world it is shown.
     */
    public void applyNetworkFluid(int x, int y, int z, byte type) {
        if (y < 0 || y >= sizeY) return;
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk == null) return;
        chunk.setBlock(x - cx * WorldChunk.SIZE, y, z - cz * WorldChunk.SIZE, type);
        for (var listener : levelListeners) listener.tileChanged(x, y, z, false);
    }

    /** Advance water flow. Called a few times per second from the game tick. */
    public void tickFluids() {
        if (!simulateFluids || fluidPending.isEmpty()) return;
        // process at most 64 cells per tick — matches MC Alpha's per-tick randomTick feel
        int limit = Math.min(64, fluidPending.size());
        var iter = fluidPending.iterator();
        long[] batch = new long[limit];
        for (int i = 0; i < limit && iter.hasNext(); i++) {
            batch[i] = iter.next();
            iter.remove();
        }
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
        // a non-water cell has nothing to push out (an empty one is handled by neighbors feeding it)
        if (!Tile.isWater(cur)) return;

        if (cur != Tile.WATER) {
            // flowing water: must be fed, else it dries up / decreases
            int fed = inflowLevel(x, y, z);
            if (fed < 0) { setBlockFluid(x, y, z, (byte) 0); return; }
            int curLvl = Tile.waterLevel(cur);
            if (fed != curLvl) { setBlockFluid(x, y, z, Tile.waterBlock(fed)); return; }
        }

        int level = Tile.waterLevel(cur);

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


    /** Anything the crosshair can hit: non-air and non-water, leaves included. */
    public boolean isPickable(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return false;
        byte b = getBlock(x, y, z);
        return b != 0 && !Tile.isWater(b);
    }

    public boolean isSolidOrSameFluid(int x, int y, int z, boolean callerIsWater) {
        if (y < 0 || y >= sizeY) return false;
        byte b = getBlock(x, y, z);
        if (callerIsWater && Tile.isWater(b)) return true; // water-water: cull face
        return Tile.isSolid(b);
    }

    public boolean isSolidTile(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return false;
        return Tile.isSolid(getBlock(x, y, z));
    }

    public float getBrightness(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return 1.0f;
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk == null) return 1.0f;
        return chunk.getBrightness(x - cx * WorldChunk.SIZE, y, z - cz * WorldChunk.SIZE);
    }

    /** Light cast by torches, 0..1. Unloaded chunks are dark, not lit. */
    /** Cheap test for "is there any torch light around here at all" before a reflood. */
    private boolean litNearby(int x, int y, int z) {
        return blockLightRaw(x, y, z) > 0
            || blockLightRaw(x + 1, y, z) > 0 || blockLightRaw(x - 1, y, z) > 0
            || blockLightRaw(x, y + 1, z) > 0 || blockLightRaw(x, y - 1, z) > 0
            || blockLightRaw(x, y, z + 1) > 0 || blockLightRaw(x, y, z - 1) > 0;
    }

    public float getBlockBrightness(int x, int y, int z) {
        if (y < 0 || y >= sizeY) return 0f;
        int cx = Math.floorDiv(x, WorldChunk.SIZE);
        int cz = Math.floorDiv(z, WorldChunk.SIZE);
        WorldChunk chunk = chunks.get(chunkKey(cx, cz));
        if (chunk == null) return 0f;
        return chunk.getBlockBrightness(x - cx * WorldChunk.SIZE, y, z - cz * WorldChunk.SIZE);
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

    public void addListener(LevelListener listener) {
        levelListeners.add(listener);
    }

    public void removeListener(LevelListener listener) {
        levelListeners.remove(listener);
    }

    /** Where edited chunks are flushed when they stream out; save/load set it implicitly. */
    public void setSaveDir(File dir) {
        this.saveDir = dir;
    }

    /**
     * Restore a chunk's blocks from the save, if it is stored there. A chunk that streams
     * back in must come from disk: regenerating it from the seed would silently undo
     * every edit ever made there, and the next save() would then overwrite the file.
     * Returns false (leaving the chunk empty) when there is nothing usable to read.
     */
    private boolean readChunk(WorldChunk chunk) {
        File dir = saveDir;
        if (dir == null) return false;
        byte[] stored = regions.read(dir, chunk.cx, chunk.cz);
        if (stored == null) stored = readLegacyChunk(dir, chunk.cx, chunk.cz);
        if (stored == null) return false;
        if (stored.length != chunk.blocks.length) {
            // incompatible save (e.g. old world height) -> hand a clean chunk to the generator
            java.util.Arrays.fill(chunk.blocks, (byte) 0);
            return false;
        }
        System.arraycopy(stored, 0, chunk.blocks, 0, stored.length);
        chunk.calcLightDepths();
        return true;
    }

    /**
     * Pre-Linear saves kept one gzip file per edited chunk. They are still read so old
     * worlds keep their builds; the chunk moves into a region file the next time it is
     * written, after which the stale .dat is ignored.
     */
    private byte[] readLegacyChunk(File dir, int cx, int cz) {
        File f = new File(dir, cx + "_" + cz + ".dat");
        if (!f.exists()) return null;
        byte[] buf = new byte[WorldChunk.SIZE * WorldChunk.HEIGHT * WorldChunk.SIZE];
        int off = 0;
        try (var dis = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            int n;
            while (off < buf.length && (n = dis.read(buf, off, buf.length - off)) > 0) off += n;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return off == buf.length ? buf : null;
    }

    /** Bring a stored chunk into memory at load time; ignored if the save has nothing usable. */
    private void adoptStoredChunk(int cx, int cz) {
        WorldChunk chunk = new WorldChunk(cx, cz, this);
        if (!readChunk(chunk)) return;
        chunk.setDirty();
        chunks.put(chunkKey(cx, cz), chunk);
    }

    public void save(File dir) {
        dir.mkdirs();
        saveDir = dir;
        // Save seed
        try (var dos = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(new File(dir, "seed.dat"))))) {
            dos.writeLong(seed);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Only edited chunks are worth storing: terrain is a pure function of the seed,
        // so writing generated chunks would bloat the save with data readChunk could
        // reproduce for free. A chunk edited in an earlier session already has its file.
        flushModified(chunks.values());
    }

    public void load(File dir) {
        saveDir = dir;
        File seedFile = new File(dir, "seed.dat");
        if (seedFile.exists()) {
            try (var dis = new DataInputStream(new GZIPInputStream(new FileInputStream(seedFile)))) {
                setSeed(dis.readLong());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        for (int[] c : regions.storedChunks(dir)) adoptStoredChunk(c[0], c[1]);
        // legacy per-chunk files, for the ones no region file covers yet
        File[] files = dir.listFiles((d, name) -> name.matches("-?\\d+_-?\\d+\\.dat"));
        if (files != null) {
            for (File f : files) {
                String name = f.getName().replace(".dat", "");
                try {
                    // handle negative coords: the second number may start with -
                    int sep = name.lastIndexOf('_');
                    int cx = Integer.parseInt(name.substring(0, sep));
                    int cz = Integer.parseInt(name.substring(sep + 1));
                    if (chunks.containsKey(chunkKey(cx, cz))) continue; // region file wins
                    adoptStoredChunk(cx, cz);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        for (var listener : levelListeners) listener.allChanged();
    }

    /**
     * Block data for a chunk the world does NOT generate from its seed — i.e. one somebody
     * edited. Returns null for untouched terrain, which a peer can reproduce from the seed
     * alone and so never needs to be sent. Reads straight from the save file when the chunk
     * is not resident, so serving a client does not drag the whole world into memory.
     */
    public byte[] getStoredBlocks(int cx, int cz) {
        byte[] resident = getResidentModifiedBlocks(cx, cz);
        if (resident != null) return resident;
        File dir = saveDir;
        if (dir == null) return null;
        byte[] stored = regions.read(dir, cx, cz);
        if (stored == null) stored = readLegacyChunk(dir, cx, cz);
        if (stored == null) return null;
        // clone: the cache still owns its copy, and the caller ships this over the wire
        return stored.length == WorldChunk.SIZE * WorldChunk.HEIGHT * WorldChunk.SIZE ? stored.clone() : null;
    }

    /**
     * Blocks of an edited chunk that is loaded right now, or null. Cheap — no disk, no
     * decompression — so a caller that must snapshot a chunk at an exact moment (the host
     * shipping one to a client, where a stale copy would undo the block changes already on
     * their way) can do it on the game thread instead of racing it from another one.
     */
    public byte[] getResidentModifiedBlocks(int cx, int cz) {
        WorldChunk c = chunks.get(chunkKey(cx, cz));
        return c != null && c.modified ? c.blocks.clone() : null;
    }

    /** Whether that chunk is loaded and edited — the same test without copying 32 KiB. */
    public boolean isEditedChunkResident(int cx, int cz) {
        WorldChunk c = chunks.get(chunkKey(cx, cz));
        return c != null && c.modified;
    }

    /**
     * Overwrite one chunk with authoritative data from the host. Not marked modified: a
     * client never owns the world it is shown and must never write it to a save folder.
     */
    public void applyNetworkChunk(int cx, int cz, byte[] blocks) {
        WorldChunk chunk = chunks.computeIfAbsent(chunkKey(cx, cz), k -> new WorldChunk(cx, cz, this));
        if (blocks.length != chunk.blocks.length) return;
        System.arraycopy(blocks, 0, chunk.blocks, 0, blocks.length);
        chunk.invalidateEmitters();
        chunk.calcLightDepths();
        chunk.setDirty();
        // only this chunk and the four whose border faces it changed: allChanged() here meant
        // a joining client remeshed every loaded chunk up to four times per tick
        markNeighborDirty(cx - 1, cz);
        markNeighborDirty(cx + 1, cz);
        markNeighborDirty(cx, cz - 1);
        markNeighborDirty(cx, cz + 1);
    }
}

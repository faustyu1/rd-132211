package com.mojang.rubydung.level;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunk-granular access to Linear region files, which the rest of the game needs
 * because chunks are written and read one at a time (a chunk streaming out, a chunk
 * streaming back in, a chunk served to a multiplayer client) while a region file
 * holds 32x32 of them in a single zstd frame.
 *
 * A small LRU of decompressed regions absorbs that mismatch: neighbouring chunks
 * share a region, so a player walking around keeps hitting the same one or two
 * entries. Writes are write-through — the region file is rewritten before write()
 * returns, so a chunk that reports a successful flush really is on disk and the
 * unload path can drop it. That also keeps every cached region clean, which is why
 * eviction can simply forget an entry.
 *
 * All methods are synchronized: chunks stream in on the generator pool and out on
 * the main thread, and the network thread reads through the same cache.
 */
final class LinearRegionCache {
    /** Regions kept decompressed. Each is ~8 KiB of index plus the stored chunks. */
    private static final int MAX_OPEN = 8;

    private File dir;
    private final Map<Long, LinearRegionFile.Region> open =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, LinearRegionFile.Region> eldest) {
                return size() > MAX_OPEN;
            }
        };

    private static long key(int rx, int rz) {
        return (long) rx << 32 | (rz & 0xFFFFFFFFL);
    }

    /** Drop cached regions when the world directory changes (world switch, join). */
    private void useDir(File d) {
        if (dir == null || !dir.getAbsolutePath().equals(d.getAbsolutePath())) {
            open.clear();
            dir = d;
        }
    }

    private LinearRegionFile.Region region(File d, int rx, int rz) throws IOException {
        useDir(d);
        LinearRegionFile.Region r = open.get(key(rx, rz));
        if (r == null) {
            r = LinearRegionFile.read(d, rx, rz);
            open.put(key(rx, rz), r);
        }
        return r;
    }

    /** Stored blocks for one chunk, or null when the region has no entry for it. */
    synchronized byte[] read(File d, int cx, int cz) {
        try {
            return region(d, LinearRegionFile.regionOf(cx), LinearRegionFile.regionOf(cz)).get(cx, cz);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /** One chunk's blocks waiting to be stored. */
    record Entry(int cx, int cz, byte[] blocks) {}

    /**
     * Store several chunks, rewriting each touched region file once. Writing them one by
     * one costs a full zstd compression of the whole 32x32 region per chunk, which is what
     * made an autosave after a building session stutter; a batch pays it once per region.
     * Returns the region keys that failed, so the caller can keep those chunks in memory
     * rather than dropping the only copy of somebody's edits.
     */
    synchronized java.util.Set<Long> writeBatch(File d, List<Entry> entries, int timestamp) {
        java.util.Set<Long> failed = new java.util.HashSet<>();
        Map<Long, List<Entry>> byRegion = new java.util.HashMap<>();
        for (Entry e : entries)
            byRegion.computeIfAbsent(regionKeyOf(e.cx(), e.cz()), k -> new ArrayList<>()).add(e);

        for (var group : byRegion.entrySet()) {
            int rx = (int) (group.getKey() >> 32), rz = (int) (long) group.getKey();
            try {
                LinearRegionFile.Region r = region(d, rx, rz);
                List<byte[]> previous = new ArrayList<>();
                for (Entry e : group.getValue()) previous.add(r.get(e.cx(), e.cz()));
                try {
                    for (Entry e : group.getValue()) r.put(e.cx(), e.cz(), e.blocks(), timestamp);
                    LinearRegionFile.write(d, r);
                } catch (Exception writeFailed) {
                    for (int i = 0; i < previous.size(); i++) {
                        Entry e = group.getValue().get(i);
                        r.put(e.cx(), e.cz(), previous.get(i), timestamp);
                    }
                    throw writeFailed;
                }
            } catch (Exception e) {
                e.printStackTrace();
                open.remove(group.getKey());
                failed.add(group.getKey());
            }
        }
        return failed;
    }

    /** The region file key a chunk belongs to; callers use it to match writeBatch failures. */
    static long regionKeyOf(int cx, int cz) {
        return key(LinearRegionFile.regionOf(cx), LinearRegionFile.regionOf(cz));
    }

    /** Every chunk coordinate stored in the world's region files. */
    synchronized List<int[]> storedChunks(File d) {
        useDir(d);
        List<int[]> out = new ArrayList<>();
        for (File f : LinearRegionFile.listRegions(d)) {
            int[] rc = LinearRegionFile.coordsOf(f);
            if (rc == null) continue;
            try {
                LinearRegionFile.Region r = LinearRegionFile.read(d, rc[0], rc[1]);
                for (int slot = 0; slot < LinearRegionFile.SLOTS; slot++) {
                    if (r.payload[slot] != null) out.add(new int[]{r.chunkX(slot), r.chunkZ(slot)});
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return out;
    }
}

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

    /** Store one chunk and rewrite its region file. False means nothing reached disk. */
    synchronized boolean write(File d, int cx, int cz, byte[] blocks, int timestamp) {
        int rx = LinearRegionFile.regionOf(cx), rz = LinearRegionFile.regionOf(cz);
        try {
            LinearRegionFile.Region r = region(d, rx, rz);
            byte[] previous = r.get(cx, cz);
            r.put(cx, cz, blocks, timestamp);
            try {
                LinearRegionFile.write(d, r);
            } catch (Exception writeFailed) {
                // keep the cache honest about what is on disk, so a later read does not
                // hand back a chunk that was never written
                r.put(cx, cz, previous, timestamp);
                throw writeFailed;
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            // a half-applied region is worse than a cold one: drop it and re-read next time
            open.remove(key(rx, rz));
            return false;
        }
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

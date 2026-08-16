package com.mojang.rubydung.level;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Linear region file format: one file per 32x32 block of chunks, whole file body
 * compressed with zstd. Replaces the old one-GZIP-file-per-chunk layout, which
 * cost a filesystem entry (and a 4 KiB block) per 16x128x16 column.
 *
 * Layout (Linear v1):
 * <pre>
 *   long  SUPERBLOCK
 *   byte  version (1)
 *   long  newestTimestamp   (unix seconds, max over stored chunks)
 *   byte  compressionLevel
 *   short chunkCount        (number of non-empty slots)
 *   int   compressedLength
 *   int   dataHash          (unused, 0)
 *   byte[compressedLength]  zstd frame
 *   long  SUPERBLOCK        (footer, marks a fully written file)
 * </pre>
 *
 * The zstd frame decompresses to 1024 * (int size, int timestamp) followed by the
 * raw chunk payloads back to back in slot order, skipping slots with size 0. A
 * payload here is a chunk's raw block array (16*128*16 bytes), not NBT.
 */
public final class LinearRegionFile {
    public static final long SUPERBLOCK = 0xc3ff13183cca9d9aL;
    public static final byte VERSION = 1;
    /** Region edge in chunks. */
    public static final int REGION = 32;
    public static final int SLOTS = REGION * REGION;
    private static final int COMPRESSION_LEVEL = 6;
    private static final int HEADER_BYTES = 8 + 1 + 8 + 1 + 2 + 4 + 4;

    private LinearRegionFile() {}

    public static int regionOf(int chunkCoord) {
        return chunkCoord >> 5;
    }

    public static int slotOf(int cx, int cz) {
        return (cz & (REGION - 1)) * REGION + (cx & (REGION - 1));
    }

    public static File fileFor(File dir, int rx, int rz) {
        return new File(dir, "r." + rx + "." + rz + ".linear");
    }

    /** All region files in a world directory (empty array if the directory is missing). */
    public static File[] listRegions(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("r.") && name.endsWith(".linear"));
        return files == null ? new File[0] : files;
    }

    /** Region coordinates parsed out of an {@code r.<rx>.<rz>.linear} name, or null. */
    public static int[] coordsOf(File regionFile) {
        String name = regionFile.getName();
        String[] parts = name.substring(0, name.length() - ".linear".length()).split("\\.");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** One region held in memory: raw payload + unix-second timestamp per slot. */
    public static final class Region {
        public final int rx, rz;
        final byte[][] payload = new byte[SLOTS][];
        final int[] timestamps = new int[SLOTS];

        public Region(int rx, int rz) {
            this.rx = rx;
            this.rz = rz;
        }

        public byte[] get(int cx, int cz) {
            return payload[slotOf(cx, cz)];
        }

        public void put(int cx, int cz, byte[] data, int timestamp) {
            int slot = slotOf(cx, cz);
            payload[slot] = data;
            timestamps[slot] = timestamp;
        }

        /** Chunk X of a slot, in world chunk coordinates. */
        public int chunkX(int slot) { return rx * REGION + (slot % REGION); }

        /** Chunk Z of a slot, in world chunk coordinates. */
        public int chunkZ(int slot) { return rz * REGION + (slot / REGION); }
    }

    /**
     * Read a region file. Returns an empty region if the file does not exist, and
     * throws if it exists but is malformed (truncated write, foreign format).
     */
    public static Region read(File dir, int rx, int rz) throws IOException {
        Region region = new Region(rx, rz);
        File f = fileFor(dir, rx, rz);
        if (!f.isFile()) return region;

        byte[] raw = Files.readAllBytes(f.toPath());
        if (raw.length < HEADER_BYTES + 8) throw new IOException("truncated region " + f.getName());

        var head = new DataInputStream(new ByteArrayInputStream(raw));
        if (head.readLong() != SUPERBLOCK) throw new IOException("bad superblock in " + f.getName());
        byte version = head.readByte();
        if (version != VERSION) throw new IOException("unsupported linear version " + version);
        head.readLong();                       // newestTimestamp, recomputed on write
        head.readByte();                       // compressionLevel, informational
        int chunkCount = head.readShort() & 0xFFFF;
        int compressedLength = head.readInt();
        head.readInt();                        // dataHash, unused
        if (compressedLength < 0 || HEADER_BYTES + compressedLength + 8 > raw.length)
            throw new IOException("bad compressed length in " + f.getName());
        // footer superblock: only a fully flushed file carries it
        long footer = ((long) (raw[raw.length - 8] & 0xFF) << 56)
                    | ((long) (raw[raw.length - 7] & 0xFF) << 48)
                    | ((long) (raw[raw.length - 6] & 0xFF) << 40)
                    | ((long) (raw[raw.length - 5] & 0xFF) << 32)
                    | ((long) (raw[raw.length - 4] & 0xFF) << 24)
                    | ((long) (raw[raw.length - 3] & 0xFF) << 16)
                    | ((long) (raw[raw.length - 2] & 0xFF) << 8)
                    | ((long) (raw[raw.length - 1] & 0xFF));
        if (footer != SUPERBLOCK) throw new IOException("missing footer superblock in " + f.getName());

        byte[] body;
        try (var zin = new ZstdInputStream(new ByteArrayInputStream(raw, HEADER_BYTES, compressedLength))) {
            body = zin.readAllBytes();
        }

        var in = new DataInputStream(new ByteArrayInputStream(body));
        int[] sizes = new int[SLOTS];
        int seen = 0;
        for (int i = 0; i < SLOTS; i++) {
            sizes[i] = in.readInt();
            region.timestamps[i] = in.readInt();
            if (sizes[i] > 0) seen++;
        }
        if (seen != chunkCount) throw new IOException("chunk count mismatch in " + f.getName());
        for (int i = 0; i < SLOTS; i++) {
            if (sizes[i] <= 0) continue;
            byte[] data = new byte[sizes[i]];
            in.readFully(data);
            region.payload[i] = data;
        }
        return region;
    }

    /** Write a region file, or delete it when the region holds no chunks. */
    public static void write(File dir, Region region) throws IOException {
        File f = fileFor(dir, region.rx, region.rz);

        int chunkCount = 0, newest = 0, payloadBytes = 0;
        for (int i = 0; i < SLOTS; i++) {
            if (region.payload[i] == null) continue;
            chunkCount++;
            payloadBytes += region.payload[i].length;
            newest = Math.max(newest, region.timestamps[i]);
        }
        if (chunkCount == 0) {
            f.delete();
            return;
        }

        var bodyBytes = new ByteArrayOutputStream(SLOTS * 8 + payloadBytes);
        var body = new DataOutputStream(bodyBytes);
        for (int i = 0; i < SLOTS; i++) {
            body.writeInt(region.payload[i] == null ? 0 : region.payload[i].length);
            body.writeInt(region.timestamps[i]);
        }
        for (int i = 0; i < SLOTS; i++) {
            if (region.payload[i] != null) body.write(region.payload[i]);
        }
        body.flush();

        byte[] compressed = Zstd.compress(bodyBytes.toByteArray(), COMPRESSION_LEVEL);

        var fileBytes = new ByteArrayOutputStream(HEADER_BYTES + compressed.length + 8);
        var out = new DataOutputStream(fileBytes);
        out.writeLong(SUPERBLOCK);
        out.writeByte(VERSION);
        out.writeLong(newest);
        out.writeByte(COMPRESSION_LEVEL);
        out.writeShort(chunkCount);
        out.writeInt(compressed.length);
        out.writeInt(0);                       // dataHash, unused
        out.write(compressed);
        out.writeLong(SUPERBLOCK);
        out.flush();

        // write to a sibling temp file first: a crash mid-save cannot shred a whole
        // region the way a partial in-place write would
        dir.mkdirs();
        File tmp = new File(dir, f.getName() + ".tmp");
        Files.write(tmp.toPath(), fileBytes.toByteArray());
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}

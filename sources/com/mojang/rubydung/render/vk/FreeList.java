package com.mojang.rubydung.render.vk;

import java.util.Map;
import java.util.TreeMap;

/**
 * Offset/size free-range bookkeeping for one contiguous region, shared by the device-memory
 * allocator and the chunk vertex arena. First fit with immediate coalescing: the ranges a
 * voxel world hands back are almost always adjacent to their neighbours, so the map stays
 * short instead of fragmenting into thousands of entries.
 */
final class FreeList {
    /** offset -> size of every free range, kept non-adjacent by {@link #release}. */
    private final TreeMap<Long, Long> free = new TreeMap<>();
    private final long capacity;
    private long used;

    FreeList(long capacity) {
        this.capacity = capacity;
        free.put(0L, capacity);
    }

    long capacity() { return capacity; }
    long used() { return used; }

    /**
     * Reserve {@code size} bytes at a multiple of {@code align}. Returns the offset, or -1 if
     * no range is large enough. Alignment padding is left behind as its own free range rather
     * than folded into the allocation, so it is reusable by a smaller request.
     */
    long allocate(long size, long align) {
        for (Map.Entry<Long, Long> e : free.entrySet()) {
            long off = e.getKey(), sz = e.getValue();
            long aligned = (off + align - 1) / align * align;
            long pad = aligned - off;
            if (sz < pad + size) continue;
            free.remove(off);
            if (pad > 0) free.put(off, pad);
            long tail = sz - pad - size;
            if (tail > 0) free.put(aligned + size, tail);
            used += size;
            return aligned;
        }
        return -1;
    }

    /** Hand a range back, merging it with the free ranges on either side. */
    void release(long offset, long size) {
        used -= size;
        Map.Entry<Long, Long> prev = free.floorEntry(offset);
        if (prev != null && prev.getKey() + prev.getValue() == offset) {
            size += prev.getValue();
            offset = prev.getKey();
            free.remove(prev.getKey());
        }
        Long nextSize = free.get(offset + size);
        if (nextSize != null) {
            free.remove(offset + size);
            size += nextSize;
        }
        free.put(offset, size);
    }

    /** True once every byte is back, i.e. the whole block can be destroyed. */
    boolean empty() {
        return free.size() == 1 && free.firstEntry().getValue() == capacity;
    }
}

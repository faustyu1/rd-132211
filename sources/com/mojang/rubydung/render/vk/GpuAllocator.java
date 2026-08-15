package com.mojang.rubydung.render.vk;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Sub-allocator over a handful of large {@code VkDeviceMemory} blocks.
 *
 * A vkAllocateMemory per resource is the obvious implementation and the wrong one: drivers
 * cap the number of live allocations (maxMemoryAllocationCount is 4096 on a lot of them),
 * and a chunk world at a large render distance wants tens of thousands of buffers. Blocks
 * are carved up instead, so the driver sees a few dozen allocations no matter how far the
 * world streams.
 *
 * Buffers and images are never placed in the same block, which sidesteps
 * bufferImageGranularity entirely.
 */
public final class GpuAllocator {
    private static final long BLOCK_SIZE = 64L * 1024 * 1024;

    /** A reserved range inside a block. Free it through {@link GpuAllocator#free}. */
    public static final class Alloc {
        public long memory;      // the VkDeviceMemory the range lives in
        public long offset;      // byte offset of the range within that memory
        public long size;
        public long mappedPtr;   // host pointer to this range, or 0 when not host visible
        private Block block;
    }

    private static final class Block {
        long memory;
        int memType;
        boolean image;
        long mappedBase;   // 0 when the memory type is not host visible
        FreeList free;
    }

    private final VkContext ctx;
    private final List<Block> blocks = new ArrayList<>();
    private int liveAllocs;

    public GpuAllocator(VkContext ctx) {
        this.ctx = ctx;
    }

    public synchronized int blockCount() { return blocks.size(); }
    public synchronized int liveAllocations() { return liveAllocs; }

    /** Total bytes reserved from the driver across all blocks. */
    public synchronized long reservedBytes() {
        long n = 0;
        for (Block b : blocks) n += b.free.capacity();
        return n;
    }

    /** Bytes actually handed out to callers. */
    public synchronized long usedBytes() {
        long n = 0;
        for (Block b : blocks) n += b.free.used();
        return n;
    }

    public Alloc allocate(VkMemoryRequirements req, int properties, boolean image) {
        return allocate(req.size(), req.alignment(), req.memoryTypeBits(), properties, image);
    }

    public synchronized Alloc allocate(long size, long alignment, int memoryTypeBits,
                                       int properties, boolean image) {
        int memType = ctx.findMemoryType(memoryTypeBits, properties);
        long align = Math.max(alignment, 1);

        for (Block b : blocks) {
            if (b.memType != memType || b.image != image) continue;
            long off = b.free.allocate(size, align);
            if (off >= 0) return wrap(b, off, size);
        }

        // Nothing fitted: a request larger than the standard block gets a block of its own.
        long blockSize = Math.max(BLOCK_SIZE, size + align);
        Block b = createBlock(memType, blockSize, image, properties);
        long off = b.free.allocate(size, align);
        if (off < 0) throw new RuntimeException("GpuAllocator: fresh block cannot fit " + size + " bytes");
        return wrap(b, off, size);
    }

    private Alloc wrap(Block b, long offset, long size) {
        Alloc a = new Alloc();
        a.block = b;
        a.memory = b.memory;
        a.offset = offset;
        a.size = size;
        a.mappedPtr = b.mappedBase == 0 ? 0 : b.mappedBase + offset;
        liveAllocs++;
        return a;
    }

    private Block createBlock(int memType, long size, boolean image, int properties) {
        try (MemoryStack stack = stackPush()) {
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(size)
                .memoryTypeIndex(memType);
            LongBuffer pMem = stack.mallocLong(1);
            int err = vkAllocateMemory(ctx.device, ai, null, pMem);
            if (err != VK_SUCCESS)
                throw new RuntimeException("vkAllocateMemory failed (" + err + ") for " + size + " bytes");

            Block b = new Block();
            b.memory = pMem.get(0);
            b.memType = memType;
            b.image = image;
            b.free = new FreeList(size);
            // Host-visible blocks are mapped once, for the lifetime of the block: mapping is
            // not free and a per-resource map/unmap would also hit the driver's map limits.
            if ((properties & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
                PointerBuffer pData = stack.mallocPointer(1);
                vkMapMemory(ctx.device, b.memory, 0, size, 0, pData);
                b.mappedBase = pData.get(0);
            }
            blocks.add(b);
            return b;
        }
    }

    public synchronized void free(Alloc a) {
        if (a == null || a.block == null) return;
        a.block.free.release(a.offset, a.size);
        a.block = null;
        a.memory = VK_NULL_HANDLE;
        a.mappedPtr = 0;
        liveAllocs--;
    }

    /**
     * Blocks are kept once created rather than returned to the driver when they empty: a
     * player walking a straight line empties and refills the same block every few seconds,
     * and re-allocating it each time is exactly the churn this class exists to avoid.
     */
    public synchronized void destroy() {
        for (Block b : blocks) {
            if (b.mappedBase != 0) vkUnmapMemory(ctx.device, b.memory);
            vkFreeMemory(ctx.device, b.memory, null);
        }
        blocks.clear();
        liveAllocs = 0;
    }
}

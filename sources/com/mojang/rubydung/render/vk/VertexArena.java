package com.mojang.rubydung.render.vk;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Device-local storage for chunk section meshes, carved out of a few large buffers.
 *
 * Two things fall out of putting every mesh in a shared buffer. The vertex data lives in
 * device-local memory, so the GPU is not reading it back over the bus every frame the way it
 * did when each mesh was its own host-visible buffer. And because hundreds of sections share
 * one {@code VkBuffer}, the renderer binds it once and addresses each mesh by
 * {@code vertexOffset}, which is what makes a single multi-draw possible instead of a bind
 * plus a draw per section.
 */
public final class VertexArena {
    private static final long BLOCK_SIZE = 32L * 1024 * 1024;

    /** A mesh's range inside one arena block. */
    public static final class Slice {
        public VkBuf buffer;
        public long offset;      // byte offset within that buffer
        public long size;
        public int blockIndex;   // which arena block, so draws can be grouped by buffer
        /** First vertex of this slice, i.e. what vkCmdDrawIndexed wants as vertexOffset. */
        public int firstVertex;
        private Block block;
    }

    private static final class Block {
        VkBuf buf;
        FreeList free;
    }

    private final VkContext ctx;
    private final List<Block> blocks = new ArrayList<>();
    private final int vertexStride;

    public VertexArena(VkContext ctx, int vertexStride) {
        this.ctx = ctx;
        this.vertexStride = vertexStride;
    }

    public synchronized int blockCount() { return blocks.size(); }
    public synchronized VkBuf block(int i) { return blocks.get(i).buf; }

    public synchronized long reservedBytes() { return (long) blocks.size() * BLOCK_SIZE; }

    public synchronized long usedBytes() {
        long n = 0;
        for (Block b : blocks) n += b.free.used();
        return n;
    }

    /**
     * Reserve space for a mesh. Offsets are aligned to the vertex stride so the range starts
     * on a whole vertex — {@code vertexOffset} counts vertices, not bytes.
     */
    public synchronized Slice allocate(long bytes) {
        long size = (bytes + vertexStride - 1) / vertexStride * vertexStride;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            long off = b.free.allocate(size, vertexStride);
            if (off >= 0) return wrap(b, i, off, size);
        }
        if (size > BLOCK_SIZE)
            throw new IllegalArgumentException("chunk mesh of " + size + " bytes exceeds the arena block size");
        Block b = new Block();
        b.buf = new VkBuf(ctx, BLOCK_SIZE,
            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        b.free = new FreeList(BLOCK_SIZE);
        blocks.add(b);
        long off = b.free.allocate(size, vertexStride);
        return wrap(b, blocks.size() - 1, off, size);
    }

    private Slice wrap(Block b, int index, long offset, long size) {
        Slice s = new Slice();
        s.block = b;
        s.buffer = b.buf;
        s.blockIndex = index;
        s.offset = offset;
        s.size = size;
        s.firstVertex = (int) (offset / vertexStride);
        return s;
    }

    public synchronized void free(Slice s) {
        if (s == null || s.block == null) return;
        s.block.free.release(s.offset, s.size);
        s.block = null;
        s.buffer = null;
    }

    public synchronized void destroy() {
        for (Block b : blocks) b.buf.free();
        blocks.clear();
    }
}

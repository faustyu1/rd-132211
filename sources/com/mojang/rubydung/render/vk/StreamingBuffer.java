package com.mojang.rubydung.render.vk;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Per-frame ring buffer for streamed (Tesselator) vertex data.
 * Offset resets each frame; grows with reallocation (old buffer deferred-deleted).
 */
public class StreamingBuffer {
    private final VkContext ctx;
    private final DeferredDeleter deleter;
    private VkBuf buf;
    private long offset;     // current byte offset within the frame
    private long capacity;

    public StreamingBuffer(VkContext ctx, DeferredDeleter deleter, long initialBytes) {
        this.ctx = ctx;
        this.deleter = deleter;
        this.capacity = initialBytes;
        this.buf = new VkBuf(ctx, capacity, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
    }

    public void beginFrame() {
        offset = 0;
    }

    /** Allocate `bytes` of space; returns the byte offset to use. Grows buffer if needed. */
    public long allocate(long bytes) {
        // align to 4 bytes (float)
        long aligned = (offset + 3) & ~3L;
        if (aligned + bytes > capacity) {
            // grow: at least double, and big enough for this allocation since frame start
            long needed = aligned + bytes;
            long newCap = capacity;
            while (newCap < needed) newCap *= 2;
            growTo(newCap);
            aligned = (offset + 3) & ~3L;
        }
        offset = aligned + bytes;
        return aligned;
    }

    private void growTo(long newCap) {
        final VkBuf old = buf;
        deleter.enqueue(old::free);
        buf = new VkBuf(ctx, newCap, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        capacity = newCap;
        // note: data written this frame before growth is lost, but allocate() is called
        // before each write, so the new buffer is used for the failing allocation onward.
    }

    public VkBuf buffer() { return buf; }

    public void free() { buf.free(); }
}

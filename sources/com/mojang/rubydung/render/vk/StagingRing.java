package com.mojang.rubydung.render.vk;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;

/**
 * Host-visible scratch space a frame copies chunk meshes through on their way into the
 * device-local {@link VertexArena}.
 *
 * The fixed size is deliberate: when a frame fills it, the meshes that did not fit simply
 * wait for the next frame. That is the backpressure that stops chunk streaming from
 * uploading faster than the GPU can consume, which is the same failure the per-frame upload
 * budget was there to prevent.
 */
public final class StagingRing {
    private final VkContext ctx;
    private final DeferredDeleter deleter;
    private VkBuf buf;
    private long capacity;
    private long offset;

    public StagingRing(VkContext ctx, DeferredDeleter deleter, long bytes) {
        this.ctx = ctx;
        this.deleter = deleter;
        this.capacity = bytes;
        this.buf = new VkBuf(ctx, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    }

    public void beginFrame() { offset = 0; }

    public VkBuf buffer() { return buf; }
    public long remaining() { return capacity - offset; }

    /**
     * Reserve {@code bytes} for this frame. Returns the byte offset, or -1 when the frame's
     * budget is spent. A single request larger than the whole ring grows it instead, since
     * refusing forever would leave that mesh permanently unuploadable.
     */
    public long allocate(long bytes) {
        long aligned = (offset + 15) & ~15L;
        if (aligned + bytes > capacity) {
            if (bytes > capacity) {
                long newCap = capacity;
                while (newCap < bytes) newCap *= 2;
                final VkBuf old = buf;
                deleter.enqueue(old::free);
                buf = new VkBuf(ctx, newCap, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                capacity = newCap;
                offset = bytes;
                return 0;
            }
            return -1;
        }
        offset = aligned + bytes;
        return aligned;
    }

    public void free() { buf.free(); }
}

package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A buffer backed by a range of one of {@link GpuAllocator}'s shared memory blocks.
 *
 * Host-visible buffers stay mapped for their whole life (the block is mapped once, this is
 * just a pointer into it). Device-local buffers have no host pointer at all and are filled
 * with {@code vkCmdCopyBuffer} from a staging range.
 */
public class VkBuf {
    public long buffer = VK_NULL_HANDLE;
    public long size;
    public long mappedPtr;        // 0 for device-local buffers
    private ByteBuffer mapped;    // wrapped view, null for device-local
    private GpuAllocator.Alloc alloc;

    private final VkContext ctx;

    /** Host-visible + coherent, persistently mapped. */
    public VkBuf(VkContext ctx, long size, int usage) {
        this(ctx, size, usage, false);
    }

    public VkBuf(VkContext ctx, long size, int usage, boolean deviceLocal) {
        this.ctx = ctx;
        this.size = size;
        int props = deviceLocal
            ? VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
            : VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        try (MemoryStack stack = stackPush()) {
            VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            if (vkCreateBuffer(ctx.device, ci, null, pBuf) != VK_SUCCESS)
                throw new RuntimeException("vkCreateBuffer failed");
            buffer = pBuf.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(ctx.device, buffer, req);
            alloc = ctx.allocator.allocate(req, props, false);
            vkBindBufferMemory(ctx.device, buffer, alloc.memory, alloc.offset);

            mappedPtr = alloc.mappedPtr;
            if (mappedPtr != 0) mapped = memByteBuffer(mappedPtr, (int) size);
        }
    }

    /** Returns the persistently-mapped buffer, positioned at 0. Host-visible buffers only. */
    public ByteBuffer map() {
        if (mapped == null) throw new IllegalStateException("VkBuf is device-local: not mappable");
        mapped.clear();
        return mapped;
    }

    /** Upload a float array starting at byteOffset. */
    public void upload(float[] data, long byteOffset) {
        upload2(data, byteOffset, data.length);
    }

    /** Upload the first floatCount floats of data starting at byteOffset. */
    public void upload2(float[] data, long byteOffset, int floatCount) {
        if (mappedPtr == 0) throw new IllegalStateException("VkBuf is device-local: not mappable");
        ByteBuffer b = memByteBuffer(mappedPtr + byteOffset, floatCount * 4);
        b.asFloatBuffer().put(data, 0, floatCount);
    }

    /** Upload raw bytes starting at byteOffset. */
    public void uploadBytes(byte[] data, long byteOffset, int count) {
        if (mappedPtr == 0) throw new IllegalStateException("VkBuf is device-local: not mappable");
        ByteBuffer b = memByteBuffer(mappedPtr + byteOffset, count);
        b.put(data, 0, count);
    }

    public void free() {
        if (buffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(ctx.device, buffer, null);
            ctx.allocator.free(alloc);
            buffer = VK_NULL_HANDLE;
            mapped = null;
            mappedPtr = 0;
        }
    }
}

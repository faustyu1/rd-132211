package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memByteBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** A host-visible|coherent buffer with persistent mapping. */
public class VkBuf {
    public long buffer = VK_NULL_HANDLE;
    public long memory = VK_NULL_HANDLE;
    public long size;
    public long mappedPtr;        // raw mapped pointer
    private ByteBuffer mapped;    // wrapped view

    private final VkContext ctx;

    public VkBuf(VkContext ctx, long size, int usage) {
        this.ctx = ctx;
        this.size = size;
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

            int memType = ctx.findMemoryType(req.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(memType);
            LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(ctx.device, ai, null, pMem) != VK_SUCCESS)
                throw new RuntimeException("vkAllocateMemory failed");
            memory = pMem.get(0);

            vkBindBufferMemory(ctx.device, buffer, memory, 0);

            PointerBuffer pData = stack.mallocPointer(1);
            vkMapMemory(ctx.device, memory, 0, size, 0, pData);
            mappedPtr = pData.get(0);
            mapped = memByteBuffer(mappedPtr, (int) size);
        }
    }

    /** Returns the persistently-mapped buffer, positioned at 0. */
    public ByteBuffer map() {
        mapped.clear();
        return mapped;
    }

    /** Upload a float array starting at byteOffset. */
    public void upload(float[] data, long byteOffset) {
        ByteBuffer b = memByteBuffer(mappedPtr + byteOffset, data.length * 4);
        b.asFloatBuffer().put(data);
    }

    /** Upload the first floatCount floats of data starting at byteOffset. */
    public void upload2(float[] data, long byteOffset, int floatCount) {
        ByteBuffer b = memByteBuffer(mappedPtr + byteOffset, floatCount * 4);
        b.asFloatBuffer().put(data, 0, floatCount);
    }

    public void free() {
        if (buffer != VK_NULL_HANDLE) {
            vkUnmapMemory(ctx.device, memory);
            vkDestroyBuffer(ctx.device, buffer, null);
            vkFreeMemory(ctx.device, memory, null);
            buffer = VK_NULL_HANDLE;
            memory = VK_NULL_HANDLE;
        }
    }
}

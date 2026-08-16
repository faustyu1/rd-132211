package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the descriptor set layout, pool, and the per-frame fog uniform buffer.
 * Layout: binding0 = combined image sampler (FRAG), binding1 = dynamic fog UBO (FRAG).
 *
 * Fog used to be a second dimension of the descriptor sets — every texture carried a
 * "fog on" set and a "fog off" set, per frame — even though the shader already reads an
 * {@code enabled} flag out of the buffer. It is a dynamic uniform buffer instead: the frame
 * writes each distinct fog state into its own slot and a bind picks one by offset, so a
 * texture needs one set per frame rather than two, and turning fog off mid-frame costs an
 * offset rather than a whole parallel set of descriptors.
 */
public class DescriptorAllocator {
    public static final int FOG_UBO_SIZE = 32; // vec4 color(16) + start+end+enabled+brightness
    /** Distinct fog states one frame may use. Off, world fog, and room to spare. */
    private static final int SLOTS = 16;
    private static final int MAX_TEXTURES = 64;

    private final VkContext ctx;
    public long setLayout = VK_NULL_HANDLE;
    private long pool = VK_NULL_HANDLE;

    /** Distance between two fog slots, padded up to the device's UBO offset alignment. */
    public final int slotStride;
    private final VkBuf[] fogBuf = new VkBuf[FrameSync.FRAMES_IN_FLIGHT];
    private final int[] slotsUsed = new int[FrameSync.FRAMES_IN_FLIGHT];
    // params of every slot written this frame, so an unchanged setFog reuses its slot
    private final float[][][] slotState = new float[FrameSync.FRAMES_IN_FLIGHT][SLOTS][6];

    public DescriptorAllocator(VkContext ctx) {
        this.ctx = ctx;
        long align = Math.max(ctx.minUniformBufferOffsetAlignment, 1);
        this.slotStride = (int) ((FOG_UBO_SIZE + align - 1) / align * align);
        createSetLayout();
        createPool();

        for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++) {
            fogBuf[i] = new VkBuf(ctx, (long) slotStride * SLOTS, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            // slot 0 is the constant "no fog, full brightness" state the UI and HUD draw with
            writeSlot(i, 0, 0, 0, 0, 0, 0, false, 1.0f);
            slotsUsed[i] = 1;
        }
    }

    /** Reset the frame's slot ring. Slot 0 stays the constant fog-off state. */
    public void beginFrame(int frame) {
        slotsUsed[frame] = 1;
    }

    /** Byte offset of the fog-off slot. */
    public int fogOffOffset() { return 0; }

    /**
     * Byte offset of a slot holding these fog parameters, writing a new one if the frame has
     * not used them yet. Falls back to reusing the last slot when the ring is exhausted,
     * which cannot happen with the handful of fog states the game actually sets.
     */
    public int fogOffset(int frame, float r, float g, float b, float start, float end, float brightness) {
        for (int s = 1; s < slotsUsed[frame]; s++) {
            float[] st = slotState[frame][s];
            if (st[0] == r && st[1] == g && st[2] == b && st[3] == start && st[4] == end && st[5] == brightness)
                return s * slotStride;
        }
        int slot = slotsUsed[frame] < SLOTS ? slotsUsed[frame]++ : SLOTS - 1;
        writeSlot(frame, slot, r, g, b, start, end, true, brightness);
        return slot * slotStride;
    }

    private void writeSlot(int frame, int slot, float r, float g, float b,
                           float start, float end, boolean enabled, float brightness) {
        ByteBuffer bb = fogBuf[frame].map();
        bb.position(slot * slotStride);
        bb.putFloat(r).putFloat(g).putFloat(b).putFloat(1.0f);
        bb.putFloat(start).putFloat(end).putFloat(enabled ? 1.0f : 0.0f).putFloat(brightness);
        float[] st = slotState[frame][slot];
        st[0] = r; st[1] = g; st[2] = b; st[3] = start; st[4] = end; st[5] = brightness;
    }

    private void createSetLayout() {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bindings.get(0)
                .binding(0)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            bindings.get(1)
                .binding(1)
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                .descriptorCount(1)
                .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);

            VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(bindings);
            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(ctx.device, ci, null, pLayout) != VK_SUCCESS)
                throw new RuntimeException("vkCreateDescriptorSetLayout failed");
            setLayout = pLayout.get(0);
        }
    }

    private void createPool() {
        try (MemoryStack stack = stackPush()) {
            int maxSets = MAX_TEXTURES * FrameSync.FRAMES_IN_FLIGHT;
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(maxSets);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC).descriptorCount(maxSets);

            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(maxSets)
                .pPoolSizes(sizes);
            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(ctx.device, ci, null, pPool) != VK_SUCCESS)
                throw new RuntimeException("vkCreateDescriptorPool failed");
            pool = pPool.get(0);
        }
    }

    /** Allocate and write one descriptor set per frame-in-flight for a texture. */
    public void allocateForTexture(VkTexture tex) {
        int frames = FrameSync.FRAMES_IN_FLIGHT;
        tex.descSets = new long[frames];
        try (MemoryStack stack = stackPush()) {
            LongBuffer layouts = stack.mallocLong(frames);
            for (int i = 0; i < frames; i++) layouts.put(i, setLayout);

            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(pool)
                .pSetLayouts(layouts);
            LongBuffer pSets = stack.mallocLong(frames);
            if (vkAllocateDescriptorSets(ctx.device, ai, pSets) != VK_SUCCESS)
                throw new RuntimeException("vkAllocateDescriptorSets failed");
            for (int i = 0; i < frames; i++) tex.descSets[i] = pSets.get(i);

            for (int frame = 0; frame < frames; frame++) {
                VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(tex.sampler)
                    .imageView(tex.view)
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                // range is what one dynamic offset makes visible, not the whole slot ring
                VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(fogBuf[frame].buffer).offset(0).range(FOG_UBO_SIZE);

                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(tex.descSets[frame]).dstBinding(0).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imgInfo);
                writes.get(1)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(tex.descSets[frame]).dstBinding(1).dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC)
                    .descriptorCount(1)
                    .pBufferInfo(bufInfo);
                vkUpdateDescriptorSets(ctx.device, writes, null);
            }
        }
    }

    public void destroy() {
        for (VkBuf b : fogBuf) if (b != null) b.free();
        if (pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx.device, pool, null);
        if (setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(ctx.device, setLayout, null);
    }
}

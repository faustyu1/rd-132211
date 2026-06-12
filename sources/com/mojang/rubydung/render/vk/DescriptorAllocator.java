package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the descriptor set layout, pool, and fog UBOs.
 * Layout: binding0 = combined image sampler (FRAG), binding1 = fog UBO (FRAG).
 * Per texture we allocate FRAMES_IN_FLIGHT x 2 sets: [fogOff|fogOn][frame].
 */
public class DescriptorAllocator {
    public static final int FOG_UBO_SIZE = 32; // vec4 color(16) + start(4)+end(4)+enabled(4)+pad(4)
    private static final int MAX_TEXTURES = 64;

    private final VkContext ctx;
    public long setLayout = VK_NULL_HANDLE;
    private long pool = VK_NULL_HANDLE;

    // fog UBOs: fogOff is constant; fogOn[frame] updated once per frame
    public final VkBuf fogOff;
    public final VkBuf[] fogOn = new VkBuf[FrameSync.FRAMES_IN_FLIGHT];

    public DescriptorAllocator(VkContext ctx) {
        this.ctx = ctx;
        createSetLayout();
        createPool();

        fogOff = new VkBuf(ctx, FOG_UBO_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
        writeFog(fogOff, 0, 0, 0, 0, 0, false, 1.0f); // disabled, full brightness (UI/HUD)
        for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++) {
            fogOn[i] = new VkBuf(ctx, FOG_UBO_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            writeFog(fogOn[i], 0.5f, 0.8f, 1.0f, 0f, 128f, true, 1.0f);
        }
    }

    // last-written fog params per frame, to skip redundant rewrites
    private final float[][] fogOnState = new float[FrameSync.FRAMES_IN_FLIGHT][6];

    public void updateFogOn(int frame, float r, float g, float b, float start, float end, float brightness) {
        float[] s = fogOnState[frame];
        if (s[0] == r && s[1] == g && s[2] == b && s[3] == start && s[4] == end && s[5] == brightness) return;
        s[0] = r; s[1] = g; s[2] = b; s[3] = start; s[4] = end; s[5] = brightness;
        writeFog(fogOn[frame], r, g, b, start, end, true, brightness);
    }

    private void writeFog(VkBuf buf, float r, float g, float b, float start, float end, boolean enabled, float brightness) {
        ByteBuffer bb = buf.map();
        bb.putFloat(r).putFloat(g).putFloat(b).putFloat(1.0f); // color vec4
        bb.putFloat(start).putFloat(end).putFloat(enabled ? 1.0f : 0.0f).putFloat(brightness);
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
                .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
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
            int setsPerTexture = FrameSync.FRAMES_IN_FLIGHT * 2;
            int maxSets = MAX_TEXTURES * setsPerTexture;
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(maxSets);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(maxSets);

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

    /** Allocate and write descriptor sets for a texture. Populates tex.descSets (length FRAMES*2). */
    public void allocateForTexture(VkTexture tex) {
        int frames = FrameSync.FRAMES_IN_FLIGHT;
        int total = frames * 2;
        tex.descSets = new long[total];
        try (MemoryStack stack = stackPush()) {
            LongBuffer layouts = stack.mallocLong(total);
            for (int i = 0; i < total; i++) layouts.put(i, setLayout);

            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(pool)
                .pSetLayouts(layouts);
            LongBuffer pSets = stack.mallocLong(total);
            if (vkAllocateDescriptorSets(ctx.device, ai, pSets) != VK_SUCCESS)
                throw new RuntimeException("vkAllocateDescriptorSets failed");
            for (int i = 0; i < total; i++) tex.descSets[i] = pSets.get(i);

            // write each set: image = tex, ubo = (fogMode==0 ? fogOff : fogOn[frame])
            for (int fogMode = 0; fogMode < 2; fogMode++) {
                for (int frame = 0; frame < frames; frame++) {
                    int idx = setIndex(fogMode, frame);
                    long set = tex.descSets[idx];
                    long ubo = (fogMode == 0) ? fogOff.buffer : fogOn[frame].buffer;

                    VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                        .sampler(tex.sampler)
                        .imageView(tex.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                        .buffer(ubo).offset(0).range(FOG_UBO_SIZE);

                    VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
                    writes.get(0)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set).dstBinding(0).dstArrayElement(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .pImageInfo(imgInfo);
                    writes.get(1)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set).dstBinding(1).dstArrayElement(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .descriptorCount(1)
                        .pBufferInfo(bufInfo);
                    vkUpdateDescriptorSets(ctx.device, writes, null);
                }
            }
        }
    }

    public static int setIndex(int fogMode, int frame) {
        return fogMode * FrameSync.FRAMES_IN_FLIGHT + frame;
    }

    public void destroy() {
        fogOff.free();
        for (VkBuf b : fogOn) if (b != null) b.free();
        if (pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(ctx.device, pool, null);
        if (setLayout != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(ctx.device, setLayout, null);
    }
}

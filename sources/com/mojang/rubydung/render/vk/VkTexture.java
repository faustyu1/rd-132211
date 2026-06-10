package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** A sampled 2D texture: image + view + sampler (NEAREST). Uploaded via staging buffer at init time. */
public class VkTexture {
    public long image = VK_NULL_HANDLE;
    public long memory = VK_NULL_HANDLE;
    public long view = VK_NULL_HANDLE;
    public long sampler = VK_NULL_HANDLE;
    public final int width, height;

    private final VkContext ctx;
    // descriptor sets bound for this texture, indexed in DescriptorAllocator
    public long[] descSets; // [fog(0/1)][frame] flattened; assigned by DescriptorAllocator

    public VkTexture(VkContext ctx, FrameSync frames, int width, int height, ByteBuffer rgba, boolean linear) {
        this.ctx = ctx;
        this.width = width;
        this.height = height;
        createImage();
        uploadViaStaging(frames, rgba);
        createView();
        createSampler(linear);
    }

    private void createImage() {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().width(width).height(height).depth(1);

            LongBuffer pImg = stack.mallocLong(1);
            if (vkCreateImage(ctx.device, ci, null, pImg) != VK_SUCCESS)
                throw new RuntimeException("vkCreateImage failed");
            image = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(ctx.device, image, req);
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(ctx.device, ai, null, pMem) != VK_SUCCESS)
                throw new RuntimeException("vkAllocateMemory (image) failed");
            memory = pMem.get(0);
            vkBindImageMemory(ctx.device, image, memory, 0);
        }
    }

    private void uploadViaStaging(FrameSync frames, ByteBuffer rgba) {
        long imageSize = (long) width * height * 4;
        VkBuf staging = new VkBuf(ctx, imageSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        ByteBuffer dst = staging.map();
        int oldPos = rgba.position();
        dst.put(rgba);
        rgba.position(oldPos);

        long cmd = frames.beginOneShot();
        try (MemoryStack stack = stackPush()) {
            transition(stack, cmd, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(new VkCommandBuffer(cmd, ctx.device), staging.buffer, image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            transition(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }
        frames.endOneShot(cmd);
        staging.free();
    }

    private void transition(MemoryStack stack, long cmd, int oldLayout, int newLayout,
                            int srcAccess, int dstAccess, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image)
            .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(new VkCommandBuffer(cmd, ctx.device), srcStage, dstStage, 0, null, null, b);
    }

    private void createView() {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM);
            ci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            if (vkCreateImageView(ctx.device, ci, null, pView) != VK_SUCCESS)
                throw new RuntimeException("vkCreateImageView (texture) failed");
            view = pView.get(0);
        }
    }

    private void createSampler(boolean linear) {
        try (MemoryStack stack = stackPush()) {
            int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(filter).minFilter(filter)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .anisotropyEnable(false)
                .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                .compareEnable(false)
                .minLod(0).maxLod(0);
            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(ctx.device, ci, null, pSampler) != VK_SUCCESS)
                throw new RuntimeException("vkCreateSampler failed");
            sampler = pSampler.get(0);
        }
    }

    public void free() {
        if (sampler != VK_NULL_HANDLE) vkDestroySampler(ctx.device, sampler, null);
        if (view != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, view, null);
        if (image != VK_NULL_HANDLE) vkDestroyImage(ctx.device, image, null);
        if (memory != VK_NULL_HANDLE) vkFreeMemory(ctx.device, memory, null);
    }
}

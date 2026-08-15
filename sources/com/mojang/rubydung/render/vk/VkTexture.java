package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A sampled texture: image + view + sampler. Two shapes are used by the game:
 *
 * <ul>
 *   <li>a plain 2D image with one mip, for the UI and the font atlas, where the geometry is
 *       screen-aligned and a mip chain would only blur it;</li>
 *   <li>a 2D <em>array</em> with a full mip chain, for the terrain. The array is what makes
 *       mipmapping possible at all: mips of a packed atlas average neighbouring tiles into
 *       each other, so a distant block ends up fringed with whatever tile sits next to it in
 *       the sheet. One tile per array layer has no neighbours to bleed from.</li>
 * </ul>
 */
public class VkTexture {
    public long image = VK_NULL_HANDLE;
    public long view = VK_NULL_HANDLE;
    public long sampler = VK_NULL_HANDLE;
    public final int width, height, layers, mipLevels;

    private final VkContext ctx;
    private GpuAllocator.Alloc memory;
    // descriptor sets bound for this texture, indexed in DescriptorAllocator
    public long[] descSets;

    /** Plain 2D texture, one mip level. */
    public VkTexture(VkContext ctx, FrameSync frames, int width, int height, ByteBuffer rgba, boolean linear) {
        this(ctx, frames, width, height, 1, 1, sliceOf(rgba, width * height * 4), linear, false);
    }

    /**
     * Slice a tile atlas into one array layer per tile, generate mips, and upload.
     * Tiles are read row-major, so layer index == tile index counted left-to-right, top-down —
     * the same numbering {@code Tile} already uses for its atlas positions.
     */
    public static VkTexture atlasArray(VkContext ctx, FrameSync frames, ByteBuffer atlas,
                                       int atlasW, int atlasH, int tileW, int tileH) {
        int cols = atlasW / tileW, rows = atlasH / tileH;
        int layers = cols * rows;
        int mips = mipCount(tileW, tileH);
        byte[][] levels = new byte[mips][];

        // level 0: every tile copied out row by row into its own layer
        byte[] base = new byte[layers * tileW * tileH * 4];
        int atlasBase = atlas.position();
        for (int layer = 0; layer < layers; layer++) {
            int tx = (layer % cols) * tileW, ty = (layer / cols) * tileH;
            int dst = layer * tileW * tileH * 4;
            for (int y = 0; y < tileH; y++) {
                int src = atlasBase + ((ty + y) * atlasW + tx) * 4;
                for (int i = 0; i < tileW * 4; i++) base[dst + y * tileW * 4 + i] = atlas.get(src + i);
            }
        }
        levels[0] = base;
        int w = tileW, h = tileH;
        for (int m = 1; m < mips; m++) {
            int nw = Math.max(1, w >> 1), nh = Math.max(1, h >> 1);
            levels[m] = downsample(levels[m - 1], layers, w, h, nw, nh);
            w = nw; h = nh;
        }
        return new VkTexture(ctx, frames, tileW, tileH, layers, mips, levels, false, true);
    }

    private static byte[][] sliceOf(ByteBuffer rgba, int bytes) {
        byte[] data = new byte[bytes];
        int pos = rgba.position();
        for (int i = 0; i < bytes; i++) data[i] = rgba.get(pos + i);
        return new byte[][]{data};
    }

    private static int mipCount(int w, int h) {
        int n = 1;
        while (w > 1 || h > 1) { w = Math.max(1, w >> 1); h = Math.max(1, h >> 1); n++; }
        return n;
    }

    /** 2x2 box filter, per layer. Done on the CPU: no blit chain, no format-feature check. */
    private static byte[] downsample(byte[] src, int layers, int w, int h, int nw, int nh) {
        byte[] dst = new byte[layers * nw * nh * 4];
        for (int layer = 0; layer < layers; layer++) {
            int sBase = layer * w * h * 4, dBase = layer * nw * nh * 4;
            for (int y = 0; y < nh; y++) {
                for (int x = 0; x < nw; x++) {
                    for (int c = 0; c < 4; c++) {
                        int x0 = Math.min(x * 2, w - 1), x1 = Math.min(x * 2 + 1, w - 1);
                        int y0 = Math.min(y * 2, h - 1), y1 = Math.min(y * 2 + 1, h - 1);
                        int sum = (src[sBase + (y0 * w + x0) * 4 + c] & 0xFF)
                                + (src[sBase + (y0 * w + x1) * 4 + c] & 0xFF)
                                + (src[sBase + (y1 * w + x0) * 4 + c] & 0xFF)
                                + (src[sBase + (y1 * w + x1) * 4 + c] & 0xFF);
                        dst[dBase + (y * nw + x) * 4 + c] = (byte) (sum >> 2);
                    }
                }
            }
        }
        return dst;
    }

    private VkTexture(VkContext ctx, FrameSync frames, int width, int height, int layers, int mipLevels,
                      byte[][] levelData, boolean linear, boolean array) {
        this.ctx = ctx;
        this.width = width;
        this.height = height;
        this.layers = layers;
        this.mipLevels = mipLevels;
        createImage();
        uploadViaStaging(frames, levelData);
        createView(array);
        createSampler(linear);
    }

    private void createImage() {
        try (MemoryStack stack = stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM)
                .mipLevels(mipLevels)
                .arrayLayers(layers)
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
            memory = ctx.allocator.allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);
            vkBindImageMemory(ctx.device, image, memory.memory, memory.offset);
        }
    }

    private void uploadViaStaging(FrameSync frames, byte[][] levelData) {
        long total = 0;
        for (byte[] level : levelData) total += level.length;
        VkBuf staging = new VkBuf(ctx, total, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        ByteBuffer dst = staging.map();
        long[] levelOffset = new long[levelData.length];
        long off = 0;
        for (int m = 0; m < levelData.length; m++) {
            levelOffset[m] = off;
            dst.put(levelData[m]);
            off += levelData[m].length;
        }

        long cmd = frames.beginOneShot();
        try (MemoryStack stack = stackPush()) {
            transition(stack, cmd, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

            // one region per mip: array layers of a level are contiguous, so they copy together
            VkBufferImageCopy.Buffer regions = VkBufferImageCopy.calloc(mipLevels, stack);
            int w = width, h = height;
            for (int m = 0; m < mipLevels; m++) {
                VkBufferImageCopy r = regions.get(m);
                r.bufferOffset(levelOffset[m]).bufferRowLength(0).bufferImageHeight(0);
                r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(m).baseArrayLayer(0).layerCount(layers);
                r.imageOffset().set(0, 0, 0);
                r.imageExtent().set(w, h, 1);
                w = Math.max(1, w >> 1);
                h = Math.max(1, h >> 1);
            }
            vkCmdCopyBufferToImage(new VkCommandBuffer(cmd, ctx.device), staging.buffer, image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);

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
        b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(layers);
        vkCmdPipelineBarrier(new VkCommandBuffer(cmd, ctx.device), srcStage, dstStage, 0, null, null, b);
    }

    private void createView(boolean array) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(image)
                .viewType(array ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D)
                .format(VK_FORMAT_R8G8B8A8_UNORM);
            ci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(mipLevels).baseArrayLayer(0).layerCount(layers);
            LongBuffer pView = stack.mallocLong(1);
            if (vkCreateImageView(ctx.device, ci, null, pView) != VK_SUCCESS)
                throw new RuntimeException("vkCreateImageView (texture) failed");
            view = pView.get(0);
        }
    }

    private void createSampler(boolean linear) {
        try (MemoryStack stack = stackPush()) {
            int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
            boolean aniso = ctx.samplerAnisotropy && mipLevels > 1;
            VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(filter)
                // Magnification stays NEAREST so a block up close keeps its hard pixels; the
                // mip chain is only about what happens as the same texel shrinks below a pixel,
                // and blending between mip levels is what removes the shimmer.
                .minFilter(filter)
                .mipmapMode(mipLevels > 1 ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .anisotropyEnable(aniso)
                .maxAnisotropy(aniso ? Math.min(8f, ctx.maxAnisotropy) : 1f)
                .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                .compareEnable(false)
                .minLod(0).maxLod(mipLevels);
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
        ctx.allocator.free(memory);
    }
}

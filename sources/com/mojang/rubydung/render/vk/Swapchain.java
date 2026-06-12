package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/** Owns the swapchain images/views and a shared depth image. Recreated on resize/out-of-date. */
public class Swapchain {
    private final VkContext ctx;

    public long swapchain = VK_NULL_HANDLE;
    public long[] images;
    public long[] imageViews;
    public int imageFormat = VK_FORMAT_B8G8R8A8_UNORM;
    public int width, height;
    public int presentMode;

    // One depth buffer per frame-in-flight (not per swapchain image): reuse is gated by
    // the per-frame fence in FrameSync, which prevents two in-flight frames sharing a
    // depth image and corrupting each other's depth test.
    public long[] depthImages;
    public long[] depthMemories;
    public long[] depthViews;
    public static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

    private boolean vsync;

    public Swapchain(VkContext ctx, int fbWidth, int fbHeight, boolean vsync) {
        this.ctx = ctx;
        this.vsync = vsync;
        create(fbWidth, fbHeight);
    }

    public void setVsync(boolean v) { this.vsync = v; }

    private void create(int fbWidth, int fbHeight) {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(ctx.physicalDevice, ctx.surface, caps);

            // extent
            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                width = caps.currentExtent().width();
                height = caps.currentExtent().height();
            } else {
                width = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), fbWidth));
                height = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), fbHeight));
            }

            // image count: want at least 3 so MAILBOX can pipeline (acquire next while
            // two are in flight) instead of blocking on the drawable pool.
            int desired = Math.max(caps.minImageCount() + 1, 3);
            int imageCount = desired;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount())
                imageCount = caps.maxImageCount();

            // surface format: prefer B8G8R8A8_UNORM
            IntBuffer fmtCount = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice, ctx.surface, fmtCount, null);
            VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(fmtCount.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(ctx.physicalDevice, ctx.surface, fmtCount, formats);
            int chosenFormat = formats.get(0).format();
            int chosenColorSpace = formats.get(0).colorSpace();
            for (int i = 0; i < formats.capacity(); i++) {
                if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM
                    && formats.get(i).colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    chosenFormat = VK_FORMAT_B8G8R8A8_UNORM;
                    chosenColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                    break;
                }
            }
            imageFormat = chosenFormat;

            // present mode
            presentMode = VK_PRESENT_MODE_FIFO_KHR; // vsync, always available
            if (!vsync) {
                IntBuffer pmCount = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice, ctx.surface, pmCount, null);
                IntBuffer modes = stack.mallocInt(pmCount.get(0));
                vkGetPhysicalDeviceSurfacePresentModesKHR(ctx.physicalDevice, ctx.surface, pmCount, modes);
                boolean hasMailbox = false, hasImmediate = false;
                for (int i = 0; i < modes.capacity(); i++) {
                    if (modes.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) hasMailbox = true;
                    if (modes.get(i) == VK_PRESENT_MODE_IMMEDIATE_KHR) hasImmediate = true;
                }
                // Uncapped: MAILBOX is ideal (tear-free, no wait); IMMEDIATE is the fallback
                // (may tear). With 3 frames in flight, IMMEDIATE on MoltenVK runs at the
                // display's max (the macOS compositor still bounds presented frames to the
                // panel's refresh rate — there is no exclusive-fullscreen bypass on Metal).
                if (hasMailbox) presentMode = VK_PRESENT_MODE_MAILBOX_KHR;
                else if (hasImmediate) presentMode = VK_PRESENT_MODE_IMMEDIATE_KHR;
                // else: stay FIFO
            }

            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR)
                .surface(ctx.surface)
                .minImageCount(imageCount)
                .imageFormat(chosenFormat)
                .imageColorSpace(chosenColorSpace)
                .imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .preTransform(caps.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                .presentMode(presentMode)
                .clipped(true)
                .oldSwapchain(VK_NULL_HANDLE);
            ci.imageExtent().width(width).height(height);

            LongBuffer pSwap = stack.mallocLong(1);
            if (vkCreateSwapchainKHR(ctx.device, ci, null, pSwap) != VK_SUCCESS)
                throw new RuntimeException("vkCreateSwapchainKHR failed");
            swapchain = pSwap.get(0);

            IntBuffer imgCount = stack.mallocInt(1);
            vkGetSwapchainImagesKHR(ctx.device, swapchain, imgCount, null);
            LongBuffer pImages = stack.mallocLong(imgCount.get(0));
            vkGetSwapchainImagesKHR(ctx.device, swapchain, imgCount, pImages);
            images = new long[imgCount.get(0)];
            imageViews = new long[imgCount.get(0)];
            for (int i = 0; i < images.length; i++) {
                images[i] = pImages.get(i);
                imageViews[i] = createView(stack, images[i], chosenFormat, VK_IMAGE_ASPECT_COLOR_BIT);
            }

            createDepth(stack);
        }
    }

    private void createDepth(MemoryStack stack) {
        int n = FrameSync.FRAMES_IN_FLIGHT;
        depthImages = new long[n];
        depthMemories = new long[n];
        depthViews = new long[n];
        for (int i = 0; i < n; i++) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK_IMAGE_TYPE_2D)
                .format(DEPTH_FORMAT)
                .mipLevels(1).arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().width(width).height(height).depth(1);

            LongBuffer pImg = stack.mallocLong(1);
            if (vkCreateImage(ctx.device, ci, null, pImg) != VK_SUCCESS)
                throw new RuntimeException("vkCreateImage (depth) failed");
            depthImages[i] = pImg.get(0);

            VkMemoryRequirements req = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(ctx.device, depthImages[i], req);
            VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(ctx.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMem = stack.mallocLong(1);
            if (vkAllocateMemory(ctx.device, ai, null, pMem) != VK_SUCCESS)
                throw new RuntimeException("vkAllocateMemory (depth) failed");
            depthMemories[i] = pMem.get(0);
            vkBindImageMemory(ctx.device, depthImages[i], depthMemories[i], 0);

            depthViews[i] = createView(stack, depthImages[i], DEPTH_FORMAT, VK_IMAGE_ASPECT_DEPTH_BIT);
        }
    }

    private long createView(MemoryStack stack, long image, int format, int aspect) {
        VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
            .image(image)
            .viewType(VK_IMAGE_VIEW_TYPE_2D)
            .format(format);
        ci.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        if (vkCreateImageView(ctx.device, ci, null, pView) != VK_SUCCESS)
            throw new RuntimeException("vkCreateImageView failed");
        return pView.get(0);
    }

    /** Recreate after waiting for the device to be idle. */
    public void recreate(int fbWidth, int fbHeight) {
        vkDeviceWaitIdle(ctx.device);
        destroyInternal();
        create(fbWidth, fbHeight);
    }

    private void destroyInternal() {
        if (depthViews != null)
            for (long v : depthViews) if (v != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, v, null);
        if (depthImages != null)
            for (long im : depthImages) if (im != VK_NULL_HANDLE) vkDestroyImage(ctx.device, im, null);
        if (depthMemories != null)
            for (long m : depthMemories) if (m != VK_NULL_HANDLE) vkFreeMemory(ctx.device, m, null);
        if (imageViews != null)
            for (long v : imageViews) if (v != VK_NULL_HANDLE) vkDestroyImageView(ctx.device, v, null);
        if (swapchain != VK_NULL_HANDLE) vkDestroySwapchainKHR(ctx.device, swapchain, null);
        swapchain = VK_NULL_HANDLE;
    }

    public void destroy() {
        destroyInternal();
    }
}

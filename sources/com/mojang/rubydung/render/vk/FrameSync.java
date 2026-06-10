package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;

/** Manages frames-in-flight: command pools, sync primitives, and begin/end rendering. */
public class FrameSync {
    public static final int FRAMES_IN_FLIGHT = 2;

    private final VkContext ctx;
    private final Swapchain swapchain;

    private final long[] commandPools = new long[FRAMES_IN_FLIGHT];
    private final VkCommandBuffer[] commandBuffers = new VkCommandBuffer[FRAMES_IN_FLIGHT];
    private final long[] imageAvailable = new long[FRAMES_IN_FLIGHT];
    private final long[] renderFinished = new long[FRAMES_IN_FLIGHT];
    private final long[] inFlight = new long[FRAMES_IN_FLIGHT];

    // one-shot pool for transfers (init-time texture uploads)
    private long oneShotPool = VK_NULL_HANDLE;

    private int frameIndex = 0;
    private int acquiredImage = -1;

    public FrameSync(VkContext ctx, Swapchain swapchain) {
        this.ctx = ctx;
        this.swapchain = swapchain;
        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
                VkCommandPoolCreateInfo poolCi = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT)
                    .queueFamilyIndex(ctx.queueFamily);
                LongBuffer pPool = stack.mallocLong(1);
                if (vkCreateCommandPool(ctx.device, poolCi, null, pPool) != VK_SUCCESS)
                    throw new RuntimeException("vkCreateCommandPool failed");
                commandPools[i] = pPool.get(0);

                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(commandPools[i])
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
                org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1);
                vkAllocateCommandBuffers(ctx.device, allocInfo, pCmd);
                commandBuffers[i] = new VkCommandBuffer(pCmd.get(0), ctx.device);

                VkSemaphoreCreateInfo semCi = VkSemaphoreCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                VkFenceCreateInfo fenceCi = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
                LongBuffer pSem1 = stack.mallocLong(1), pSem2 = stack.mallocLong(1), pFence = stack.mallocLong(1);
                vkCreateSemaphore(ctx.device, semCi, null, pSem1);
                vkCreateSemaphore(ctx.device, semCi, null, pSem2);
                vkCreateFence(ctx.device, fenceCi, null, pFence);
                imageAvailable[i] = pSem1.get(0);
                renderFinished[i] = pSem2.get(0);
                inFlight[i] = pFence.get(0);
            }

            VkCommandPoolCreateInfo poolCi = VkCommandPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                .flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(ctx.queueFamily);
            LongBuffer pPool = stack.mallocLong(1);
            vkCreateCommandPool(ctx.device, poolCi, null, pPool);
            oneShotPool = pPool.get(0);
        }
    }

    public int frameIndex() { return frameIndex; }
    public VkCommandBuffer cmd() { return commandBuffers[frameIndex]; }

    /**
     * Begin a frame: wait for fence, acquire image, reset pool, begin cmd, transition to
     * color attachment, begin dynamic rendering with the given clear color.
     * @return false if the swapchain is out of date and the frame should be skipped.
     */
    public boolean begin(float cr, float cg, float cb) {
        try (MemoryStack stack = stackPush()) {
            vkWaitForFences(ctx.device, inFlight[frameIndex], true, Long.MAX_VALUE);

            IntBuffer pImageIndex = stack.mallocInt(1);
            int acq = vkAcquireNextImageKHR(ctx.device, swapchain.swapchain, Long.MAX_VALUE,
                imageAvailable[frameIndex], VK_NULL_HANDLE, pImageIndex);
            if (acq == VK_ERROR_OUT_OF_DATE_KHR) return false;
            if (acq != VK_SUCCESS && acq != VK_SUBOPTIMAL_KHR)
                throw new RuntimeException("vkAcquireNextImageKHR failed: " + acq);
            acquiredImage = pImageIndex.get(0);

            vkResetFences(ctx.device, inFlight[frameIndex]);
            vkResetCommandPool(ctx.device, commandPools[frameIndex], 0);

            VkCommandBuffer cmd = commandBuffers[frameIndex];
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, beginInfo);

            // color: UNDEFINED -> COLOR_ATTACHMENT_OPTIMAL
            imageBarrier(stack, cmd, swapchain.images[acquiredImage], VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            // depth: UNDEFINED -> DEPTH_ATTACHMENT
            imageBarrier(stack, cmd, swapchain.depthImages[frameIndex], VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL,
                0, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT);

            VkRenderingAttachmentInfoKHR.Buffer colorAtt = VkRenderingAttachmentInfoKHR.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(swapchain.imageViews[acquiredImage])
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            colorAtt.clearValue().color().float32(0, cr).float32(1, cg).float32(2, cb).float32(3, 1.0f);

            VkRenderingAttachmentInfoKHR depthAtt = VkRenderingAttachmentInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO_KHR)
                .imageView(swapchain.depthViews[frameIndex])
                .imageLayout(VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAtt.clearValue().depthStencil().depth(1.0f).stencil(0);

            VkRenderingInfoKHR renderingInfo = VkRenderingInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO_KHR)
                .layerCount(1)
                .pColorAttachments(colorAtt)
                .pDepthAttachment(depthAtt);
            renderingInfo.renderArea().offset().set(0, 0);
            renderingInfo.renderArea().extent().set(swapchain.width, swapchain.height);

            vkCmdBeginRenderingKHR(cmd, renderingInfo);
            return true;
        }
    }

    /** End rendering, transition to present, submit and present. Returns false if swapchain needs recreate. */
    public boolean end() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer cmd = commandBuffers[frameIndex];
            vkCmdEndRenderingKHR(cmd);

            imageBarrier(stack, cmd, swapchain.images[acquiredImage], VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

            vkEndCommandBuffer(cmd);

            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(imageAvailable[frameIndex]))
                .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                .pCommandBuffers(stack.pointers(cmd))
                .pSignalSemaphores(stack.longs(renderFinished[frameIndex]));
            if (vkQueueSubmit(ctx.queue, submit, inFlight[frameIndex]) != VK_SUCCESS)
                throw new RuntimeException("vkQueueSubmit failed");

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                .pWaitSemaphores(stack.longs(renderFinished[frameIndex]))
                .swapchainCount(1)
                .pSwapchains(stack.longs(swapchain.swapchain))
                .pImageIndices(stack.ints(acquiredImage));
            int res = vkQueuePresentKHR(ctx.queue, present);

            frameIndex = (frameIndex + 1) % FRAMES_IN_FLIGHT;

            if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR) return false;
            if (res != VK_SUCCESS) throw new RuntimeException("vkQueuePresentKHR failed: " + res);
            return true;
        }
    }

    private void imageBarrier(MemoryStack stack, VkCommandBuffer cmd, long image, int aspect,
                              int oldLayout, int newLayout, int srcAccess, int dstAccess,
                              int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack)
            .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
            .oldLayout(oldLayout).newLayout(newLayout)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image)
            .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
        b.subresourceRange().aspectMask(aspect).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
    }

    // ── one-shot command buffers (init-time transfers) ──
    public long beginOneShot() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(oneShotPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
            org.lwjgl.PointerBuffer pCmd = stack.mallocPointer(1);
            vkAllocateCommandBuffers(ctx.device, ai, pCmd);
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), ctx.device);
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, bi);
            return cmd.address();
        }
    }

    public void endOneShot(long cmdHandle) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer cmd = new VkCommandBuffer(cmdHandle, ctx.device);
            vkEndCommandBuffer(cmd);
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(stack.pointers(cmd));
            vkQueueSubmit(ctx.queue, submit, VK_NULL_HANDLE);
            vkQueueWaitIdle(ctx.queue);
            vkFreeCommandBuffers(ctx.device, oneShotPool, cmd);
        }
    }

    public void destroy() {
        for (int i = 0; i < FRAMES_IN_FLIGHT; i++) {
            if (imageAvailable[i] != VK_NULL_HANDLE) vkDestroySemaphore(ctx.device, imageAvailable[i], null);
            if (renderFinished[i] != VK_NULL_HANDLE) vkDestroySemaphore(ctx.device, renderFinished[i], null);
            if (inFlight[i] != VK_NULL_HANDLE) vkDestroyFence(ctx.device, inFlight[i], null);
            if (commandPools[i] != VK_NULL_HANDLE) vkDestroyCommandPool(ctx.device, commandPools[i], null);
        }
        if (oneShotPool != VK_NULL_HANDLE) vkDestroyCommandPool(ctx.device, oneShotPool, null);
    }
}

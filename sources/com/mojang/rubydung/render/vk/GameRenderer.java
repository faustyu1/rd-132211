package com.mojang.rubydung.render.vk;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Facade that replaces fixed-function GL for the game. Owns the whole Vulkan stack and
 * exposes a GL-like API: a matrix stack, push-constant uniforms, texture/fog/pipeline
 * selection, and indexed/streamed draws.
 */
public class GameRenderer {
    /** Global instance; set on construction so static-style call sites can reach the renderer. */
    public static GameRenderer instance;

    private final long window;

    public final VkContext ctx;
    public Swapchain swapchain;
    public FrameSync frames;
    public Pipelines pipelines;
    public DescriptorAllocator descriptors;
    public QuadIndexBuffer quadIndex;
    private final StreamingBuffer[] streaming = new StreamingBuffer[FrameSync.FRAMES_IN_FLIGHT];
    public final DeferredDeleter deleter = new DeferredDeleter(FrameSync.FRAMES_IN_FLIGHT);

    public VkTexture whiteTexture;

    // matrix state
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f modelView = new Matrix4f();
    private final Deque<Matrix4f> mvStack = new ArrayDeque<>();
    private boolean matricesDirty = true;

    // default color for Tesselator
    public float colR = 1, colG = 1, colB = 1, colA = 1;

    // render state
    private VkTexture currentTexture;
    private boolean fogEnabled = false;
    private Pipelines.Pipeline currentPipeline = null;
    private long boundPipelineHandle = VK_NULL_HANDLE;
    private long boundDescriptorSet = VK_NULL_HANDLE;
    private boolean indexBound = false;
    // reusable single-element buffers to avoid stackPush() in the hot draw path
    private final java.nio.LongBuffer vbHandle = org.lwjgl.system.MemoryUtil.memAllocLong(1);
    private final java.nio.LongBuffer vbOffset = org.lwjgl.system.MemoryUtil.memAllocLong(1);
    private final java.nio.LongBuffer descSet  = org.lwjgl.system.MemoryUtil.memAllocLong(1);
    private final java.nio.ByteBuffer pcBuf     = org.lwjgl.system.MemoryUtil.memAlloc(128);

    private boolean vsync;
    private volatile boolean resizeRequested = false;

    public GameRenderer(long window, boolean vsync) {
        instance = this;
        this.window = window;
        this.vsync = vsync;

        int[] fbw = new int[1], fbh = new int[1];
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, fbw, fbh);

        ctx = new VkContext(window);
        swapchain = new Swapchain(ctx, fbw[0], fbh[0], vsync);
        frames = new FrameSync(ctx, swapchain);
        descriptors = new DescriptorAllocator(ctx);
        pipelines = new Pipelines(ctx, descriptors, swapchain.imageFormat, Swapchain.DEPTH_FORMAT);
        quadIndex = new QuadIndexBuffer(ctx);
        for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++)
            streaming[i] = new StreamingBuffer(ctx, deleter, 16L * 1024 * 1024);

        // 1x1 white texture for untextured draws
        ByteBuffer white = org.lwjgl.system.MemoryUtil.memAlloc(4);
        white.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
        whiteTexture = new VkTexture(ctx, frames, 1, 1, white, false);
        descriptors.allocateForTexture(whiteTexture);
        org.lwjgl.system.MemoryUtil.memFree(white);
    }

    // ── texture creation helpers ──
    public VkTexture createTexture(int w, int h, ByteBuffer rgba, boolean linear) {
        VkTexture t = new VkTexture(ctx, frames, w, h, rgba, linear);
        descriptors.allocateForTexture(t);
        return t;
    }

    public void requestResize() { resizeRequested = true; }
    public void setVsync(boolean v) {
        if (this.vsync != v) {
            this.vsync = v;
            swapchain.setVsync(v);
            resizeRequested = true;
        }
    }

    private boolean skipFrame = false;

    // ── frame lifecycle ──
    /** Begin a frame with a clear color. Returns false if rendering was skipped (e.g. 0-size / out-of-date). */
    public boolean beginFrame(float r, float g, float b) {
        if (resizeRequested) { recreateSwapchain(); resizeRequested = false; }

        int[] fbw = new int[1], fbh = new int[1];
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, fbw, fbh);
        if (fbw[0] == 0 || fbh[0] == 0) { skipFrame = true; return false; }

        if (!frames.begin(r, g, b)) {
            recreateSwapchain();
            skipFrame = true;
            return false;
        }
        skipFrame = false;

        streaming[frames.frameIndex()].beginFrame();
        currentTexture = null;
        currentPipeline = null;
        boundPipelineHandle = VK_NULL_HANDLE;
        boundDescriptorSet = VK_NULL_HANDLE;
        indexBound = false;
        matricesDirty = true;

        setViewportScissor();
        return true;
    }

    private void setViewportScissor() {
        try (MemoryStack stack = stackPush()) {
            // Y-flip: negative-height viewport so GL-style matrices work unchanged
            VkViewport.Buffer vp = VkViewport.calloc(1, stack)
                .x(0).y(swapchain.height)
                .width(swapchain.width).height(-swapchain.height)
                .minDepth(0).maxDepth(1);
            vkCmdSetViewport(frames.cmd(), 0, vp);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.offset().set(0, 0);
            scissor.extent().set(swapchain.width, swapchain.height);
            vkCmdSetScissor(frames.cmd(), 0, scissor);
        }
    }

    public void endFrame() {
        if (skipFrame) { return; }
        if (!frames.end()) recreateSwapchain();
        deleter.tick();
    }

    private void recreateSwapchain() {
        int[] fbw = new int[1], fbh = new int[1];
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, fbw, fbh);
        if (fbw[0] == 0 || fbh[0] == 0) return;
        swapchain.recreate(fbw[0], fbh[0]);
    }

    public int width() { return swapchain.width; }
    public int height() { return swapchain.height; }

    // ── matrix stack (modelView) ──
    public void push() { mvStack.push(new Matrix4f(modelView)); }
    public void pop() { if (!mvStack.isEmpty()) { modelView.set(mvStack.pop()); matricesDirty = true; } }
    public void loadIdentity() { modelView.identity(); matricesDirty = true; }
    public void translate(float x, float y, float z) { modelView.translate(x, y, z); matricesDirty = true; }
    public void rotate(float deg, float x, float y, float z) {
        modelView.rotate((float) Math.toRadians(deg), x, y, z); matricesDirty = true;
    }
    public void scale(float x, float y, float z) { modelView.scale(x, y, z); matricesDirty = true; }
    public void loadModelView(Matrix4f m) { modelView.set(m); matricesDirty = true; }
    public Matrix4f getModelView(Matrix4f dst) { return dst.set(modelView); }
    public Matrix4f getProjection(Matrix4f dst) { return dst.set(projection); }

    public void setProjection(Matrix4f m) { projection.set(m); matricesDirty = true; }

    /** Set an ortho projection matching GL's glOrtho(0,w,h,0,-1,1), with zZeroToOne for Vulkan. */
    public void setOrtho(float w, float h) {
        projection.identity().setOrtho(0, w, h, 0, -1, 1, true);
        matricesDirty = true;
    }

    /** Perspective projection (degrees fov), zZeroToOne for Vulkan. */
    public void setPerspective(float fovDeg, float aspect, float near, float far) {
        projection.identity().perspective((float) Math.toRadians(fovDeg), aspect, near, far, true);
        matricesDirty = true;
    }

    // ── render state ──
    public void setColor(float r, float g, float b, float a) { colR = r; colG = g; colB = b; colA = a; }

    public void bindTexture(VkTexture tex) { currentTexture = tex; }
    public void bindWhite() { currentTexture = whiteTexture; }

    public void setFog(float r, float g, float b, float start, float end) {
        setFog(r, g, b, start, end, 1.0f);
    }

    /** Fog + global day/night brightness multiplier applied to lit geometry. */
    public void setFog(float r, float g, float b, float start, float end, float brightness) {
        descriptors.updateFogOn(frames.frameIndex(), r, g, b, start, end, brightness);
        fogEnabled = true;
    }
    public void disableFog() { fogEnabled = false; }

    public void setPipeline(Pipelines.Pipeline p) { currentPipeline = p; }

    // ── drawing ──
    private void bindPipelineIfNeeded() {
        long handle = pipelines.get(currentPipeline);
        if (handle != boundPipelineHandle) {
            vkCmdBindPipeline(frames.cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
            boundPipelineHandle = handle;
        }
    }

    private void pushConstantsIfNeeded() {
        if (!matricesDirty) return;
        projection.get(0, pcBuf);
        modelView.get(64, pcBuf);
        vkCmdPushConstants(frames.cmd(), pipelines.pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pcBuf);
        matricesDirty = false;
    }

    private void bindDescriptor() {
        VkTexture tex = currentTexture != null ? currentTexture : whiteTexture;
        int fogMode = fogEnabled ? 1 : 0;
        long set = tex.descSets[DescriptorAllocator.setIndex(fogMode, frames.frameIndex())];
        if (set == boundDescriptorSet) return;
        descSet.put(0, set);
        vkCmdBindDescriptorSets(frames.cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipelines.pipelineLayout, 0, descSet, null);
        boundDescriptorSet = set;
    }

    private void bindQuadIndexIfNeeded() {
        if (indexBound) return;
        vkCmdBindIndexBuffer(frames.cmd(), quadIndex.buffer.buffer, 0, VK_INDEX_TYPE_UINT32);
        indexBound = true;
    }

    /** Draw a persistent quad vertex buffer (e.g. a chunk layer). vertexCount = number of quad-corners. */
    public void draw(VkBuf vb, int vertexCount) {
        if (skipFrame || vertexCount == 0) return;
        VkCommandBuffer cmd = frames.cmd();
        bindPipelineIfNeeded();
        pushConstantsIfNeeded();
        bindDescriptor();
        vbHandle.put(0, vb.buffer); vbOffset.put(0, 0L);
        vkCmdBindVertexBuffers(cmd, 0, vbHandle, vbOffset);
        bindQuadIndexIfNeeded();
        vkCmdDrawIndexed(cmd, vertexCount / 4 * 6, 1, 0, 0, 0);
    }

    /** Stream interleaved triangle/quad geometry from the Tesselator (vertexCount = quad-corners). */
    public void drawStreamQuads(float[] data, int floatCount, int vertexCount) {
        if (skipFrame || vertexCount == 0) return;
        VkCommandBuffer cmd = frames.cmd();
        StreamingBuffer sb = streaming[frames.frameIndex()];
        long byteOffset = sb.allocate((long) floatCount * 4);
        sb.buffer().upload2(data, byteOffset, floatCount);

        bindPipelineIfNeeded();
        pushConstantsIfNeeded();
        bindDescriptor();
        vbHandle.put(0, sb.buffer().buffer); vbOffset.put(0, byteOffset);
        vkCmdBindVertexBuffers(cmd, 0, vbHandle, vbOffset);
        bindQuadIndexIfNeeded();
        vkCmdDrawIndexed(cmd, vertexCount / 4 * 6, 1, 0, 0, 0);
    }

    /** Stream line geometry (vertexCount = vertices, drawn as LINE_LIST). */
    public void drawStreamLines(float[] data, int floatCount, int vertexCount) {
        if (skipFrame || vertexCount == 0) return;
        VkCommandBuffer cmd = frames.cmd();
        StreamingBuffer sb = streaming[frames.frameIndex()];
        long byteOffset = sb.allocate((long) floatCount * 4);
        sb.buffer().upload2(data, byteOffset, floatCount);

        bindPipelineIfNeeded();
        pushConstantsIfNeeded();
        bindDescriptor();
        vbHandle.put(0, sb.buffer().buffer); vbOffset.put(0, byteOffset);
        vkCmdBindVertexBuffers(cmd, 0, vbHandle, vbOffset);
        vkCmdDraw(cmd, vertexCount, 1, 0, 0);
    }

    public void destroy() {
        vkDeviceWaitIdle(ctx.device);
        deleter.flushAll();
        for (StreamingBuffer sb : streaming) sb.free();
        quadIndex.free();
        whiteTexture.free();
        pipelines.destroy();
        descriptors.destroy();
        frames.destroy();
        swapchain.destroy();
        ctx.destroy();
    }
}

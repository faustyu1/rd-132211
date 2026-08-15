package com.mojang.rubydung.render.vk;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkMemoryBarrier;
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
    /** Chunk-section vertex memory: a few big device-local buffers sliced up. */
    public VertexArena chunkArena;
    private final StagingRing[] staging = new StagingRing[FrameSync.FRAMES_IN_FLIGHT];

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
    /** Byte offset of the fog state this frame's draws read, into the dynamic fog UBO. */
    private int fogOffset;
    private int boundFogOffset = -1;
    private Pipelines.Pipeline currentPipeline = null;
    private long boundPipelineHandle = VK_NULL_HANDLE;
    private long boundDescriptorSet = VK_NULL_HANDLE;
    private boolean indexBound = false;
    // reusable single-element buffers to avoid stackPush() in the hot draw path
    private final java.nio.LongBuffer vbHandle = org.lwjgl.system.MemoryUtil.memAllocLong(1);
    private final java.nio.LongBuffer vbOffset = org.lwjgl.system.MemoryUtil.memAllocLong(1);
    private final java.nio.LongBuffer descSet  = org.lwjgl.system.MemoryUtil.memAllocLong(1);
    private final java.nio.IntBuffer dynOffset = org.lwjgl.system.MemoryUtil.memAllocInt(1);
    private final java.nio.ByteBuffer pcBuf     = org.lwjgl.system.MemoryUtil.memAlloc(128);
    // chunk batches bind two bindings at once: the arena block and the per-instance origins
    private final java.nio.LongBuffer chunkVb    = org.lwjgl.system.MemoryUtil.memAllocLong(2);
    private final java.nio.LongBuffer chunkVbOff = org.lwjgl.system.MemoryUtil.memAllocLong(2);

    /** Per-pass GPU timings from timestamp queries; see the F3 overlay. */
    public GpuProfiler profiler;

    private boolean vsync;
    private volatile boolean resizeRequested = false;

    // ── frame phase ──
    private boolean renderingStarted = false;
    private boolean pendingTransfers = false;
    private long boundVertexBuffer = VK_NULL_HANDLE;
    private float clearR, clearG, clearB;

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
        chunkArena = new VertexArena(ctx, Pipelines.CHUNK_VERTEX_STRIDE);
        profiler = new GpuProfiler(ctx);
        for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++) {
            streaming[i] = new StreamingBuffer(ctx, deleter, 16L * 1024 * 1024);
            staging[i] = new StagingRing(ctx, deleter, 8L * 1024 * 1024);
        }
        createDrawBuffers(INITIAL_DRAWS);

        // 1x1 white texture for untextured draws
        ByteBuffer white = org.lwjgl.system.MemoryUtil.memAlloc(4);
        white.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
        whiteTexture = new VkTexture(ctx, frames, 1, 1, white, false);
        descriptors.allocateForTexture(whiteTexture);
        org.lwjgl.system.MemoryUtil.memFree(white);

        System.out.println("[vk] chunk draws: "
            + (indirectDraws() ? "multi-draw indirect" : "one draw per section")
            + ", vertex " + Pipelines.CHUNK_VERTEX_STRIDE + "B");
    }

    private boolean indirectDraws() {
        return ctx.multiDrawIndirect && ctx.drawIndirectFirstInstance;
    }

    // ── texture creation helpers ──
    public VkTexture createTexture(int w, int h, ByteBuffer rgba, boolean linear) {
        VkTexture t = new VkTexture(ctx, frames, w, h, rgba, linear);
        descriptors.allocateForTexture(t);
        return t;
    }

    /** Terrain atlas as a mipmapped 2D array, one layer per tile. */
    public VkTexture createTileArray(ByteBuffer atlas, int atlasW, int atlasH, int tileW, int tileH) {
        VkTexture t = VkTexture.atlasArray(ctx, frames, atlas, atlasW, atlasH, tileW, tileH);
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

        if (!frames.beginCommands()) {
            recreateSwapchain();
            skipFrame = true;
            return false;
        }
        skipFrame = false;
        // The frame opens in a transfer phase: chunk meshes are copied from staging into the
        // arena here, which is illegal once a render pass has begun. Rendering therefore
        // starts lazily, at the first draw — see ensureRendering.
        renderingStarted = false;
        pendingTransfers = false;
        clearR = r; clearG = g; clearB = b;

        int frame = frames.frameIndex();
        profiler.beginFrame(frames.cmd(), frame);
        streaming[frame].beginFrame();
        staging[frame].beginFrame();
        descriptors.beginFrame(frame);
        growDrawBuffersIfNeeded();
        chunkDrawCount = 0;
        batchStart = 0;
        boundVertexBuffer = VK_NULL_HANDLE;
        fogOffset = descriptors.fogOffOffset();
        boundFogOffset = -1;
        currentTexture = null;
        currentPipeline = null;
        boundPipelineHandle = VK_NULL_HANDLE;
        boundDescriptorSet = VK_NULL_HANDLE;
        indexBound = false;
        matricesDirty = true;
        return true;
    }

    /**
     * Close the transfer phase and start rendering. Every draw path calls this first, so the
     * uploads issued earlier in the frame land where they are legal without any call site
     * having to know which phase it is in.
     */
    private void ensureRendering() {
        if (renderingStarted || skipFrame) return;
        VkCommandBuffer cmd = frames.cmd();
        if (pendingTransfers) {
            try (MemoryStack stack = stackPush()) {
                // arena writes this frame must be visible to the vertex fetch of the draws below
                VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_VERTEX_ATTRIBUTE_READ_BIT);
                vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_INPUT_BIT, 0, barrier, null, null);
            }
        }
        frames.beginRendering(clearR, clearG, clearB);
        renderingStarted = true;
        setViewportScissor();
        profiler.begin(cmd, frames.frameIndex(), GpuProfiler.Zone.FRAME);
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

    /** Bracket a pass with GPU timestamps. Zones must not nest apart from inside FRAME. */
    public void zoneBegin(GpuProfiler.Zone z) {
        if (skipFrame) return;
        ensureRendering();
        profiler.begin(frames.cmd(), frames.frameIndex(), z);
    }

    public void zoneEnd(GpuProfiler.Zone z) {
        if (skipFrame || !renderingStarted) return;
        profiler.end(frames.cmd(), frames.frameIndex(), z);
    }

    public void endFrame() {
        if (skipFrame) { return; }
        ensureRendering();
        profiler.end(frames.cmd(), frames.frameIndex(), GpuProfiler.Zone.FRAME);
        if (!frames.end()) recreateSwapchain();
        deleter.tick();
    }

    private void recreateSwapchain() {
        int[] fbw = new int[1], fbh = new int[1];
        org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window, fbw, fbh);
        if (fbw[0] == 0 || fbh[0] == 0) return;
        swapchain.recreate(fbw[0], fbh[0]);
        frames.onSwapchainRecreated();
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
        fogOffset = descriptors.fogOffset(frames.frameIndex(), r, g, b, start, end, brightness);
    }

    public void disableFog() { fogOffset = descriptors.fogOffOffset(); }

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
        long set = tex.descSets[frames.frameIndex()];
        // Fog is a dynamic offset into the frame's slot ring rather than a second descriptor
        // set per texture, so switching it mid-frame rebinds an offset, not a parallel set.
        if (set == boundDescriptorSet && fogOffset == boundFogOffset) return;
        descSet.put(0, set);
        dynOffset.put(0, fogOffset);
        vkCmdBindDescriptorSets(frames.cmd(), VK_PIPELINE_BIND_POINT_GRAPHICS,
            pipelines.pipelineLayout, 0, descSet, dynOffset);
        boundDescriptorSet = set;
        boundFogOffset = fogOffset;
    }

    private void bindQuadIndexIfNeeded() {
        if (indexBound) return;
        vkCmdBindIndexBuffer(frames.cmd(), quadIndex.buffer.buffer, 0, VK_INDEX_TYPE_UINT32);
        indexBound = true;
    }

    /** Stream interleaved triangle/quad geometry from the Tesselator (vertexCount = quad-corners). */
    public void drawStreamQuads(float[] data, int floatCount, int vertexCount) {
        if (skipFrame || vertexCount == 0) return;
        ensureRendering();
        VkCommandBuffer cmd = frames.cmd();
        StreamingBuffer sb = streaming[frames.frameIndex()];
        long byteOffset = sb.allocate((long) floatCount * 4);
        sb.buffer().upload2(data, byteOffset, floatCount);

        bindPipelineIfNeeded();
        pushConstantsIfNeeded();
        bindDescriptor();
        vbHandle.put(0, sb.buffer().buffer); vbOffset.put(0, byteOffset);
        vkCmdBindVertexBuffers(cmd, 0, vbHandle, vbOffset);
        boundVertexBuffer = VK_NULL_HANDLE;
        bindQuadIndexIfNeeded();
        vkCmdDrawIndexed(cmd, vertexCount / 4 * 6, 1, 0, 0, 0);
    }

    /** Stream line geometry (vertexCount = vertices, drawn as LINE_LIST). */
    public void drawStreamLines(float[] data, int floatCount, int vertexCount) {
        if (skipFrame || vertexCount == 0) return;
        ensureRendering();
        VkCommandBuffer cmd = frames.cmd();
        StreamingBuffer sb = streaming[frames.frameIndex()];
        long byteOffset = sb.allocate((long) floatCount * 4);
        sb.buffer().upload2(data, byteOffset, floatCount);

        bindPipelineIfNeeded();
        pushConstantsIfNeeded();
        bindDescriptor();
        vbHandle.put(0, sb.buffer().buffer); vbOffset.put(0, byteOffset);
        vkCmdBindVertexBuffers(cmd, 0, vbHandle, vbOffset);
        boundVertexBuffer = VK_NULL_HANDLE;
        vkCmdDraw(cmd, vertexCount, 1, 0, 0);
    }


    // ── chunk meshes: arena storage, staged uploads, batched draws ──

    private static final int INITIAL_DRAWS = 8192;
    private static final int INDIRECT_STRIDE = 20; // sizeof(VkDrawIndexedIndirectCommand)

    private final VkBuf[] instanceBuf = new VkBuf[FrameSync.FRAMES_IN_FLIGHT];
    private final VkBuf[] indirectBuf = new VkBuf[FrameSync.FRAMES_IN_FLIGHT];
    private int drawCapacity;
    private int wantedCapacity = INITIAL_DRAWS;

    private int chunkDrawCount;
    private int batchStart;
    private int[] drawBlock = new int[INITIAL_DRAWS];
    private int[] drawIndexCount = new int[INITIAL_DRAWS];
    private int[] drawFirstVertex = new int[INITIAL_DRAWS];

    private void createDrawBuffers(int capacity) {
        for (int i = 0; i < FrameSync.FRAMES_IN_FLIGHT; i++) {
            if (instanceBuf[i] != null) { final VkBuf o = instanceBuf[i]; deleter.enqueue(o::free); }
            if (indirectBuf[i] != null) { final VkBuf o = indirectBuf[i]; deleter.enqueue(o::free); }
            instanceBuf[i] = new VkBuf(ctx, (long) capacity * Pipelines.CHUNK_INSTANCE_STRIDE,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            indirectBuf[i] = new VkBuf(ctx, (long) capacity * INDIRECT_STRIDE,
                VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT);
        }
        drawBlock = new int[capacity];
        drawIndexCount = new int[capacity];
        drawFirstVertex = new int[capacity];
        drawCapacity = capacity;
    }

    /**
     * Grow the per-frame draw buffers between frames rather than during one: resizing
     * mid-frame would free buffers the frame has already recorded binds against.
     */
    private void growDrawBuffersIfNeeded() {
        if (wantedCapacity <= drawCapacity) return;
        int cap = drawCapacity;
        while (cap < wantedCapacity) cap *= 2;
        createDrawBuffers(cap);
    }

    /** Bytes of staging still available to chunk uploads this frame. */
    public long stagingRemaining() {
        return skipFrame ? 0 : staging[frames.frameIndex()].remaining();
    }

    /** Reserve arena space for a chunk section mesh. */
    public VertexArena.Slice allocChunkMesh(long bytes) {
        return chunkArena.allocate(bytes);
    }

    public void freeChunkMesh(VertexArena.Slice slice) {
        chunkArena.free(slice);
    }

    /**
     * Copy a finished mesh into its arena slice through this frame's staging ring. Must be
     * issued before the first draw of the frame, while the command buffer is still in its
     * transfer phase.
     */
    public void uploadChunkMesh(int[] data, int intCount, VertexArena.Slice dst) {
        if (skipFrame || dst == null || intCount == 0) return;
        if (renderingStarted)
            throw new IllegalStateException("chunk uploads must be issued before the frame's first draw");
        long bytes = (long) intCount * 4;
        StagingRing ring = staging[frames.frameIndex()];
        long srcOffset = ring.allocate(bytes);
        if (srcOffset < 0) return;

        ByteBuffer b = org.lwjgl.system.MemoryUtil.memByteBuffer(
            ring.buffer().mappedPtr + srcOffset, (int) bytes);
        b.asIntBuffer().put(data, 0, intCount);

        try (MemoryStack stack = stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                .srcOffset(srcOffset).dstOffset(dst.offset).size(bytes);
            vkCmdCopyBuffer(frames.cmd(), ring.buffer().buffer, dst.buffer.buffer, region);
        }
        pendingTransfers = true;
    }

    /** Start collecting chunk draws. They are emitted in the order they are added. */
    public void beginChunkBatch(Pipelines.Pipeline pipeline) {
        if (skipFrame) return;
        ensureRendering();
        setPipeline(pipeline);
        batchStart = chunkDrawCount;
    }

    public void addChunkDraw(VertexArena.Slice slice, int vertexCount, float ox, float oy, float oz) {
        if (skipFrame || slice == null || vertexCount == 0) return;
        if (chunkDrawCount >= drawCapacity) {
            // remember for next frame: dropping the overflow costs one frame of geometry,
            // reallocating buffers already bound this frame would cost correctness
            wantedCapacity = Math.max(wantedCapacity, drawCapacity * 2);
            return;
        }
        int i = chunkDrawCount++;
        int frame = frames.frameIndex();
        drawBlock[i] = slice.blockIndex;
        drawIndexCount[i] = vertexCount / 4 * 6;
        drawFirstVertex[i] = slice.firstVertex;

        org.lwjgl.system.MemoryUtil.memByteBuffer(
                instanceBuf[frame].mappedPtr + (long) i * Pipelines.CHUNK_INSTANCE_STRIDE,
                Pipelines.CHUNK_INSTANCE_STRIDE)
            .putFloat(ox).putFloat(oy).putFloat(oz);

        org.lwjgl.system.MemoryUtil.memByteBuffer(
                indirectBuf[frame].mappedPtr + (long) i * INDIRECT_STRIDE, INDIRECT_STRIDE)
            .putInt(drawIndexCount[i])   // indexCount
            .putInt(1)                   // instanceCount
            .putInt(0)                   // firstIndex
            .putInt(slice.firstVertex)   // vertexOffset
            .putInt(i);                  // firstInstance: this draw's section origin
    }

    /**
     * Submit the collected draws. Consecutive draws living in the same arena block share one
     * bind and, where the device allows it, one multi-draw. Runs keep the order they were
     * added in, because the water pass depends on drawing back to front.
     */
    public void flushChunkBatch() {
        if (skipFrame || chunkDrawCount == batchStart) return;
        VkCommandBuffer cmd = frames.cmd();
        int frame = frames.frameIndex();
        boolean indirect = indirectDraws();

        bindPipelineIfNeeded();
        pushConstantsIfNeeded();
        bindDescriptor();
        bindQuadIndexIfNeeded();

        int runStart = batchStart;
        while (runStart < chunkDrawCount) {
            int block = drawBlock[runStart];
            int runEnd = runStart + 1;
            while (runEnd < chunkDrawCount && drawBlock[runEnd] == block) runEnd++;

            long buffer = chunkArena.block(block).buffer;
            if (buffer != boundVertexBuffer) {
                chunkVb.put(0, buffer).put(1, instanceBuf[frame].buffer);
                chunkVbOff.put(0, 0L).put(1, 0L);
                vkCmdBindVertexBuffers(cmd, 0, chunkVb, chunkVbOff);
                boundVertexBuffer = buffer;
            }

            if (indirect) {
                vkCmdDrawIndexedIndirect(cmd, indirectBuf[frame].buffer,
                    (long) runStart * INDIRECT_STRIDE, runEnd - runStart, INDIRECT_STRIDE);
            } else {
                for (int i = runStart; i < runEnd; i++)
                    vkCmdDrawIndexed(cmd, drawIndexCount[i], 1, 0, drawFirstVertex[i], i);
            }
            runStart = runEnd;
        }
        batchStart = chunkDrawCount;
    }

    /** Chunk section draws submitted this frame, for the debug overlay. */
    public int chunkDrawCount() { return chunkDrawCount; }

    public void destroy() {
        vkDeviceWaitIdle(ctx.device);
        deleter.flushAll();
        for (StreamingBuffer sb : streaming) sb.free();
        for (StagingRing sr : staging) sr.free();
        for (VkBuf b : instanceBuf) if (b != null) b.free();
        for (VkBuf b : indirectBuf) if (b != null) b.free();
        chunkArena.destroy();
        quadIndex.free();
        profiler.destroy();
        whiteTexture.free();
        pipelines.destroy();
        descriptors.destroy();
        frames.destroy();
        swapchain.destroy();
        ctx.destroy();

        org.lwjgl.system.MemoryUtil.memFree(vbHandle);
        org.lwjgl.system.MemoryUtil.memFree(vbOffset);
        org.lwjgl.system.MemoryUtil.memFree(descSet);
        org.lwjgl.system.MemoryUtil.memFree(dynOffset);
        org.lwjgl.system.MemoryUtil.memFree(pcBuf);
        org.lwjgl.system.MemoryUtil.memFree(chunkVb);
        org.lwjgl.system.MemoryUtil.memFree(chunkVbOff);
    }
}

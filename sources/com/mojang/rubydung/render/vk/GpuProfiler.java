package com.mojang.rubydung.render.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Per-pass GPU timing from timestamp queries.
 *
 * CPU-side frame time says nothing about which pass costs what once the queue runs ahead of
 * the game loop, so each pass brackets itself with two timestamps. Results are read back for
 * the frame slot about to be reused: its fence has just been waited on, so the values are
 * already there and nothing stalls to fetch them. Timings are therefore
 * {@code FRAMES_IN_FLIGHT} frames old, which is what a profiler that does not perturb the
 * thing it measures looks like.
 */
public final class GpuProfiler {
    public enum Zone {
        FRAME("frame"),
        CHUNKS_OPAQUE("chunks"),
        CHUNKS_WATER("water"),
        ENTITIES("entities"),
        UI("ui");

        public final String label;
        Zone(String label) { this.label = label; }
    }

    private static final Zone[] ZONES = Zone.values();
    private static final int QUERIES_PER_FRAME = ZONES.length * 2;

    private final VkContext ctx;
    private final boolean supported;
    private long pool = VK_NULL_HANDLE;

    /** Milliseconds per zone, smoothed, from the most recent frame that has reported back. */
    private final double[] millis = new double[ZONES.length];
    // which zones were actually written this frame; a zone that did not run must not report
    // the timings of whichever pass happened to reuse its query slot
    private final boolean[][] written = new boolean[FrameSync.FRAMES_IN_FLIGHT][ZONES.length];

    public GpuProfiler(VkContext ctx) {
        this.ctx = ctx;
        this.supported = ctx.timestampValidBits > 0 && ctx.timestampPeriod > 0;
        if (!supported) {
            System.out.println("[vk] timestamp queries unsupported on this queue; GPU profiling disabled");
            return;
        }
        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo ci = VkQueryPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                .queryType(VK_QUERY_TYPE_TIMESTAMP)
                .queryCount(QUERIES_PER_FRAME * FrameSync.FRAMES_IN_FLIGHT);
            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateQueryPool(ctx.device, ci, null, pPool) != VK_SUCCESS)
                throw new RuntimeException("vkCreateQueryPool failed");
            pool = pPool.get(0);
        }
    }

    public boolean supported() { return supported; }

    /** Milliseconds the given zone took, or 0 when it did not run / is unsupported. */
    public double millis(Zone z) { return millis[z.ordinal()]; }

    /**
     * Collect the results still sitting in this frame slot, then clear it for reuse. Must be
     * recorded outside a render pass, which is why it runs in the frame's transfer phase.
     */
    public void beginFrame(VkCommandBuffer cmd, int frame) {
        if (!supported) return;
        readBack(frame);
        vkCmdResetQueryPool(cmd, pool, frame * QUERIES_PER_FRAME, QUERIES_PER_FRAME);
        java.util.Arrays.fill(written[frame], false);
    }

    public void begin(VkCommandBuffer cmd, int frame, Zone z) {
        if (!supported) return;
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, pool, queryIndex(frame, z, false));
    }

    public void end(VkCommandBuffer cmd, int frame, Zone z) {
        if (!supported) return;
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, pool, queryIndex(frame, z, true));
        written[frame][z.ordinal()] = true;
    }

    private int queryIndex(int frame, Zone z, boolean end) {
        return frame * QUERIES_PER_FRAME + z.ordinal() * 2 + (end ? 1 : 0);
    }

    private void readBack(int frame) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer results = stack.mallocLong(QUERIES_PER_FRAME);
            int res = vkGetQueryPoolResults(ctx.device, pool, frame * QUERIES_PER_FRAME, QUERIES_PER_FRAME,
                results, Long.BYTES, VK_QUERY_RESULT_64_BIT);
            if (res != VK_SUCCESS) return;   // first frames: nothing recorded yet
            // Timestamps are only valid in the low timestampValidBits bits; masking off the
            // rest is what stops a device with, say, 36 valid bits from reporting garbage.
            long mask = ctx.timestampValidBits >= 64 ? -1L : (1L << ctx.timestampValidBits) - 1;
            for (int i = 0; i < ZONES.length; i++) {
                if (!written[frame][i]) { millis[i] = 0; continue; }
                long begin = results.get(i * 2) & mask;
                long end = results.get(i * 2 + 1) & mask;
                if (end < begin) continue;   // counter wrapped, drop this sample
                double ms = (end - begin) * ctx.timestampPeriod / 1_000_000.0;
                // light smoothing: raw per-frame GPU times jitter too much to read
                millis[i] = millis[i] == 0 ? ms : millis[i] * 0.8 + ms * 0.2;
            }
        }
    }

    public void destroy() {
        if (pool != VK_NULL_HANDLE) vkDestroyQueryPool(ctx.device, pool, null);
    }
}

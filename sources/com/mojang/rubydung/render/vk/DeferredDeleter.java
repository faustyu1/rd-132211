package com.mojang.rubydung.render.vk;

import java.util.ArrayDeque;

/** Defers resource frees until the GPU is guaranteed to no longer reference them. */
public class DeferredDeleter {
    private record Entry(long frame, Runnable action) {}

    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private long currentFrame = 0;
    private final int graceFrames;

    public DeferredDeleter(int framesInFlight) {
        // framesInFlight alone is one frame too short. An action enqueued during frame N would
        // run from tick() at the END of frame N+2, but frame N shares its fence slot with frame
        // N+3, and that fence is only waited on at the START of frame N+3 — so at the end of
        // frame N+2 the GPU may still be executing frame N. That is harmless for chunk meshes
        // (uploadPending swaps a buffer the current frame no longer references) but not for
        // StreamingBuffer.growTo(), which discards a buffer the current frame has already
        // recorded draw commands against. One extra frame pushes the free past frame N+3's
        // fence wait, at which point frame N is provably complete.
        this.graceFrames = framesInFlight + 1;
    }

    /** Schedule an action to run once the current frame's resources are safe to delete. */
    public void enqueue(Runnable action) {
        queue.addLast(new Entry(currentFrame, action));
    }

    /** Advance the frame counter and run any actions whose grace period has elapsed. */
    public void tick() {
        currentFrame++;
        while (!queue.isEmpty() && currentFrame - queue.peekFirst().frame() >= graceFrames) {
            queue.pollFirst().action().run();
        }
    }

    /** Run every pending action immediately (after a deviceWaitIdle). */
    public void flushAll() {
        while (!queue.isEmpty()) queue.pollFirst().action().run();
    }
}

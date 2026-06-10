package com.mojang.rubydung.render.vk;

import java.util.ArrayDeque;

/** Defers resource frees until the GPU is guaranteed to no longer reference them. */
public class DeferredDeleter {
    private record Entry(long frame, Runnable action) {}

    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private long currentFrame = 0;
    private final int framesInFlight;

    public DeferredDeleter(int framesInFlight) {
        this.framesInFlight = framesInFlight;
    }

    /** Schedule an action to run once the current frame's resources are safe to delete. */
    public void enqueue(Runnable action) {
        queue.addLast(new Entry(currentFrame, action));
    }

    /** Advance the frame counter and run any actions whose grace period has elapsed. */
    public void tick() {
        currentFrame++;
        while (!queue.isEmpty() && currentFrame - queue.peekFirst().frame() >= framesInFlight) {
            queue.pollFirst().action().run();
        }
    }

    /** Run every pending action immediately (after a deviceWaitIdle). */
    public void flushAll() {
        while (!queue.isEmpty()) queue.pollFirst().action().run();
    }
}

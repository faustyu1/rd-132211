package com.mojang.rubydung.level;

/**
 * How the background chunk work is allowed to use the machine.
 *
 * Terrain generation and mesh building each used to size their own pool at "all cores but
 * one", which together oversubscribes every core and leaves the render thread competing
 * for a slot — the work gets done, but it arrives as stutter. Both pools are budgeted here
 * instead, out of the cores left after the render thread and the OS, and their threads run
 * below normal priority so a frame always wins against a chunk.
 */
final class Threads {
    private Threads() {}

    private static final int WORKERS = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);

    /** Mesh building is the half that has to keep up with the camera, so it gets the larger share. */
    static final int MESH = Math.max(1, (WORKERS * 3 + 4) / 5);
    static final int GEN = Math.max(1, WORKERS - MESH);

    static java.util.concurrent.ThreadFactory factory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 2);
            return t;
        };
    }
}

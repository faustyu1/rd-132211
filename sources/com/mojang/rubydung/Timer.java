package com.mojang.rubydung;

public class Timer {
    /** Rate the game loop actually runs at; durations elsewhere are expressed against it. */
    public static final int TICKS_PER_SECOND = 60;

    private static final long NS_PER_SECOND = 1_000_000_000L;
    private static final int MAX_TICKS_PER_UPDATE = 100;

    private final float ticksPerSecond;
    public int ticks;
    public float a;
    public float timeScale = 1.0f;
    public float passedTime = 0.0f;
    private long lastTime = System.nanoTime();

    public Timer(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
    }

    /**
     * Ticks in a wall-clock duration, so gameplay code can state timings in seconds
     * instead of hard-coding tick counts that silently change meaning with the tick rate.
     */
    public static int seconds(double s) {
        return Math.max(1, (int) Math.round(s * TICKS_PER_SECOND));
    }

    public void advanceTime() {
        long now = System.nanoTime();
        long passedNs = Math.clamp(now - lastTime, 0L, NS_PER_SECOND);
        lastTime = now;

        passedTime += (passedNs * timeScale * ticksPerSecond) / 1.0e9f;
        ticks = Math.min((int) passedTime, MAX_TICKS_PER_UPDATE);
        passedTime -= ticks;
        a = passedTime;
    }
}

package com.mojang.rubydung;

public class Timer {
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

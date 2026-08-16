package com.mojang.rubydung.net;

import com.mojang.rubydung.level.Tile;

public final class Packet {
    public static final byte PLAYER_POS   = 0x01;
    public static final byte SET_TILE     = 0x02;
    public static final byte WELCOME      = 0x04;
    public static final byte PING         = 0x05;
    public static final byte PLAYER_NAME  = 0x06;
    public static final byte CHAT         = 0x07;
    public static final byte CHUNK        = 0x08;
    /** A peer is gone. Without it every client keeps rendering a player who quit. */
    public static final byte PLAYER_LEAVE = 0x09;
    /** Client tells the host how far it renders, so edited chunks are pushed that far. */
    public static final byte CLIENT_INFO  = 0x0A;
    /**
     * A fluid cell the host's simulation moved. Separate from SET_TILE because flow ids are
     * not placeable: only the host may send this, and only water ids are accepted off it.
     */
    public static final byte SET_FLUID    = 0x0B;

    /**
     * Bumped whenever the meaning of a packet changes. WELCOME carries it so a mismatched
     * build is rejected with a clear message instead of misreading the stream.
     */
    public static final int PROTOCOL_VERSION = 3;

    /** Longest chat message accepted off the wire; the input field caps at 100 characters. */
    public static final int MAX_CHAT_CHARS = 120;

    /**
     * Whether a SET_TILE id off the wire may be applied: AIR (a break) or a real block.
     * The flowing-water ids belong to the fluid simulation and items are not blocks at all,
     * so neither may arrive from a peer; anything else would render as fallback stone.
     */
    public static boolean isPlaceable(int tile) {
        return tile == Tile.AIR || Tile.isKnownBlock((byte) tile);
    }

    /** Whether a SET_FLUID id off the wire may be applied: water at any flow level, or AIR. */
    public static boolean isFluid(int tile) {
        return tile == Tile.AIR || Tile.isWater((byte) tile);
    }

    /** A position is usable if it is finite and inside the world the generator can produce. */
    public static boolean isSanePosition(float x, float y, float z, float yRot, float xRot) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
         || !Float.isFinite(yRot) || !Float.isFinite(xRot)) return false;
        return Math.abs(x) <= 30_000_000f && Math.abs(z) <= 30_000_000f && y >= -128f && y <= 1024f;
    }

    private Packet() {}
}

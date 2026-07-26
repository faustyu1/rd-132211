package com.mojang.rubydung.net;

import com.mojang.rubydung.level.Tile;

public final class Packet {
    public static final byte PLAYER_POS  = 0x01;
    public static final byte SET_TILE    = 0x02;
    public static final byte WELCOME     = 0x04;
    public static final byte PING        = 0x05;
    public static final byte PLAYER_NAME = 0x06;
    public static final byte CHAT        = 0x07;
    public static final byte CHUNK       = 0x08;

    /**
     * Bumped whenever the meaning of a packet changes. WELCOME carries it so a mismatched
     * build is rejected with a clear message instead of misreading the stream.
     */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Whether a SET_TILE id off the wire may be applied: AIR (a break) or a real block.
     * The flowing-water ids belong to the fluid simulation and items are not blocks at all,
     * so neither may arrive from a peer; anything else would render as fallback stone.
     */
    public static boolean isPlaceable(int tile) {
        return tile == Tile.AIR || Tile.isKnownBlock((byte) tile);
    }

    private Packet() {}
}

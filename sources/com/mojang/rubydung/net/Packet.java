package com.mojang.rubydung.net;

import com.mojang.rubydung.level.Tile;

public final class Packet {
    public static final byte PLAYER_POS  = 0x01;
    public static final byte SET_TILE    = 0x02;
    public static final byte WELCOME     = 0x04;
    public static final byte PING        = 0x05;
    public static final byte PLAYER_NAME = 0x06;
    public static final byte CHAT        = 0x07;

    /**
     * Whether a SET_TILE id off the wire may be applied. Only AIR..SNOW are real blocks;
     * the flowing-water ids above them belong to the fluid simulation and would otherwise
     * let a peer inject states the host never produces, and anything outside the range at
     * all renders as fallback stone.
     */
    public static boolean isPlaceable(int tile) {
        return tile >= Tile.AIR && tile <= Tile.SNOW;
    }

    private Packet() {}
}

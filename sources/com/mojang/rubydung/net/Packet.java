package com.mojang.rubydung.net;

public final class Packet {
    public static final byte PLAYER_POS  = 0x01;
    public static final byte SET_TILE    = 0x02;
    public static final byte WELCOME     = 0x04;
    public static final byte PING        = 0x05;
    public static final byte PLAYER_NAME = 0x06;

    private Packet() {}
}

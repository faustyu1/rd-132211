package com.mojang.rubydung.net;

import com.github.luben.zstd.Zstd;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Utility to build outgoing packet byte arrays. */
public final class PacketWriter {

    /** Same level the region files use: chunk payloads are the same kind of data. */
    private static final int CHUNK_COMPRESSION = 3;

    /**
     * Builds one packet. A ByteArrayOutputStream never actually throws, so the checked
     * IOException every writer would otherwise have to declare is swallowed here once.
     */
    private static byte[] build(int size, Body body) {
        var bos = new ByteArrayOutputStream(size);
        try (var dos = new DataOutputStream(bos)) {
            body.write(dos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    private interface Body { void write(DataOutputStream dos) throws IOException; }

    public static byte[] playerPos(int connId, float x, float y, float z, float yRot, float xRot) {
        return build(26, dos -> {
            dos.writeByte(Packet.PLAYER_POS);
            dos.writeInt(connId);
            dos.writeFloat(x); dos.writeFloat(y); dos.writeFloat(z);
            dos.writeFloat(yRot); dos.writeFloat(xRot);
        });
    }

    public static byte[] setTile(int x, int y, int z, int type) {
        return build(17, dos -> {
            dos.writeByte(Packet.SET_TILE);
            dos.writeInt(x); dos.writeInt(y); dos.writeInt(z); dos.writeInt(type);
        });
    }

    /**
     * The cells the host's fluid simulation moved this tick. Clients do not simulate water
     * themselves — two independent simulations drift apart within seconds — so the host is
     * the only authority and these are what keep the two worlds showing the same river.
     *
     * Batched because the simulation moves up to 64 cells per fluid tick: one packet each
     * would be a thousand tiny packets a second per client, enough to fill the receive queue
     * of a client that drains it once per frame.
     */
    public static byte[] fluidBatch(java.util.List<int[]> cells, int from, int count) {
        return build(5 + count * 13, dos -> {
            dos.writeByte(Packet.SET_FLUID);
            dos.writeInt(count);
            for (int i = from; i < from + count; i++) {
                int[] c = cells.get(i);
                dos.writeInt(c[0]);
                dos.writeShort(c[1]);   // world height is 128; a short is room to spare
                dos.writeInt(c[2]);
                dos.writeByte(c[3]);
            }
        });
    }

    /**
     * The handshake carries the world seed, not the world: terrain is a pure function of the
     * seed, so both sides can generate it independently and only the blocks somebody edited
     * ever have to travel (see {@link #chunk}). The old form sent every loaded chunk in one
     * packet — tens of megabytes at a normal render distance, and still not enough, because
     * the client generated its own terrain outside that snapshot.
     */
    public static byte[] welcome(int assignedId, long seed) {
        return build(17, dos -> {
            dos.writeByte(Packet.WELCOME);
            dos.writeInt(Packet.PROTOCOL_VERSION);
            dos.writeInt(assignedId);
            dos.writeLong(seed);
        });
    }

    /**
     * One edited chunk: everything the seed cannot reproduce. The 32 KiB block array is
     * zstd-compressed — it is mostly long runs of one id, so it leaves at a fraction of the
     * size, and a joining client is streamed several of these every tick.
     */
    public static byte[] chunk(int cx, int cz, byte[] blocks) {
        byte[] comp = Zstd.compress(blocks, CHUNK_COMPRESSION);
        return build(13 + comp.length, dos -> {
            dos.writeByte(Packet.CHUNK);
            dos.writeInt(cx);
            dos.writeInt(cz);
            dos.writeInt(blocks.length);   // decompressed size, so the reader can size its buffer
            dos.write(comp);
        });
    }

    public static byte[] chat(String message) {
        byte[] mb = message.getBytes(StandardCharsets.UTF_8);
        return build(3 + mb.length, dos -> {
            dos.writeByte(Packet.CHAT);
            dos.writeShort(mb.length);
            dos.write(mb);
        });
    }

    public static byte[] ping() {
        return new byte[]{Packet.PING};
    }

    public static byte[] playerLeave(int connId) {
        return build(5, dos -> {
            dos.writeByte(Packet.PLAYER_LEAVE);
            dos.writeInt(connId);
        });
    }

    /** Client's render distance in chunks, so the host knows how far its edits have to travel. */
    public static byte[] clientInfo(int viewDistChunks) {
        return build(5, dos -> {
            dos.writeByte(Packet.CLIENT_INFO);
            dos.writeInt(viewDistChunks);
        });
    }

    public static byte[] playerName(int connId, String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        return build(7 + nameBytes.length, dos -> {
            dos.writeByte(Packet.PLAYER_NAME);
            dos.writeInt(connId);
            dos.writeShort(nameBytes.length);
            dos.write(nameBytes);
        });
    }
}

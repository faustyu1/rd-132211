package com.mojang.rubydung.net;

import java.io.*;

/** Utility to build outgoing packet byte arrays. */
public final class PacketWriter {

    public static byte[] playerPos(int connId, float x, float y, float z, float yRot, float xRot) {
        try {
            var bos = new ByteArrayOutputStream(26);
            var dos = new DataOutputStream(bos);
            dos.writeByte(Packet.PLAYER_POS);
            dos.writeInt(connId);
            dos.writeFloat(x); dos.writeFloat(y); dos.writeFloat(z);
            dos.writeFloat(yRot); dos.writeFloat(xRot);
            return bos.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public static byte[] setTile(int x, int y, int z, int type) {
        try {
            var bos = new ByteArrayOutputStream(17);
            var dos = new DataOutputStream(bos);
            dos.writeByte(Packet.SET_TILE);
            dos.writeInt(x); dos.writeInt(y); dos.writeInt(z); dos.writeInt(type);
            return bos.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public static byte[] welcome(int assignedId, byte[] blocks) {
        try {
            var bos = new ByteArrayOutputStream(5 + blocks.length);
            var dos = new DataOutputStream(bos);
            dos.writeByte(Packet.WELCOME);
            dos.writeInt(assignedId);
            dos.write(blocks);
            return bos.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public static byte[] chat(String message) {
        try {
            byte[] mb = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var bos = new ByteArrayOutputStream(3 + mb.length);
            var dos = new DataOutputStream(bos);
            dos.writeByte(Packet.CHAT);
            dos.writeShort(mb.length);
            dos.write(mb);
            return bos.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    public static byte[] ping() {
        return new byte[]{Packet.PING};
    }

    public static byte[] playerName(int connId, String name) {
        try {
            byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var bos = new ByteArrayOutputStream(5 + nameBytes.length);
            var dos = new DataOutputStream(bos);
            dos.writeByte(Packet.PLAYER_NAME);
            dos.writeInt(connId);
            dos.writeShort(nameBytes.length);
            dos.write(nameBytes);
            return bos.toByteArray();
        } catch (IOException e) { throw new RuntimeException(e); }
    }
}

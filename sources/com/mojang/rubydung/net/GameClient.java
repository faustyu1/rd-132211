package com.mojang.rubydung.net;

import com.mojang.rubydung.level.Level;
import java.io.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class GameClient {
    private final Connection conn;
    public  final int        localId;

    private final Level level;

    private final Map<Integer, float[]>  remotePlayers = new ConcurrentHashMap<>();
    private final Map<Integer, String>   remoteNames   = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingChat = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public GameClient(String host, int port, Level level) throws IOException {
        this.level = level;
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 5000);
        sock.setTcpNoDelay(true);

        // read WELCOME synchronously
        var bootstrapIn = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
        int len = bootstrapIn.readInt();
        byte[] welcomePkt = new byte[len];
        bootstrapIn.readFully(welcomePkt);

        var wis = new DataInputStream(new ByteArrayInputStream(welcomePkt));
        if (wis.readByte() != Packet.WELCOME) throw new IOException("Expected WELCOME");
        localId = wis.readInt();
        byte[] blocks = new byte[wis.available()];
        wis.readFully(blocks);
        level.setRawBlocks(blocks);

        conn = new Connection(localId, sock);
    }

    /** Send our name right after connecting. */
    public void sendName(String name) {
        conn.send(PacketWriter.playerName(localId, name));
    }

    /** Called every game tick from the main thread. */
    public void tick(float x, float y, float z, float yRot, float xRot) {
        conn.send(PacketWriter.playerPos(localId, x, y, z, yRot, xRot));
        byte[] pkt;
        while ((pkt = conn.poll()) != null) handlePacket(pkt);
    }

    private void handlePacket(byte[] pkt) {
        if (pkt.length == 0) return;
        byte type = pkt[0];
        try (var dis = new DataInputStream(new ByteArrayInputStream(pkt, 1, pkt.length - 1))) {
            switch (type) {
                case Packet.PLAYER_POS -> {
                    int id = dis.readInt();
                    float x = dis.readFloat(), y = dis.readFloat(), z = dis.readFloat();
                    float yr = dis.readFloat(), xr = dis.readFloat();
                    if (id != localId) remotePlayers.put(id, new float[]{x, y, z, yr, xr});
                }
                case Packet.SET_TILE -> {
                    int x = dis.readInt(), y = dis.readInt(), z = dis.readInt(), tile = dis.readInt();
                    level.setTile(x, y, z, tile);
                }
                case Packet.PLAYER_NAME -> {
                    int id = dis.readInt();
                    int nlen = dis.readShort() & 0xFFFF;
                    byte[] nb = new byte[nlen]; dis.readFully(nb);
                    remoteNames.put(id, new String(nb, java.nio.charset.StandardCharsets.UTF_8));
                }
                case Packet.CHAT -> {
                    int mlen = dis.readShort() & 0xFFFF;
                    byte[] mb = new byte[mlen]; dis.readFully(mb);
                    pendingChat.add(new String(mb, java.nio.charset.StandardCharsets.UTF_8));
                }
                case Packet.PING -> conn.send(PacketWriter.ping());
            }
        } catch (IOException ignored) {}
    }

    public void sendSetTile(int x, int y, int z, int type) {
        conn.send(PacketWriter.setTile(x, y, z, type));
    }

    public void sendChat(String message) {
        conn.send(PacketWriter.chat(message));
    }

    public String pollChat() { return pendingChat.poll(); }

    public Map<Integer, float[]> getRemotePlayers() { return remotePlayers; }
    public Map<Integer, String>  getRemoteNames()   { return remoteNames; }

    public boolean isAlive() { return conn.isAlive(); }
    public void stop()       { conn.close(); }
}

package com.mojang.rubydung.net;

import com.github.luben.zstd.Zstd;
import com.mojang.rubydung.level.Level;
import java.io.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Map;

public class GameClient {
    /**
     * Read timeout for the bootstrap phase only. It runs on the game thread, so a server
     * that accepts the socket and then goes silent must not freeze the window forever.
     * SO_TIMEOUT applies per read() call rather than to the whole transfer; it is cleared
     * before the reader thread takes over so gameplay reads can block indefinitely.
     */
    private static final int BOOTSTRAP_TIMEOUT_MS = 15_000;
    /** Silence from the host that means the link is gone. The host pings once a second. */
    private static final long TIMEOUT_MS = 30_000;
    /** Movement below this is not worth a packet; the host's pings keep the link proven. */
    private static final float POS_EPSILON = 0.002f, ROT_EPSILON = 0.05f;

    private final Connection conn;
    public  final int        localId;
    public  final long       worldSeed;

    // Assigned once the caller has built a Level for the seed we were handed. Packets that
    // arrive before that are simply queued in the Connection and drained on the first tick.
    private Level level;

    // last position actually sent, so a standing player does not spend a packet per tick
    private float sx, sy, sz, syr, sxr;
    private boolean posSent;

    private final Map<Integer, float[]>  remotePlayers = new ConcurrentHashMap<>();
    private final Map<Integer, String>   remoteNames   = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> pendingChat = new ConcurrentLinkedQueue<>();

    public GameClient(String host, int port) throws IOException {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress(host, port), 5000);
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(BOOTSTRAP_TIMEOUT_MS);

            // read WELCOME synchronously
            var bootstrapIn = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
            byte[] welcomePkt = Connection.readPacket(bootstrapIn, Connection.MAX_HANDSHAKE_BYTES);

            var wis = new DataInputStream(new ByteArrayInputStream(welcomePkt));
            if (wis.readByte() != Packet.WELCOME) throw new IOException("Expected WELCOME");
            int protocol = wis.readInt();
            if (protocol != Packet.PROTOCOL_VERSION)
                throw new IOException("Server speaks protocol " + protocol
                    + ", this client speaks " + Packet.PROTOCOL_VERSION);
            localId = wis.readInt();
            worldSeed = wis.readLong();

            sock.setSoTimeout(0);
            // hand the bootstrap stream over: a second buffered stream on this socket would
            // discard the packets it already read ahead while draining WELCOME
            conn = new Connection(localId, sock, bootstrapIn);
        } catch (IOException e) {
            try { sock.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    /** Give the client the world built from {@link #worldSeed}; until then packets just queue. */
    public void attachLevel(Level level) {
        this.level = level;
    }

    /** Send our name right after connecting. */
    public void sendName(String name) {
        conn.send(PacketWriter.playerName(localId, name));
    }

    /** Tell the host how far we draw, so its edited chunks are pushed that far out. */
    public void sendViewDistance(int chunks) {
        conn.send(PacketWriter.clientInfo(chunks));
    }

    /** Called every game tick from the main thread. */
    public void tick(float x, float y, float z, float yRot, float xRot) {
        // the sanity check guards the comparison too: every later abs() against a stored NaN
        // is false, which would silently stop this client from ever reporting again
        if (Packet.isSanePosition(x, y, z, yRot, xRot)
            && (!posSent
                || Math.abs(x - sx) > POS_EPSILON || Math.abs(y - sy) > POS_EPSILON
                || Math.abs(z - sz) > POS_EPSILON
                || Math.abs(yRot - syr) > ROT_EPSILON || Math.abs(xRot - sxr) > ROT_EPSILON)) {
            conn.send(PacketWriter.playerPos(localId, x, y, z, yRot, xRot));
            sx = x; sy = y; sz = z; syr = yRot; sxr = xRot;
            posSent = true;
        }
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
                    if (id != localId && Packet.isSanePosition(x, y, z, yr, xr))
                        remotePlayers.put(id, new float[]{x, y, z, yr, xr});
                }
                case Packet.PLAYER_LEAVE -> {
                    int id = dis.readInt();
                    remotePlayers.remove(id);
                    remoteNames.remove(id);
                }
                case Packet.CHUNK -> {
                    int cx = dis.readInt(), cz = dis.readInt();
                    int rawLen = dis.readInt();
                    if (rawLen < 1 || rawLen > 1 << 20) break;   // a chunk is 32 KiB; this is corruption
                    byte[] comp = dis.readAllBytes();
                    byte[] blocks = Zstd.decompress(comp, rawLen);
                    if (level != null) level.applyNetworkChunk(cx, cz, blocks);
                }
                case Packet.SET_TILE -> {
                    int x = dis.readInt(), y = dis.readInt(), z = dis.readInt(), tile = dis.readInt();
                    // a rogue host is no more trusted than a rogue peer
                    if (Packet.isPlaceable(tile) && level != null) level.setTile(x, y, z, tile);
                }
                case Packet.SET_FLUID -> {
                    int count = dis.readInt();
                    for (int i = 0; i < count; i++) {
                        int x = dis.readInt(), y = dis.readShort(), z = dis.readInt();
                        int tile = dis.readByte() & 0xFF;
                        if (Packet.isFluid(tile) && level != null) level.applyNetworkFluid(x, y, z, (byte) tile);
                    }
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
        // Exception, not IOException: a corrupt zstd frame throws from the decompressor, and
        // a malformed packet must cost one packet, not the game loop
        } catch (Exception ignored) {}
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

    /** Alive means the socket is open *and* the host has been heard from recently. */
    public boolean isAlive() { return conn.isAlive() && conn.idleMillis() <= TIMEOUT_MS; }
    public void stop()       { conn.close(); }
}

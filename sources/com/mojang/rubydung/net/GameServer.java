package com.mojang.rubydung.net;

import com.mojang.rubydung.level.Level;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs on the host. Accepts incoming TCP connections, distributes the world
 * snapshot on connect, then relays packets between all peers.
 */
public class GameServer {
    public static final int DEFAULT_PORT = 25565;

    private final Level                          level;
    private final ServerSocket                   serverSocket;
    private final Map<Integer, Connection>       clients      = new ConcurrentHashMap<>();
    private final Map<Integer, float[]>          clientPos    = new ConcurrentHashMap<>(); // id->[x,y,z,yr,xr]
    private final Map<Integer, String>           clientNames  = new ConcurrentHashMap<>();
    private final AtomicInteger                  nextId       = new AtomicInteger(1);
    private volatile boolean                     running      = true;
    public  final int                            port;
    // host's own data visible to game thread for rendering
    private volatile float hx, hy, hz, hyr, hxr;
    private String hostName = "Host";
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingChat = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public GameServer(Level level, int port) throws IOException {
        this.level        = level;
        this.port         = port;
        this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        startAcceptor();
    }

    private void startAcceptor() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket sock = serverSocket.accept();
                    sock.setTcpNoDelay(true);
                    int id = nextId.getAndIncrement();
                    // send WELCOME before creating Connection so its reader thread
                    // doesn't race against the client's synchronous bootstrap read
                    byte[] welcome = PacketWriter.welcome(id, level.getRawBlocks());
                    var directOut = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
                    directOut.writeInt(welcome.length);
                    directOut.write(welcome);
                    directOut.flush();
                    var conn = new Connection(id, sock);
                    clients.put(id, conn);
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "rd23-server-acceptor");
        t.setDaemon(true);
        t.start();
    }

    public void setHostName(String name) { this.hostName = name; clientNames.put(0, name); }

    /**
     * Called every game tick from the main thread.
     * Reads packets from all clients and applies them.
     */
    public void tick(float hostX, float hostY, float hostZ, float hostYRot, float hostXRot) {
        hx = hostX; hy = hostY; hz = hostZ; hyr = hostYRot; hxr = hostXRot;

        // broadcast host position to all clients
        broadcast(PacketWriter.playerPos(0, hostX, hostY, hostZ, hostYRot, hostXRot), -1);

        // drain each client
        List<Integer> dead = null;
        for (var entry : clients.entrySet()) {
            int id   = entry.getKey();
            var conn = entry.getValue();
            if (!conn.isAlive()) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(id);
                continue;
            }
            byte[] pkt;
            while ((pkt = conn.poll()) != null) handlePacket(id, pkt, conn);
        }
        if (dead != null) {
            dead.forEach(id -> { clients.remove(id); clientPos.remove(id); clientNames.remove(id); });
        }
    }

    /** Called from game thread when host places/breaks a block. */
    public void broadcastTile(int x, int y, int z, int type) {
        broadcast(PacketWriter.setTile(x, y, z, type), -1);
    }

    public void broadcastChat(String message) {
        pendingChat.add(message);
        broadcast(PacketWriter.chat(message), -1);
    }

    public String pollChat() { return pendingChat.poll(); }

    private void handlePacket(int senderId, byte[] pkt, Connection sender) {
        if (pkt.length == 0) return;
        byte type = pkt[0];
        try (var dis = new DataInputStream(new ByteArrayInputStream(pkt, 1, pkt.length - 1))) {
            switch (type) {
                case Packet.PLAYER_POS -> {
                    dis.readInt(); // skip client-sent id
                    float x    = dis.readFloat(), y  = dis.readFloat(), z     = dis.readFloat();
                    float yRot = dis.readFloat(), xRot = dis.readFloat();
                    clientPos.put(senderId, new float[]{x, y, z, yRot, xRot});
                    broadcast(PacketWriter.playerPos(senderId, x, y, z, yRot, xRot), senderId);
                }
                case Packet.SET_TILE -> {
                    int x = dis.readInt(), y = dis.readInt(), z = dis.readInt(), tile = dis.readInt();
                    level.setTile(x, y, z, tile);
                    broadcast(PacketWriter.setTile(x, y, z, tile), senderId);
                }
                case Packet.CHAT -> {
                    int mlen = dis.readShort() & 0xFFFF;
                    byte[] mb = new byte[mlen]; dis.readFully(mb);
                    pendingChat.add(new String(mb, java.nio.charset.StandardCharsets.UTF_8));
                    broadcast(pkt, senderId);
                }
                case Packet.PLAYER_NAME -> {
                    dis.readInt(); // skip id
                    int len = dis.readShort() & 0xFFFF;
                    byte[] nb = new byte[len]; dis.readFully(nb);
                    String name = new String(nb, java.nio.charset.StandardCharsets.UTF_8);
                    clientNames.put(senderId, name);
                    // relay name to all others + host gets it via getClientNames()
                    broadcast(PacketWriter.playerName(senderId, name), senderId);
                    // also send host name to this new client
                    sender.send(PacketWriter.playerName(0, hostName));
                    // send all existing names to new client
                    for (var ne : clientNames.entrySet()) {
                        if (ne.getKey() != senderId) sender.send(PacketWriter.playerName(ne.getKey(), ne.getValue()));
                    }
                }
                case Packet.PING -> sender.send(PacketWriter.ping());
            }
        } catch (IOException ignored) {}
    }

    private void broadcast(byte[] pkt, int excludeId) {
        for (var conn : clients.values()) {
            if (conn.id != excludeId && conn.isAlive()) conn.send(pkt);
        }
    }

    public Map<Integer, Connection> getClients()   { return clients; }
    public Map<Integer, float[]>   getClientPos()  { return clientPos; }
    public Map<Integer, String>    getClientNames() { return clientNames; }

    public void stop() {
        running = false;
        try { serverSocket.close(); } catch (IOException ignored) {}
        clients.values().forEach(Connection::close);
    }
}

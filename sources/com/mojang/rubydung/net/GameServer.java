package com.mojang.rubydung.net;

import com.mojang.rubydung.level.Level;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs on the host. Accepts incoming TCP connections, hands each one the world seed,
 * then relays packets between all peers and pushes the chunks somebody edited.
 */
public class GameServer {
    public static final int DEFAULT_PORT = 25565;

    /** More peers than a hosted game is meant to carry; the rest are turned away at accept. */
    private static final int MAX_CLIENTS = 16;

    private final Level                          level;
    private final ServerSocket                   serverSocket;
    private final Map<Integer, Connection>       clients      = new ConcurrentHashMap<>();
    private final Map<Integer, float[]>          clientPos    = new ConcurrentHashMap<>(); // id->[x,y,z,yr,xr]
    private final Map<Integer, String>           clientNames  = new ConcurrentHashMap<>();
    private final Map<Integer, ClientState>      clientState  = new ConcurrentHashMap<>();
    private final AtomicInteger                  nextId       = new AtomicInteger(1);
    private volatile boolean                     running      = true;
    public  final int                            port;
    private String hostName = "Host";
    private final ConcurrentLinkedQueue<String> pendingChat = new ConcurrentLinkedQueue<>();
    private long lastPingMillis = System.currentTimeMillis();

    /** Per-client bookkeeping: what it has been sent, how far it sees, how fast it may talk. */
    private static final class ClientState {
        // chunks already delivered, so an edited chunk is sent once and not re-sent every
        // pass; entries are dropped once the client walks far away from them
        final Set<Long> sentChunks = ConcurrentHashMap.newKeySet();
        // chunks the streamer has found (and read off disk where needed), waiting for the
        // game thread to snapshot and send them
        final ConcurrentLinkedQueue<PendingChunk> readyChunks = new ConcurrentLinkedQueue<>();
        volatile int viewDist = DEFAULT_SEND_RADIUS;
        // rate-limit windows, only ever touched from the game thread
        long tileWindowStart, chatWindowStart;
        int  tileCount, chatCount;
    }

    /** A chunk found for a client, with the copy read off disk if it was not resident. */
    private record PendingChunk(int cx, int cz, byte[] fromDisk) {}

    public GameServer(Level level, int port) throws IOException {
        this.level        = level;
        this.port         = port;
        this.serverSocket = new ServerSocket(port, 50, InetAddress.getByName("0.0.0.0"));
        startAcceptor();
        startChunkStreamer();
    }

    private void startAcceptor() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket sock = serverSocket.accept();
                    if (clients.size() >= MAX_CLIENTS) { sock.close(); continue; }
                    sock.setTcpNoDelay(true);
                    int id = nextId.getAndIncrement();
                    // send WELCOME before creating Connection so its reader thread
                    // doesn't race against the client's synchronous bootstrap read
                    byte[] welcome = PacketWriter.welcome(id, level.getSeed());
                    var directOut = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
                    directOut.writeInt(welcome.length);
                    directOut.write(welcome);
                    directOut.flush();
                    var conn = new Connection(id, sock);
                    clientState.put(id, new ClientState());
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

    /** How often a peer is asked to prove it is still there, and how long silence is tolerated. */
    private static final long PING_INTERVAL_MS = 1_000, TIMEOUT_MS = 30_000;
    /** Edits and chat messages one client may send per second before the surplus is dropped. */
    private static final int MAX_TILE_EDITS_PER_SEC = 60, MAX_CHATS_PER_SEC = 2;

    /**
     * Called every game tick from the main thread.
     * Reads packets from all clients and applies them.
     */
    public void tick(float hostX, float hostY, float hostZ, float hostYRot, float hostXRot) {
        // broadcast host position to all clients
        broadcast(PacketWriter.playerPos(0, hostX, hostY, hostZ, hostYRot, hostXRot), -1);

        // a TCP socket whose peer vanished without closing (unplugged cable, killed VM) stays
        // writable forever, so silence is the only evidence there is
        boolean ping = System.currentTimeMillis() - lastPingMillis >= PING_INTERVAL_MS;
        if (ping) lastPingMillis = System.currentTimeMillis();

        List<Integer> dead = null;
        for (var entry : clients.entrySet()) {
            int id   = entry.getKey();
            var conn = entry.getValue();
            if (!conn.isAlive() || conn.idleMillis() > TIMEOUT_MS) {
                if (dead == null) dead = new ArrayList<>();
                dead.add(id);
                continue;
            }
            byte[] pkt;
            while ((pkt = conn.poll()) != null) handlePacket(id, pkt, conn);
            if (ping) conn.send(PacketWriter.ping());
        }
        if (dead != null) dead.forEach(this::dropClient);
        sendReadyChunks();
        flushFluidChanges();
    }

    /** Forget a peer and tell everybody else, or they keep rendering a player who left. */
    private void dropClient(int id) {
        Connection conn = clients.remove(id);
        if (conn != null) conn.close();
        clientPos.remove(id);
        clientNames.remove(id);
        clientState.remove(id);
        broadcast(PacketWriter.playerLeave(id), -1);
    }

    // Default push radius for a client that never reported one, and how much further it has
    // to walk before that record is forgotten. The gap stops a client sitting on a boundary
    // from re-requesting the same chunk every pass.
    private static final int DEFAULT_SEND_RADIUS = 8, FORGET_MARGIN = 4, MAX_SEND_RADIUS = 32;
    private static final int MAX_CHUNKS_PER_PASS = 4;
    /** How often the streamer looks for chunks to push — roughly a game tick. */
    private static final long STREAM_INTERVAL_MS = 50;

    /**
     * Finds the chunks each client cannot generate for itself, on its own thread. Terrain
     * comes from the seed; only blocks somebody edited travel. This half runs off the game
     * thread because a chunk that is not resident is read straight from the region files — a
     * synchronous zstd decompression of a whole 32x32 region, which has no business
     * happening inside a tick. The sending half is {@link #sendReadyChunks()}.
     */
    private void startChunkStreamer() {
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(STREAM_INTERVAL_MS);
                    streamEditedChunks();
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                }
            }
        }, "rd23-chunk-stream");
        t.setDaemon(true);
        t.start();
    }

    private void streamEditedChunks() {
        for (var entry : clients.entrySet()) {
            int id = entry.getKey();
            Connection conn = entry.getValue();
            float[] pos = clientPos.get(id);
            ClientState st = clientState.get(id);
            if (pos == null || st == null || !conn.isAlive()) continue;
            int radius = st.viewDist;
            int pcx = (int) Math.floor(pos[0] / 16.0f), pcz = (int) Math.floor(pos[2] / 16.0f);
            st.sentChunks.removeIf(key -> Math.abs((int) (key >> 32) - pcx) > radius + FORGET_MARGIN
                                       || Math.abs((int) (long) key - pcz) > radius + FORGET_MARGIN);
            int budget = MAX_CHUNKS_PER_PASS;
            for (int r = 0; r <= radius && budget > 0; r++) {
                for (int dx = -r; dx <= r && budget > 0; dx++) {
                    for (int dz = -r; dz <= r && budget > 0; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue; // ring by ring, nearest first
                        int cx = pcx + dx, cz = pcz + dz;
                        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                        if (!st.sentChunks.add(key)) continue;
                        // a resident chunk is left for the game thread to snapshot; only the
                        // ones that have to come off disk are read here
                        boolean resident = level.isEditedChunkResident(cx, cz);
                        byte[] blocks = resident ? null : level.getStoredBlocks(cx, cz);
                        if (!resident && blocks == null) continue;   // pure terrain: the seed is enough
                        st.readyChunks.add(new PendingChunk(cx, cz, blocks));
                        budget--;
                    }
                }
            }
        }
    }

    /**
     * Sends the chunks the streamer found, from the game thread.
     *
     * The snapshot has to be taken here, not on the streamer thread, because a chunk copied
     * a moment earlier is *older* than the block and fluid updates already queued behind it:
     * the client would apply those first and then have them wiped by the stale snapshot.
     * Reading and queueing on the same thread that broadcasts the updates puts them in the
     * one order the client replays them in.
     */
    private void sendReadyChunks() {
        for (var entry : clients.entrySet()) {
            ClientState st = clientState.get(entry.getKey());
            if (st == null) continue;
            Connection conn = entry.getValue();
            for (int i = 0; i < MAX_CHUNKS_PER_PASS; i++) {
                PendingChunk p = st.readyChunks.poll();
                if (p == null) break;
                // prefer the live copy: the chunk may have loaded (and been edited) since
                byte[] blocks = level.getResidentModifiedBlocks(p.cx(), p.cz());
                if (blocks == null) blocks = p.fromDisk();
                if (blocks == null) continue;
                conn.send(PacketWriter.chunk(p.cx(), p.cz(), blocks));
            }
        }
    }

    /** Called from game thread when host places/breaks a block. */
    public void broadcastTile(int x, int y, int z, int type) {
        broadcast(PacketWriter.setTile(x, y, z, type), -1);
    }

    /** Cells the simulation moved since the last tick, waiting to go out as one packet. */
    private final ConcurrentLinkedQueue<int[]> fluidChanges = new ConcurrentLinkedQueue<>();
    /** Cells per packet, and the most a single tick may ship before the rest is left to the next. */
    private static final int FLUID_BATCH = 512, MAX_FLUID_PER_TICK = 4096;

    /**
     * Called from the fluid simulation. The host is the only side that runs it, so every
     * cell it moves has to be told to the clients or their rivers stop where ours flowed on.
     * Queued rather than sent: a fluid tick moves up to 64 cells and they travel as one packet.
     */
    public void broadcastFluid(int x, int y, int z, int type) {
        if (clients.isEmpty()) return;
        fluidChanges.add(new int[]{x, y, z, type});
    }

    private void flushFluidChanges() {
        if (fluidChanges.isEmpty()) return;
        List<int[]> batch = new ArrayList<>(FLUID_BATCH);
        int shipped = 0;
        int[] cell;
        while (shipped < MAX_FLUID_PER_TICK && (cell = fluidChanges.poll()) != null) {
            batch.add(cell);
            shipped++;
            if (batch.size() == FLUID_BATCH) {
                broadcast(PacketWriter.fluidBatch(batch, 0, batch.size()), -1);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) broadcast(PacketWriter.fluidBatch(batch, 0, batch.size()), -1);
    }

    public void broadcastChat(String message) {
        pendingChat.add(message);
        broadcast(PacketWriter.chat(message), -1);
    }

    public String pollChat() { return pendingChat.poll(); }

    private void handlePacket(int senderId, byte[] pkt, Connection sender) {
        if (pkt.length == 0) return;
        ClientState st = clientState.get(senderId);
        if (st == null) return;
        long now = System.currentTimeMillis();
        byte type = pkt[0];
        try (var dis = new DataInputStream(new ByteArrayInputStream(pkt, 1, pkt.length - 1))) {
            switch (type) {
                case Packet.PLAYER_POS -> {
                    dis.readInt(); // skip client-sent id
                    float x    = dis.readFloat(), y  = dis.readFloat(), z     = dis.readFloat();
                    float yRot = dis.readFloat(), xRot = dis.readFloat();
                    // a NaN or a position out past the world edge would poison the chunk
                    // streamer's arithmetic and every peer's renderer
                    if (!Packet.isSanePosition(x, y, z, yRot, xRot)) break;
                    clientPos.put(senderId, new float[]{x, y, z, yRot, xRot});
                    broadcast(PacketWriter.playerPos(senderId, x, y, z, yRot, xRot), senderId);
                }
                case Packet.SET_TILE -> {
                    int x = dis.readInt(), y = dis.readInt(), z = dis.readInt(), tile = dis.readInt();
                    if (now - st.tileWindowStart >= 1_000) { st.tileWindowStart = now; st.tileCount = 0; }
                    // a peer editing faster than a human can click is rewriting the world
                    if (++st.tileCount > MAX_TILE_EDITS_PER_SEC) break;
                    // drop edits from a peer that names an id it is not allowed to place
                    if (Packet.isPlaceable(tile)) {
                        level.setTile(x, y, z, tile);
                        broadcast(PacketWriter.setTile(x, y, z, tile), senderId);
                    }
                }
                case Packet.CHAT -> {
                    int mlen = dis.readShort() & 0xFFFF;
                    byte[] mb = new byte[mlen]; dis.readFully(mb);
                    if (now - st.chatWindowStart >= 1_000) { st.chatWindowStart = now; st.chatCount = 0; }
                    if (++st.chatCount > MAX_CHATS_PER_SEC) break;
                    String msg = new String(mb, java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (msg.isEmpty()) break;
                    if (msg.length() > Packet.MAX_CHAT_CHARS) msg = msg.substring(0, Packet.MAX_CHAT_CHARS);
                    // the name is the host's record of who this connection is, never text the
                    // sender supplied — otherwise any peer can speak as anybody
                    String formatted = "<" + clientNames.getOrDefault(senderId, "Player" + senderId) + "> " + msg;
                    pendingChat.add(formatted);
                    broadcast(PacketWriter.chat(formatted), senderId);
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
                case Packet.CLIENT_INFO -> {
                    int viewDist = dis.readInt();
                    // edits have to reach as far as the client actually draws, or it renders
                    // untouched terrain where somebody built a house
                    st.viewDist = Math.max(2, Math.min(MAX_SEND_RADIUS, viewDist));
                }
                case Packet.PING -> { /* the reply itself is the proof of life */ }
            }
        // one malformed packet costs one packet, never the tick
        } catch (Exception ignored) {}
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

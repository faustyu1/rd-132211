package com.mojang.rubydung.net;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Wraps a TCP socket. A reader thread queues incoming packets for the game thread to
 * drain, and a writer thread drains outgoing ones onto the socket.
 *
 * Both directions are queued on purpose. A blocking write from the game thread stalls the
 * whole host for as long as the peer's TCP window stays full — and Java's blocking sockets
 * have no write timeout at all, so one client on bad wifi could freeze everybody. Handing
 * the bytes to a writer thread turns that into a growing queue instead, and a queue that
 * stops draining is a peer that has stopped keeping up: the connection is dropped rather
 * than allowed to consume memory without bound.
 */
public final class Connection {
    /**
     * Sanity cap for one packet. The largest thing that legitimately travels is a
     * compressed 32 KiB chunk; anything past this is corruption or an attack.
     */
    public static final int MAX_PACKET_BYTES = 128 * 1024;
    /**
     * Cap for the handshake read, which happens before there is any reason to trust the
     * peer. WELCOME is 17 bytes; a hostile server must not be able to talk the client into
     * allocating more than a line of text.
     */
    public static final int MAX_HANDSHAKE_BYTES = 64;

    /** Packets queued in either direction before the peer counts as unable to keep up. */
    private static final int QUEUE_CAPACITY = 4096;
    /** Writes coalesced into one flush; a broadcast tick hands over several small packets. */
    private static final int WRITE_BATCH = 64;

    public  final int                    id;
    private final Socket                 socket;
    private final DataInputStream        in;
    private final DataOutputStream       out;
    private final BlockingQueue<byte[]>  inbox  = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<byte[]>  outbox = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean             alive  = true;
    /** When the peer last said anything, for the keepalive timeout. */
    private volatile long                lastRecvMillis = System.currentTimeMillis();
    private Thread readerThread, writerThread;

    public Connection(int id, Socket socket) throws IOException {
        this(id, socket, new DataInputStream(new BufferedInputStream(socket.getInputStream())));
    }

    /**
     * Adopts an input stream that has already been read from (the client's WELCOME
     * bootstrap). Wrapping a second buffer around the same socket would drop whatever
     * the first one read ahead, so there must only ever be one buffered stream per socket.
     */
    public Connection(int id, Socket socket, DataInputStream in) throws IOException {
        this.id     = id;
        this.socket = socket;
        this.in     = in;
        this.out    = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        startReader();
        startWriter();
    }

    /** Reads one length-prefixed packet, rejecting implausible lengths instead of blindly allocating. */
    public static byte[] readPacket(DataInputStream in, int maxLen) throws IOException {
        int len = in.readInt();
        if (len < 1 || len > maxLen) throw new IOException("Bad packet length " + len + " (allowed 1.." + maxLen + ")");
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    private void startReader() {
        readerThread = new Thread(() -> {
            try (var stream = in) {
                while (alive) {
                    byte[] pkt = readPacket(stream, MAX_PACKET_BYTES);
                    lastRecvMillis = System.currentTimeMillis();
                    // a full inbox means the game thread stopped draining or the peer is
                    // flooding; either way this connection is not viable any more
                    if (!inbox.offer(pkt)) throw new IOException("Inbox overflow");
                }
            } catch (Exception e) {
                close();
            }
        }, "rd23-reader-" + id);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void startWriter() {
        writerThread = new Thread(() -> {
            try {
                while (alive) {
                    byte[] first = outbox.take();
                    writeOne(first);
                    // whatever else is already queued goes out under the same flush
                    for (int i = 0; i < WRITE_BATCH; i++) {
                        byte[] more = outbox.poll();
                        if (more == null) break;
                        writeOne(more);
                    }
                    out.flush();
                }
            } catch (Exception e) {
                close();
            }
        }, "rd23-writer-" + id);
        writerThread.setDaemon(true);
        writerThread.start();
    }

    private void writeOne(byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    /** Called from game thread — drain all available packets. */
    public byte[] poll() {
        return inbox.poll();
    }

    /** Thread-safe, non-blocking send. The writer thread does the actual socket write. */
    public void send(byte[] data) {
        if (!alive) return;
        // a peer that cannot absorb 4096 queued packets is not going to catch up
        if (!outbox.offer(data)) close();
    }

    public boolean isAlive() { return alive; }

    /** Milliseconds since the peer last sent anything — how a half-open socket is spotted. */
    public long idleMillis() { return System.currentTimeMillis() - lastRecvMillis; }

    public void close() {
        if (!alive) return;
        alive = false;
        try { socket.close(); } catch (IOException ignored) {}
        // the writer parks in take() forever otherwise
        if (writerThread != null) writerThread.interrupt();
    }
}

package com.mojang.rubydung.net;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Wraps a TCP socket. Reads incoming packets on a background thread and
 * queues them for the game thread to drain. Writes are done from the game
 * thread (or any thread) via send().
 */
public final class Connection {
    /** Sanity cap for a gameplay packet (position, tile edit, chat); anything bigger is corruption or an attack. */
    public static final int MAX_PACKET_BYTES = 4_000_000;
    /**
     * Cap for the WELCOME world snapshot, which carries every loaded chunk
     * (4 + count * (8 + 32768) bytes). The host keeps up to 53x53 chunks resident at
     * the largest render distance — roughly 88 MB — so the bootstrap read needs a far
     * bigger ceiling than a gameplay packet.
     */
    public static final int MAX_WORLD_PACKET_BYTES = 128 * 1024 * 1024;

    public  final int                    id;
    private final Socket                 socket;
    private final DataInputStream        in;
    private final DataOutputStream       out;
    private final BlockingQueue<byte[]>  inbox = new LinkedBlockingQueue<>();
    private volatile boolean             alive = true;

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
        Thread t = new Thread(() -> {
            try (var stream = in) {
                while (alive) {
                    inbox.put(readPacket(stream, MAX_PACKET_BYTES));
                }
            } catch (Exception e) {
                close();
            }
        }, "rd23-reader-" + id);
        t.setDaemon(true);
        t.start();
    }

    /** Called from game thread — drain all available packets. */
    public byte[] poll() {
        return inbox.poll();
    }

    /** Thread-safe send. Prefixes with 4-byte length. */
    public synchronized void send(byte[] data) {
        if (!alive) return;
        try {
            out.writeInt(data.length);
            out.write(data);
            out.flush();
        } catch (IOException e) {
            close();
        }
    }

    public boolean isAlive() { return alive; }

    public void close() {
        alive = false;
        try { socket.close(); } catch (IOException ignored) {}
    }
}

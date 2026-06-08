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
public class Connection {
    public  final int                    id;
    private final Socket                 socket;
    private final DataOutputStream       out;
    private final BlockingQueue<byte[]>  inbox = new LinkedBlockingQueue<>();
    private volatile boolean             alive = true;

    public Connection(int id, Socket socket) throws IOException {
        this.id     = id;
        this.socket = socket;
        this.out    = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        startReader();
    }

    private void startReader() {
        Thread t = new Thread(() -> {
            try (var in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                while (alive) {
                    int len = in.readInt();
                    if (len < 1 || len > 4_000_000) { close(); break; }
                    byte[] buf = new byte[len];
                    in.readFully(buf);
                    inbox.put(buf);
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

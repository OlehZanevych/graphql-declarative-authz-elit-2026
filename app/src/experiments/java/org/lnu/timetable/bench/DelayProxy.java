package org.lnu.timetable.bench;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * A TCP relay that adds a fixed one-way delay to every chunk in both directions, preserving order,
 * to emulate a database that is not on the same host. The kernel's netem qdisc is not available in
 * the benchmark container, so the delay is applied in user space.
 *
 * usage: DelayProxy <listenPort> <targetHost> <targetPort> <oneWayDelayMicros>
 */
public final class DelayProxy {

    record Chunk(long dueNanos, byte[] data, int len) {}

    public static void main(String[] args) throws Exception {
        int listen = Integer.parseInt(args[0]);
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        long delayNanos = Long.parseLong(args[3]) * 1_000L;
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", listen), 1024);
            System.out.println("delay proxy :" + listen + " -> " + host + ":" + port + " one-way " + args[3] + " us");
            while (true) {
                Socket client = server.accept();
                Thread.ofVirtual().start(() -> {
                    try {
                        Socket upstream = new Socket(host, port);
                        client.setTcpNoDelay(true);
                        upstream.setTcpNoDelay(true);
                        pipe(client, upstream, delayNanos);
                        pipe(upstream, client, delayNanos);
                    } catch (Exception e) {
                        try { client.close(); } catch (Exception ignored) { }
                    }
                });
            }
        }
    }

    static void pipe(Socket from, Socket to, long delayNanos) throws Exception {
        LinkedBlockingQueue<Chunk> q = new LinkedBlockingQueue<>();
        InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream();
        Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    byte[] copy = java.util.Arrays.copyOf(buf, n);
                    q.put(new Chunk(System.nanoTime() + delayNanos, copy, n));
                }
            } catch (Exception ignored) {
            } finally {
                try { q.put(new Chunk(0, null, -1)); } catch (InterruptedException ignored) { }
            }
        });
        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                while (true) {
                    Chunk c = q.take();
                    if (c.len() < 0) break;
                    long wait;
                    while ((wait = c.dueNanos() - System.nanoTime()) > 0) LockSupport.parkNanos(wait);
                    out.write(c.data(), 0, c.len());
                    out.flush();
                }
            } catch (Exception ignored) {
            } finally {
                try { to.shutdownOutput(); } catch (Exception ignored) { }
            }
        });
    }
}

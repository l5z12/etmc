package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Join side: listens on a loopback {@link ServerSocket} that we point Minecraft at, and for each
 * incoming local connection opens a userspace TCP stream to the host over the mesh, then bridges
 * the two. Minecraft connects to {@code 127.0.0.1:localPort} as if it were a normal LAN server.
 */
public final class JoinProxy {

    private static final long CONNECT_TIMEOUT_MS = 15_000L;

    private final EasyTier et;
    private final String instName;
    private final String hostIp;
    private final int hostPort;
    private final int desiredPort;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Set<TcpBridge> bridges = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectionSeq = new AtomicLong();
    private volatile ServerSocket server;
    private volatile int localPort;
    private volatile Thread acceptThread;

    /**
     * @param desiredPort preferred loopback port (0 = ephemeral). A stable port lets mods like
     *                    ViaFabricPlus persist their per-server (per-address) settings across joins.
     */
    public JoinProxy(EasyTier et, String instName, String hostIp, int hostPort, int desiredPort) {
        this.et = et;
        this.instName = instName;
        this.hostIp = hostIp;
        this.hostPort = hostPort;
        this.desiredPort = desiredPort;
    }

    /** Opens the loopback listener and starts accepting. Returns the bound local port. */
    public int start() throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            try {
                ss.bind(new InetSocketAddress(loopback, Math.max(0, desiredPort)));
            } catch (IOException preferredTaken) {
                // desired port unavailable -> fall back to an ephemeral port
                ss.bind(new InetSocketAddress(loopback, 0));
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(ss);
            throw e;
        }
        server = ss;
        localPort = ss.getLocalPort();
        acceptThread = Threads.start("etmc-join-accept", this::acceptLoop);
        return localPort;
    }

    public int localPort() {
        return localPort;
    }

    private void acceptLoop() {
        ServerSocket ss = server;
        while (!stopped.get()) {
            Socket sock;
            try {
                sock = ss.accept();
            } catch (IOException e) {
                break; // server closed
            }
            // The mesh connect blocks (up to CONNECT_TIMEOUT_MS), so it must not run here: this loop
            // has to stay free to accept the next connection (Minecraft opens one to ping and another
            // to play, and mods may open more).
            Threads.start("etmc-join-connect-" + connectionSeq.incrementAndGet(), () -> bridgeToHost(sock));
        }
    }

    private void bridgeToHost(Socket sock) {
        EasyTier.Bind conn;
        try {
            sock.setTcpNoDelay(true);
            conn = et.tcpConnect(instName, hostIp, hostPort, CONNECT_TIMEOUT_MS);
        } catch (Throwable e) {
            closeQuietly(sock);
            return;
        }
        TcpBridge bridge = new TcpBridge(et, sock, conn.handle(), bridges::remove);
        bridges.add(bridge);
        if (stopped.get()) {
            // stop() raced us and already drained the set — don't leave this one running
            bridge.close();
            return;
        }
        bridge.start("host");
    }

    public int activeConnections() {
        return bridges.size();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        closeQuietly(server);
        for (TcpBridge b : bridges) {
            b.close();
        }
        bridges.clear();
        Thread t = acceptThread;
        if (t != null) t.interrupt();
    }

    private static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}

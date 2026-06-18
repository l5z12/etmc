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
    private ServerSocket server;
    private volatile int localPort;
    private volatile Thread acceptThread;

    public JoinProxy(EasyTier et, String instName, String hostIp, int hostPort) {
        this(et, instName, hostIp, hostPort, 0);
    }

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
        server = new ServerSocket();
        server.setReuseAddress(true);
        try {
            server.bind(new InetSocketAddress(loopback, Math.max(0, desiredPort)));
        } catch (IOException preferredTaken) {
            // desired port unavailable -> fall back to an ephemeral port
            server.bind(new InetSocketAddress(loopback, 0));
        }
        localPort = server.getLocalPort();
        Thread t = new Thread(this::acceptLoop, "etmc-join-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
        return localPort;
    }

    public int localPort() {
        return localPort;
    }

    private void acceptLoop() {
        while (!stopped.get()) {
            Socket sock;
            try {
                sock = server.accept();
            } catch (IOException e) {
                break; // server closed
            }
            handleLocal(sock);
        }
    }

    private void handleLocal(Socket sock) {
        EasyTier.Bind conn;
        try {
            sock.setTcpNoDelay(true);
            conn = et.tcpConnect(instName, hostIp, hostPort, CONNECT_TIMEOUT_MS);
        } catch (Throwable e) {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
            return;
        }
        final TcpBridge[] ref = new TcpBridge[1];
        TcpBridge bridge = new TcpBridge(et, sock, conn.handle(), () -> bridges.remove(ref[0]));
        ref[0] = bridge;
        bridges.add(bridge);
        bridge.start("host");
    }

    public int activeConnections() {
        return bridges.size();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (TcpBridge b : bridges) {
            b.close();
        }
        bridges.clear();
        Thread t = acceptThread;
        if (t != null) t.interrupt();
    }
}

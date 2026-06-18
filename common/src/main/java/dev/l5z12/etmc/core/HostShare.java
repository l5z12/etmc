package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host side: binds a userspace TCP listener on the mesh and forwards every accepted peer stream to
 * the local "Open to LAN" server on {@code 127.0.0.1:lanPort}.
 */
public final class HostShare {

    private static final long ACCEPT_TIMEOUT_MS = 1000L;

    private final EasyTier et;
    private final String instName;
    private final int virtualPort;
    private final int lanPort;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Set<TcpBridge> bridges = ConcurrentHashMap.newKeySet();
    private final AtomicLong totalConnections = new AtomicLong();
    private volatile long listenerHandle = 0;
    private volatile Thread acceptThread;

    public HostShare(EasyTier et, String instName, int virtualPort, int lanPort) {
        this.et = et;
        this.instName = instName;
        this.virtualPort = virtualPort;
        this.lanPort = lanPort;
    }

    /** Binds the mesh listener and starts accepting. Throws if the bind keeps failing. */
    public void start() {
        EasyTier.Bind b = bindWithRetry();
        listenerHandle = b.handle();
        Thread t = new Thread(this::acceptLoop, "etmc-host-accept");
        t.setDaemon(true);
        acceptThread = t;
        t.start();
    }

    /** The instance may not be ready the instant after run_network_instance; give the bind a few tries. */
    private EasyTier.Bind bindWithRetry() {
        RuntimeException last = null;
        for (int i = 0; i < 12; i++) {
            try {
                return et.tcpBind(instName, virtualPort, 10_000L);
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last != null ? last : new IllegalStateException("tcp bind failed");
    }

    private void acceptLoop() {
        while (!stopped.get()) {
            EasyTier.Accept acc;
            try {
                acc = et.tcpAccept(listenerHandle, ACCEPT_TIMEOUT_MS);
            } catch (Throwable t) {
                if (stopped.get()) break;
                continue;
            }
            if (acc == null) continue; // timeout
            handlePeer(acc);
        }
    }

    private void handlePeer(EasyTier.Accept acc) {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress("127.0.0.1", lanPort), 5000);
            sock.setTcpNoDelay(true);
        } catch (Exception e) {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
            et.tcpClose(acc.handle());
            return;
        }
        totalConnections.incrementAndGet();
        final TcpBridge[] ref = new TcpBridge[1];
        TcpBridge bridge = new TcpBridge(et, sock, acc.handle(), () -> bridges.remove(ref[0]));
        ref[0] = bridge;
        bridges.add(bridge);
        bridge.start(acc.peerIp() == null ? "peer" : acc.peerIp());
    }

    public int activeConnections() {
        return bridges.size();
    }

    public long totalConnections() {
        return totalConnections.get();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        try {
            et.tcpListenerClose(listenerHandle);
        } catch (Throwable ignored) {
        }
        for (TcpBridge b : bridges) {
            b.close();
        }
        bridges.clear();
        Thread t = acceptThread;
        if (t != null) t.interrupt();
    }
}

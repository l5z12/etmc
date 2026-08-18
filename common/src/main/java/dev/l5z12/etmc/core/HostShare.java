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
    /** Retries for the initial bind: the instance isn't ready the instant run_network_instance returns. */
    private static final int BIND_ATTEMPTS = 12;
    private static final long BIND_RETRY_MS = 300L;
    /** Pause after a failed accept, so a persistently erroring listener can't spin a core. */
    private static final long ACCEPT_ERROR_BACKOFF_MS = 250L;
    private static final int LAN_CONNECT_TIMEOUT_MS = 5000;

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
        listenerHandle = bindWithRetry().handle();
        acceptThread = Threads.start("etmc-host-accept", this::acceptLoop);
    }

    /** The instance may not be ready the instant after run_network_instance; give the bind a few tries. */
    private EasyTier.Bind bindWithRetry() {
        RuntimeException last = null;
        for (int i = 0; i < BIND_ATTEMPTS; i++) {
            try {
                return et.tcpBind(instName, virtualPort, 10_000L);
            } catch (RuntimeException e) {
                last = e;
                if (!sleep(BIND_RETRY_MS)) break; // interrupted
            }
        }
        throw last != null ? last : new IllegalStateException("tcp bind failed");
    }

    private void acceptLoop() {
        while (!stopped.get()) {
            EasyTier.Accept acc;
            try {
                acc = et.tcpAccept(listenerHandle, ACCEPT_TIMEOUT_MS);
            } catch (Throwable ignored) {
                // A failing accept usually means the listener is gone; back off so a permanent
                // failure can't turn this loop into a busy spin.
                if (stopped.get() || !sleep(ACCEPT_ERROR_BACKOFF_MS)) break;
                continue;
            }
            if (acc == null) continue; // timeout
            try {
                handlePeer(acc);
            } catch (Throwable ignored) {
                // One peer must never take the loop down with it: the world would keep running while
                // silently refusing every later join.
                Io.closeStream(et, acc.handle());
            }
        }
    }

    private void handlePeer(EasyTier.Accept acc) {
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress("127.0.0.1", lanPort), LAN_CONNECT_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
        } catch (Exception e) {
            Io.closeQuietly(sock);
            Io.closeStream(et, acc.handle());
            return;
        }
        totalConnections.incrementAndGet();
        TcpBridge bridge = new TcpBridge(et, sock, acc.handle(), bridges::remove);
        bridges.add(bridge);
        if (stopped.get()) {
            // stop() raced us and has already closed everything it could see — don't leave this one
            // pumping (and holding a socket + mesh stream) after the session is gone.
            bridge.close();
            return;
        }
        bridge.start(acc.peerIp() == null ? "peer" : acc.peerIp());
    }

    public int activeConnections() {
        return bridges.size();
    }

    /** Total peer connections accepted since {@link #start()} (bridged or not). */
    public long totalConnections() {
        return totalConnections.get();
    }

    public void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        try {
            et.tcpListenerClose(listenerHandle);
        } catch (Throwable ignored) {
        }
        listenerHandle = 0;
        // Each close() removes its own bridge via the onClose callback, so the set empties itself;
        // clearing it here instead would drop a bridge that raced in without ever closing it.
        for (TcpBridge b : bridges) {
            b.close();
        }
        Thread t = acceptThread;
        if (t != null) t.interrupt();
    }

    /** Sleeps, returning false if the thread was interrupted (caller should unwind). */
    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

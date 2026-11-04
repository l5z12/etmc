package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.DataPlaneError;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.EasyTierException;

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
    /**
     * Retries for the initial bind, for the one failure that fixes itself: the instance not being
     * ready the instant run_network_instance returns. Anything else (port in use, ...) fails at once,
     * so "Starting..." never sits for minutes before reporting.
     */
    private static final int BIND_ATTEMPTS = 5;
    private static final long BIND_RETRY_MS = 500L;
    private static final long BIND_TIMEOUT_MS = 5_000L;
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
    /** Why the host stopped accepting joiners while still running; {@code null} while healthy. */
    private volatile String failure;

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
                return et.tcpBind(instName, virtualPort, BIND_TIMEOUT_MS);
            } catch (RuntimeException e) {
                last = e;
                if (!notReadyYet(e) || !sleep(BIND_RETRY_MS)) break;
            }
        }
        throw last != null ? last : new IllegalStateException("tcp bind failed");
    }

    private static boolean notReadyYet(RuntimeException e) {
        return e instanceof EasyTierException ete && (ete.is(DataPlaneError.HANDLE_CLOSED)
                || ete.is(DataPlaneError.INSTANCE_STOPPED) || ete.is(DataPlaneError.PATH_NOT_READY));
    }

    private void acceptLoop() {
        while (!stopped.get()) {
            EasyTier.Accept acc;
            try {
                acc = et.tcpAccept(listenerHandle, ACCEPT_TIMEOUT_MS);
            } catch (Throwable t) {
                if (stopped.get()) break;
                if (t instanceof EasyTierException e && (e.is(DataPlaneError.HANDLE_CLOSED)
                        || e.is(DataPlaneError.INSTANCE_STOPPED))) {
                    // The listener (or the whole network) is gone and won't come back: stop, and say
                    // so, rather than keep the world "hosting" while nobody can join.
                    failure = Errors.message(t);
                    break;
                }
                // Anything else may be transient; back off so it can't turn into a busy spin.
                if (!sleep(ACCEPT_ERROR_BACKOFF_MS)) break;
                continue;
            }
            if (acc == null) continue; // nobody connected within the timeout
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

    /** Why this host stopped accepting joiners, or {@code null} while it is healthy. */
    public String failure() {
        return failure;
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

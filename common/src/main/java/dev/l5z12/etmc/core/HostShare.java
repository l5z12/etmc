package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.DataPlaneError;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.EasyTierException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Host side: binds a userspace TCP listener on the mesh and forwards every accepted peer stream to
 * the local "Open to LAN" server on {@code 127.0.0.1:lanPort}.
 *
 * <p>With a {@link HostKey}, a joiner who opens with {@link HostTunnel#MAGIC} gets to check this really
 * is the host. Anyone else is forwarded as before: a link from before host keys, or a joiner on an
 * older etmc, still works, and turning them away wouldn't protect anybody. The check is the joiner's
 * to insist on, which a link with a key does.
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
    /** How long a joiner gets to say what it is, and to finish the handshake. */
    private static final long HELLO_TIMEOUT_MS = 10_000L;

    private final EasyTier et;
    private final String instName;
    private final int virtualPort;
    private final int lanPort;
    /** Answers joiners checking this host's key; {@code null} when it has none. */
    private final HostTunnel tunnel;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final Set<TcpBridge> bridges = ConcurrentHashMap.newKeySet();
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong connectionSeq = new AtomicLong();
    private final ConnectionLimiter limiter = new ConnectionLimiter();
    private volatile long listenerHandle = 0;
    private volatile Thread acceptThread;
    /** Why the host stopped accepting joiners while still running; {@code null} while healthy. */
    private volatile String failure;

    public HostShare(EasyTier et, String instName, int virtualPort, int lanPort) {
        this(et, instName, virtualPort, lanPort, null);
    }

    /** @param key proves this host to joiners; {@code null} to offer them nothing to check */
    public HostShare(EasyTier et, String instName, int virtualPort, int lanPort, HostKey key) {
        this(et, instName, virtualPort, lanPort, key, null);
    }

    /** @param cert proves the host's Minecraft account too; {@code null} unless the host opted in */
    public HostShare(EasyTier et, String instName, int virtualPort, int lanPort, HostKey key,
                     Supplier<PlayerCert> cert) {
        this.et = et;
        this.instName = instName;
        this.virtualPort = virtualPort;
        this.lanPort = lanPort;
        this.tunnel = key == null ? null : HostTunnel.answering(et, key, cert);
    }

    /** Binds the mesh listener and starts accepting. Throws if the bind keeps failing. */
    public void start() {
        listenerHandle = bindWithRetry().handle();
        acceptThread = Threads.start("etmc-host-accept", this::acceptLoop);
        EtmcLog.info("listening on mesh port " + virtualPort + " for the world on port " + lanPort);
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
        String lastError = null;
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
                    EtmcLog.warn("stopped accepting joiners: " + failure);
                    break;
                }
                // Anything else may be transient; back off so it can't turn into a busy spin.
                String error = Errors.message(t);
                if (!error.equals(lastError)) EtmcLog.warn("couldn't accept a connection, retrying: " + error);
                lastError = error;
                if (!sleep(ACCEPT_ERROR_BACKOFF_MS)) break;
                continue;
            }
            if (acc == null) continue; // nobody connected within the timeout
            if (!limiter.admit(acc.peerIp())) {
                Io.closeStream(et, acc.handle());
                int refused = limiter.refused();
                if (refused > 0) {
                    EtmcLog.warn("refused " + refused + " connection(s) over the limit, latest from " + peer(acc));
                }
                continue;
            }
            // Off this thread: a peer that connects and then says nothing mustn't hold up everyone else.
            Threads.start("etmc-host-peer-" + connectionSeq.incrementAndGet(), () -> {
                try {
                    handlePeer(acc);
                } catch (Throwable t) {
                    EtmcLog.info("connection from " + peer(acc) + " ended early: " + Errors.message(t));
                    Io.closeStream(et, acc.handle());
                } finally {
                    limiter.started();
                }
            });
        }
    }

    private static String peer(EasyTier.Accept acc) {
        return acc.peerIp() == null ? "an unknown peer" : acc.peerIp();
    }

    private void handlePeer(EasyTier.Accept acc) throws IOException {
        EasyTier stream = et;
        byte[] early = new byte[0];
        if (tunnel != null) {
            early = readHello(acc.handle());
            if (Arrays.equals(early, HostTunnel.MAGIC)) {
                try {
                    tunnel.answer(acc.handle(), HELLO_TIMEOUT_MS);
                } catch (GeneralSecurityException | EasyTierException e) {
                    EtmcLog.info(peer(acc) + " failed the host key handshake, so it may have a link for an "
                            + "older key: " + Errors.message(e));
                    Io.closeStream(et, acc.handle()); // an old link, from before this host's key changed
                    return;
                }
                stream = tunnel;
                early = new byte[0];
            }
        }
        Socket sock = new Socket();
        try {
            sock.connect(new InetSocketAddress("127.0.0.1", lanPort), LAN_CONNECT_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
            if (early.length > 0) sock.getOutputStream().write(early);
        } catch (Exception e) {
            EtmcLog.warn("couldn't reach the world on port " + lanPort + " for " + peer(acc) + ": "
                    + Errors.message(e));
            Io.closeQuietly(sock);
            Io.closeStream(stream, acc.handle());
            return;
        }
        EtmcLog.info("connection from " + peer(acc) + (stream == et ? ": plain, no host check" : ": host key checked"));
        totalConnections.incrementAndGet();
        TcpBridge bridge = new TcpBridge(stream, sock, acc.handle(), bridges::remove);
        bridges.add(bridge);
        if (stopped.get()) {
            // stop() raced us and has already closed everything it could see — don't leave this one
            // pumping (and holding a socket + mesh stream) after the session is gone.
            bridge.close();
            return;
        }
        bridge.start(acc.peerIp() == null ? "peer" : acc.peerIp());
    }

    /**
     * Reads the peer's first bytes, stopping as soon as they stop matching {@link HostTunnel#MAGIC}, so
     * nothing past it is taken from the handshake. What comes back is {@code MAGIC}, or the opening of
     * a plain connection for the world to read. Minecraft clients always speak first.
     */
    private byte[] readHello(long stream) throws IOException {
        byte[] hello = new byte[HostTunnel.MAGIC.length];
        byte[] one = new byte[1];
        int got = 0;
        while (got < hello.length) {
            int n = et.tcpRead(stream, one, 1, HELLO_TIMEOUT_MS);
            if (n <= 0) throw new IOException("the peer left before saying anything");
            hello[got++] = one[0];
            if (one[0] != HostTunnel.MAGIC[got - 1]) break;
        }
        return Arrays.copyOf(hello, got);
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
        EtmcLog.info("stopped hosting after " + totalConnections.get() + " connection(s)");
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

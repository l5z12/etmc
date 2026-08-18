package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory stand-in for the EasyTier FFI: instances are recorded rather than run, and the data
 * plane is a little loopback mesh of linked stream pairs.
 *
 * <p>It exists so the parts of etmc that matter most — the session state machine and the two
 * bridges that carry Minecraft's traffic — can be tested for real without the native library, a
 * relay, or a second peer (the data plane is peer-only, so a single process can never test it
 * against the real thing).
 *
 * <p>The one behaviour worth calling out, because {@code TcpBridge} and {@code EtmcChannel} depend
 * on it: closing a stream <b>cancels an in-flight read</b>, exactly as the real FFI does. Both use
 * a read timeout of an hour and rely on close to unblock them.
 */
final class FakeEasyTier implements EasyTier {

    /** Every TOML passed to {@code run_network_instance}, in order. */
    final List<String> started = new CopyOnWriteArrayList<>();
    /** Every instance name passed to {@code delete_network_instance}. */
    final List<String> deleted = new CopyOnWriteArrayList<>();
    /** What {@code collect_network_infos} reports, keyed by instance name. */
    final Map<String, String> infos = new LinkedHashMap<>();

    /** When set, the matching call throws instead of succeeding. */
    volatile RuntimeException runFailure;
    volatile RuntimeException bindFailure;
    volatile RuntimeException connectFailure;
    /** How many upcoming accepts should throw, as a listener that has gone away does. */
    final java.util.concurrent.atomic.AtomicInteger acceptFailures =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Invoked with the caller's end of each new outbound stream, so a test can script the reply. */
    volatile java.util.function.Consumer<Stream> onConnect;

    private final AtomicLong nextHandle = new AtomicLong(1);
    private final Map<Long, Listener> listeners = new ConcurrentHashMap<>();
    private final Map<Long, Stream> streams = new ConcurrentHashMap<>();
    private volatile Stream lastConnected;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void parseConfig(String toml) {
        // nothing to validate here; EtmcConfigTest covers what we generate
    }

    @Override
    public void runNetworkInstance(String toml) {
        RuntimeException fail = runFailure;
        if (fail != null) throw fail;
        started.add(toml);
    }

    @Override
    public void deleteNetworkInstance(String... names) {
        if (names != null) deleted.addAll(Arrays.asList(names));
    }

    @Override
    public Map<String, String> collectNetworkInfos(int max) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : infos.entrySet()) {
            if (out.size() >= max) break;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    @Override
    public String lastError() {
        return "(fake)";
    }

    // ------------------------------------------------------------------ data plane

    @Override
    public Bind tcpBind(String inst, int localPort, long timeoutMs) {
        RuntimeException fail = bindFailure;
        if (fail != null) throw fail;
        long handle = nextHandle.getAndIncrement();
        listeners.put(handle, new Listener(localPort));
        return new Bind(handle, EtmcConfig.HOST_VIRTUAL_IP, localPort);
    }

    @Override
    public Accept tcpAccept(long listener, long timeoutMs) {
        if (acceptFailures.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
            throw new IllegalStateException("data_plane_tcp_accept failed: listener closed");
        }
        Listener l = listeners.get(listener);
        if (l == null) return null;
        Stream serverSide;
        try {
            serverSide = l.pending.poll(Math.min(timeoutMs, 2_000L), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (serverSide == null) return null;
        long handle = register(serverSide);
        return new Accept(handle, EtmcConfig.HOST_VIRTUAL_IP, l.port, "10.126.126.9", 40000);
    }

    @Override
    public Bind tcpConnect(String inst, String dstIp, int dstPort, long timeoutMs) {
        RuntimeException fail = connectFailure;
        if (fail != null) throw fail;
        Stream client = new Stream();
        Stream server = new Stream();
        client.peer = server;
        server.peer = client;
        lastConnected = client;
        long handle = register(client);
        for (Listener l : listeners.values()) {
            if (l.port == dstPort) {
                l.pending.add(server);
                break;
            }
        }
        java.util.function.Consumer<Stream> hook = onConnect;
        if (hook != null) hook.accept(client);
        return new Bind(handle, dstIp, dstPort);
    }

    @Override
    public int tcpRead(long handle, byte[] buf, int len, long timeoutMs) {
        Stream s = streams.get(handle);
        if (s == null) return -1;
        return s.read(buf, len, timeoutMs);
    }

    @Override
    public int tcpWrite(long handle, byte[] data, int len, long timeoutMs) {
        Stream s = streams.get(handle);
        if (s == null) return -1;
        return s.write(data, len);
    }

    @Override
    public void tcpClose(long handle) {
        Stream s = streams.remove(handle);
        if (s != null) s.close();
    }

    @Override
    public void tcpListenerClose(long handle) {
        listeners.remove(handle);
    }

    // ------------------------------------------------------------------ test-side handles

    /**
     * Queues an inbound mesh connection on a listener, as if a peer had dialled us, and returns the
     * end the accepting code will be handed — the same view {@link #stream(long)} gives for an
     * outbound one. So {@code deliver()} feeds the code under test, and {@code written()} is what it
     * sent back.
     */
    Stream connectFromPeer(int listenerPort) {
        Stream accepted = new Stream();
        Stream dialler = new Stream();
        accepted.peer = dialler;
        dialler.peer = accepted;
        for (Listener l : listeners.values()) {
            if (l.port == listenerPort) {
                l.pending.add(accepted);
                return accepted;
            }
        }
        throw new IllegalStateException("nothing is listening on mesh port " + listenerPort);
    }

    /** The stream behind a handle the code under test was given. */
    Stream stream(long handle) {
        return streams.get(handle);
    }

    /** The caller's end of the most recent outbound stream, i.e. what a bridge is talking through. */
    Stream lastConnected() {
        return lastConnected;
    }

    boolean isOpen(long handle) {
        return streams.containsKey(handle);
    }

    private long register(Stream s) {
        long handle = nextHandle.getAndIncrement();
        streams.put(handle, s);
        return handle;
    }

    private static final class Listener {
        final int port;
        final LinkedBlockingQueue<Stream> pending = new LinkedBlockingQueue<>();

        Listener(int port) {
            this.port = port;
        }
    }

    /** One end of a mesh stream: a queue of readable chunks plus a record of what was written. */
    static final class Stream {
        private final Deque<byte[]> readable = new ArrayDeque<>();
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private byte[] partial;
        private int partialOffset;
        private volatile Stream peer;
        private boolean closed;

        /** Queues bytes for whoever reads this end. */
        void deliver(byte[] data) {
            synchronized (this) {
                readable.add(data.clone());
                notifyAll();
            }
        }

        /** Everything written to this end so far. */
        synchronized byte[] written() {
            return written.toByteArray();
        }

        synchronized boolean isClosed() {
            return closed;
        }

        void close() {
            synchronized (this) {
                closed = true;
                notifyAll();
            }
            Stream p = peer;
            if (p != null) {
                synchronized (p) {
                    p.closed = true;
                    p.notifyAll();
                }
            }
        }

        private int write(byte[] data, int len) {
            Stream p;
            synchronized (this) {
                if (closed) return -1;
                written.write(data, 0, len);
                p = peer;
            }
            if (p != null) {
                p.deliver(Arrays.copyOf(data, len));
            }
            return len;
        }

        private synchronized int read(byte[] buf, int len, long timeoutMs) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMs, 5_000L));
            while (true) {
                if (partial != null) {
                    int n = Math.min(len, partial.length - partialOffset);
                    System.arraycopy(partial, partialOffset, buf, 0, n);
                    partialOffset += n;
                    if (partialOffset >= partial.length) partial = null;
                    return n;
                }
                byte[] chunk = readable.poll();
                if (chunk != null) {
                    partial = chunk;
                    partialOffset = 0;
                    continue;
                }
                if (closed) return 0; // EOF, once everything buffered has been handed over
                if (System.nanoTime() >= deadline) return -1; // timed out
                try {
                    wait(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
    }
}

package dev.l5z12.etmc.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A netty {@link io.netty.channel.Channel} whose transport is an EasyTier data-plane TCP stream.
 *
 * <p>This lets Minecraft's vanilla networking ride the P2P mesh with <b>no loopback socket and no
 * port</b>: the connection's bytes are read/written through the EasyTier FFI ({@code data_plane_tcp_*})
 * instead of an OS socket. The EasyTier reads/writes are blocking, so they run on dedicated threads;
 * inbound bytes and lifecycle events are fired back on the channel's event loop.
 *
 * <p>It composes with protocol mods (e.g. ViaFabricPlus): this is purely the transport layer — the
 * netty pipeline (splitter/decoder/handlers) is set up by Minecraft as usual on top of it.
 */
public final class EtmcChannel extends AbstractChannel {

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);
    private static final int BUF = 32 * 1024;
    private static final long READ_TIMEOUT_MS = 3_600_000L;
    private static final long WRITE_TIMEOUT_MS = 60_000L;

    private final ChannelConfig config = new DefaultChannelConfig(this);
    private final EtmcConnect.Target target;
    private final SocketAddress remote;

    private volatile boolean open = true;
    private volatile boolean active = false;
    private volatile long stream = 0;
    private volatile Thread reader;
    private volatile Thread writer;
    /** Outbound payloads handed off from the event loop to {@link #writer} so writes never block it. */
    private final BlockingQueue<byte[]> writeQueue = new LinkedBlockingQueue<>();

    public EtmcChannel(EtmcConnect.Target target) {
        super(null);
        this.target = target;
        this.remote = resolved(target.hostIp(), target.hostPort());
    }

    /**
     * Builds a <b>resolved</b> {@link InetSocketAddress} for the host's virtual EasyTier IP. We must
     * not use {@link InetSocketAddress#createUnresolved} here: some mods (e.g. Axiom) call
     * {@code remoteAddress().getAddress().getHostAddress()} every tick, which NPEs on an unresolved
     * address. The host IP is a literal IPv4 on the mesh, so this never performs a DNS lookup; if it
     * somehow can't be parsed we fall back to loopback so {@code getAddress()} is still non-null.
     */
    private static InetSocketAddress resolved(String host, int port) {
        try {
            return new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (Exception e) {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
        }
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return new EtmcUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return true;
    }

    @Override
    protected SocketAddress localAddress0() {
        return null;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }

    @Override
    protected void doBind(SocketAddress localAddress) {
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        open = false;
        active = false;
        long s = stream;
        stream = 0;
        if (s != 0) {
            try {
                target.et().tcpClose(s);
            } catch (Throwable ignored) {
            }
        }
        Thread r = reader;
        if (r != null) r.interrupt();
        Thread w = writer;
        if (w != null) w.interrupt();
        // Tell the client manager the connection is gone so it leaves the EasyTier network.
        EtmcConnect.fireDisconnect(target.instanceName());
    }

    @Override
    protected void doBeginRead() {
        // The reader thread runs continuously once connected; nothing to schedule here.
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        // The EasyTier write is blocking, so we MUST NOT do it here (the netty event loop): a stalled
        // relay write would freeze the loop and starve inbound reads (→ client read timeout). Copy each
        // payload out and hand it to the writer thread instead; ordering is preserved by the FIFO queue.
        while (true) {
            Object msg = in.current();
            if (msg == null) break;
            if (msg instanceof ByteBuf buf) {
                int len = buf.readableBytes();
                if (len > 0) {
                    byte[] data = new byte[len];
                    buf.getBytes(buf.readerIndex(), data);
                    writeQueue.add(data);
                }
            }
            in.remove();
        }
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    private void startReader() {
        Thread t = new Thread(() -> {
            byte[] tmp = new byte[BUF];
            try {
                while (open) {
                    int n = target.et().tcpRead(stream, tmp, BUF, READ_TIMEOUT_MS);
                    if (n <= 0) break;
                    ByteBuf out = config.getAllocator().buffer(n);
                    out.writeBytes(tmp, 0, n);
                    eventLoop().execute(() -> {
                        pipeline().fireChannelRead(out);
                        pipeline().fireChannelReadComplete();
                    });
                }
            } catch (Throwable ignored) {
                // fall through to close
            } finally {
                eventLoop().execute(() -> close(voidPromise()));
            }
        }, "etmc-channel-rx");
        t.setDaemon(true);
        reader = t;
        t.start();
    }

    /** Drains {@link #writeQueue}, doing the blocking EasyTier write off the event loop (preserves order). */
    private void startWriter() {
        Thread t = new Thread(() -> {
            try {
                while (open) {
                    byte[] data = writeQueue.take(); // blocks until there's something to send
                    if (data.length == 0) continue;
                    int w = target.et().tcpWrite(stream, data, data.length, WRITE_TIMEOUT_MS);
                    if (w < 0) {
                        throw new java.io.IOException("etmc data-plane write failed");
                    }
                }
            } catch (InterruptedException ignored) {
                // channel closing
            } catch (Throwable ex) {
                eventLoop().execute(() -> close(voidPromise()));
            }
        }, "etmc-channel-tx");
        t.setDaemon(true);
        writer = t;
        t.start();
    }

    private final class EtmcUnsafe extends AbstractUnsafe {
        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }
            // EasyTier connect is blocking; do it off the event loop, then resolve on it.
            Thread t = new Thread(() -> {
                try {
                    var c = target.et().tcpConnect(target.instanceName(), target.hostIp(), target.hostPort(), 15_000L);
                    stream = c.handle();
                    active = true;
                    eventLoop().execute(() -> {
                        promise.setSuccess();
                        pipeline().fireChannelActive();
                        startReader();
                        startWriter();
                    });
                } catch (Throwable ex) {
                    eventLoop().execute(() -> {
                        promise.tryFailure(ex);
                        close(voidPromise());
                    });
                }
            }, "etmc-channel-connect");
            t.setDaemon(true);
            t.start();
        }
    }
}

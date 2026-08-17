package dev.l5z12.etmc.core;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The netty channel whose transport is a mesh stream — the {@code etmc://} join path, where there
 * is no loopback socket and no port at all. Minecraft builds its usual pipeline on top of this, so
 * it has to behave like a real channel: connect, carry bytes both ways, and report closure.
 */
class EtmcChannelTest {

    private final FakeEasyTier et = new FakeEasyTier();
    private EventLoopGroup group;
    private EtmcChannel channel;

    @AfterEach
    void tearDown() throws Exception {
        if (channel != null && channel.isOpen()) channel.close().await(5, TimeUnit.SECONDS);
        if (group != null) group.shutdownGracefully(0, 1, TimeUnit.SECONDS).await(5, TimeUnit.SECONDS);
    }

    /** Registers and connects a channel, returning the mesh stream on the other side of it. */
    private FakeEasyTier.Stream connect(ChannelInboundHandlerAdapter... handlers) throws Exception {
        group = new DefaultEventLoopGroup(1);
        channel = new EtmcChannel(new EtmcConnect.Target(et, "etmc-host-ann", EtmcConfig.HOST_VIRTUAL_IP, 25577));
        group.register(channel).sync();
        for (ChannelInboundHandlerAdapter h : handlers) {
            channel.pipeline().addLast(h);
        }
        assertTrue(channel.connect(channel.remoteAddress()).await(5, TimeUnit.SECONDS), "connect timed out");
        assertTrue(channel.isActive(), "the channel must be active once the mesh stream is up");
        return et.lastConnected();
    }

    @Test
    void theRemoteAddressIsResolvedSoModsCanReadIt() throws Exception {
        connect();

        InetSocketAddress remote = (InetSocketAddress) channel.remoteAddress();
        // Mods (Axiom, for one) call getAddress().getHostAddress() every tick; an unresolved
        // address would NPE there.
        assertNotNull(remote.getAddress(), "the address must be resolved, not a placeholder");
        assertEquals(EtmcConfig.HOST_VIRTUAL_IP, remote.getAddress().getHostAddress());
        assertEquals(25577, remote.getPort());
    }

    @Test
    void writesReachTheMesh() throws Exception {
        FakeEasyTier.Stream mesh = connect();
        byte[] payload = "handshake".getBytes(StandardCharsets.UTF_8);

        channel.writeAndFlush(Unpooled.copiedBuffer(payload)).await(5, TimeUnit.SECONDS);

        Await.until("the payload to reach the mesh", () -> mesh.written().length >= payload.length);
        assertEquals("handshake", new String(mesh.written(), StandardCharsets.UTF_8));
    }

    @Test
    void writesKeepTheirOrder() throws Exception {
        FakeEasyTier.Stream mesh = connect();
        for (int i = 0; i < 50; i++) {
            channel.write(Unpooled.copiedBuffer(new byte[] {(byte) i}));
        }
        channel.flush();

        Await.until("all 50 packets to arrive", () -> mesh.written().length == 50);
        byte[] got = mesh.written();
        for (int i = 0; i < 50; i++) {
            assertEquals((byte) i, got[i], "packet " + i + " arrived out of order");
        }
    }

    @Test
    void inboundBytesAreFiredIntoThePipeline() throws Exception {
        Collector collector = new Collector();
        FakeEasyTier.Stream mesh = connect(collector);

        mesh.deliver("login success".getBytes(StandardCharsets.UTF_8));

        Await.until("the pipeline to see the bytes", () -> collector.text().equals("login success"));
    }

    @Test
    void aClosedMeshStreamClosesTheChannel() throws Exception {
        AtomicBoolean inactive = new AtomicBoolean();
        FakeEasyTier.Stream mesh = connect(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                inactive.set(true);
            }
        });

        mesh.close();

        Await.until("the channel to go inactive", inactive::get);
        Await.until("the channel to close", () -> !channel.isOpen());
    }

    @Test
    void closingTheChannelReleasesTheMeshStreamAndNotifiesTheManager() throws Exception {
        AtomicReference<String> disconnected = new AtomicReference<>();
        EtmcConnect.setOnDisconnect(disconnected::set);
        try {
            FakeEasyTier.Stream mesh = connect();

            channel.close().await(5, TimeUnit.SECONDS);

            assertFalse(channel.isActive());
            Await.until("the mesh stream to be released", mesh::isClosed);
            assertEquals("etmc-host-ann", disconnected.get(),
                    "the manager must learn which instance to tear down");
        } finally {
            EtmcConnect.setOnDisconnect(null);
        }
    }

    /** Accumulates whatever the pipeline delivers, so a test can assert on it. */
    private static final class Collector extends ChannelInboundHandlerAdapter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                synchronized (out) {
                    out.write(data, 0, data.length);
                }
            } finally {
                buf.release();
            }
        }

        String text() {
            synchronized (out) {
                return out.toString(StandardCharsets.UTF_8);
            }
        }
    }
}

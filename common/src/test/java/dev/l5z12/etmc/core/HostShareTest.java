package dev.l5z12.etmc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hosting player's side: peers arriving over the mesh are forwarded to the world that was
 * opened to LAN. The "LAN server" here is a plain loopback {@link ServerSocket}, which is exactly
 * what Minecraft's Open to LAN is from the bridge's point of view.
 */
class HostShareTest {

    private static final int VIRTUAL_PORT = 25577;

    private final FakeEasyTier et = new FakeEasyTier();
    private ServerSocket lan;
    private HostShare share;

    @AfterEach
    void tearDown() {
        if (share != null) share.stop();
        Sockets.closeQuietly(lan);
    }

    private void start() throws IOException {
        lan = Sockets.listen();
        share = new HostShare(et, "etmc-host", VIRTUAL_PORT, lan.getLocalPort());
        share.start();
    }

    @Test
    void aPeerArrivingOverTheMeshIsForwardedToTheLanWorld() throws Exception {
        start();

        FakeEasyTier.Stream peer = et.connectFromPeer(VIRTUAL_PORT);
        lan.setSoTimeout(5000);
        try (Socket world = lan.accept()) {
            Await.until("the bridge to register the peer", () -> share.activeConnections() == 1);
            assertEquals(1, share.totalConnections());

            peer.deliver("join please".getBytes(StandardCharsets.UTF_8));
            byte[] got = new byte[11];
            world.setSoTimeout(5000);
            assertEquals(got.length, world.getInputStream().readNBytes(got, 0, got.length));
            assertEquals("join please", new String(got, StandardCharsets.UTF_8));

            world.getOutputStream().write("welcome".getBytes(StandardCharsets.UTF_8));
            world.getOutputStream().flush();
            Await.until("the world's reply to reach the peer", () -> peer.written().length >= 7);
            assertEquals("welcome", new String(peer.written(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void aPeerIsDroppedWhenTheLanWorldIsGone() throws Exception {
        start();
        lan.close(); // the player closed their world

        FakeEasyTier.Stream peer = et.connectFromPeer(VIRTUAL_PORT);

        Await.until("the unroutable peer to be dropped", peer::isClosed);
        assertEquals(0, share.activeConnections());
    }

    @Test
    void severalPeersAreCountedIndependently() throws Exception {
        start();
        lan.setSoTimeout(5000);

        et.connectFromPeer(VIRTUAL_PORT);
        Socket first = lan.accept();
        et.connectFromPeer(VIRTUAL_PORT);
        Socket second = lan.accept();

        try (first; second) {
            Await.until("both peers to be bridged", () -> share.activeConnections() == 2);
            assertEquals(2, share.totalConnections());
        }
    }

    @Test
    void stoppingClosesEveryBridge() throws Exception {
        start();
        FakeEasyTier.Stream peer = et.connectFromPeer(VIRTUAL_PORT);
        lan.setSoTimeout(5000);
        try (Socket world = lan.accept()) {
            Await.until("the peer to be bridged", () -> share.activeConnections() == 1);

            share.stop();

            assertEquals(0, share.activeConnections());
            Await.until("the peer's stream to be released", peer::isClosed);
            Await.until("the world-side connection to be dropped", () -> Sockets.peerGone(world));
        }
    }

    @Test
    void aBindThatNeverSucceedsIsReported() throws IOException {
        lan = Sockets.listen();
        et.bindFailure = new IllegalStateException("instance not found");
        share = new HostShare(et, "etmc-host", VIRTUAL_PORT, lan.getLocalPort());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, share::start);

        assertTrue(thrown.getMessage().contains("instance not found"),
                "the native reason must survive the retries");
    }
}

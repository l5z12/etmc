package dev.l5z12.etmc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The joining player's side: a loopback listener Minecraft connects to, tunnelled to the host over
 * the mesh. Minecraft opens more than one connection (it pings before it plays), so the accept loop
 * must keep accepting while a mesh connect is still in flight.
 */
class JoinProxyTest {

    private final FakeEasyTier et = new FakeEasyTier();
    private JoinProxy proxy;

    @AfterEach
    void tearDown() {
        if (proxy != null) proxy.stop();
    }

    private int start(int desiredPort) throws IOException {
        proxy = new JoinProxy(et, "etmc-host-ann", EtmcConfig.HOST_VIRTUAL_IP, 25577, desiredPort);
        return proxy.start();
    }

    private static Socket connectTo(int port) throws IOException {
        return Sockets.connect(port);
    }

    @Test
    void aLocalConnectionIsBridgedToTheHostOverTheMesh() throws Exception {
        int port = start(0);

        try (Socket client = connectTo(port)) {
            byte[] request = "hello host".getBytes(StandardCharsets.UTF_8);
            client.getOutputStream().write(request);
            client.getOutputStream().flush();

            Await.until("the proxy to bridge the connection", () -> proxy.activeConnections() == 1);
            FakeEasyTier.Stream mesh = et.lastConnected();
            Await.until("the request to reach the host", () -> mesh.written().length >= request.length);
            assertEquals("hello host", new String(mesh.written(), StandardCharsets.UTF_8));

            byte[] reply = "hello player".getBytes(StandardCharsets.UTF_8);
            mesh.deliver(reply);
            byte[] got = new byte[reply.length];
            client.setSoTimeout(5000);
            assertEquals(reply.length, client.getInputStream().readNBytes(got, 0, got.length));
            assertEquals("hello player", new String(got, StandardCharsets.UTF_8));
        }
    }

    @Test
    void severalConnectionsAreBridgedIndependently() throws Exception {
        int port = start(0);

        try (Socket ping = connectTo(port); Socket play = connectTo(port)) {
            Await.until("both connections to be bridged", () -> proxy.activeConnections() == 2);
            assertTrue(ping.isConnected() && play.isConnected());
        }
    }

    @Test
    void aFailedMeshConnectDropsOnlyThatConnection() throws Exception {
        et.connectFailure = new IllegalStateException("data_plane_tcp_connect failed: no route");
        int port = start(0);

        try (Socket client = connectTo(port)) {
            Await.until("the failed connection to be dropped",
                    () -> Sockets.peerGone(client));
            assertEquals(0, proxy.activeConnections());
        }

        // the proxy must still be listening for the next attempt
        et.connectFailure = null;
        try (Socket retry = connectTo(port)) {
            Await.until("the retry to be bridged", () -> proxy.activeConnections() == 1);
            assertTrue(retry.isConnected());
        }
    }

    @Test
    void aTakenPreferredPortFallsBackToAnEphemeralOne() throws Exception {
        try (ServerSocket squatter = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int taken = squatter.getLocalPort();

            int port = start(taken);

            assertNotEquals(taken, port, "the preferred port was in use");
            assertTrue(port > 0);
        }
    }

    @Test
    void stoppingClosesEveryBridgeAndTheMeshStreams() throws Exception {
        int port = start(0);
        try (Socket client = connectTo(port)) {
            Await.until("the connection to be bridged", () -> proxy.activeConnections() == 1);
            FakeEasyTier.Stream mesh = et.lastConnected();

            proxy.stop();

            assertEquals(0, proxy.activeConnections());
            Await.until("the bridged socket to be dropped", () -> Sockets.peerGone(client));
            assertTrue(mesh.isClosed(), "the mesh stream must be released, not leaked");
        }
    }

}

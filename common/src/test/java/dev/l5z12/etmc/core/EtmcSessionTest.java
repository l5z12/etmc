package dev.l5z12.etmc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session state machine: one EasyTier instance at a time, always torn down again, and never
 * left half-started after a failure (a stale instance would make the next host/join fail with
 * "instance not found" from inside the native library).
 */
class EtmcSessionTest {

    private final FakeEasyTier et = new FakeEasyTier();
    private final EtmcSession session = new EtmcSession(et);

    private static final List<String> RELAYS = List.of("tcp://relay.example:11010");

    @AfterEach
    void tearDown() {
        session.leave();
    }

    private JoinCode host() {
        return session.host(25565, "smp", "pw", RELAYS, EtmcConfig.DEFAULT_VIRTUAL_PORT);
    }

    // ------------------------------------------------------------------ hosting

    @Test
    void hostingStartsAnInstanceAndReturnsAShareableCode() {
        JoinCode code = host();

        assertEquals(EtmcSession.State.HOSTING, session.state());
        assertEquals(EtmcSession.Mode.HOST, session.mode());
        assertTrue(session.isActive());
        assertEquals("etmc-host", session.instName(), "no player name on a dedicated server");
        assertEquals(25565, session.lanPort());

        assertEquals(1, et.started.size());
        String toml = et.started.get(0);
        assertTrue(toml.contains("instance_name = \"etmc-host\""));
        assertTrue(toml.contains("ipv4 = \"" + EtmcConfig.HOST_VIRTUAL_IP + "\""));
        assertTrue(toml.contains("uri = \"tcp://relay.example:11010\""));

        assertEquals("smp", code.networkName);
        assertEquals("pw", code.networkSecret);
        assertEquals(RELAYS, code.relays);
        assertEquals(EtmcConfig.HOST_VIRTUAL_IP, code.hostIp);
        assertEquals(EtmcConfig.DEFAULT_VIRTUAL_PORT, code.hostPort);
        assertSame(code, session.currentCode());
    }

    @Test
    void thePlayerNameShapesTheDeviceNameAndIsSanitized() {
        session.setPlayerName("Steve/../Alex 42");
        host();
        assertEquals("etmc-host-SteveAlex42", session.instName());
    }

    @Test
    void aBlankPlayerNameIsIgnored() {
        session.setPlayerName("  ");
        host();
        assertEquals("etmc-host", session.instName());
    }

    @Test
    void anInvalidVirtualPortFallsBackToTheDefault() {
        assertEquals(EtmcConfig.DEFAULT_VIRTUAL_PORT,
                session.host(25565, "smp", "", RELAYS, 0).hostPort);
    }

    // ------------------------------------------------------------------ one at a time

    @Test
    void aSecondSessionIsRejectedWhileOneIsRunning() {
        host();
        IllegalStateException e = assertThrows(IllegalStateException.class, this::host);
        assertTrue(e.getMessage().contains("leave first"));
        assertThrows(IllegalStateException.class, () -> session.join(code()));
        assertEquals(1, et.started.size(), "the rejected calls must not have started anything");
    }

    @Test
    void leavingTearsTheInstanceDownAndAllowsAFreshStart() {
        host();
        String inst = session.instName();

        session.leave();

        assertEquals(List.of(inst), et.deleted);
        assertEquals(EtmcSession.State.IDLE, session.state());
        assertEquals(EtmcSession.Mode.NONE, session.mode());
        assertFalse(session.isActive());
        assertNull(session.instName());
        assertNull(session.currentCode());
        assertEquals(0, session.lanPort());

        host(); // idle again, so this must work
        assertEquals(EtmcSession.State.HOSTING, session.state());
    }

    @Test
    void leavingTwiceIsHarmless() {
        host();
        session.leave();
        session.leave();
        assertEquals(EtmcSession.State.IDLE, session.state());
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    void aFailedStartLeavesNothingBehind() {
        et.runFailure = new IllegalStateException("run_network_instance failed: bad relay");

        assertThrows(IllegalStateException.class, this::host);

        assertEquals(EtmcSession.State.ERROR, session.state());
        assertEquals(EtmcSession.Mode.NONE, session.mode(), "a failed host must not look active");
        assertFalse(session.isActive());
        assertNull(session.instName());
        assertTrue(session.lastError().contains("bad relay"));

        // and the session must still be usable once the cause is gone
        et.runFailure = null;
        assertNotNull(host());
    }

    @Test
    void aFailedMeshBindIsReportedAndCleanedUp() {
        et.bindFailure = new IllegalStateException("data_plane_tcp_bind failed: instance not found");

        assertThrows(IllegalStateException.class, this::host);

        assertEquals(EtmcSession.Mode.NONE, session.mode());
        assertTrue(et.deleted.contains("etmc-host"), "the instance that did start must be deleted");
    }

    // ------------------------------------------------------------------ joining

    private static JoinCode code() {
        return new JoinCode("smp", "pw", RELAYS, EtmcConfig.HOST_VIRTUAL_IP, 25577, "Their SMP");
    }

    @Test
    void joiningOpensALoopbackPortForMinecraft() throws Exception {
        session.setPlayerName("ann");
        int port = session.join(code());

        assertTrue(port > 0 && port <= 65535, "port was " + port);
        assertEquals(port, session.localPort());
        assertEquals(EtmcSession.State.JOINING, session.state());
        assertEquals(EtmcSession.Mode.JOIN, session.mode());
        assertEquals("etmc-host-ann", session.instName());
        assertTrue(et.started.get(0).contains("dhcp = true"), "joiners take a DHCP address");

        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            assertTrue(s.isConnected(), "Minecraft must be able to reach the proxy");
        }
    }

    @Test
    void joiningWithAnImportedConfigUsesItsInstanceAndAddress() {
        ImportedConfig cfg = ImportedConfig.parse("""
                dhcp = true

                [etmc]
                server = "10.126.126.5:25599"
                label = "Imported"
                """, "etmc-cfg-abcd1234", null);

        int port = session.joinWithConfig(cfg);

        assertTrue(port > 0);
        assertEquals("etmc-cfg-abcd1234", session.instName());
        assertEquals("Imported", session.currentCode().label);
        assertEquals("10.126.126.5", session.currentCode().hostIp);
        assertEquals(25599, session.currentCode().hostPort);
    }

    @Test
    void theLinkFlowStartsTheInstanceWithoutALoopbackProxy() {
        session.setPlayerName("bob");
        String inst = session.startLinkInstance(code());

        assertEquals("etmc-host-bob", inst);
        assertEquals(EtmcSession.State.JOINING, session.state());
        assertEquals(0, session.localPort(), "the etmc:// flow rides the data plane directly");
        assertEquals(1, et.started.size());
    }

    // ------------------------------------------------------------------ status

    @Test
    void statusIsReadForTheRunningInstance() {
        host();
        et.infos.put(session.instName(),
                "{\"running\":true,\"my_node_info\":{\"virtual_ipv4\":{\"address\":{\"addr\":176061953}}}}");

        NetworkStatus st = session.status();

        assertTrue(st.running());
        assertEquals(EtmcConfig.HOST_VIRTUAL_IP, st.virtualIp());
    }

    @Test
    void statusIsEmptyWhenIdleOrUnreadable() {
        assertEquals(0, session.status().peerCount());
        assertNull(session.status().virtualIp());

        host();
        et.infos.put(session.instName(), "{ this is not json");
        assertNull(session.status().virtualIp());
    }

    @Test
    void thereAreNoConnectionsUntilSomeoneBridges() {
        assertEquals(0, session.activeConnections());
        host();
        assertEquals(0, session.activeConnections());
    }
}

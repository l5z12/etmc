package dev.l5z12.etmc.ffi;

import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.NetworkStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check of etmc against the <b>real</b> bundled EasyTier library: a host and a joiner
 * instance in this one JVM, linked over localhost, exchanging bytes over the data plane.
 *
 * <p>This is the guard for EasyTier updates (easytier-update.yml): it exercises exactly what etmc
 * relies on — the TOML it generates, the status JSON it parses, and the data-plane ABI through
 * whichever backend this JVM picks (FFM on Java 21+, JNA on 17). Off by default, since it needs the
 * platform native and opens a local port; CI turns it on with {@code -Petmc.nativeSmoke=true}.
 */
@EnabledIfSystemProperty(named = "etmc.nativeSmoke", matches = "true")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class NativeSmokeTest {

    private static final int VPORT = 25565;
    private static EasyTier et;
    private static String hostInst;
    private static String joinInst;
    private static long listener;

    @BeforeAll
    static void startMesh() throws Exception {
        // Not a @TempDir: Windows can't delete a loaded DLL, so JUnit's cleanup of one always fails.
        Path dir = java.nio.file.Files.createTempDirectory("etmc-smoke");
        et = EasyTier.load(NativeLoader.extract(dir).path());

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String network = "etmc-smoke-" + id;
        String secret = "s-" + id;
        hostInst = "smoke-host-" + id;
        joinInst = "smoke-join-" + id;
        String peer = "tcp://127.0.0.1:" + port;

        // The host is etmc's own host config (its random-port listeners included) plus a localhost
        // listener for the joiner to dial (a real session reaches its relay instead).
        String hostToml = loopbackOnly(EtmcConfig.hostToml(hostInst, network, secret, List.of())
                .replace("listeners = [", "listeners = [\"" + peer + "\", "));
        assertTrue(hostToml.contains(peer), "host TOML layout changed; update this test");
        et.parseConfig(hostToml);
        et.runNetworkInstance(hostToml);

        // The joiner dials 127.0.0.1, which etmc's own config handles (no device binding for a local relay).
        String joinToml = EtmcConfig.joinToml(joinInst, network, secret, List.of(peer));
        assertTrue(joinToml.contains("bind_device = false"), "a loopback relay must turn device binding off");
        et.parseConfig(joinToml);
        et.runNetworkInstance(joinToml);

        listener = et.tcpBind(hostInst, VPORT, 10_000).handle();
    }

    @AfterAll
    static void stopMesh() {
        if (et != null) et.deleteNetworkInstance(hostInst, joinInst);
    }

    @Test
    void joinerGetsAnAddressAndSeesTheHost() throws Exception {
        NetworkStatus st = awaitStatus(joinInst, s -> s.virtualIp() != null
                && s.peers().stream().anyMatch(p -> EtmcConfig.HOST_VIRTUAL_IP.equals(p.ipv4())));
        assertTrue(st.running());
        assertTrue(st.virtualIp().startsWith("10.126.126."), st.virtualIp());
    }

    @Test
    void bytesFlowBothWaysAndEndOfStreamIsSeen() throws Exception {
        CompletableFuture<EasyTier.Accept> accepted = CompletableFuture.supplyAsync(this::acceptOne);
        long client = connectToHost();
        EasyTier.Accept server = accepted.get(60, TimeUnit.SECONDS);
        assertNotNull(server.peerIp());

        byte[] up = new byte[200_000];
        new Random(1).nextBytes(up);
        CompletableFuture<byte[]> got = CompletableFuture.supplyAsync(() -> readFully(server.handle(), up.length));
        assertEquals(up.length, et.tcpWrite(client, up, up.length, 30_000));
        assertArrayEquals(up, got.get(60, TimeUnit.SECONDS));

        byte[] down = "hello from the host".getBytes();
        et.tcpWrite(server.handle(), down, down.length, 30_000);
        assertArrayEquals(down, readFully(client, down.length));

        et.tcpClose(client);
        byte[] buf = new byte[64];
        assertEquals(0, et.tcpRead(server.handle(), buf, buf.length, 30_000), "peer close reads as EOF");
        et.tcpClose(server.handle());
    }

    @Test
    void aReadTimeoutLeavesTheStreamUsable() throws Exception {
        CompletableFuture<EasyTier.Accept> accepted = CompletableFuture.supplyAsync(this::acceptOne);
        long client = connectToHost();
        EasyTier.Accept server = accepted.get(60, TimeUnit.SECONDS);

        byte[] buf = new byte[64];
        EasyTierException e = assertThrows(EasyTierException.class,
                () -> et.tcpRead(client, buf, buf.length, 300));
        assertEquals(DataPlaneError.DEADLINE_EXCEEDED, e.kind());

        byte[] late = "after the timeout".getBytes();
        et.tcpWrite(server.handle(), late, late.length, 30_000);
        assertArrayEquals(late, readFully(client, late.length));
        et.tcpClose(client);
        et.tcpClose(server.handle());
    }

    @Test
    void acceptTimesOutWithNull() {
        assertNull(et.tcpAccept(listener, 200));
    }

    @Test
    void aClosedPortNeverYieldsData() throws Exception {
        // Make sure the route is up first, so the outcome is about the port; take the connection off
        // the listener's queue, or a later test's accept would get it.
        CompletableFuture<EasyTier.Accept> accepted = CompletableFuture.supplyAsync(this::acceptOne);
        long probe = connectToHost();
        et.tcpClose(accepted.get(60, TimeUnit.SECONDS).handle());
        et.tcpClose(probe);

        // EasyTier (759a270) reports a connect to a virtual port nobody listens on as done, and the
        // stream then just stays silent (no reset reaches the joiner). Minecraft's own read timeout
        // covers that in practice; what must never happen is the stream producing data.
        try {
            long h = et.tcpConnect(joinInst, EtmcConfig.HOST_VIRTUAL_IP, VPORT + 1, 10_000).handle();
            byte[] buf = new byte[16];
            try {
                assertEquals(0, et.tcpRead(h, buf, buf.length, 3_000), "nothing listens there");
            } catch (EasyTierException e) {
                assertNotNull(e.kind(), e.getMessage());
            } finally {
                et.tcpClose(h);
            }
        } catch (EasyTierException e) {
            assertNotNull(e.kind(), e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * EasyTier binds its sockets to the physical interfaces, and a socket bound there can't reach
     * 127.0.0.1 (Windows: WSAEADDRNOTAVAIL). The joiner's config turns that off by itself (its relay is
     * on loopback); the host has no relays, only this test's localhost listener, so it's done here.
     */
    private static String loopbackOnly(String toml) {
        assertTrue(toml.contains("[flags]\n"), "TOML layout changed; update this test");
        return toml.replace("[flags]\n", "[flags]\nbind_device = false\n");
    }

    private EasyTier.Accept acceptOne() {
        for (int i = 0; i < 60; i++) {
            EasyTier.Accept a = et.tcpAccept(listener, 1_000);
            if (a != null) return a;
        }
        throw new AssertionError("no connection accepted");
    }

    /** Connects once the route to the host exists (it takes a moment to propagate). */
    private static long connectToHost() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try {
                return et.tcpConnect(joinInst, EtmcConfig.HOST_VIRTUAL_IP, VPORT, 10_000).handle();
            } catch (EasyTierException e) {
                boolean notYet = e.is(DataPlaneError.NO_OVERLAY_ROUTE) || e.is(DataPlaneError.PATH_NOT_READY)
                        || e.is(DataPlaneError.DEADLINE_EXCEEDED);
                if (!notYet || System.nanoTime() > deadline) throw e;
                sleep(500);
            }
        }
    }

    private static byte[] readFully(long stream, int len) {
        byte[] out = new byte[len];
        byte[] buf = new byte[32 * 1024];
        int got = 0;
        while (got < len) {
            int n = et.tcpRead(stream, buf, buf.length, 30_000);
            if (n == 0) throw new AssertionError("EOF after " + got + " of " + len + " bytes");
            System.arraycopy(buf, 0, out, got, n);
            got += n;
        }
        return Arrays.copyOf(out, got);
    }

    private static NetworkStatus awaitStatus(String inst, java.util.function.Predicate<NetworkStatus> ok) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        NetworkStatus st = NetworkStatus.empty();
        while (System.nanoTime() < deadline) {
            st = NetworkStatus.parse(et.collectNetworkInfos(16).get(inst));
            if (ok.test(st)) return st;
            sleep(500);
        }
        Map<String, String> all = et.collectNetworkInfos(16);
        throw new AssertionError("status never got there: " + st + "\n  host status: "
                + NetworkStatus.parse(all.get(hostInst)) + "\n  raw joiner json: " + all.get(joinInst));
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}

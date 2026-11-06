package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.DataPlaneError;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.EasyTierException;
import dev.l5z12.etmc.ffi.NativeLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attack itself, on the real EasyTier library. An impostor joins the network with the secret from
 * an invite link and simply claims the host's address, with etmc's own host config. It sits closer to
 * the joiner than the real host does, so EasyTier routes the address to it.
 *
 * <p>{@link #aPlainJoinerLandsOnTheImpostor} is why a link needs a host key: if EasyTier ever starts
 * refusing this, that test fails, and this whole mechanism is worth a second look. The rest show the
 * key stops it, and that the handshake works over the real data plane, not only the fake one.
 *
 * <p>Off by default for the same reasons as {@code NativeSmokeTest}; {@code -Petmc.nativeSmoke=true}.
 */
@EnabledIfSystemProperty(named = "etmc.nativeSmoke", matches = "true")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class HostImpersonationNativeTest {

    private static final int VPORT = 25565;
    private static final String HOST_IP = EtmcConfig.HOST_VIRTUAL_IP;
    private static final long TIMEOUT_MS = 10_000L;

    private static final HostKey HOST_KEY = HostKey.generate();
    private static final HostKey IMPOSTOR_KEY = HostKey.generate();

    private static EasyTier et;
    private static String hostInst;
    private static String impostorInst;
    private static String joinInst;
    private static ServerSocket hostWorld;
    private static ServerSocket impostorWorld;
    private static HostShare host;
    private static HostShare impostor;

    @BeforeAll
    static void startMesh() throws Exception {
        // Not a @TempDir: Windows can't delete a loaded DLL, so JUnit's cleanup of one always fails.
        Path dir = java.nio.file.Files.createTempDirectory("etmc-impersonation");
        et = EasyTier.load(NativeLoader.extract(dir).path());

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String network = "etmc-impersonation-" + id;
        String secret = "s-" + id;
        hostInst = "imp-host-" + id;
        impostorInst = "imp-fake-" + id;
        joinInst = "imp-join-" + id;
        String relay = "tcp://127.0.0.1:" + port;

        // Everyone meets at the impostor, as they would at a relay: one hop to it, two to the real host.
        String impostorToml = EtmcConfig.hostToml(impostorInst, network, secret, List.of())
                .replace("listeners = [", "listeners = [\"" + relay + "\", ")
                .replace("[flags]\n", "[flags]\nbind_device = false\n");
        assertTrue(impostorToml.contains(relay), "host TOML layout changed; update this test");
        et.runNetworkInstance(impostorToml);
        et.runNetworkInstance(relayedOnly(EtmcConfig.hostToml(hostInst, network, secret, List.of(relay))));
        et.runNetworkInstance(relayedOnly(EtmcConfig.joinToml(joinInst, network, secret, List.of(relay))));

        hostWorld = Sockets.listen();
        impostorWorld = Sockets.listen();
        hostWorld.setSoTimeout(30_000);
        impostorWorld.setSoTimeout(30_000);
        host = new HostShare(et, hostInst, VPORT, hostWorld.getLocalPort(), HOST_KEY);
        impostor = new HostShare(et, impostorInst, VPORT, impostorWorld.getLocalPort(), IMPOSTOR_KEY);
        host.start();
        impostor.start();

        // Both claimants must be in the joiner's route table, or this proves nothing.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (claimants() < 2) {
            assertTrue(System.nanoTime() < deadline, "the joiner never saw both claimants: "
                    + et.collectNetworkInfos(16).get(joinInst));
            Thread.sleep(500);
        }
    }

    /**
     * Keeps the joiner two hops from the real host. A direct link would tie the route, and the winner
     * would then depend on how long the test took to get there.
     */
    private static String relayedOnly(String toml) {
        assertTrue(toml.contains("[flags]\n"), "TOML layout changed; update this test");
        return toml.replace("[flags]\n", "[flags]\ndisable_p2p = true\n");
    }

    private static long claimants() {
        return NetworkStatus.parse(et.collectNetworkInfos(16).get(joinInst)).peers().stream()
                .filter(p -> HOST_IP.equals(p.ipv4())).count();
    }

    @AfterAll
    static void stopMesh() {
        if (host != null) host.stop();
        if (impostor != null) impostor.stop();
        Sockets.closeQuietly(hostWorld);
        Sockets.closeQuietly(impostorWorld);
        if (et != null) et.deleteNetworkInstance(hostInst, impostorInst, joinInst);
    }

    /** Retries only while the route is still settling. */
    private static <T> T onceRouted(Supplier<T> connect) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try {
                return connect.get();
            } catch (EasyTierException e) {
                boolean notYet = e.is(DataPlaneError.NO_OVERLAY_ROUTE) || e.is(DataPlaneError.PATH_NOT_READY);
                if (!notYet || System.nanoTime() > deadline) throw e;
                Thread.sleep(500);
            }
        }
    }

    @Test
    void aPlainJoinerLandsOnTheImpostor() throws Exception {
        long stream = onceRouted(() -> et.tcpConnect(joinInst, HOST_IP, VPORT, TIMEOUT_MS)).handle();
        try {
            byte[] login = "my login".getBytes(StandardCharsets.UTF_8);
            et.tcpWrite(stream, login, login.length, TIMEOUT_MS);

            try (Socket stolen = impostorWorld.accept()) {
                stolen.setSoTimeout(30_000);
                assertArrayEquals(login, stolen.getInputStream().readNBytes(login.length));
            }
        } finally {
            et.tcpClose(stream);
        }
    }

    @Test
    void aJoinerWithTheHostsKeyRefusesTheImpostor() throws Exception {
        HostTunnel tunnel = HostTunnel.dialing(et, HOST_KEY.publicText());

        assertThrows(HostTunnel.WrongHost.class,
                () -> onceRouted(() -> tunnel.tcpConnect(joinInst, HOST_IP, VPORT, TIMEOUT_MS)));
    }

    @Test
    void thePingReportsTheImpostorToo() throws Exception {
        HostTunnel tunnel = HostTunnel.dialing(et, HOST_KEY.publicText());

        StatusPing.Result r = onceRouted(() -> StatusPing.probe(tunnel, joinInst, HOST_IP, VPORT, TIMEOUT_MS));

        assertEquals(StatusPing.Outcome.WRONG_HOST, r.outcome());
    }

    /** The key that does match what's there: this is the handshake working over the real data plane. */
    @Test
    void theHandshakeAndBulkDataWorkOverTheRealDataPlane() throws Exception {
        HostTunnel tunnel = HostTunnel.dialing(et, IMPOSTOR_KEY.publicText());

        long stream = onceRouted(() -> tunnel.tcpConnect(joinInst, HOST_IP, VPORT, TIMEOUT_MS)).handle();
        try (Socket world = impostorWorld.accept()) {
            world.setSoTimeout(30_000);
            byte[] up = new byte[300_000];
            new Random(1).nextBytes(up);
            tunnel.tcpWrite(stream, up, up.length, TIMEOUT_MS);
            assertArrayEquals(up, world.getInputStream().readNBytes(up.length));

            byte[] down = new byte[300_000];
            new Random(2).nextBytes(down);
            world.getOutputStream().write(down);
            world.getOutputStream().flush();
            byte[] got = new byte[down.length];
            byte[] buf = new byte[32 * 1024];
            int n = 0;
            while (n < got.length) {
                int r = tunnel.tcpRead(stream, buf, buf.length, TIMEOUT_MS);
                assertTrue(r > 0, "ended after " + n + " bytes");
                System.arraycopy(buf, 0, got, n, r);
                n += r;
            }
            assertArrayEquals(down, got);
        } finally {
            tunnel.tcpClose(stream);
        }
    }
}

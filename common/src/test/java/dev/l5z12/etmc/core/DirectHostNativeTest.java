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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The address-taking attack from {@code HostImpersonationNativeTest}, against a server with a public
 * address, on the real EasyTier library. The impostor gets every advantage it can have: it claims the
 * host's address, listens for direct links, and leaves P2P on. The joiner's only peer is the host, so
 * the host stays one hop away, and the impostor can't take the address however long it tries.
 *
 * <p>Off by default for the same reasons as {@code NativeSmokeTest}; {@code -Petmc.nativeSmoke=true}.
 */
@EnabledIfSystemProperty(named = "etmc.nativeSmoke", matches = "true")
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class DirectHostNativeTest {

    private static final int VPORT = 25565;
    private static final String HOST_IP = EtmcConfig.HOST_VIRTUAL_IP;
    private static final long TIMEOUT_MS = 10_000L;

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
        Path dir = java.nio.file.Files.createTempDirectory("etmc-direct");
        et = EasyTier.load(NativeLoader.extract(dir).path());

        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String network = "etmc-direct-" + id;
        String secret = "s-" + id;
        hostInst = "direct-host-" + id;
        impostorInst = "direct-fake-" + id;
        joinInst = "direct-join-" + id;
        String publicAddress = "tcp://127.0.0.1:" + port;

        String hostToml = EtmcConfig.hostToml(hostInst, hostInst, network, secret, List.of(),
                        EtmcConfig.listenerFor(publicAddress))
                .replace("[flags]\n", "[flags]\nbind_device = false\n");
        et.runNetworkInstance(hostToml);
        // etmc's own host config, so all its listeners, with P2P on: everything an attacker could want.
        et.runNetworkInstance(EtmcConfig.hostToml(impostorInst, network, secret, List.of(publicAddress)));
        et.runNetworkInstance(EtmcConfig.joinToml(joinInst, joinInst, network, secret, List.of(publicAddress), true));

        hostWorld = Sockets.listen();
        impostorWorld = Sockets.listen();
        hostWorld.setSoTimeout(30_000);
        impostorWorld.setSoTimeout(1_000);
        host = new HostShare(et, hostInst, VPORT, hostWorld.getLocalPort());
        impostor = new HostShare(et, impostorInst, VPORT, impostorWorld.getLocalPort());
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

    private static long connect() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try {
                return et.tcpConnect(joinInst, HOST_IP, VPORT, TIMEOUT_MS).handle();
            } catch (EasyTierException e) {
                boolean notYet = e.is(DataPlaneError.NO_OVERLAY_ROUTE) || e.is(DataPlaneError.PATH_NOT_READY);
                if (!notYet || System.nanoTime() > deadline) throw e;
                Thread.sleep(500);
            }
        }
    }

    @Test
    void aJoinerReachesTheRealServerWithAnImpostorAtItsAddress() throws Exception {
        assertEquals(2, claimants());

        long stream = connect();
        try {
            byte[] login = "my login".getBytes(StandardCharsets.UTF_8);
            et.tcpWrite(stream, login, login.length, TIMEOUT_MS);

            try (Socket real = hostWorld.accept()) {
                real.setSoTimeout(30_000);
                assertArrayEquals(login, real.getInputStream().readNBytes(login.length));
            }
        } finally {
            et.tcpClose(stream);
        }
    }
}

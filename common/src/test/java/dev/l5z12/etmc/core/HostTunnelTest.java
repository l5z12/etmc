package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.DataPlaneError;
import dev.l5z12.etmc.ffi.EasyTierException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a joiner with a host key really can't be fooled. The fake mesh here plays the attack: any
 * member of a network can sit at the host's address, so these tests put someone else there and check
 * nothing reaches them, on every path a join can take.
 */
class HostTunnelTest {

    private static final int PORT = 25577;
    private static final String INST = "etmc-join";
    private static final String HOST_IP = EtmcConfig.HOST_VIRTUAL_IP;
    private static final long TIMEOUT_MS = 3_000L;

    private final FakeEasyTier et = new FakeEasyTier();
    private final HostKey key = HostKey.generate();
    private final List<HostShare> shares = new ArrayList<>();
    private ServerSocket lan;

    @AfterEach
    void tearDown() {
        shares.forEach(HostShare::stop);
        Sockets.closeQuietly(lan);
    }

    /** Hosts a world for real, holding {@code hostKey}. */
    private void host(HostKey hostKey) throws IOException {
        lan = Sockets.listen();
        lan.setSoTimeout(5000);
        HostShare share = new HostShare(et, "etmc-host", PORT, lan.getLocalPort(), hostKey);
        share.start();
        shares.add(share);
    }

    private HostTunnel dialing(HostKey hostKey) {
        return HostTunnel.dialing(et, hostKey.publicText());
    }

    private static String readAll(Socket s, int n) throws IOException {
        s.setSoTimeout(5000);
        return new String(s.getInputStream().readNBytes(n), StandardCharsets.UTF_8);
    }

    private String readAll(HostTunnel tunnel, long stream, int n) {
        byte[] out = new byte[n];
        byte[] buf = new byte[n];
        int got = 0;
        while (got < n) {
            int r = tunnel.tcpRead(stream, buf, n - got, TIMEOUT_MS);
            if (r <= 0) break;
            System.arraycopy(buf, 0, out, got, r);
            got += r;
        }
        return new String(out, 0, got, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ the real host

    @Test
    void theRealHostIsReachedAndBytesFlowBothWays() throws Exception {
        host(key);
        HostTunnel tunnel = dialing(key);

        long stream = tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS).handle();
        try (Socket world = lan.accept()) {
            byte[] hello = "join please".getBytes(StandardCharsets.UTF_8);
            tunnel.tcpWrite(stream, hello, hello.length, TIMEOUT_MS);
            assertEquals("join please", readAll(world, hello.length));

            world.getOutputStream().write("welcome".getBytes(StandardCharsets.UTF_8));
            world.getOutputStream().flush();
            assertEquals("welcome", readAll(tunnel, stream, 7));
        }
    }

    @Test
    void whatCrossesTheMeshIsNotReadable() throws Exception {
        host(key);
        HostTunnel tunnel = dialing(key);

        long stream = tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS).handle();
        try (Socket world = lan.accept()) {
            byte[] secret = "a very private message".getBytes(StandardCharsets.UTF_8);
            tunnel.tcpWrite(stream, secret, secret.length, TIMEOUT_MS);
            assertEquals("a very private message", readAll(world, secret.length));

            String onTheWire = new String(et.stream(stream).written(), StandardCharsets.ISO_8859_1);
            assertFalse(onTheWire.contains("very private"));
        }
    }

    @Test
    void writesLargerThanOneFrameArriveWhole() throws Exception {
        host(key);
        HostTunnel tunnel = dialing(key);

        long stream = tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS).handle();
        try (Socket world = lan.accept()) {
            byte[] big = new byte[200_000];
            new Random(1).nextBytes(big);
            tunnel.tcpWrite(stream, big, big.length, TIMEOUT_MS);

            world.setSoTimeout(5000);
            assertArrayEquals(big, world.getInputStream().readNBytes(big.length));
        }
    }

    @Test
    void aPlainJoinerIsStillForwardedWithItsFirstBytesIntact() throws Exception {
        host(key);

        FakeEasyTier.Stream peer = et.connectFromPeer(PORT);
        peer.deliver("join please".getBytes(StandardCharsets.UTF_8));

        try (Socket world = lan.accept()) {
            assertEquals("join please", readAll(world, 11));
        }
    }

    @Test
    void aPlainJoinerWhoseOpeningLooksLikeTheMagicIsNotLost() throws Exception {
        host(key);
        byte[] opening = Arrays.copyOf(HostTunnel.MAGIC, 4);
        opening[3] = 'X'; // starts like the magic, then isn't

        FakeEasyTier.Stream peer = et.connectFromPeer(PORT);
        peer.deliver(opening);

        try (Socket world = lan.accept()) {
            world.setSoTimeout(5000);
            assertArrayEquals(opening, world.getInputStream().readNBytes(opening.length));
        }
    }

    @Test
    void aSilentPeerDoesNotHoldUpOtherJoiners() throws Exception {
        host(key);
        et.connectFromPeer(PORT); // connects, says nothing

        HostTunnel tunnel = dialing(key);
        long stream = tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS).handle();

        try (Socket world = lan.accept()) {
            byte[] hi = {'h', 'i'};
            tunnel.tcpWrite(stream, hi, hi.length, TIMEOUT_MS);
            assertEquals("hi", readAll(world, 2));
        }
    }

    @Test
    void aFloodFromOnePeerIsCutOffWhileItsFirstConnectionsAreKept() throws Exception {
        host(key);
        int flood = ConnectionLimiter.BURST + 20;
        List<FakeEasyTier.Stream> peers = new ArrayList<>();
        for (int i = 0; i < flood; i++) peers.add(et.connectFromPeer(PORT)); // connect, say nothing

        Await.until("the excess to be dropped", () -> peers.stream().filter(FakeEasyTier.Stream::isClosed).count() >= 20);
        assertEquals(20, peers.stream().filter(FakeEasyTier.Stream::isClosed).count());
    }

    // ------------------------------------------------------------------ the impostor

    @Test
    void anotherEtmcHostAtTheAddressIsRefused() throws Exception {
        host(HostKey.generate()); // the network secret is enough to be here, but this isn't the host

        HostTunnel tunnel = dialing(key);

        assertThrows(HostTunnel.WrongHost.class, () -> tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS));
    }

    @Test
    void aPlainServerAtTheAddressIsRefused() throws Exception {
        // What an impostor's vanilla server does with a handshake it can't read.
        et.onConnect = s -> s.deliver(new byte[64]);

        HostTunnel tunnel = dialing(key);

        assertThrows(HostTunnel.WrongHost.class, () -> tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS));
    }

    @Test
    void anImpostorThatHangsUpIsRefused() throws Exception {
        et.onConnect = FakeEasyTier.Stream::close;

        HostTunnel tunnel = dialing(key);

        assertThrows(HostTunnel.WrongHost.class, () -> tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS));
    }

    @Test
    void aRefusedStreamIsClosed() {
        et.onConnect = s -> s.deliver(new byte[64]);

        HostTunnel tunnel = dialing(key);
        assertThrows(HostTunnel.WrongHost.class, () -> tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS));

        assertTrue(et.lastConnected().isClosed());
    }

    @Test
    void nothingIsSentToAnImpostorButTheHandshake() throws Exception {
        et.onConnect = s -> s.deliver(new byte[64]);

        HostTunnel tunnel = dialing(key);
        assertThrows(HostTunnel.WrongHost.class, () -> tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS));

        assertEquals(HostTunnel.MAGIC.length + NoiseNk.KEY_LEN + NoiseNk.TAG_LEN, et.lastConnected().written().length);
    }

    @Test
    void relayingTheHandshakeGetsTheManInTheMiddleNothing() throws Exception {
        host(key);
        HostTunnel tunnel = dialing(key);

        long stream = tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS).handle();
        try (Socket world = lan.accept()) {
            // Past the handshake, something on the path slips its own bytes in. A framed but forged
            // message: this is what taking over a connection after relaying the proof looks like.
            byte[] forged = new byte[2 + 32];
            forged[1] = 32;
            et.stream(stream).deliver(forged);

            EasyTierException e = assertThrows(EasyTierException.class,
                    () -> tunnel.tcpRead(stream, new byte[16], 16, TIMEOUT_MS));
            assertTrue(e.getMessage().contains("tampered"));
            assertNotEquals(DataPlaneError.HANDLE_CLOSED, e.kind());
        }
    }

    @Test
    void aFrameLengthTooBigToBeRealIsRefused() throws Exception {
        host(key);
        HostTunnel tunnel = dialing(key);

        long stream = tunnel.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS).handle();
        try (Socket ignored = lan.accept()) {
            et.stream(stream).deliver(new byte[]{(byte) 0xFF, (byte) 0xFF});

            assertThrows(EasyTierException.class, () -> tunnel.tcpRead(stream, new byte[16], 16, TIMEOUT_MS));
        }
    }

    @Test
    void aHandleThatSkippedTheHandshakeGetsNoAccess() {
        HostTunnel tunnel = dialing(key);
        long plain = et.tcpConnect(INST, HOST_IP, PORT, TIMEOUT_MS).handle();

        EasyTierException e = assertThrows(EasyTierException.class,
                () -> tunnel.tcpWrite(plain, new byte[1], 1, TIMEOUT_MS));
        assertTrue(e.is(DataPlaneError.HANDLE_CLOSED));
        assertEquals(0, et.stream(plain).written().length);
    }

    // ------------------------------------------------------------------ the status ping

    @Test
    void thePingReportsAnImpostor() {
        et.onConnect = s -> s.deliver(new byte[64]);

        StatusPing.Result r = StatusPing.probe(dialing(key), INST, HOST_IP, PORT, TIMEOUT_MS);

        assertEquals(StatusPing.Outcome.WRONG_HOST, r.outcome());
    }

    @Test
    void thePingStillReportsAPortNobodyAnswers() {
        StatusPing.Result r = StatusPing.probe(dialing(key), INST, HOST_IP, PORT, 200);

        assertEquals(StatusPing.Outcome.SILENT, r.outcome());
    }

    // ------------------------------------------------------------------ the session

    @Test
    void aHostedLinkNamesTheKeyAndAJoinUsesIt() {
        EtmcSession hosting = new EtmcSession(et);
        hosting.setHostKey(key);

        JoinCode code = hosting.host(25565, "net", "s", List.of("tcp://r:1"), PORT);

        assertEquals(key.publicText(), code.hostKey);
        assertEquals(key.publicText(), JoinCode.decode(code.encodeLink()).hostKey);
        hosting.leave();

        EtmcSession joining = new EtmcSession(et);
        joining.startLinkInstance(code);
        assertTrue(joining.easyTier() instanceof HostTunnel, "the join must go through the check");
        joining.leave();
        assertFalse(joining.easyTier() instanceof HostTunnel, "the next session starts fresh");
    }

    @Test
    void aLinkWithoutAKeyJoinsAsItAlwaysDid() {
        EtmcSession joining = new EtmcSession(et);

        joining.startLinkInstance(new JoinCode("net", "s", List.of("tcp://r:1"), HOST_IP, PORT, ""));

        assertEquals(et, joining.easyTier());
    }

    @Test
    void aProxyJoinToAnImpostorSaysWhyAndCarriesNothing() throws Exception {
        et.onConnect = s -> s.deliver(new byte[64]);
        JoinCode code = new JoinCode("net", "s", List.of("tcp://r:1"), HOST_IP, PORT, "");
        code.hostKey = key.publicText();
        EtmcSession joining = new EtmcSession(et);

        int port = joining.join(code);
        try (Socket minecraft = new Socket("127.0.0.1", port)) {
            minecraft.getOutputStream().write("my login".getBytes(StandardCharsets.UTF_8));
            minecraft.setSoTimeout(5000);

            assertEquals(-1, minecraft.getInputStream().read(), "the proxy hangs up on Minecraft");
        }
        Await.until("the refusal to be reported", () -> joining.joinFailure() != null);
        assertFalse(new String(et.lastConnected().written(), StandardCharsets.ISO_8859_1).contains("my login"));
        joining.leave();
    }

    @Test
    void aDamagedKeyStopsTheJoinRatherThanSkippingTheCheck() {
        JoinCode code = new JoinCode("net", "s", List.of("tcp://r:1"), HOST_IP, PORT, "");
        code.hostKey = "not-a-key";

        assertThrows(IllegalArgumentException.class, () -> JoinCode.decode(code.encodeLink()));
        assertThrows(RuntimeException.class, () -> new EtmcSession(et).startLinkInstance(code));
        assertThrows(RuntimeException.class, () -> new EtmcSession(et).join(code));
        assertTrue(et.started.isEmpty(), "no network should have started");
    }

    @Test
    void aBlankKeyInALinkIsDamagedNotAbsent() {
        JoinCode code = new JoinCode("net", "s", List.of("tcp://r:1"), HOST_IP, PORT, "");
        code.hostKey = "";

        assertThrows(IllegalArgumentException.class, () -> JoinCode.decode(code.encodeLink()));
    }

    // ------------------------------------------------------------------ configs

    private static final String CONFIG = """
            [network_identity]
            network_name = "net"
            network_secret = "s"

            [[peer]]
            uri = "tcp://r:1"

            [etmc]
            server = "10.126.126.1:25565"
            host_key = "%s"
            """;

    @Test
    void aHostedConfigCarriesItsKeyThroughEveryWayOfJoining() {
        String toml = CONFIG.formatted(key.publicText());

        assertEquals(key.publicText(), JoinCode.fromConfig(toml).hostKey);
        assertEquals(key.publicText(), ImportedConfig.parse(toml, "etmc-x", null).hostKey());
    }

    @Test
    void aConfigJoinUsesTheCheck() {
        EtmcSession joining = new EtmcSession(et);

        joining.joinWithConfig(ImportedConfig.parse(CONFIG.formatted(key.publicText()), "etmc-x", null));

        assertTrue(joining.easyTier() instanceof HostTunnel);
        assertEquals(key.publicText(), joining.currentCode().hostKey);
        joining.leave();
    }

    @Test
    void theKeyNeverReachesEasyTier() {
        String toml = ImportedConfig.parse(CONFIG.formatted(key.publicText()), "etmc-x", null).toml();

        assertFalse(toml.contains("host_key"));
    }

    @Test
    void aDamagedKeyInAConfigIsRefused() {
        String toml = CONFIG.formatted("not-a-key");

        assertThrows(IllegalArgumentException.class, () -> JoinCode.fromConfig(toml));
        assertThrows(IllegalArgumentException.class, () -> ImportedConfig.parse(toml, "etmc-x", null));
    }

    // ------------------------------------------------------------------ the key file

    @Test
    void theKeyIsTheSameEachTimeSoLinksKeepWorking(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested").resolve("host-key");

        HostKey first = HostKey.loadOrCreate(file);
        HostKey second = HostKey.loadOrCreate(file);

        assertEquals(first.publicText(), second.publicText());
        assertTrue(HostKey.isValid(first.publicText()));
    }

    @Test
    void theFileDoesNotHoldThePublicKey(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("host-key");

        HostKey key = HostKey.loadOrCreate(file);

        assertFalse(Files.readString(file).contains(key.publicText()));
    }

    @Test
    void aDamagedKeyFileIsAnErrorNotANewIdentity(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("host-key");
        Files.writeString(file, "garbage");

        IOException e = assertThrows(IOException.class, () -> HostKey.loadOrCreate(file));

        assertTrue(e.getMessage().contains("damaged"));
        assertNull(e.getCause(), "Errors.message would show the cause instead of this");
        assertEquals("garbage", Files.readString(file), "the file might be recoverable");
    }

    @Test
    void differentHostsGetDifferentKeys() {
        assertNotEquals(HostKey.generate().publicText(), HostKey.generate().publicText());
    }

    @Test
    void wrongHostExplainsItselfToThePlayer() {
        String shown = Errors.message(new HostTunnel.WrongHost());

        assertTrue(shown.contains("real host"), shown);
    }
}

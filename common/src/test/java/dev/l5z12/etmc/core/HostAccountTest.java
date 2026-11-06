package dev.l5z12.etmc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A host proving its Minecraft account. "Mojang" here is an RSA key this test owns, signing what
 * Mojang signs; the point is what a joiner accepts, and that a forger can't satisfy it.
 */
class HostAccountTest {

    private static final int PORT = 25577;
    private static final String INST = "etmc-join";
    private static final long TIMEOUT_MS = 3_000L;
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");
    private static final long IN_A_DAY = System.currentTimeMillis() + 86_400_000L;

    private static final KeyPair MOJANG = rsa();
    private static final KeyPair FORGER = rsa();

    private final FakeEasyTier et = new FakeEasyTier();
    private final HostKey key = HostKey.generate();
    private final List<HostShare> shares = new ArrayList<>();
    private final Directory directory = new Directory();
    private ServerSocket lan;

    private static KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }

    /** A certificate for {@code account}, signed by {@code issuer}. */
    private static PlayerCert cert(UUID account, long expiresAtMs, KeyPair issuer) throws Exception {
        KeyPair player = rsa();
        byte[] der = player.getPublic().getEncoded();
        Signature s = Signature.getInstance("SHA1withRSA");
        s.initSign(issuer.getPrivate());
        s.update(HostAccount.certifiedBytes(account, expiresAtMs, der));
        return new PlayerCert(account, expiresAtMs, der, s.sign(), player.getPrivate());
    }

    private static final class Directory implements AccountDirectory {
        final AtomicInteger asked = new AtomicInteger();
        volatile boolean down;

        @Override
        public Collection<PublicKey> certificateKeys() throws IOException {
            asked.incrementAndGet();
            if (down) throw new IOException("Mojang is down");
            return List.of(FORGER_FREE_KEY, MOJANG.getPublic());
        }

        @Override
        public String nameOf(UUID account) throws IOException {
            if (down) throw new IOException("Mojang is down");
            return account.equals(STEVE) ? "Steve" : "Alex";
        }
    }

    /** A second Mojang key, as the live endpoint lists two: the right one needn't come first. */
    private static final PublicKey FORGER_FREE_KEY = rsa().getPublic();

    @BeforeEach
    void listen() throws IOException {
        lan = Sockets.listen();
        lan.setSoTimeout(5000);
    }

    @AfterEach
    void tearDown() {
        shares.forEach(HostShare::stop);
        Sockets.closeQuietly(lan);
    }

    private void host(Supplier<PlayerCert> cert) {
        HostShare share = new HostShare(et, "etmc-host", PORT, lan.getLocalPort(), key, cert);
        share.start();
        shares.add(share);
    }

    private HostTunnel dialing(String name) {
        return HostTunnel.dialing(et, key.publicText(), name, directory);
    }

    private void assertRefused(HostTunnel tunnel, String why) {
        HostTunnel.WrongHost e = assertThrows(HostTunnel.WrongHost.class,
                () -> tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS));
        assertTrue(e.getMessage().contains(why), e.getMessage());
        assertNull(tunnel.confirmedName());
    }

    @Test
    void aRealAccountIsConfirmedAndTheConnectionCarriesOn() throws Exception {
        PlayerCert cert = cert(STEVE, IN_A_DAY, MOJANG);
        host(() -> cert);
        HostTunnel tunnel = dialing("Steve");

        long stream = tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS).handle();

        assertEquals("Steve", tunnel.confirmedName());
        try (Socket world = lan.accept()) {
            tunnel.tcpWrite(stream, new byte[]{'h', 'i'}, 2, TIMEOUT_MS);
            world.setSoTimeout(5000);
            assertEquals('h', world.getInputStream().read(), "the proof mustn't leak into the game's bytes");
        }
    }

    @Test
    void theNameIsMatchedAsMojangDoes() throws Exception {
        PlayerCert cert = cert(STEVE, IN_A_DAY, MOJANG);
        host(() -> cert);
        HostTunnel tunnel = dialing("sTEVE");

        tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS);

        assertEquals("Steve", tunnel.confirmedName(), "Mojang's spelling is what gets shown");
    }

    @Test
    void anotherRealAccountCannotUseTheName() throws Exception {
        PlayerCert alex = cert(ALEX, IN_A_DAY, MOJANG);
        host(() -> alex);

        assertRefused(dialing("Steve"), "account is Alex");
    }

    @Test
    void aCertificateMojangDidNotSignIsRefused() throws Exception {
        PlayerCert forged = cert(STEVE, IN_A_DAY, FORGER);
        host(() -> forged);

        assertRefused(dialing("Steve"), "couldn't prove");
    }

    @Test
    void anExpiredCertificateIsRefused() throws Exception {
        PlayerCert old = cert(STEVE, System.currentTimeMillis() - 1000, MOJANG);
        host(() -> old);

        assertRefused(dialing("Steve"), "couldn't prove");
    }

    @Test
    void aHostGivingNoProofIsRefusedWhenTheLinkNamesThem() {
        host(null);

        assertRefused(dialing("Steve"), "couldn't prove");
    }

    @Test
    void aStolenProofDoesNotWorkOnAnotherConnection() throws Exception {
        byte[] stolen = HostAccount.prove(cert(STEVE, IN_A_DAY, MOJANG), new byte[32]);

        assertEquals(STEVE, HostAccount.verify(stolen, new byte[32], List.of(MOJANG.getPublic()),
                System.currentTimeMillis()), "valid on the handshake it was made for");
        byte[] anotherHandshake = new byte[32];
        anotherHandshake[0] = 1;
        assertNull(HostAccount.verify(stolen, anotherHandshake, List.of(MOJANG.getPublic()),
                System.currentTimeMillis()));
    }

    @Test
    void aCertificateCannotBeMovedToAnotherAccount() throws Exception {
        PlayerCert alex = cert(ALEX, IN_A_DAY, MOJANG);
        PlayerCert swapped = new PlayerCert(STEVE, alex.expiresAtMs(), alex.publicKey(), alex.mojangSignature(),
                alex.privateKey());
        host(() -> swapped);

        assertRefused(dialing("Steve"), "couldn't prove");
    }

    @Test
    void aMalformedClaimProvesNothing() {
        assertNull(HostAccount.verify(new byte[3], new byte[32], List.of(MOJANG.getPublic()), 0));
        assertNull(HostAccount.verify(new byte[500], new byte[32], List.of(MOJANG.getPublic()), 0));
    }

    @Test
    void whenMojangIsDownTheJoinGoesOnUnconfirmed() throws Exception {
        PlayerCert cert = cert(STEVE, IN_A_DAY, MOJANG);
        host(() -> cert);
        directory.down = true;
        HostTunnel tunnel = dialing("Steve");

        tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS);

        assertNull(tunnel.confirmedName());
    }

    // ------------------------------------------------------------------ opting in

    @Test
    void aHostThatDidNotOptInSharesNoAccount() throws Exception {
        host(null);
        HostTunnel tunnel = dialing(null);

        long stream = tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS).handle();

        // The opening frame is the empty one: a 2-byte length and a bare tag after the handshake.
        int handshake = NoiseNk.KEY_LEN + NoiseNk.TAG_LEN;
        assertEquals(handshake + 2 + NoiseNk.TAG_LEN, et.stream(stream).peerWritten().length);
    }

    @Test
    void aLinkNamingNobodyNeverContactsMojang() throws Exception {
        PlayerCert cert = cert(STEVE, IN_A_DAY, MOJANG);
        host(() -> cert);
        HostTunnel tunnel = dialing(null);

        tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS);

        assertEquals(0, directory.asked.get());
    }

    @Test
    void theCertificateIsAskedForOnEachConnectionSoRenewalsAreUsed() throws Exception {
        PlayerCert cert = cert(STEVE, IN_A_DAY, MOJANG);
        AtomicInteger asked = new AtomicInteger();
        host(() -> {
            asked.incrementAndGet();
            return cert;
        });
        HostTunnel tunnel = dialing("Steve");

        tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS);
        tunnel.tcpConnect(INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS);

        assertEquals(2, asked.get());
    }

    // ------------------------------------------------------------------ through a session

    private JoinCode namedLink(String name) {
        JoinCode code = new JoinCode("net", "s", List.of("tcp://r:1"), EtmcConfig.HOST_VIRTUAL_IP, PORT, "");
        code.hostKey = key.publicText();
        code.hostName = name;
        return code;
    }

    @Test
    void aSessionReportsTheConfirmedHost() throws Exception {
        PlayerCert cert = cert(STEVE, IN_A_DAY, MOJANG);
        host(() -> cert);
        EtmcSession joining = new EtmcSession(et);
        joining.setDirectory(directory);
        joining.startLinkInstance(namedLink("Steve"));

        StatusPing.probe(joining.easyTier(), INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS);

        assertEquals("Steve", joining.confirmedHost());
        joining.leave();
    }

    @Test
    void aForgedNameStopsTheJoinAtThePingAndSaysWhy() throws Exception {
        PlayerCert alex = cert(ALEX, IN_A_DAY, MOJANG);
        host(() -> alex);
        EtmcSession joining = new EtmcSession(et);
        joining.setDirectory(directory);
        joining.startLinkInstance(namedLink("Steve"));

        StatusPing.Result r = StatusPing.probe(joining.easyTier(), INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, TIMEOUT_MS);

        assertEquals(StatusPing.Outcome.WRONG_HOST, r.outcome());
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("Alex"), r.reason());
        assertNull(joining.confirmedHost());
        joining.leave();
    }

    @Test
    void anUnusableNameInALinkIsNotCheckedAndNotShown() {
        host(null);
        EtmcSession joining = new EtmcSession(et);
        joining.setDirectory(directory);
        JoinCode code = namedLink("§kSteve");
        joining.startLinkInstance(code);

        StatusPing.probe(joining.easyTier(), INST, EtmcConfig.HOST_VIRTUAL_IP, PORT, 200);

        assertNull(code.invite());
        assertEquals(0, directory.asked.get());
        joining.leave();
    }

    // ------------------------------------------------------------------ Mojang's replies

    @Test
    void readsTheShapeOfMojangsKeyList() throws Exception {
        String der = java.util.Base64.getEncoder().encodeToString(MOJANG.getPublic().getEncoded());
        String json = "{\"profilePropertyKeys\":[],\"playerCertificateKeys\":[{\"publicKey\":\"" + der + "\"}]}";

        assertEquals(List.of(MOJANG.getPublic()), MojangDirectory.parseKeys(json));
    }

    @Test
    void anUnusableKeyListCountsAsCantTellNotAsFake() {
        assertThrows(IOException.class, () -> MojangDirectory.parseKeys("{}"));
        assertThrows(IOException.class, () -> MojangDirectory.parseKeys("{\"playerCertificateKeys\":[]}"));
        assertThrows(IOException.class, () -> MojangDirectory.parseKeys("not json"));
    }

    @Test
    void readsTheNameFromAProfile() throws Exception {
        assertEquals("Steve", MojangDirectory.parseName("{\"id\":\"aaaa\",\"name\":\"Steve\"}"));
        assertThrows(IOException.class, () -> MojangDirectory.parseName("{\"id\":\"aaaa\"}"));
        assertFalse(MojangDirectory.parseName("{\"name\":\"Alex\"}").isEmpty());
    }
}

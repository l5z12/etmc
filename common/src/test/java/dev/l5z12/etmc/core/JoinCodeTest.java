package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The etmc:// link is the mod's wire format: players copy/paste it, so it must round-trip. */
class JoinCodeTest {

    private static JoinCode sample() {
        return new JoinCode("my-net", "s3cret", List.of("tcp://relay.example:11010"),
                "10.126.126.1", 25577, "My SMP");
    }

    @Test
    void linkRoundTrips() {
        JoinCode in = sample();
        JoinCode out = JoinCode.decode(in.encodeLink());

        assertEquals("my-net", out.networkName);
        assertEquals("s3cret", out.networkSecret);
        assertEquals(List.of("tcp://relay.example:11010"), out.relays);
        assertEquals("10.126.126.1", out.hostIp);
        assertEquals(25577, out.hostPort);
        assertEquals("My SMP", out.label);
    }

    @Test
    void decodeToleratesWhitespaceAndMissingPrefix() {
        String link = sample().encodeLink();
        String payload = link.substring(JoinCode.LINK_PREFIX.length());
        assertEquals("my-net", JoinCode.decode("  " + link + "\n").networkName);
        assertEquals("my-net", JoinCode.decode(payload).networkName);
    }

    @Test
    void linkDetectionIsCaseInsensitiveAndTrimmed() {
        assertTrue(JoinCode.isLink("etmc://v1/abc"));
        assertTrue(JoinCode.isLink("  ETMC://V1/abc"));
        assertFalse(JoinCode.isLink("localhost:25565"));
        assertFalse(JoinCode.isLink(null));
    }

    @Test
    void malformedLinksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> JoinCode.decode(null));
        assertThrows(IllegalArgumentException.class, () -> JoinCode.decode("etmc://v1/not base64!!"));
        // valid base64 of JSON without a network name
        assertThrows(IllegalArgumentException.class,
                () -> JoinCode.decode(JoinCode.LINK_PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"label\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void decodeNormalizesOutOfRangeFields() {
        JoinCode jc = new JoinCode("net", "", List.of(), "", 70000, null);
        JoinCode out = JoinCode.decode(jc.encodeLink());
        assertEquals(EtmcConfig.HOST_VIRTUAL_IP, out.hostIp);
        assertEquals(EtmcConfig.DEFAULT_VIRTUAL_PORT, out.hostPort);
        assertEquals(List.of(), out.relays);
    }

    @Test
    void fromTomlReadsIdentityRelaysAndEtmcTable() {
        String toml = """
                # a comment
                dhcp = true
                listeners = []

                [network_identity]
                network_name = "smp"
                network_secret = "pw"

                [[peer]]
                uri = "tcp://a.example:11010"

                [[peer]]
                uri = "tcp://b.example:11010"

                [flags]
                no_tun = true

                [etmc]
                server = "10.126.126.1:25599"
                label = "Server label"
                """;
        JoinCode jc = JoinCode.fromToml(toml);

        assertEquals("smp", jc.networkName);
        assertEquals("pw", jc.networkSecret);
        assertEquals(List.of("tcp://a.example:11010", "tcp://b.example:11010"), jc.relays);
        assertEquals("10.126.126.1", jc.hostIp);
        assertEquals(25599, jc.hostPort);
        assertEquals("Server label", jc.label);
        assertTrue(jc.isValidCandidate());
    }

    @Test
    void fromTomlFallsBackToTheRootIpv4WhenThereIsNoEtmcTable() {
        String toml = """
                ipv4 = "10.9.9.9/24"

                [network_identity]
                network_name = "smp"
                """;
        JoinCode jc = JoinCode.fromToml(toml);
        assertEquals("10.9.9.9", jc.hostIp);
        assertEquals(EtmcConfig.DEFAULT_VIRTUAL_PORT, jc.hostPort);
    }

    @Test
    void fromTomlOfNonsenseIsNotAValidCandidate() {
        assertFalse(JoinCode.fromToml(null).isValidCandidate());
        assertFalse(JoinCode.fromToml("not a config").isValidCandidate());
    }

    @Test
    void configLinksGiveTheirUrlAndCountAsLinks() {
        String link = "  ETMC://v1/config/https://example.com/Etmc.toml?v=1\n";
        assertTrue(JoinCode.isLink(link));
        assertEquals("https://example.com/Etmc.toml?v=1", JoinCode.configUrl(link));
        assertNull(JoinCode.configUrl(sample().encodeLink()));
        assertNull(JoinCode.configUrl(null));
    }

    @Test
    void decodeSaysAConfigLinkHasNoDetailsOfItsOwn() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> JoinCode.decode("etmc://v1/config/https://example.com/etmc.toml"));
        assertTrue(e.getMessage().contains("loads a config"), e.getMessage());
    }

    @Test
    void aConfigLinkFromANewerVersionAsksForAnUpdate() {
        String link = "etmc://v2/config/https://example.com/etmc.toml";
        assertTrue(JoinCode.isLink(link));
        assertNull(JoinCode.configUrl(link));
        var e = assertThrows(IllegalArgumentException.class, () -> JoinCode.decode(link));
        assertTrue(e.getMessage().contains("newer version"), e.getMessage());
    }

    @Test
    void aConfigLinkNeedsItsVersion() {
        String link = "etmc://config/https://example.com/etmc.toml";
        assertNull(JoinCode.configUrl(link));
        assertThrows(IllegalArgumentException.class, () -> JoinCode.decode(link));
    }

    @Test
    void fromConfigKeepsOnlyTheJoinDetails() {
        JoinCode jc = JoinCode.fromConfig("""
                dhcp = true
                listeners = ["tcp://0.0.0.0:11010"]

                [network_identity]
                network_name = "my-smp"
                network_secret = "abc"

                [[peer]]
                uri = "tcp://relay.example.com:11010"

                [etmc]
                server = "10.144.144.1:25566"
                label = "My SMP"
                """);

        assertEquals("my-smp", jc.networkName);
        assertEquals("abc", jc.networkSecret);
        assertEquals(List.of("tcp://relay.example.com:11010"), jc.relays);
        assertEquals("10.144.144.1", jc.hostIp);
        assertEquals(25566, jc.hostPort);
        assertEquals("My SMP", jc.label);
    }

    @Test
    void fromConfigAcceptsAHostedLink() {
        assertEquals("my-net", JoinCode.fromConfig("\n" + sample().encodeLink() + "\n").networkName);
    }

    @Test
    void fromConfigKeepsABlankSecretForPublicServers() {
        JoinCode jc = JoinCode.fromConfig("""
                [network_identity]
                network_name = "public"

                [etmc]
                server = "10.144.144.1:25565"
                """);
        assertEquals("", jc.networkSecret);
        assertEquals("10.144.144.1:25565", jc.label);
    }

    @Test
    void fromConfigRejectsWhatItCantJoin() {
        var noServer = assertThrows(IllegalArgumentException.class, () -> JoinCode.fromConfig("""
                [network_identity]
                network_name = "my-smp"
                """));
        assertTrue(noServer.getMessage().contains("[etmc] server"), noServer.getMessage());
        assertThrows(IllegalArgumentException.class, () -> JoinCode.fromConfig("""
                [etmc]
                server = "10.144.144.1:25565"
                """));
        assertThrows(IllegalArgumentException.class, () -> JoinCode.fromConfig(null));
        // A hosted config link would make one join fetch again, so it stops at the first hop.
        assertThrows(IllegalArgumentException.class,
                () -> JoinCode.fromConfig("etmc://v1/config/https://example.com/etmc.toml"));
    }

    @Test
    void theDisplayLabelPrefersTheLabelAndIsNeverBlank() {
        assertEquals("My SMP", sample().displayLabel());
        assertEquals("my-net", new JoinCode("my-net", "", List.of(), "10.0.0.1", 1, "  ").displayLabel());
        assertEquals("my-net", new JoinCode("my-net", "", List.of(), "10.0.0.1", 1, null).displayLabel());
        assertFalse(new JoinCode().displayLabel().isBlank(), "screens put this straight in a title");
    }
}

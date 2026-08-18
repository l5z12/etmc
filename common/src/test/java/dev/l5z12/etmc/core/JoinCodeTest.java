package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The join code is the mod's wire format: players copy/paste it, so both forms must round-trip. */
class JoinCodeTest {

    private static JoinCode sample() {
        return new JoinCode("my-net", "s3cret", List.of("tcp://relay.example:11010"),
                "10.126.126.1", 25577, "My SMP");
    }

    @Test
    void codeRoundTrips() {
        JoinCode in = sample();
        JoinCode out = JoinCode.decode(in.encode());

        assertEquals("my-net", out.networkName);
        assertEquals("s3cret", out.networkSecret);
        assertEquals(List.of("tcp://relay.example:11010"), out.relays);
        assertEquals("10.126.126.1", out.hostIp);
        assertEquals(25577, out.hostPort);
        assertEquals("My SMP", out.label);
    }

    @Test
    void linkRoundTrips() {
        JoinCode out = JoinCode.decode(sample().encodeLink());
        assertEquals("my-net", out.networkName);
        assertEquals(25577, out.hostPort);
    }

    @Test
    void bothFormsShareOnePayload() {
        JoinCode jc = sample();
        assertEquals(jc.encode().substring(JoinCode.PREFIX.length()),
                jc.encodeLink().substring(JoinCode.LINK_PREFIX.length()));
    }

    @Test
    void decodeToleratesWhitespaceAndMissingPrefix() {
        String payload = sample().encode().substring(JoinCode.PREFIX.length());
        assertEquals("my-net", JoinCode.decode("  " + sample().encode() + "\n").networkName);
        assertEquals("my-net", JoinCode.decode(payload).networkName);
    }

    @Test
    void linkDetectionIsCaseInsensitiveAndTrimmed() {
        assertTrue(JoinCode.isLink("etmc://v1/abc"));
        assertTrue(JoinCode.isLink("  ETMC://V1/abc"));
        assertFalse(JoinCode.isLink("ETMC1:abc"));
        assertFalse(JoinCode.isLink("localhost:25565"));
        assertFalse(JoinCode.isLink(null));
    }

    @Test
    void malformedCodesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> JoinCode.decode(null));
        assertThrows(IllegalArgumentException.class, () -> JoinCode.decode("ETMC1:not base64!!"));
        // valid base64 of JSON without a network name
        assertThrows(IllegalArgumentException.class,
                () -> JoinCode.decode("ETMC1:" + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString("{\"label\":\"x\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    @Test
    void decodeNormalizesOutOfRangeFields() {
        JoinCode jc = new JoinCode("net", "", List.of(), "", 70000, null);
        JoinCode out = JoinCode.decode(jc.encode());
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
    void theDisplayLabelPrefersTheLabelAndIsNeverBlank() {
        assertEquals("My SMP", sample().displayLabel());
        assertEquals("my-net", new JoinCode("my-net", "", List.of(), "10.0.0.1", 1, "  ").displayLabel());
        assertEquals("my-net", new JoinCode("my-net", "", List.of(), "10.0.0.1", 1, null).displayLabel());
        assertFalse(new JoinCode().displayLabel().isBlank(), "screens put this straight in a title");
    }
}

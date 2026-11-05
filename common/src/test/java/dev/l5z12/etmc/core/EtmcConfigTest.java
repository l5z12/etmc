package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TOML etmc hands to {@code run_network_instance}. Two things are load-bearing and were learned
 * the hard way: the instance key is {@code instance_name} (EasyTier silently ignores anything else
 * and defaults the name, which breaks the data plane), and {@code no_tun} must always be on.
 */
class EtmcConfigTest {

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    @Test
    void hostConfigTakesTheFixedVirtualIp() {
        String toml = EtmcConfig.hostToml("etmc-host-bob", "smp", "pw", List.of("tcp://r:11010"));

        assertTrue(toml.contains("instance_name = \"etmc-host-bob\""));
        assertTrue(toml.contains("hostname = \"etmc-host-bob\""));
        assertTrue(toml.contains("ipv4 = \"" + EtmcConfig.HOST_VIRTUAL_IP + "\""));
        assertFalse(toml.contains("dhcp"), "the host must be findable at a fixed address");
        assertTrue(toml.contains("listeners = []"));
        assertTrue(toml.contains("[network_identity]"));
        assertTrue(toml.contains("network_name = \"smp\""));
        assertTrue(toml.contains("network_secret = \"pw\""));
        assertTrue(toml.contains("[flags]\nno_tun = true"));
    }

    @Test
    void joinConfigUsesDhcp() {
        String toml = EtmcConfig.joinToml("etmc-host-ann", "smp", "pw", List.of());
        assertTrue(toml.contains("dhcp = true"));
        assertFalse(toml.contains("ipv4 ="));
        assertTrue(toml.contains("[flags]\nno_tun = true"));
    }

    @Test
    void relaysBecomePeerTablesWithoutBlanksOrRepeats() {
        String toml = EtmcConfig.joinToml("i", "n", "", Arrays.asList(
                "tcp://a:11010", "  ", null, "tcp://a:11010", " tcp://b:11010 "));

        assertEquals(2, count(toml, "[[peer]]"));
        assertTrue(toml.contains("uri = \"tcp://a:11010\""));
        assertTrue(toml.contains("uri = \"tcp://b:11010\""));
    }

    @Test
    void aRelayOnThisMachineTurnsOffDeviceBinding() {
        // Sockets bound to a physical interface can't reach loopback, so a local relay needs this off.
        for (String relay : List.of("tcp://127.0.0.1:11010", "udp://localhost:11010", "tcp://[::1]:11010",
                "TCP://LOCALHOST:1", "wg://127.8.9.10:11011")) {
            String toml = EtmcConfig.hostToml("i", "n", "", List.of("tcp://relay.example:11010", relay));
            assertEquals(1, count(toml, "bind_device = false"), relay);
            assertTrue(toml.contains("[flags]\nno_tun = true\nbind_device = false\n"), relay);
        }
        for (String relay : List.of("tcp://relay.example:11010", "tcp://10.0.0.1:11010", "tcp://128.0.0.1:1",
                "tcp://localhost.example:1", "not a uri")) {
            assertFalse(EtmcConfig.joinToml("i", "n", "", List.of(relay)).contains("bind_device"), relay);
        }
        assertFalse(EtmcConfig.joinToml("i", "n", "", null).contains("bind_device"));
    }

    @Test
    void aMissingSecretIsAnEmptyStringNotNull() {
        assertTrue(EtmcConfig.joinToml("i", "n", null, null).contains("network_secret = \"\""));
    }

    @Test
    void hostileNamesCannotBreakOutOfTheirTomlString() {
        String toml = EtmcConfig.joinToml("i", "ev\"il\nnetwork_name = \"other", "", List.of());

        // The quote and the newline are escaped, so the injected text stays one value on one line.
        assertTrue(toml.contains("network_name = \"ev\\\"il\\nnetwork_name = \\\"other\""));
        assertEquals(1, toml.lines().filter(l -> l.startsWith("network_name")).count(),
                "an injected newline must not be able to declare a second key");
    }
}

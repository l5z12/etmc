package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An imported config comes from a third party, so the two things etmc depends on — a unique
 * {@code instance_name} and {@code no_tun} — must end up set no matter how the file was written,
 * while everything else survives untouched.
 */
class ImportedConfigTest {

    private static final String INST = "etmc-cfg-1234abcd";

    @Test
    void forcesInstanceNameAndNoTunIntoAnExistingFlagsTable() {
        String raw = """
                instance_name = "theirs"
                dhcp = true

                [flags]
                no_tun = false
                latency_first = true
                """;
        ImportedConfig cfg = ImportedConfig.parse(raw, INST, "10.0.0.1:25565");

        assertEquals(INST, cfg.instanceName());
        assertTrue(cfg.toml().startsWith("instance_name = \"" + INST + "\"\n"));
        assertFalse(cfg.toml().contains("\"theirs\""), "the original instance_name must be dropped");
        assertTrue(cfg.toml().contains("[flags]\nno_tun = true"));
        assertFalse(cfg.toml().contains("no_tun = false"));
        assertTrue(cfg.toml().contains("latency_first = true"), "other flags must survive");
    }

    @Test
    void appendsAFlagsTableWhenTheConfigHasNone() {
        ImportedConfig cfg = ImportedConfig.parse("dhcp = true\n", INST, "10.0.0.1");
        assertTrue(cfg.toml().contains("[flags]\nno_tun = true"));
    }

    @Test
    void stripsTheEtmcExtensionTableAndReadsTheServerFromIt() {
        String raw = """
                dhcp = true

                [etmc]
                server = "10.126.126.1:25599"
                label = "Their SMP"

                [network_identity]
                network_name = "smp"
                """;
        ImportedConfig cfg = ImportedConfig.parse(raw, INST, null);

        assertEquals("10.126.126.1", cfg.serverIp());
        assertEquals(25599, cfg.serverPort());
        assertEquals("Their SMP", cfg.label());
        assertFalse(cfg.toml().contains("[etmc]"), "EasyTier would reject the etmc extension table");
        assertFalse(cfg.toml().contains("server ="));
        assertTrue(cfg.toml().contains("network_name = \"smp\""), "tables after [etmc] must survive");
    }

    @Test
    void keepsCommentsAndUnknownKeysVerbatim() {
        String raw = """
                # keep me
                listeners = []
                mtu = 1300
                """;
        ImportedConfig cfg = ImportedConfig.parse(raw, INST, "10.0.0.1");
        assertTrue(cfg.toml().contains("# keep me"));
        assertTrue(cfg.toml().contains("mtu = 1300"));
    }

    @Test
    void theOverrideOnlyAppliesWhenTheConfigHasNoServer() {
        String withServer = "[etmc]\nserver = \"10.1.1.1:25565\"\n";
        assertEquals("10.1.1.1", ImportedConfig.parse(withServer, INST, "10.2.2.2:1").serverIp());
        assertEquals("10.2.2.2", ImportedConfig.parse("dhcp = true\n", INST, "10.2.2.2:1").serverIp());
    }

    @Test
    void labelDefaultsToTheAddress() {
        ImportedConfig cfg = ImportedConfig.parse("dhcp = true\n", INST, "10.2.2.2:1234");
        assertEquals("10.2.2.2:1234", cfg.label());
    }

    @Test
    void aConfigWithNoServerAnywhereIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ImportedConfig.parse("dhcp = true\n", INST, null));
        assertThrows(IllegalArgumentException.class,
                () -> ImportedConfig.parse("dhcp = true\n", INST, "  "));
        assertThrows(IllegalArgumentException.class, () -> ImportedConfig.parse("", INST, "10.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> ImportedConfig.parse(null, INST, "10.0.0.1"));
    }

    @Test
    void normalizedConfigSurvivesAReReadByJoinCode() {
        String raw = """
                dhcp = true

                [network_identity]
                network_name = "smp"
                network_secret = "pw"

                [[peer]]
                uri = "tcp://relay.example:11010"

                [etmc]
                server = "10.126.126.1:25599"
                """;
        ImportedConfig cfg = ImportedConfig.parse(raw, INST, null);

        // The [etmc] table is gone from the normalized TOML, so a re-read finds identity + relays only.
        JoinCode reread = JoinCode.fromToml(cfg.toml());
        assertEquals("smp", reread.networkName);
        assertEquals("pw", reread.networkSecret);
        assertEquals(java.util.List.of("tcp://relay.example:11010"), reread.relays);
    }
}

package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The line-oriented TOML helpers behind config writing and the two importers. */
class TomlTest {

    @Test
    void quoteEscapesWhatTomlForbidsRaw() {
        assertEquals("\"plain\"", Toml.quote("plain"));
        assertEquals("\"\"", Toml.quote(null));
        assertEquals("\"a\\\\b\"", Toml.quote("a\\b"));
        assertEquals("\"say \\\"hi\\\"\"", Toml.quote("say \"hi\""));
        assertEquals("\"a\\nb\\tc\\rd\"", Toml.quote("a\nb\tc\rd"));
        assertEquals("\"\\u0001\"", Toml.quote("\u0001"));
        assertEquals("\"\\u007f\"", Toml.quote("\u007F"));
    }

    @Test
    void quoteLeavesNonAsciiAlone() {
        assertEquals("\"héllo — ✓\"", Toml.quote("héllo — ✓"));
    }

    @Test
    void linesNormalizeEveryLineEndingAndKeepBlanks() {
        assertArrayEquals(new String[] {"a", "b", "c", ""}, Toml.lines("a\r\nb\rc\n"));
    }

    @Test
    void tableNameStripsBracketsCommentsAndCase() {
        assertEquals("flags", Toml.tableName("[flags]"));
        assertEquals("peer", Toml.tableName("[[peer]]"));
        assertEquals("network_identity", Toml.tableName("[Network_Identity]   # note"));
        assertEquals("etmc.extra", Toml.tableName("[etmc.extra]"));
    }

    @Test
    void keyNameIsTheLowerCasedLeftHandSide() {
        assertEquals("uri", Toml.keyName("URI = \"tcp://x\""));
        assertEquals("no_tun", Toml.keyName("no_tun=true"));
        assertEquals("", Toml.keyName("# uri = x"));
        assertEquals("", Toml.keyName("just-text"));
        assertEquals("", Toml.keyName(""));
    }

    @Test
    void stringValueUnwrapsQuotesAndDropsTrailingComments() {
        assertEquals("tcp://x:1", Toml.stringValue("uri = \"tcp://x:1\""));
        assertEquals("tcp://x:1", Toml.stringValue("uri = 'tcp://x:1'"));
        assertEquals("true", Toml.stringValue("no_tun = true   # forced"));
        assertEquals("a # b", Toml.stringValue("k = \"a # b\""), "a # inside quotes is data");
        assertEquals("", Toml.stringValue("novalue"));
    }
}

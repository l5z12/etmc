package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsTest {

    @Test
    void randomSecretsAreDistinctAndHaveNoLookAlikes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String s = Secrets.random();
            assertTrue(s.matches("[a-km-np-z2-9]{12}"), s);
            seen.add(s);
        }
        assertEquals(200, seen.size());
    }

    @Test
    void networkNamesAreRandomWithNoLookAlikes() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String n = Secrets.networkName();
            assertTrue(n.matches("etmc-[a-km-np-z2-9]{6}"), n);
            seen.add(n);
        }
        assertTrue(seen.size() > 190, "only " + seen.size() + " distinct names in 200");
    }

    @Test
    void onlyTheShippedDefaultCounts() {
        assertTrue(Secrets.isDefault("change-me"));
        assertFalse(Secrets.isDefault("x7k2m9qpv4ta"));
    }

    @Test
    void aBlankSecretIsAChoiceForPublicServers() {
        assertFalse(Secrets.isDefault(""));
    }

    @Test
    void replacesOnlyTheSecretLineAndKeepsComments() {
        String yaml = "# a comment about secret: values\nnetwork: \"my-server\"\nsecret: \"change-me\"\n\nrelays: []\n";
        assertEquals("# a comment about secret: values\nnetwork: \"my-server\"\nsecret: \"abc\"\n\nrelays: []\n",
                Secrets.withSecret(yaml, "abc"));
    }

    @Test
    void keepsWindowsLineEndings() {
        assertEquals("network: \"n\"\r\nsecret: \"abc\"\r\nrelays: []\r\n",
                Secrets.withSecret("network: \"n\"\r\nsecret: \"\"\r\nrelays: []\r\n", "abc"));
    }

    @Test
    void addsTheSecretWhenTheConfigHasNone() {
        assertEquals("network: \"n\"\nsecret: \"abc\"\n", Secrets.withSecret("network: \"n\"", "abc"));
    }
}

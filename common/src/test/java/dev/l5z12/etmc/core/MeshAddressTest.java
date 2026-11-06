package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshAddressTest {

    @Test
    void isValidAcceptsOnlyAnIpv4AndAnOptionalPort() {
        for (String s : new String[] {"10.144.144.1", " 10.144.144.1:25565 ", "0.0.0.0:1", "255.255.255.255:65535"}) {
            assertTrue(MeshAddress.isValid(s), s);
        }
        for (String s : new String[] {"not an ip", "example.com:25565", "10.144.144", "10.144.144.256", "010.0.0.1",
                "10.144.144.1:abc", "10.144.144.1:0", "10.144.144.1:65536", "10.144.144.1:", "::1", "", null}) {
            assertFalse(MeshAddress.isValid(s), String.valueOf(s));
        }
    }

    private static final int DEFAULT_PORT = 25565;

    @Test
    void parsesHostAndPort() {
        MeshAddress a = MeshAddress.parse(" 10.126.126.1:25599 ", DEFAULT_PORT);
        assertEquals("10.126.126.1", a.ip());
        assertEquals(25599, a.port());
        assertEquals("10.126.126.1:25599", a.toString());
    }

    @Test
    void aBareHostGetsTheDefaultPort() {
        assertEquals(new MeshAddress("10.126.126.1", DEFAULT_PORT),
                MeshAddress.parse("10.126.126.1", DEFAULT_PORT));
    }

    @Test
    void anUnusablePortFallsBackToTheDefault() {
        assertEquals(DEFAULT_PORT, MeshAddress.parse("10.0.0.1:0", DEFAULT_PORT).port());
        assertEquals(DEFAULT_PORT, MeshAddress.parse("10.0.0.1:70000", DEFAULT_PORT).port());
        assertEquals(DEFAULT_PORT, MeshAddress.parse("10.0.0.1:nope", DEFAULT_PORT).port());
    }

    @Test
    void anIpv6LiteralIsLeftWhole() {
        MeshAddress a = MeshAddress.parse("fd00::1", DEFAULT_PORT);
        assertEquals("fd00::1", a.ip());
        assertEquals(DEFAULT_PORT, a.port());
    }

    @Test
    void nothingParsesToNothing() {
        assertNull(MeshAddress.parse(null, DEFAULT_PORT));
        assertNull(MeshAddress.parse("   ", DEFAULT_PORT));
    }
}

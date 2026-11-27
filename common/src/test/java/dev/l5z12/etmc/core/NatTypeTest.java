package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.l5z12.etmc.core.NatType.FULL_CONE;
import static dev.l5z12.etmc.core.NatType.OPEN_INTERNET;
import static dev.l5z12.etmc.core.NatType.PORT_RESTRICTED;
import static dev.l5z12.etmc.core.NatType.SYMMETRIC;
import static dev.l5z12.etmc.core.NatType.SYMMETRIC_EASY_DEC;
import static dev.l5z12.etmc.core.NatType.SYMMETRIC_EASY_INC;
import static dev.l5z12.etmc.core.NatType.SYM_UDP_FIREWALL;
import static dev.l5z12.etmc.core.NatType.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NatTypeTest {

    @Test
    void readsEasyTiersNamesAndNumbers() {
        assertEquals(PORT_RESTRICTED, NatType.of("PortRestricted"));
        assertEquals(NatType.NO_PAT, NatType.of("NoPAT"));
        assertEquals(SYMMETRIC_EASY_DEC, NatType.of("symmetriceasydec"));
        assertEquals(SYMMETRIC, NatType.of("6"), "the protobuf number");
        assertEquals(SYMMETRIC_EASY_DEC, NatType.of("9"));
        for (String bad : new String[] {null, "", "Cone", "10", "-1", "1.5"}) {
            assertEquals(UNKNOWN, NatType.of(bad), "input: " + bad);
        }
    }

    @Test
    void ordinalsAreEasyTiersNumbers() {
        assertEquals(0, UNKNOWN.ordinal());
        assertEquals(5, PORT_RESTRICTED.ordinal());
        assertEquals(7, SYM_UDP_FIREWALL.ordinal());
        assertEquals(9, SYMMETRIC_EASY_DEC.ordinal());
    }

    @Test
    void gradesFollowWhatEasyTierCanPunchThrough() {
        assertEquals(NatType.Grade.OPEN, OPEN_INTERNET.grade());
        assertEquals(NatType.Grade.OPEN, PORT_RESTRICTED.grade());
        assertEquals(NatType.Grade.MODERATE, SYMMETRIC_EASY_INC.grade());
        assertEquals(NatType.Grade.STRICT, SYMMETRIC.grade());
        assertEquals(NatType.Grade.STRICT, SYM_UDP_FIREWALL.grade());
        assertEquals(NatType.Grade.UNKNOWN, UNKNOWN.grade());
    }

    /** EasyTier's UdpNatType::get_punch_hole_method, from both ends. */
    @Test
    void onlyTwoSymmetricNatsWithAHardOneBlockDirectLinks() {
        assertTrue(SYMMETRIC.blocksDirect(SYMMETRIC));
        assertTrue(SYMMETRIC.blocksDirect(SYMMETRIC_EASY_INC));
        assertTrue(SYMMETRIC_EASY_DEC.blocksDirect(SYM_UDP_FIREWALL));

        assertFalse(SYMMETRIC_EASY_INC.blocksDirect(SYMMETRIC_EASY_DEC), "two predictable ones punch through");
        assertFalse(SYMMETRIC.blocksDirect(PORT_RESTRICTED), "symmetric to cone");
        assertFalse(FULL_CONE.blocksDirect(SYMMETRIC));
        assertFalse(SYMMETRIC.blocksDirect(OPEN_INTERNET));
        assertFalse(SYMMETRIC.blocksDirect(UNKNOWN), "nothing is known about the other end");
        assertFalse(UNKNOWN.blocksDirect(SYMMETRIC));
        assertFalse(SYMMETRIC.blocksDirect(null));
    }

    @Test
    void gradesBlockEachOtherTheSameWay() {
        NatType.Grade open = NatType.Grade.OPEN, moderate = NatType.Grade.MODERATE, strict = NatType.Grade.STRICT;
        assertFalse(open.blocksDirect(strict));
        assertFalse(moderate.blocksDirect(moderate));
        assertTrue(moderate.blocksDirect(strict));
        assertTrue(strict.blocksDirect(moderate));
        assertTrue(strict.blocksDirect(strict));
        assertFalse(strict.blocksDirect(NatType.Grade.UNKNOWN));
    }

    @Test
    void namesTheTypeForPeopleWhoKnowNats() {
        assertEquals("Port-restricted cone", PORT_RESTRICTED.label());
        assertEquals("Symmetric, increasing ports", SYMMETRIC_EASY_INC.label());
        assertEquals("Endpoint-independent", PORT_RESTRICTED.mapping());
        assertEquals("Address and port-dependent", PORT_RESTRICTED.filtering());
        assertEquals("Endpoint-dependent", SYMMETRIC.mapping());
        assertNull(SYMMETRIC.filtering(), "EasyTier's check doesn't test a symmetric NAT's filtering");
        assertNull(UNKNOWN.mapping());
    }

    @Test
    void saysWhatItMeansForPeopleWhoDont() {
        assertEquals("Direct connections with everyone", PORT_RESTRICTED.grade().summary());
        assertEquals("Direct connections with most players", SYMMETRIC_EASY_DEC.grade().summary());
        assertEquals("Direct connections with some players", SYMMETRIC.grade().summary());
        assertEquals("Recommended, most games connect directly", SYMMETRIC_EASY_INC.grade().verdict());
        assertEquals("Let a friend host if you can", SYMMETRIC.grade().hosting());
        for (NatType.Grade g : NatType.Grade.values()) {
            for (String plain : new String[] {g.summary(), g.meaning(), g.verdict(), g.hosting()}) {
                assertFalse(plain.matches("(?i).*\\b(open|moderate|strict|nat)\\b.*"), "no jargon: " + plain);
            }
        }
    }

    @Test
    void portsAreOneOrARangeAndAddressesAreSplitByFamily() {
        assertEquals("27623", new NatInfo(PORT_RESTRICTED, UNKNOWN, 27623, 27623).ports());
        assertEquals("40100–40112", new NatInfo(SYMMETRIC, UNKNOWN, 40100, 40112).ports());
        assertNull(NatInfo.UNKNOWN.ports());
        assertNull(NatInfo.UNKNOWN.ipv4());
        assertEquals("100.64.0.1, 100.64.0.2", new NatInfo(SYMMETRIC, UNKNOWN, 1, 2,
                List.of("100.64.0.1", "2001:db8::1", "100.64.0.2")).ipv4(), "a pool of addresses");
    }
}

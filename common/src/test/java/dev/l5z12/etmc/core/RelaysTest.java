package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaysTest {

    @Test
    void everySeparatorAPlayerMightUseWorks() {
        assertEquals(List.of("tcp://a:1", "udp://b:2", "ws://c", "tcp://d:4"),
                Relays.parse(" tcp://a:1, udp://b:2\nws://c ; tcp://d:4  tcp://a:1 "));
        assertEquals(List.of(), Relays.parse(null));
    }

    @Test
    void ordinaryRelayAddressesPass() {
        for (String ok : List.of("tcp://relay.example.com:11010", "udp://1.2.3.4:11010", "wss://relay.example.com",
                "ws://relay.example.com:80/path", "quic://[2001:db8::1]:11012", "TCP://Relay.Example:11010")) {
            assertNull(Relays.problem(ok), ok);
        }
    }

    @Test
    void aJoinLinkPastedAsARelayIsCaught() {
        String link = new JoinCode("n", "s", List.of(), "10.126.126.1", 25565, "").encodeLink();
        String p = Relays.problem(link);
        assertNotNull(p);
        assertTrue(p.contains("join link"), p);
        assertNotNull(Relays.problem("ETMC1:abc"));
    }

    @Test
    void commonMistakesAreExplained() {
        assertTrue(Relays.problem("relay.example.com:11010").contains("no protocol"));
        assertTrue(Relays.problem("tcp://relay.example.com").contains("no port"));
        assertTrue(Relays.problem("ftp://relay.example.com:21").contains("isn't a relay protocol"));
        assertTrue(Relays.problem("tcp://:11010").contains("no host"));
        assertNotNull(Relays.problem("tcp://relay.example.com:99999"));
        assertNotNull(Relays.problem(" "));
    }

    @Test
    void firstProblemReportsTheBadEntry() {
        assertNull(Relays.firstProblem(List.of("tcp://a:1", "udp://b:2")));
        assertTrue(Relays.firstProblem(List.of("tcp://a:1", "b:2")).contains("'b:2'"));
    }
}

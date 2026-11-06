package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A server that is its own relay, for owners whose machine players can reach. */
class DirectHostTest {

    private static final String ADDRESS = "tcp://play.example.com:11010";

    private final FakeEasyTier et = new FakeEasyTier();

    private JoinCode host(String publicAddress) {
        EtmcSession session = new EtmcSession(et);
        session.setPublicAddress(publicAddress);
        return session.host(25565, "net", "s", List.of("tcp://other.relay:1"), 25565);
    }

    @Test
    void theLinkLeadsOnlyToTheServer() {
        JoinCode code = host(ADDRESS);

        assertEquals(List.of(ADDRESS), code.relays);
        assertTrue(code.isDirect());
        assertTrue(JoinCode.decode(code.encodeLink()).isDirect());
    }

    @Test
    void theServerListensOnThatPort() {
        host(ADDRESS);

        assertTrue(et.started.get(0).contains("\"tcp://0.0.0.0:11010\""), et.started.get(0));
    }

    @Test
    void aJoinerLinksToNobodyElse() {
        JoinCode code = host(ADDRESS);

        new EtmcSession(et).startLinkInstance(code);
        new EtmcSession(et).join(code);

        assertTrue(et.started.get(1).contains("disable_p2p = true"), et.started.get(1));
        assertTrue(et.started.get(2).contains("disable_p2p = true"), et.started.get(2));
    }

    @Test
    void withoutOneNothingChanges() {
        JoinCode code = host(null);

        assertNull(code.direct);
        assertEquals(List.of("tcp://other.relay:1"), code.relays);
        assertFalse(code.encodeLink().contains("direct"));
        new EtmcSession(et).startLinkInstance(code);
        assertFalse(et.started.get(1).contains("disable_p2p"), "P2P is the normal way to play");
    }

    @Test
    void aHostedConfigCarriesIt() {
        String toml = """
                [network_identity]
                network_name = "net"

                [[peer]]
                uri = "tcp://play.example.com:11010"

                [etmc]
                server = "10.126.126.1:25565"
                direct = true
                """;

        assertTrue(JoinCode.fromConfig(toml).isDirect());
    }

    @Test
    void anAddressWithoutAPortIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> EtmcConfig.listenerFor("tcp://play.example.com"));
        assertThrows(IllegalArgumentException.class, () -> EtmcConfig.listenerFor("play.example.com:11010"));
    }
}

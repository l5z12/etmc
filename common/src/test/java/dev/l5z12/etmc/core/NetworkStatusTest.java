package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of {@code collect_network_infos} output. The shapes here mirror what the real FFI returns
 * (prost/serde JSON): a u32 IPv4 inside {@code address.addr}, and per-connection {@code latency_us}.
 */
class NetworkStatusTest {

    /** 10.126.126.x as the u32 the FFI reports. */
    private static long addr(int last) {
        return (10L << 24) | (126L << 16) | (126L << 8) | last;
    }

    private static final String JSON = """
            {
              "running": true,
              "my_node_info": { "virtual_ipv4": { "address": { "addr": %d }, "network_length": 24 } },
              "peer_route_pairs": [
                { "route": { "ipv4_addr": { "address": { "addr": %d } }, "hostname": "alice", "cost": 1 },
                  "peer": { "conns": [ { "stats": { "latency_us": 24000 } },
                                       { "stats": { "latency_us": 26000 } } ] } },
                { "route": { "ipv4_addr": { "address": { "addr": %d } }, "hostname": "", "peer_id": 7, "cost": 2 },
                  "peer": { "conns": [] } },
                { "route": { "hostname": "public-relay", "cost": 2 }, "peer": { "conns": [] } }
              ]
            }
            """.formatted(addr(1), addr(2), addr(3));

    @Test
    void parsesNodeAndPeers() {
        NetworkStatus st = NetworkStatus.parse(JSON);

        assertTrue(st.running());
        assertEquals("10.126.126.1", st.virtualIp());
        assertEquals(2, st.peerCount(), "the relay node without a virtual IP is not a game peer");
        assertEquals(1, st.directPeerCount());

        NetworkStatus.Peer direct = st.peers().get(0);
        assertEquals("alice", direct.hostname());
        assertEquals("10.126.126.2", direct.ipv4());
        assertEquals(25, direct.latencyMs(), "averaged from conns[].stats.latency_us");
        assertFalse(direct.relay());
        assertEquals(1, direct.cost());

        NetworkStatus.Peer relayed = st.peers().get(1);
        assertEquals("peer-7", relayed.hostname(), "no hostname yet -> synthesized from peer_id");
        assertEquals("10.126.126.3", relayed.ipv4());
        assertEquals(-1, relayed.latencyMs(), "no direct connection -> unknown, not a routing cost");
        assertTrue(relayed.relay());
    }

    @Test
    void badInputYieldsAnEmptyStatusRatherThanThrowing() {
        for (String bad : new String[] {null, "", "   ", "not json", "[1,2,3]", "{\"peer_route_pairs\":42}"}) {
            NetworkStatus st = NetworkStatus.parse(bad);
            assertEquals(0, st.peerCount(), "input: " + bad);
            assertNull(st.virtualIp(), "input: " + bad);
        }
    }

    @Test
    void emptyStatusIsIdle() {
        NetworkStatus st = NetworkStatus.empty();
        assertFalse(st.running());
        assertNull(st.virtualIp());
        assertEquals(0, st.peerCount());
        assertEquals(0, st.directPeerCount());
    }

    @Test
    void theSnapshotIsFrozen() {
        NetworkStatus st = NetworkStatus.parse(JSON);
        assertThrows(UnsupportedOperationException.class,
                () -> st.peers().add(new NetworkStatus.Peer("x", "10.0.0.1", 1, false, 1)));
    }

    @Test
    void aNullPeerListIsTreatedAsEmpty() {
        assertEquals(0, new NetworkStatus(true, null, null, null).peerCount());
    }
}

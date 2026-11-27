package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NatCheckTest {

    private static final String STUN_DONE = "{\"running\":true,\"my_node_info\":{\"stun_info\":"
            + "{\"udp_nat_type\":\"SymmetricEasyInc\",\"tcp_nat_type\":\"FullCone\"}}}";

    @Test
    void readsTheTypeAndRemovesItsInstance() throws InterruptedException {
        FakeEasyTier et = new FakeEasyTier();
        et.infos.put("etmc-nat-test", STUN_DONE);

        assertEquals(NatType.SYMMETRIC_EASY_INC, NatCheck.run(et, "etmc-nat-test", 5_000));
        assertEquals(List.of("etmc-nat-test"), et.deleted);
    }

    /** It must never join anything: no relays to dial, nothing to listen on, no adapter. */
    @Test
    void itsInstanceTalksToNobody() throws InterruptedException {
        FakeEasyTier et = new FakeEasyTier();
        NatCheck.run(et, "etmc-nat-test", 0);

        String toml = et.started.get(0);
        assertTrue(toml.contains("instance_name = \"etmc-nat-test\""));
        assertTrue(toml.contains("listeners = []"));
        assertTrue(toml.contains("no_tun = true"));
        assertFalse(toml.contains("[[peer]]"));
    }

    @Test
    void givesUpAsUnknownAndStillRemovesItsInstance() throws InterruptedException {
        FakeEasyTier et = new FakeEasyTier();
        et.infos.put("etmc-nat-test", "{\"running\":true,\"my_node_info\":{\"stun_info\":{}}}");

        long t0 = System.nanoTime();
        assertEquals(NatType.UNKNOWN, NatCheck.run(et, "etmc-nat-test", 300));
        assertTrue(System.nanoTime() - t0 >= 300_000_000L, "it waits the whole timeout");
        assertEquals(List.of("etmc-nat-test"), et.deleted);
    }

    @Test
    void anInstanceThatWontStartIsAnError() {
        FakeEasyTier et = new FakeEasyTier();
        et.runFailure = new IllegalStateException("no");
        assertThrows(IllegalStateException.class, () -> NatCheck.run(et, 1_000));
    }

    @Test
    void aRunningSessionWaitsForItsOwnAnswer() throws InterruptedException {
        FakeEasyTier et = new FakeEasyTier();
        EtmcSession s = new EtmcSession(et);
        s.host(25565, "net", "secret", List.of("tcp://relay.example:11010"), EtmcConfig.DEFAULT_VIRTUAL_PORT);
        String inst = s.instName();
        et.infos.put(inst, STUN_DONE);
        int started = et.started.size();

        assertEquals(NatType.SYMMETRIC_EASY_INC, s.natType(5_000));
        assertEquals(started, et.started.size(), "no second instance");
        s.leave();
    }
}

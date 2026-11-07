package dev.l5z12.etmc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Players post these logs in public, so what matters most is what stays out of them. */
class EtmcLogTest {

    private static final String SECRET = "a-network-secret-nobody-should-see";

    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final FakeEasyTier et = new FakeEasyTier();

    @BeforeEach
    void capture() {
        EtmcLog.to((level, message, thrown) -> lines.add(level + " " + message));
    }

    @AfterEach
    void release() {
        EtmcLog.to(null);
    }

    private String log() {
        return String.join("\n", lines);
    }

    @Test
    void linesReachWhereverTheyAreSent() {
        EtmcLog.info("one");
        EtmcLog.warn("two");

        assertEquals(List.of("INFO one", "WARNING two"), lines);
    }

    @Test
    void hostingAndJoiningLeaveTheSecretsOut() {
        HostKey key = HostKey.generate();
        EtmcSession host = new EtmcSession(et);
        host.setHostKey(key);
        JoinCode code = host.host(25565, "net", SECRET, List.of("tcp://relay:1"), 25565);

        new EtmcSession(et).startLinkInstance(code);

        assertTrue(log().contains(key.shortText()), log());
        assertFalse(log().contains(SECRET), log());
        assertFalse(log().contains(code.encodeLink()), log());
        assertFalse(log().contains(key.publicText()), log());
    }

    @Test
    void aJoinWithNothingToCheckSaysSo() {
        JoinCode code = new EtmcSession(et).host(25565, "net", SECRET, List.of("tcp://relay:1"), 25565);

        new EtmcSession(et).startLinkInstance(code);

        assertTrue(log().contains("no host key"), log());
    }
}

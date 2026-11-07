package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final ConnectionLimiter limiter = new ConnectionLimiter(now::get);

    private void burn(String peer, int times) {
        for (int i = 0; i < times; i++) {
            assertTrue(limiter.admit(peer), "connection " + i);
            limiter.started();
        }
    }

    @Test
    void aRealJoinIsNeverAffected() {
        burn("10.126.126.2", 2); // one to ping, one to play
        assertTrue(limiter.admit("10.126.126.2"));
    }

    @Test
    void aPeerIsCutOffPastItsBurst() {
        burn("10.126.126.2", ConnectionLimiter.BURST);

        assertFalse(limiter.admit("10.126.126.2"));
    }

    @Test
    void onePeerFloodingDoesNotShutOutAnother() {
        burn("10.126.126.2", ConnectionLimiter.BURST);

        assertTrue(limiter.admit("10.126.126.3"));
    }

    @Test
    void aPeerGetsBackInAfterWaiting() {
        burn("10.126.126.2", ConnectionLimiter.BURST);
        assertFalse(limiter.admit("10.126.126.2"));

        now.addAndGet(1_500L);

        assertTrue(limiter.admit("10.126.126.2"));
    }

    @Test
    void waitingDoesNotSaveUpMoreThanOneBurst() {
        now.addAndGet(3_600_000L);
        burn("10.126.126.2", ConnectionLimiter.BURST);

        assertFalse(limiter.admit("10.126.126.2"));
    }

    @Test
    void aFloodIsReportedNowAndThenNotLineByLine() {
        assertEquals(1, limiter.refused(), "the first refusal is worth a line");
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, limiter.refused());
        }

        now.addAndGet(ConnectionLimiter.REPORT_EVERY_MS);

        assertEquals(1001, limiter.refused(), "nothing goes uncounted");
    }

    @Test
    void manyPeersTogetherCannotPileUpUnfinishedHandshakes() {
        for (int i = 0; i < ConnectionLimiter.MAX_STARTING; i++) {
            assertTrue(limiter.admit("10.0.0." + i));
        }
        assertFalse(limiter.admit("10.1.1.1"), "the limit is reached");

        limiter.started();

        assertTrue(limiter.admit("10.1.1.1"), "a finished handshake frees a place");
    }
}

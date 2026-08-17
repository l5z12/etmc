package dev.l5z12.etmc.core;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Waits for something another thread will do. etmc's bridges pump bytes on their own threads, so a
 * test that asserts immediately after acting would race them; polling with a generous ceiling keeps
 * these tests honest without making them timing-sensitive on a slow CI runner.
 */
final class Await {

    private static final long TIMEOUT_MS = 5_000;
    private static final long POLL_MS = 10;

    private Await() {}

    static void until(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            sleep();
        }
        if (!condition.getAsBoolean()) {
            fail("timed out after " + TIMEOUT_MS + "ms waiting for: " + what);
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting");
        }
    }
}

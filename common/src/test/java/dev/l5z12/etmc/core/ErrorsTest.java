package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorsTest {

    @Test
    void unwrapsToTheInnermostCause() {
        IllegalStateException root = new IllegalStateException("data_plane_tcp_bind failed");
        Throwable wrapped = new CompletionException(new IOException("join failed", root));

        assertSame(root, Errors.root(wrapped));
        assertEquals("data_plane_tcp_bind failed", Errors.message(wrapped));
    }

    @Test
    void fallsBackToTheTypeWhenThereIsNoMessage() {
        assertTrue(Errors.message(new IllegalStateException()).contains("IllegalStateException"));
        assertTrue(Errors.message(new IllegalStateException("  ")).contains("IllegalStateException"));
    }

    @Test
    void handlesNullAndSelfReferencingCauses() {
        assertEquals("unknown error", Errors.message(null));

        Throwable self = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals("loop", Errors.message(self));
    }

    /**
     * A chain that cycles back through another link, rather than straight to itself. This runs on the
     * client thread to build an error message, so walking it must terminate, not freeze the game.
     */
    @Test
    void handlesACauseChainThatCyclesBackAround() {
        Throwable[] pair = new Throwable[2];
        pair[0] = new RuntimeException("outer") {
            @Override
            public synchronized Throwable getCause() {
                return pair[1];
            }
        };
        pair[1] = new RuntimeException("inner") {
            @Override
            public synchronized Throwable getCause() {
                return pair[0];
            }
        };

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            String message = Errors.message(pair[0]);
            assertTrue(message.equals("outer") || message.equals("inner"), "got: " + message);
        });
    }
}

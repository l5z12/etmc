package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
}

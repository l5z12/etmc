package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

/**
 * Best-effort close helpers for the etmc I/O paths.
 *
 * <p>Every close in this package runs while something has already ended — a peer vanished, a session
 * is being torn down, a bridge is unwinding — so a failure to close tells us nothing we could act on.
 * What it can do is damage: an FFI close that throws inside an accept loop kills the loop, and the
 * host silently stops taking peers. Swallowing it in one place keeps that from happening, and keeps
 * the interesting code free of five-line try/catch blocks.
 */
final class Io {

    private Io() {}

    /** Closes a socket or stream, ignoring any failure. Null-safe. */
    static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    /** Releases an EasyTier data-plane stream handle, ignoring any failure. */
    static void closeStream(EasyTier et, long handle) {
        if (et == null || handle == 0) return;
        try {
            et.tcpClose(handle);
        } catch (Throwable ignored) {
        }
    }
}

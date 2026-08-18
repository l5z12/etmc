package dev.l5z12.etmc.core;

/**
 * Turns a thrown exception into something worth showing a player.
 *
 * <p>etmc's failures arrive wrapped: an EasyTier FFI error surfaces through a
 * {@code CompletionException} from the async host/join pipeline, often around an {@code IOException}
 * around the real message. The outermost {@code toString()} is noise ("java.util.concurrent.
 * CompletionException: java.lang.IllegalStateException: …"), so the UI always reports the innermost
 * cause instead.
 */
public final class Errors {

    /** Cause chains are a handful deep in practice; the bound is only there to stop a cyclic one. */
    private static final int MAX_DEPTH = 32;

    private Errors() {}

    /**
     * The innermost cause of {@code t} (or {@code t} itself). Bounded: this runs on the client thread
     * to build an error message, and a cause chain that loops back on itself (A caused by B caused by
     * A) would otherwise freeze the game rather than report the failure.
     */
    public static Throwable root(Throwable t) {
        Throwable c = t;
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            Throwable cause = c.getCause();
            if (cause == null || cause == c) break;
            c = cause;
        }
        return c;
    }

    /** A short, user-facing description of {@code t}: its root cause's message, else its type. */
    public static String message(Throwable t) {
        if (t == null) return "unknown error";
        Throwable c = root(t);
        String msg = c.getMessage();
        return msg == null || msg.isBlank() ? c.toString() : msg;
    }
}

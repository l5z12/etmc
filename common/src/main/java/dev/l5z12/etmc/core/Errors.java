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

    private Errors() {}

    /** The innermost cause of {@code t} (or {@code t} itself). */
    public static Throwable root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
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

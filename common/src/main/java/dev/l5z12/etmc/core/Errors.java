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

    /**
     * A short, user-facing description of {@code t}: its root cause's message (else its type), with the
     * library's function-name prefixes turned into what they mean for the player.
     */
    public static String message(Throwable t) {
        if (t == null) return Msg.tr("etmc.error.unknown", "unknown error");
        Throwable c = root(t);
        String msg = c.getMessage();
        if (msg == null || msg.isBlank()) return c.toString();
        return friendly(msg);
    }

    /** Rewrites raw EasyTier lifecycle errors ("run_network_instance failed: ...") for players. */
    static String friendly(String msg) {
        String m = msg.trim();
        String lower = m.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("data plane net is not ready")) {
            return Msg.tr("etmc.error.not_ready", "the EasyTier network isn't ready yet (no route to the host so far)");
        }
        for (String prefix : PREFIXES) {
            if (m.startsWith(prefix)) {
                String detail = m.substring(prefix.length()).trim();
                if (detail.startsWith("anyhow error:")) detail = detail.substring("anyhow error:".length()).trim();
                String what = meaning(prefix);
                return detail.isEmpty() ? what : what + ": " + detail;
            }
        }
        return m;
    }

    private static final String[] PREFIXES = {
            "run_network_instance failed:", "parse_config failed:",
            "delete_network_instance failed:", "collect_network_infos failed:",
    };

    /** What a failed library call means for the player (one literal Msg.tr each, for LangFilesTest). */
    private static String meaning(String prefix) {
        switch (prefix) {
            case "run_network_instance failed:":
                return Msg.tr("etmc.error.start", "couldn't start the EasyTier network");
            case "parse_config failed:":
                return Msg.tr("etmc.error.config", "EasyTier rejected the network settings");
            case "delete_network_instance failed:":
                return Msg.tr("etmc.error.stop", "couldn't stop the EasyTier network");
            default:
                return Msg.tr("etmc.error.status", "couldn't read the EasyTier network status");
        }
    }
}

package dev.l5z12.etmc.core;

/**
 * Where the core's log lines go. Left alone that's {@link System.Logger}, which reaches a Paper
 * server's log. A client points this at the game's log, the one a player can attach to a bug report.
 *
 * <p>These lines get posted in public, so never log a network secret, a link, or anything private
 * from a key or certificate. {@link HostKey#shortText} is enough to tell host keys apart. Everything
 * here is info or above, because the game's log leaves out debug. Keep it to a line per connection
 * at most, and never one per packet.
 */
public final class EtmcLog {

    /** Receives each line. {@code thrown} is usually {@code null}. */
    public interface Sink {
        void log(System.Logger.Level level, String message, Throwable thrown);
    }

    private static final System.Logger FALLBACK = System.getLogger("etmc");
    private static final Sink DEFAULT = (level, message, thrown) -> FALLBACK.log(level, message, thrown);

    private static volatile Sink sink = DEFAULT;

    private EtmcLog() {}

    /** Sends lines to {@code target} from now on; {@code null} goes back to the default. */
    public static void to(Sink target) {
        sink = target == null ? DEFAULT : target;
    }

    public static void info(String message) {
        sink.log(System.Logger.Level.INFO, message, null);
    }

    public static void warn(String message) {
        sink.log(System.Logger.Level.WARNING, message, null);
    }

    public static void warn(String message, Throwable thrown) {
        sink.log(System.Logger.Level.WARNING, message, thrown);
    }
}

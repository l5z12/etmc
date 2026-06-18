package dev.l5z12.etmc.ffi;

import java.nio.file.Path;
import java.util.Map;

/**
 * Backend-agnostic binding to the EasyTier FFI C-ABI library ({@code easytier_ffi}).
 *
 * <p>Two implementations exist so etmc runs across the whole Java range Minecraft uses:
 * <ul>
 *   <li>{@link FfmEasyTier} — {@code java.lang.foreign} (FFM/Panama), Java 19+ (MC 1.20.5+, 1.21.x, 26.x);</li>
 *   <li>{@code JnaEasyTier} — JNA, for Java 17 runtimes (MC 1.19.x and 1.20.1–1.20.4) where FFM is absent.</li>
 * </ul>
 *
 * <p>Obtain the process-wide instance via {@link #load(Path)} / {@link #get()}. The native library
 * keeps a single global instance manager, so this is a singleton.
 *
 * <p>Data-plane reads/writes use plain {@code byte[]} so callers never touch native memory; each
 * backend marshals into its own native buffers internally.
 */
public interface EasyTier {

    /** Result of a bind/connect: a stream/listener handle plus the reported local address. */
    record Bind(long handle, String localIp, int localPort) {}

    /** Result of an accept: a stream handle plus the local and peer addresses. */
    record Accept(long handle, String localIp, int localPort, String peerIp, int peerPort) {}

    // ------------------------------------------------------------------ lifecycle
    void parseConfig(String toml);
    void runNetworkInstance(String toml);
    void deleteNetworkInstance(String... names);
    Map<String, String> collectNetworkInfos(int max);
    String lastError();

    // ------------------------------------------------------------------ data plane (TCP)
    Bind tcpBind(String inst, int localPort, long timeoutMs);
    Accept tcpAccept(long listener, long timeoutMs);
    Bind tcpConnect(String inst, String dstIp, int dstPort, long timeoutMs);

    /** Reads up to {@code len} bytes into {@code buf}; returns bytes read (0 = EOF-ish), or -1 on failure. */
    int tcpRead(long handle, byte[] buf, int len, long timeoutMs);

    /** Writes {@code len} bytes from {@code data}; returns &gt;=0 on success, -1 on failure. */
    int tcpWrite(long handle, byte[] data, int len, long timeoutMs);

    void tcpClose(long handle);
    void tcpListenerClose(long handle);

    // ------------------------------------------------------------------ process-wide singleton

    /** Loads the native library at {@code lib} with the best available backend. Idempotent. */
    static EasyTier load(Path lib) {
        synchronized (Holder.class) {
            if (Holder.instance == null) {
                if (FfmEasyTier.isSupported()) {
                    Holder.instance = FfmEasyTier.create(lib);
                } else {
                    // JNA backend (Java 17) is wired in a later stage of multi-version support.
                    throw new EasyTierException(
                            "FFM (java.lang.foreign) unavailable on this JVM and the JNA backend is not wired yet",
                            Panama.initError());
                }
            }
            return Holder.instance;
        }
    }

    static EasyTier get() {
        EasyTier i = Holder.instance;
        if (i == null) throw new EasyTierException("EasyTier native library not loaded yet");
        return i;
    }

    static boolean isLoaded() {
        return Holder.instance != null;
    }

    /** Holds the singleton (interfaces can't have mutable static fields directly). */
    final class Holder {
        private Holder() {}
        static volatile EasyTier instance;
    }
}

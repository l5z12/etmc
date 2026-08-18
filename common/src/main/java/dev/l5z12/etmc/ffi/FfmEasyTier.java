package dev.l5z12.etmc.ffi;

import dev.l5z12.etmc.ffi.Panama.Kind;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link EasyTier} backend built on {@code java.lang.foreign} (FFM) via the {@link Panama} reflection
 * facade. Used on Java 19+ runtimes (MC 1.20.5+, 1.21.x, 26.x).
 *
 * <p>Data-plane reads/writes accept {@code byte[]} and marshal through a per-thread, growable native
 * scratch buffer so the hot path avoids per-call arena churn while keeping the public API allocation-free
 * of native types.
 */
public final class FfmEasyTier implements EasyTier {

    private static final long KV_PAIR_BYTES = 16;
    private static final long PTR_BYTES = 8;
    private static final int CSTR_CAP = 4 * 1024 * 1024;

    // lifecycle
    private final MethodHandle parseConfig;
    private final MethodHandle runNetworkInstance;
    private final MethodHandle deleteInstance;
    private final MethodHandle collectInfos;
    private final MethodHandle getErrorMsg;
    private final MethodHandle freeString;

    // data plane (TCP)
    private final MethodHandle tcpConnect;
    private final MethodHandle tcpBind;
    private final MethodHandle tcpAccept;
    private final MethodHandle tcpRead;
    private final MethodHandle tcpWrite;
    private final MethodHandle tcpCloseH;
    private final MethodHandle tcpListenerCloseH;

    private FfmEasyTier(Object lookup) {
        parseConfig = Panama.downcall(lookup, "parse_config", Kind.INT, Kind.PTR);
        runNetworkInstance = Panama.downcall(lookup, "run_network_instance", Kind.INT, Kind.PTR);
        deleteInstance = Panama.downcall(lookup, "delete_network_instance", Kind.INT, Kind.PTR, Kind.LONG);
        collectInfos = Panama.downcall(lookup, "collect_network_infos", Kind.INT, Kind.PTR, Kind.LONG);
        getErrorMsg = Panama.downcall(lookup, "get_error_msg", Kind.VOID, Kind.PTR);
        freeString = Panama.downcall(lookup, "free_string", Kind.VOID, Kind.PTR);

        tcpConnect = Panama.downcall(lookup, "data_plane_tcp_connect",
                Kind.LONG, Kind.PTR, Kind.PTR, Kind.SHORT, Kind.LONG, Kind.PTR, Kind.PTR);
        tcpBind = Panama.downcall(lookup, "data_plane_tcp_bind",
                Kind.LONG, Kind.PTR, Kind.SHORT, Kind.LONG, Kind.PTR, Kind.PTR);
        tcpAccept = Panama.downcall(lookup, "data_plane_tcp_accept",
                Kind.LONG, Kind.LONG, Kind.LONG, Kind.PTR, Kind.PTR, Kind.PTR, Kind.PTR);
        tcpRead = Panama.downcall(lookup, "data_plane_tcp_read",
                Kind.INT, Kind.LONG, Kind.PTR, Kind.INT, Kind.LONG);
        tcpWrite = Panama.downcall(lookup, "data_plane_tcp_write",
                Kind.INT, Kind.LONG, Kind.PTR, Kind.INT, Kind.LONG);
        tcpCloseH = Panama.downcall(lookup, "data_plane_tcp_close", Kind.INT, Kind.LONG);
        tcpListenerCloseH = Panama.downcall(lookup, "data_plane_tcp_listener_close", Kind.INT, Kind.LONG);
    }

    /** Whether the FFM backend can be used on this JVM (java.lang.foreign present). */
    public static boolean isSupported() {
        return Panama.isAvailable();
    }

    /**
     * Loads the native library at {@code dll} and binds an FFM backend.
     *
     * <p>The path is absolutized first, as the JNA backend already does. A relative one does load —
     * {@code libraryLookup} resolves it against the working directory — but the Paper plugin passes
     * {@code getDataFolder()}, which Bukkit may well hand back relative, and a library found only
     * from the right working directory is a footgun neither backend should carry.
     */
    public static FfmEasyTier create(Path dll) {
        if (!Panama.isAvailable()) {
            throw new EasyTierException("FFM (java.lang.foreign) unavailable", Panama.initError());
        }
        return new FfmEasyTier(Panama.loadLibrary(dll.toAbsolutePath()));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void parseConfig(String toml) {
        Object arena = Panama.newArena();
        try {
            int rc = (int) invoke(parseConfig, Panama.cString(arena, toml));
            if (rc != 0) throw new EasyTierException("parse_config failed: " + lastError());
        } finally {
            Panama.closeArena(arena);
        }
    }

    @Override
    public void runNetworkInstance(String toml) {
        Object arena = Panama.newArena();
        try {
            int rc = (int) invoke(runNetworkInstance, Panama.cString(arena, toml));
            if (rc != 0) throw new EasyTierException("run_network_instance failed: " + lastError());
        } finally {
            Panama.closeArena(arena);
        }
    }

    @Override
    public void deleteNetworkInstance(String... names) {
        if (names == null || names.length == 0) return;
        Object arena = Panama.newArena();
        try {
            Object array = Panama.alloc(arena, (long) names.length * PTR_BYTES);
            ByteBuffer arr = Panama.buffer(array);
            for (int i = 0; i < names.length; i++) {
                Object s = Panama.cString(arena, names[i]);
                arr.putLong(i * (int) PTR_BYTES, Panama.addressOf(s));
            }
            int rc = (int) invoke(deleteInstance, array, (long) names.length);
            if (rc != 0) throw new EasyTierException("delete_network_instance failed: " + lastError());
        } finally {
            Panama.closeArena(arena);
        }
    }

    @Override
    public Map<String, String> collectNetworkInfos(int max) {
        Object arena = Panama.newArena();
        try {
            Object array = Panama.alloc(arena, (long) max * KV_PAIR_BYTES);
            ByteBuffer bb = Panama.buffer(array);
            // Zeroed up front: the native call fills only the pairs it has, so a stale pointer left
            // in the tail would be read as a real key/value (and then freed) if it over-reported.
            for (int i = 0; i < bb.capacity(); i++) bb.put(i, (byte) 0);

            int count = (int) invoke(collectInfos, array, (long) max);
            if (count < 0) throw new EasyTierException("collect_network_infos failed: " + lastError());

            Map<String, String> out = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                long base = i * KV_PAIR_BYTES;
                long keyPtr = bb.getLong((int) base);
                long valPtr = bb.getLong((int) (base + PTR_BYTES));
                String key = Panama.readCString(keyPtr, CSTR_CAP);
                String val = Panama.readCString(valPtr, CSTR_CAP);
                if (keyPtr != 0) freeNative(keyPtr);
                if (valPtr != 0) freeNative(valPtr);
                if (key != null) out.put(key, val == null ? "" : val);
            }
            return out;
        } finally {
            Panama.closeArena(arena);
        }
    }

    @Override
    public String lastError() {
        Object arena = Panama.newArena();
        try {
            Object slot = Panama.alloc(arena, PTR_BYTES);
            Panama.buffer(slot).putLong(0, 0L);
            invoke(getErrorMsg, slot);
            long ptr = Panama.buffer(slot).getLong(0);
            if (ptr == 0) return "(no error message)";
            String msg = Panama.readCString(ptr, CSTR_CAP);
            freeNative(ptr);
            return msg == null ? "(no error message)" : msg;
        } finally {
            Panama.closeArena(arena);
        }
    }

    private void freeNative(long ptr) {
        try {
            invoke(freeString, Panama.segmentOfAddress(ptr));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ data plane (TCP)

    @Override
    public Bind tcpBind(String inst, int localPort, long timeoutMs) {
        Object arena = Panama.newArena();
        try {
            Object outIp = ptrSlot(arena);
            Object outPort = shortSlot(arena);
            long h = (long) invoke(tcpBind, Panama.cString(arena, inst),
                    (short) localPort, timeoutMs, outIp, outPort);
            if (h == 0) throw new EasyTierException("data_plane_tcp_bind failed: " + lastError());
            return new Bind(h, takeString(outIp), readUShort(outPort));
        } finally {
            Panama.closeArena(arena);
        }
    }

    @Override
    public Accept tcpAccept(long listener, long timeoutMs) {
        Object arena = Panama.newArena();
        try {
            Object outLip = ptrSlot(arena);
            Object outLport = shortSlot(arena);
            Object outPip = ptrSlot(arena);
            Object outPport = shortSlot(arena);
            long h = (long) invoke(tcpAccept, listener, timeoutMs, outLip, outLport, outPip, outPport);
            if (h == 0) return null;
            return new Accept(h, takeString(outLip), readUShort(outLport),
                    takeString(outPip), readUShort(outPport));
        } finally {
            Panama.closeArena(arena);
        }
    }

    @Override
    public Bind tcpConnect(String inst, String dstIp, int dstPort, long timeoutMs) {
        Object arena = Panama.newArena();
        try {
            Object outIp = ptrSlot(arena);
            Object outPort = shortSlot(arena);
            long h = (long) invoke(tcpConnect, Panama.cString(arena, inst), Panama.cString(arena, dstIp),
                    (short) dstPort, timeoutMs, outIp, outPort);
            if (h == 0) throw new EasyTierException("data_plane_tcp_connect failed: " + lastError());
            return new Bind(h, takeString(outIp), readUShort(outPort));
        } finally {
            Panama.closeArena(arena);
        }
    }

    @Override
    public int tcpRead(long handle, byte[] buf, int len, long timeoutMs) {
        Scratch s = scratch(len);
        int n = (int) invoke(tcpRead, handle, s.seg, len, timeoutMs);
        if (n > 0) s.bb.get(0, buf, 0, n);
        return n;
    }

    @Override
    public int tcpWrite(long handle, byte[] data, int len, long timeoutMs) {
        Scratch s = scratch(len);
        s.bb.put(0, data, 0, len);
        return (int) invoke(tcpWrite, handle, s.seg, len, timeoutMs);
    }

    @Override
    public void tcpClose(long handle) {
        if (handle != 0) invoke(tcpCloseH, handle);
    }

    @Override
    public void tcpListenerClose(long handle) {
        if (handle != 0) invoke(tcpListenerCloseH, handle);
    }

    // ------------------------------------------------------------------ per-thread native scratch

    /**
     * A per-thread, growable native buffer used to marshal data-plane reads/writes.
     *
     * <p>Its arena is an <b>automatic</b> one, not a confined one: etmc's data-plane threads are
     * per-connection (two pumps per bridged player, plus the channel's reader/writer), and a confined
     * arena is freed only by {@code close()} — which a thread-local cache never gets to call. Every
     * finished connection would strand 32 KB of native memory for the life of the JVM. An automatic
     * arena is collected with the {@link Scratch} that holds it once the thread is gone.
     */
    private static final class Scratch {
        Object arena;
        Object seg;
        ByteBuffer bb;
        int cap;
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final int SCRATCH_MIN_BYTES = 32 * 1024;

    private static Scratch scratch(int len) {
        Scratch s = SCRATCH.get();
        if (s.cap < len) {
            int cap = Math.max(len, SCRATCH_MIN_BYTES);
            // The outgrown arena needs no close: dropping the last reference to it is the release.
            s.arena = Panama.newAutoArena();
            s.seg = Panama.alloc(s.arena, cap);
            s.bb = Panama.buffer(s.seg);
            s.cap = cap;
        }
        return s;
    }

    // ------------------------------------------------------------------ helpers

    private static Object ptrSlot(Object arena) {
        Object slot = Panama.alloc(arena, PTR_BYTES);
        Panama.buffer(slot).putLong(0, 0L);
        return slot;
    }

    private static Object shortSlot(Object arena) {
        Object slot = Panama.alloc(arena, 2);
        Panama.buffer(slot).putShort(0, (short) 0);
        return slot;
    }

    private static int readUShort(Object slot) {
        return Panama.buffer(slot).getShort(0) & 0xFFFF;
    }

    private String takeString(Object slot) {
        long ptr = Panama.buffer(slot).getLong(0);
        if (ptr == 0) return null;
        String s = Panama.readCString(ptr, CSTR_CAP);
        freeNative(ptr);
        return s;
    }

    private static Object invoke(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new EasyTierException("native call failed", t);
        }
    }
}

package dev.l5z12.etmc.ffi;

import dev.l5z12.etmc.ffi.Panama.Kind;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed Java binding for the EasyTier FFI C-ABI library ({@code easytier_ffi}).
 *
 * <p>All native interaction goes through {@link Panama} (the reflection facade over FFM). Lifecycle
 * functions ({@code parse_config}, {@code run_network_instance}, {@code delete_network_instance},
 * {@code collect_network_infos}) start/stop/inspect named network instances; the data-plane
 * functions ({@code data_plane_tcp_*}) give us userspace TCP sockets over the mesh so we never need
 * a TUN device.
 *
 * <p>This is a process-wide singleton: the native library keeps a single global instance manager.
 */
public final class EasyTier {

    /** Size of one {@code KeyValuePair { const char* key; const char* value; }} on a 64-bit target. */
    private static final long KV_PAIR_BYTES = 16;
    private static final long PTR_BYTES = 8;
    /** Upper bound when scanning a returned C string for its NUL terminator. */
    private static final int CSTR_CAP = 4 * 1024 * 1024;

    private static volatile EasyTier instance;

    // lifecycle
    private final MethodHandle parseConfig;        // int parse_config(const char*)
    private final MethodHandle runNetworkInstance; // int run_network_instance(const char*)
    private final MethodHandle deleteInstance;     // int delete_network_instance(const char**, size_t)
    private final MethodHandle collectInfos;       // int collect_network_infos(KeyValuePair*, size_t)
    private final MethodHandle getErrorMsg;        // void get_error_msg(const char**)
    private final MethodHandle freeString;         // void free_string(const char*)

    // data plane (TCP)
    private final MethodHandle tcpConnect;         // u64 (inst, dst_ip, dst_port, timeout, out_ip, out_port)
    private final MethodHandle tcpBind;            // u64 (inst, local_port, timeout, out_ip, out_port)
    private final MethodHandle tcpAccept;          // u64 (handle, timeout, out_lip, out_lport, out_pip, out_pport)
    private final MethodHandle tcpRead;            // int (handle, buf, len, timeout)
    private final MethodHandle tcpWrite;           // int (handle, buf, len, timeout)
    private final MethodHandle tcpClose;           // int (handle)
    private final MethodHandle tcpListenerClose;   // int (handle)

    private EasyTier(Object lookup) {
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
        tcpClose = Panama.downcall(lookup, "data_plane_tcp_close", Kind.INT, Kind.LONG);
        tcpListenerClose = Panama.downcall(lookup, "data_plane_tcp_listener_close", Kind.INT, Kind.LONG);
    }

    /** Loads the native library at {@code dll} and binds the singleton. Idempotent. */
    public static synchronized EasyTier load(Path dll) {
        if (instance == null) {
            if (!Panama.isAvailable()) {
                throw new EasyTierException("FFM (java.lang.foreign) unavailable", Panama.initError());
            }
            Object lookup = Panama.loadLibrary(dll);
            instance = new EasyTier(lookup);
        }
        return instance;
    }

    public static EasyTier get() {
        EasyTier i = instance;
        if (i == null) throw new EasyTierException("EasyTier native library not loaded yet");
        return i;
    }

    public static boolean isLoaded() {
        return instance != null;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Validates a TOML config string without starting anything. */
    public void parseConfig(String toml) {
        Object arena = Panama.newArena();
        try {
            int rc = (int) invoke(parseConfig, Panama.cString(arena, toml));
            if (rc != 0) throw new EasyTierException("parse_config failed: " + lastError());
        } finally {
            Panama.closeArena(arena);
        }
    }

    /** Starts (or replaces) a network instance described by the TOML config. */
    public void runNetworkInstance(String toml) {
        Object arena = Panama.newArena();
        try {
            int rc = (int) invoke(runNetworkInstance, Panama.cString(arena, toml));
            if (rc != 0) throw new EasyTierException("run_network_instance failed: " + lastError());
        } finally {
            Panama.closeArena(arena);
        }
    }

    /** Stops the named instances. Unknown names are ignored. */
    public void deleteNetworkInstance(String... names) {
        if (names == null || names.length == 0) return;
        Object arena = Panama.newArena();
        try {
            // Build a const char*[] : an array of pointers to C strings.
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

    /**
     * Collects running instance info. Returns a map of instance-name to a JSON string describing
     * that instance's live state (virtual IP, peers, routes, etc.).
     */
    public Map<String, String> collectNetworkInfos(int max) {
        Object arena = Panama.newArena();
        try {
            Object array = Panama.alloc(arena, (long) max * KV_PAIR_BYTES);
            // zero it so unwritten slots read as null pointers
            ByteBuffer zb = Panama.buffer(array);
            for (int i = 0; i < zb.capacity(); i++) zb.put(i, (byte) 0);

            int count = (int) invoke(collectInfos, array, (long) max);
            if (count < 0) throw new EasyTierException("collect_network_infos failed: " + lastError());

            Map<String, String> out = new LinkedHashMap<>();
            ByteBuffer bb = Panama.buffer(array);
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

    /** Reads the current thread's last error message (may be empty). */
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

    /** Result of a bind/accept/connect: a handle plus reported addresses. */
    public record Bind(long handle, String localIp, int localPort) {}
    public record Accept(long handle, String localIp, int localPort, String peerIp, int peerPort) {}

    /** Binds a userspace TCP listener on the mesh for {@code inst} at {@code localPort} (0 = any). */
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

    /** Accepts one connection from a listener handle. Returns null on timeout. */
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

    /** Opens a userspace TCP connection to {@code dstIp:dstPort} over the mesh. */
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

    /**
     * Reads up to {@code len} bytes from a stream handle into {@code nativeBuf}.
     * Returns bytes read (0 on a clean EOF-ish read), or -1 on failure.
     * {@code nativeBuf} must be a native segment of at least {@code len} bytes owned by this thread.
     */
    public int tcpRead(long handle, Object nativeBuf, int len, long timeoutMs) {
        return (int) invoke(tcpRead, handle, nativeBuf, len, timeoutMs);
    }

    /** Writes exactly {@code len} bytes from {@code nativeBuf} to a stream handle. */
    public int tcpWrite(long handle, Object nativeBuf, int len, long timeoutMs) {
        return (int) invoke(tcpWrite, handle, nativeBuf, len, timeoutMs);
    }

    public void tcpClose(long handle) {
        if (handle != 0) invoke(tcpClose, handle);
    }

    public void tcpListenerClose(long handle) {
        if (handle != 0) invoke(tcpListenerClose, handle);
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

    /** Reads the C string written into a {@code char**} out slot and frees the native copy. */
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

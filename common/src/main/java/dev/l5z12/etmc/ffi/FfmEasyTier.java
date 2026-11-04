package dev.l5z12.etmc.ffi;

import dev.l5z12.etmc.ffi.Panama.Kind;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link EasyTier} backend built on {@code java.lang.foreign} (FFM) via the {@link Panama} reflection
 * facade. Used on Java 21+ runtimes (MC 1.20.5+, 1.21.x, 26.x).
 *
 * <p>The data plane is EasyTier's ABI v3 ({@link DataPlaneAbi}); {@link DataPlane} turns it into the
 * blocking stream API. Read/write payloads go through a per-thread, growable native scratch buffer so
 * the hot path avoids per-call arena churn.
 */
public final class FfmEasyTier extends NativeEasyTier {

    private static final long KV_PAIR_BYTES = 16;
    /** {@code sizeof(DataPlaneCompletion)}: u64 + u16 + u16, padded to 8-byte alignment. */
    private static final int COMPLETION_BYTES = 16;
    private static final long PTR_BYTES = 8;
    private static final int CSTR_CAP = 4 * 1024 * 1024;

    // lifecycle
    private final MethodHandle parseConfig;
    private final MethodHandle runNetworkInstance;
    private final MethodHandle deleteInstance;
    private final MethodHandle collectInfos;
    private final MethodHandle getErrorMsg;
    private final MethodHandle freeString;

    // data plane (ABI v3)
    private final MethodHandle sessionOpen;
    private final MethodHandle sessionClose;
    private final MethodHandle tcpConnectSubmit;
    private final MethodHandle tcpBindSubmit;
    private final MethodHandle tcpAcceptSubmit;
    private final MethodHandle tcpReadSubmit;
    private final MethodHandle tcpWriteSubmit;
    private final MethodHandle completionWait;
    private final MethodHandle completionDrain;
    private final MethodHandle tcpConnectTake;
    private final MethodHandle tcpBindTake;
    private final MethodHandle tcpAcceptTake;
    private final MethodHandle tcpReadTake;
    private final MethodHandle tcpWriteTake;
    private final MethodHandle operationCancel;
    private final MethodHandle operationFree;
    private final MethodHandle resourceClose;

    private FfmEasyTier(Object lookup) {
        parseConfig = Panama.downcall(lookup, "parse_config", Kind.INT, Kind.PTR);
        runNetworkInstance = Panama.downcall(lookup, "run_network_instance", Kind.INT, Kind.PTR);
        deleteInstance = Panama.downcall(lookup, "delete_network_instance", Kind.INT, Kind.PTR, Kind.LONG);
        collectInfos = Panama.downcall(lookup, "collect_network_infos", Kind.INT, Kind.PTR, Kind.LONG);
        getErrorMsg = Panama.downcall(lookup, "get_error_msg", Kind.VOID, Kind.PTR);
        freeString = Panama.downcall(lookup, "free_string", Kind.VOID, Kind.PTR);

        // Binding every symbol up front doubles as the ABI check: a library from before ABI v3 (or one
        // that dropped a function) fails here, at load, naming the missing symbol.
        sessionOpen = Panama.downcall(lookup, "data_plane_session_open", Kind.INT, Kind.PTR, Kind.PTR);
        sessionClose = Panama.downcall(lookup, "data_plane_session_close", Kind.INT, Kind.LONG);
        tcpConnectSubmit = Panama.downcall(lookup, "data_plane_tcp_connect_submit",
                Kind.INT, Kind.LONG, Kind.SOCKADDR, Kind.LONG, Kind.PTR);
        tcpBindSubmit = Panama.downcall(lookup, "data_plane_tcp_bind_submit",
                Kind.INT, Kind.LONG, Kind.SHORT, Kind.LONG, Kind.PTR);
        tcpAcceptSubmit = Panama.downcall(lookup, "data_plane_tcp_accept_submit",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.LONG, Kind.PTR);
        tcpReadSubmit = Panama.downcall(lookup, "data_plane_tcp_read_submit",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.INT, Kind.PTR);
        tcpWriteSubmit = Panama.downcall(lookup, "data_plane_tcp_write_submit",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.PTR, Kind.INT, Kind.PTR);
        completionWait = Panama.downcall(lookup, "data_plane_completion_wait", Kind.INT, Kind.LONG, Kind.LONG);
        completionDrain = Panama.downcall(lookup, "data_plane_completion_drain",
                Kind.INT, Kind.LONG, Kind.PTR, Kind.INT);
        tcpConnectTake = Panama.downcall(lookup, "data_plane_tcp_connect_result_take",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.PTR, Kind.PTR, Kind.PTR);
        tcpBindTake = Panama.downcall(lookup, "data_plane_tcp_bind_result_take",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.PTR, Kind.PTR);
        tcpAcceptTake = Panama.downcall(lookup, "data_plane_tcp_accept_result_take",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.PTR, Kind.PTR, Kind.PTR);
        tcpReadTake = Panama.downcall(lookup, "data_plane_tcp_read_result_take",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.PTR, Kind.INT, Kind.PTR, Kind.PTR);
        tcpWriteTake = Panama.downcall(lookup, "data_plane_tcp_write_result_take",
                Kind.INT, Kind.LONG, Kind.LONG, Kind.PTR);
        operationCancel = Panama.downcall(lookup, "data_plane_operation_cancel", Kind.INT, Kind.LONG, Kind.LONG);
        operationFree = Panama.downcall(lookup, "data_plane_operation_free", Kind.INT, Kind.LONG, Kind.LONG);
        resourceClose = Panama.downcall(lookup, "data_plane_resource_close", Kind.INT, Kind.LONG, Kind.LONG);
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
        callWithToml(parseConfig, "parse_config", toml);
    }

    @Override
    public void runNetworkInstance(String toml) {
        callWithToml(runNetworkInstance, "run_network_instance", toml);
    }

    /** The two TOML entry points differ only in which handle they call and what the failure is called. */
    private void callWithToml(MethodHandle fn, String name, String toml) {
        useArena(arena -> {
            int rc = (int) invoke(fn, Panama.cString(arena, toml));
            if (rc != 0) throw new EasyTierException(name + " failed: " + lastError());
        });
    }

    @Override
    protected void deleteInstances(String... names) {
        useArena(arena -> {
            Object array = Panama.alloc(arena, (long) names.length * PTR_BYTES);
            ByteBuffer arr = Panama.buffer(array);
            for (int i = 0; i < names.length; i++) {
                Object s = Panama.cString(arena, names[i]);
                arr.putLong(i * (int) PTR_BYTES, Panama.addressOf(s));
            }
            int rc = (int) invoke(deleteInstance, array, (long) names.length);
            if (rc != 0) throw new EasyTierException("delete_network_instance failed: " + lastError());
        });
    }

    @Override
    public Map<String, String> collectNetworkInfos(int max) {
        return inArena(arena -> {
            // An arena hands back zeroed memory, and this relies on it: the native call fills only the
            // pairs it has, so a non-zero tail would be read as a real key/value (and then freed) if it
            // over-reported. Reuse this array across calls and it would have to be cleared by hand.
            Object array = Panama.alloc(arena, (long) max * KV_PAIR_BYTES);
            ByteBuffer bb = Panama.buffer(array);

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
        });
    }

    @Override
    public String lastError() {
        return inArena(arena -> {
            Object slot = ptrSlot(arena);
            invoke(getErrorMsg, slot);
            long ptr = Panama.buffer(slot).getLong(0);
            if (ptr == 0) return "(no error message)";
            String msg = Panama.readCString(ptr, CSTR_CAP);
            freeNative(ptr);
            return msg == null ? "(no error message)" : msg;
        });
    }

    private void freeNative(long ptr) {
        try {
            invoke(freeString, Panama.segmentOfAddress(ptr));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ data plane (ABI v3)

    @Override
    public long sessionOpen(String instName) {
        return inArena(arena -> {
            Object out = ptrSlot(arena);
            rc("data_plane_session_open", invoke(sessionOpen, Panama.cString(arena, instName), out));
            return Panama.buffer(out).getLong(0);
        });
    }

    @Override
    public void sessionClose(long session) {
        rc("data_plane_session_close", invoke(sessionClose, session));
    }

    @Override
    public long tcpConnectSubmit(long session, byte[] ipv4, int port, long timeoutMs) {
        return inArena(arena -> {
            Object addr = Panama.alloc(arena, Panama.SOCKADDR_BYTES);
            DataPlaneAbi.putSockAddr(Panama.buffer(addr), ipv4, port);
            Object out = ptrSlot(arena);
            rc("data_plane_tcp_connect_submit", invoke(tcpConnectSubmit, session, addr, timeoutMs, out));
            return Panama.buffer(out).getLong(0);
        });
    }

    @Override
    public long tcpBindSubmit(long session, int port, long timeoutMs) {
        return inArena(arena -> {
            Object out = ptrSlot(arena);
            rc("data_plane_tcp_bind_submit", invoke(tcpBindSubmit, session, (short) port, timeoutMs, out));
            return Panama.buffer(out).getLong(0);
        });
    }

    @Override
    public long tcpAcceptSubmit(long session, long listener, long timeoutMs) {
        return inArena(arena -> {
            Object out = ptrSlot(arena);
            rc("data_plane_tcp_accept_submit", invoke(tcpAcceptSubmit, session, listener, timeoutMs, out));
            return Panama.buffer(out).getLong(0);
        });
    }

    @Override
    public long tcpReadSubmit(long session, long stream, int maxLen) {
        return inArena(arena -> {
            Object out = ptrSlot(arena);
            rc("data_plane_tcp_read_submit", invoke(tcpReadSubmit, session, stream, maxLen, out));
            return Panama.buffer(out).getLong(0);
        });
    }

    @Override
    public long tcpWriteSubmit(long session, long stream, byte[] data, int len) {
        // The library copies the bytes before the submit returns, so the scratch buffer is reusable.
        Scratch s = scratch(len);
        s.bb.put(0, data, 0, len);
        return inArena(arena -> {
            Object out = ptrSlot(arena);
            rc("data_plane_tcp_write_submit", invoke(tcpWriteSubmit, session, stream, s.seg, len, out));
            return Panama.buffer(out).getLong(0);
        });
    }

    @Override
    public int completionWait(long session, long timeoutMs) {
        return rc("data_plane_completion_wait", invoke(completionWait, session, timeoutMs));
    }

    @Override
    public int completionDrain(long session, long[] ops, int[] statuses) {
        return inArena(arena -> {
            // DataPlaneCompletion { u64 operation_id; u16 operation_kind; u16 status; }
            Object arr = Panama.alloc(arena, (long) ops.length * COMPLETION_BYTES);
            int n = rc("data_plane_completion_drain", invoke(completionDrain, session, arr, ops.length));
            ByteBuffer bb = Panama.buffer(arr);
            for (int i = 0; i < n; i++) {
                int base = i * COMPLETION_BYTES;
                ops[i] = bb.getLong(base);
                statuses[i] = bb.getShort(base + 10) & 0xFFFF;
            }
            return n;
        });
    }

    @Override
    public Opened tcpConnectResultTake(long session, long op) {
        return takeStream(tcpConnectTake, "data_plane_tcp_connect_result_take", session, op);
    }

    @Override
    public Opened tcpAcceptResultTake(long session, long op) {
        return takeStream(tcpAcceptTake, "data_plane_tcp_accept_result_take", session, op);
    }

    private Opened takeStream(MethodHandle fn, String name, long session, long op) {
        return inArena(arena -> {
            Object stream = ptrSlot(arena);
            Object local = Panama.alloc(arena, Panama.SOCKADDR_BYTES);
            Object peer = Panama.alloc(arena, Panama.SOCKADDR_BYTES);
            rc(name, invoke(fn, session, op, stream, local, peer));
            ByteBuffer l = Panama.buffer(local);
            ByteBuffer p = Panama.buffer(peer);
            return new Opened(Panama.buffer(stream).getLong(0),
                    DataPlaneAbi.sockAddrIp(l), DataPlaneAbi.sockAddrPort(l),
                    DataPlaneAbi.sockAddrIp(p), DataPlaneAbi.sockAddrPort(p));
        });
    }

    @Override
    public Opened tcpBindResultTake(long session, long op) {
        return inArena(arena -> {
            Object listener = ptrSlot(arena);
            Object local = Panama.alloc(arena, Panama.SOCKADDR_BYTES);
            rc("data_plane_tcp_bind_result_take", invoke(tcpBindTake, session, op, listener, local));
            ByteBuffer l = Panama.buffer(local);
            return new Opened(Panama.buffer(listener).getLong(0),
                    DataPlaneAbi.sockAddrIp(l), DataPlaneAbi.sockAddrPort(l), null, 0);
        });
    }

    @Override
    public int tcpReadResultTake(long session, long op, byte[] buf, int capacity) {
        Scratch s = scratch(capacity);
        return inArena(arena -> {
            Object outLen = Panama.alloc(arena, 4);
            Object outEof = Panama.alloc(arena, 1);
            rc("data_plane_tcp_read_result_take",
                    invoke(tcpReadTake, session, op, s.seg, capacity, outLen, outEof));
            int n = Panama.buffer(outLen).getInt(0);
            if (n > 0) s.bb.get(0, buf, 0, n);
            return n;
        });
    }

    @Override
    public int tcpWriteResultTake(long session, long op) {
        return inArena(arena -> {
            Object outLen = Panama.alloc(arena, 4);
            rc("data_plane_tcp_write_result_take", invoke(tcpWriteTake, session, op, outLen));
            return Panama.buffer(outLen).getInt(0);
        });
    }

    @Override
    public void operationCancel(long session, long op) {
        rc("data_plane_operation_cancel", invoke(operationCancel, session, op));
    }

    @Override
    public void operationFree(long session, long op) {
        rc("data_plane_operation_free", invoke(operationFree, session, op));
    }

    @Override
    public void resourceClose(long session, long resource) {
        rc("data_plane_resource_close", invoke(resourceClose, session, resource));
    }

    /** Returns a non-negative ABI result, or throws the kind a negative one encodes. */
    private int rc(String fn, Object result) {
        int rc = (int) result;
        if (rc < 0) throw abiFailure(fn, rc);
        return rc;
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

    /**
     * Runs {@code body} against a fresh confined arena and releases it afterwards. Every call that
     * marshals arguments into native memory the callee must not outlive goes through here, so arena
     * lifetime is stated once rather than in a try/finally per call.
     */
    private static <T> T inArena(Function<Object, T> body) {
        Object arena = Panama.newArena();
        try {
            return body.apply(arena);
        } finally {
            Panama.closeArena(arena);
        }
    }

    /** {@link #inArena} for the calls that return nothing. */
    private static void useArena(Consumer<Object> body) {
        inArena(arena -> {
            body.accept(arena);
            return null;
        });
    }

    /** A zeroed pointer-sized out-parameter (also used for {@code u64} outputs). */
    private static Object ptrSlot(Object arena) {
        Object slot = Panama.alloc(arena, PTR_BYTES);
        Panama.buffer(slot).putLong(0, 0L);
        return slot;
    }

    private static Object invoke(MethodHandle h, Object... args) {
        try {
            return h.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new EasyTierException("native call failed", t);
        }
    }
}

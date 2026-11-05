package dev.l5z12.etmc.ffi;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.Structure;
import com.sun.jna.ptr.ByteByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;

import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link EasyTier} backend built on JNA, for Java 17 runtimes (MC 1.17.1–1.20.4) where
 * {@code java.lang.foreign} doesn't exist yet. Mirrors {@link FfmEasyTier}; JNA marshals the C-ABI
 * calls, and {@link DataPlane} builds the blocking stream API on the ABI v3 calls below.
 *
 * <p>The native structs are read straight out of {@link Memory} blocks: {@code KeyValuePair} is
 * 16 bytes and {@code DataPlaneCompletion} 16 bytes on a 64-bit target (all our Java-17 platforms
 * are 64-bit); {@code DataPlaneSocketAddr} is 20 bytes and is passed by value as {@link SockAddr}.
 */
public final class JnaEasyTier extends NativeEasyTier {

    private static final long KV_PAIR_BYTES = 16;
    private static final long PTR_BYTES = 8;
    private static final int COMPLETION_BYTES = 16;

    /** {@code DataPlaneSocketAddr}, by value. Public + static: JNA instantiates it reflectively. */
    @Structure.FieldOrder({"family", "port", "address"})
    public static class SockAddr extends Structure implements Structure.ByValue {
        public short family;
        public short port;
        public byte[] address = new byte[16];
    }

    /** Direct JNA mapping of the easytier_ffi C-ABI. */
    private interface Lib extends Library {
        int parse_config(String toml);
        int run_network_instance(String toml);
        int delete_network_instance(StringArray names, long count);   // (const char**, size_t)
        int collect_network_infos(Pointer kvArray, long max);          // (KeyValuePair*, size_t)
        void get_error_msg(PointerByReference out);                    // (const char**)
        void free_string(Pointer s);

        int data_plane_session_open(String inst, LongByReference outSession);
        int data_plane_session_close(long session);
        int data_plane_tcp_connect_submit(long session, SockAddr peer, long timeoutMs, LongByReference outOp);
        int data_plane_tcp_bind_submit(long session, short port, long timeoutMs, LongByReference outOp);
        int data_plane_tcp_accept_submit(long session, long listener, long timeoutMs, LongByReference outOp);
        int data_plane_tcp_read_submit(long session, long stream, int maxLen, LongByReference outOp);
        int data_plane_tcp_write_submit(long session, long stream, byte[] data, int len, LongByReference outOp);
        int data_plane_completion_wait(long session, long timeoutMs);
        int data_plane_completion_drain(long session, Pointer completions, int capacity);
        int data_plane_tcp_connect_result_take(long session, long op, LongByReference outStream,
                                               Pointer outLocal, Pointer outPeer);
        int data_plane_tcp_bind_result_take(long session, long op, LongByReference outListener, Pointer outLocal);
        int data_plane_tcp_accept_result_take(long session, long op, LongByReference outStream,
                                              Pointer outLocal, Pointer outPeer);
        int data_plane_tcp_read_result_take(long session, long op, byte[] data, int capacity,
                                            IntByReference outLen, ByteByReference outEof);
        int data_plane_tcp_write_result_take(long session, long op, IntByReference outLen);
        int data_plane_operation_cancel(long session, long op);
        int data_plane_operation_free(long session, long op);
        int data_plane_resource_close(long session, long resource);
    }

    private final Lib lib;

    /**
     * Scratch block for {@link #collectNetworkInfos}, reused across polls. A {@code Memory} is native
     * memory that only the GC reclaims on the JNA a Java-17 game ships — it gained {@code Closeable}
     * (so try-with-resources) in 5.12, and MC 1.17.1/1.18.x are on 5.8/5.10, where the only free is
     * {@code protected} — so allocating one per poll, every ~1.5s, strands a block each time. A single
     * block per backend has nothing to free. Guarded by {@code this}, along with the read out of it.
     */
    private Memory kvScratch;
    private int kvScratchPairs;

    /** The completion-drain block; only the dispatcher thread drains, but guard it anyway. */
    private final Memory drainScratch = new Memory((long) DRAIN_BATCH * COMPLETION_BYTES);

    private JnaEasyTier(Lib lib) {
        this.lib = lib;
    }

    /**
     * Loads the native library at {@code dll} and binds a JNA backend.
     *
     * <p>The string encoding is passed to the load itself. The {@code jna.encoding} system property
     * would only take effect if set before JNA first initialises — and Minecraft ships and uses JNA
     * long before etmc runs, so on, say, Chinese Windows every string would go out as GBK. The
     * library expects UTF-8 (network names, secrets and player names can be non-ASCII).
     */
    public static JnaEasyTier create(Path dll) {
        Map<String, Object> options = Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-8");
        String path = dll.toAbsolutePath().toString();
        Lib lib = Native.load(path, Lib.class, options);
        // JNA resolves symbols on first call; resolve them all now so a library with a different ABI
        // (pre-v3, or missing a function) fails at load naming the symbol, as the FFM backend does.
        NativeLibrary nl = NativeLibrary.getInstance(path, options);
        for (java.lang.reflect.Method m : Lib.class.getMethods()) {
            nl.getFunction(m.getName());
        }
        return new JnaEasyTier(lib);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void parseConfig(String toml) {
        if (lib.parse_config(toml) != 0) throw new EasyTierException("parse_config failed: " + lastError());
    }

    @Override
    public void runNetworkInstance(String toml) {
        if (lib.run_network_instance(toml) != 0) throw new EasyTierException("run_network_instance failed: " + lastError());
    }

    @Override
    protected void deleteInstances(String... names) {
        if (lib.delete_network_instance(new StringArray(names, "UTF-8"), names.length) != 0) {
            throw new EasyTierException("delete_network_instance failed: " + lastError());
        }
    }

    @Override
    public synchronized Map<String, String> collectNetworkInfos(int max) {
        if (kvScratch == null || kvScratchPairs < max) {
            kvScratch = new Memory(max * KV_PAIR_BYTES);
            kvScratchPairs = max;
        }
        Memory mem = kvScratch;
        // Zeroed up front: the native call fills only the pairs it has, so a pointer left in the tail
        // — stale, from an earlier poll, now that the block is reused — would be read as a real
        // key/value (and then freed) if it over-reported. The FFM backend zeroes its array the same way.
        mem.clear();
        int count = lib.collect_network_infos(mem, max);
        if (count < 0) throw new EasyTierException("collect_network_infos failed: " + lastError());

        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            long base = i * KV_PAIR_BYTES;
            Pointer keyP = mem.getPointer(base);
            Pointer valP = mem.getPointer(base + PTR_BYTES);
            String key = keyP == null ? null : keyP.getString(0, "UTF-8");
            String val = valP == null ? null : valP.getString(0, "UTF-8");
            if (keyP != null) lib.free_string(keyP);
            if (valP != null) lib.free_string(valP);
            if (key != null) out.put(key, val == null ? "" : val);
        }
        return out;
    }

    @Override
    public String lastError() {
        PointerByReference ref = new PointerByReference();
        lib.get_error_msg(ref);
        Pointer p = ref.getValue();
        if (p == null) return "(no error message)";
        String msg = p.getString(0, "UTF-8");
        lib.free_string(p);
        return msg == null ? "(no error message)" : msg;
    }

    // ------------------------------------------------------------------ data plane (ABI v3)

    @Override
    public long sessionOpen(String instName) {
        LongByReference out = new LongByReference();
        rc("data_plane_session_open", lib.data_plane_session_open(instName, out));
        return out.getValue();
    }

    @Override
    public void sessionClose(long session) {
        rc("data_plane_session_close", lib.data_plane_session_close(session));
    }

    @Override
    public long tcpConnectSubmit(long session, byte[] ipv4, int port, long timeoutMs) {
        SockAddr addr = new SockAddr();
        addr.family = 4;
        addr.port = (short) port;
        System.arraycopy(ipv4, 0, addr.address, 0, 4);
        LongByReference out = new LongByReference();
        rc("data_plane_tcp_connect_submit", lib.data_plane_tcp_connect_submit(session, addr, timeoutMs, out));
        return out.getValue();
    }

    @Override
    public long tcpBindSubmit(long session, int port, long timeoutMs) {
        LongByReference out = new LongByReference();
        rc("data_plane_tcp_bind_submit", lib.data_plane_tcp_bind_submit(session, (short) port, timeoutMs, out));
        return out.getValue();
    }

    @Override
    public long tcpAcceptSubmit(long session, long listener, long timeoutMs) {
        LongByReference out = new LongByReference();
        rc("data_plane_tcp_accept_submit", lib.data_plane_tcp_accept_submit(session, listener, timeoutMs, out));
        return out.getValue();
    }

    @Override
    public long tcpReadSubmit(long session, long stream, int maxLen) {
        LongByReference out = new LongByReference();
        rc("data_plane_tcp_read_submit", lib.data_plane_tcp_read_submit(session, stream, maxLen, out));
        return out.getValue();
    }

    @Override
    public long tcpWriteSubmit(long session, long stream, byte[] data, int len) {
        LongByReference out = new LongByReference();
        rc("data_plane_tcp_write_submit", lib.data_plane_tcp_write_submit(session, stream, data, len, out));
        return out.getValue();
    }

    @Override
    public int completionWait(long session, long timeoutMs) {
        return rc("data_plane_completion_wait", lib.data_plane_completion_wait(session, timeoutMs));
    }

    @Override
    public synchronized int completionDrain(long session, long[] ops, int[] statuses) {
        int cap = Math.min(ops.length, DRAIN_BATCH);
        int n = rc("data_plane_completion_drain", lib.data_plane_completion_drain(session, drainScratch, cap));
        for (int i = 0; i < n; i++) {
            long base = (long) i * COMPLETION_BYTES;
            ops[i] = drainScratch.getLong(base);
            statuses[i] = drainScratch.getShort(base + 10) & 0xFFFF;
        }
        return n;
    }

    @Override
    public Opened tcpConnectResultTake(long session, long op) {
        LongByReference stream = new LongByReference();
        Memory local = new Memory(SOCKADDR_BYTES);
        Memory peer = new Memory(SOCKADDR_BYTES);
        rc("data_plane_tcp_connect_result_take",
                lib.data_plane_tcp_connect_result_take(session, op, stream, local, peer));
        return opened(stream.getValue(), local, peer);
    }

    @Override
    public Opened tcpAcceptResultTake(long session, long op) {
        LongByReference stream = new LongByReference();
        Memory local = new Memory(SOCKADDR_BYTES);
        Memory peer = new Memory(SOCKADDR_BYTES);
        rc("data_plane_tcp_accept_result_take",
                lib.data_plane_tcp_accept_result_take(session, op, stream, local, peer));
        return opened(stream.getValue(), local, peer);
    }

    @Override
    public Opened tcpBindResultTake(long session, long op) {
        LongByReference listener = new LongByReference();
        Memory local = new Memory(SOCKADDR_BYTES);
        rc("data_plane_tcp_bind_result_take", lib.data_plane_tcp_bind_result_take(session, op, listener, local));
        var l = local.getByteBuffer(0, SOCKADDR_BYTES).order(ByteOrder.nativeOrder());
        return new Opened(listener.getValue(), DataPlaneAbi.sockAddrIp(l), DataPlaneAbi.sockAddrPort(l), null, 0);
    }

    private static Opened opened(long resource, Memory local, Memory peer) {
        var l = local.getByteBuffer(0, SOCKADDR_BYTES).order(ByteOrder.nativeOrder());
        var p = peer.getByteBuffer(0, SOCKADDR_BYTES).order(ByteOrder.nativeOrder());
        return new Opened(resource, DataPlaneAbi.sockAddrIp(l), DataPlaneAbi.sockAddrPort(l),
                DataPlaneAbi.sockAddrIp(p), DataPlaneAbi.sockAddrPort(p));
    }

    @Override
    public int tcpReadResultTake(long session, long op, byte[] buf, int capacity) {
        IntByReference len = new IntByReference();
        ByteByReference eof = new ByteByReference();
        rc("data_plane_tcp_read_result_take",
                lib.data_plane_tcp_read_result_take(session, op, buf, capacity, len, eof));
        return len.getValue();
    }

    @Override
    public int tcpWriteResultTake(long session, long op) {
        IntByReference len = new IntByReference();
        rc("data_plane_tcp_write_result_take", lib.data_plane_tcp_write_result_take(session, op, len));
        return len.getValue();
    }

    @Override
    public void operationCancel(long session, long op) {
        rc("data_plane_operation_cancel", lib.data_plane_operation_cancel(session, op));
    }

    @Override
    public void operationFree(long session, long op) {
        rc("data_plane_operation_free", lib.data_plane_operation_free(session, op));
    }

    @Override
    public void resourceClose(long session, long resource) {
        rc("data_plane_resource_close", lib.data_plane_resource_close(session, resource));
    }

    /** Returns a non-negative ABI result, or throws the kind a negative one encodes. */
    private int rc(String fn, int rc) {
        if (rc < 0) throw abiFailure(fn, rc);
        return rc;
    }
}

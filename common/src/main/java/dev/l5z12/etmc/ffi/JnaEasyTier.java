package dev.l5z12.etmc.ffi;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.ptr.ShortByReference;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link EasyTier} backend built on JNA, for Java 17 runtimes (MC 1.19.x and 1.20.1–1.20.4) where
 * {@code java.lang.foreign} doesn't exist yet. Mirrors {@link FfmEasyTier} exactly; JNA marshals the
 * C-ABI calls. Data-plane reads/writes take {@code byte[]} (JNA pins the array and copies back).
 *
 * <p>The native struct {@code KeyValuePair { const char* key; const char* value; }} is 16 bytes on a
 * 64-bit target (all our Java-17 platforms are 64-bit), read directly out of a {@link Memory} block.
 */
public final class JnaEasyTier implements EasyTier {

    static {
        // Match FfmEasyTier: all C strings are UTF-8.
        System.setProperty("jna.encoding", "UTF-8");
    }

    private static final long KV_PAIR_BYTES = 16;
    private static final long PTR_BYTES = 8;

    /** Direct JNA mapping of the easytier_ffi C-ABI. */
    private interface Lib extends Library {
        int parse_config(String toml);
        int run_network_instance(String toml);
        int delete_network_instance(StringArray names, long count);   // (const char**, size_t)
        int collect_network_infos(Pointer kvArray, long max);          // (KeyValuePair*, size_t)
        void get_error_msg(PointerByReference out);                    // (const char**)
        void free_string(Pointer s);

        long data_plane_tcp_connect(String inst, String dstIp, short dstPort, long timeoutMs,
                                    PointerByReference outIp, ShortByReference outPort);
        long data_plane_tcp_bind(String inst, short localPort, long timeoutMs,
                                 PointerByReference outIp, ShortByReference outPort);
        long data_plane_tcp_accept(long listener, long timeoutMs,
                                   PointerByReference outLip, ShortByReference outLport,
                                   PointerByReference outPip, ShortByReference outPport);
        int data_plane_tcp_read(long handle, byte[] buf, int len, long timeoutMs);
        int data_plane_tcp_write(long handle, byte[] data, int len, long timeoutMs);
        int data_plane_tcp_close(long handle);
        int data_plane_tcp_listener_close(long handle);
    }

    private final Lib lib;

    private JnaEasyTier(Lib lib) {
        this.lib = lib;
    }

    /** Loads the native library at {@code dll} and binds a JNA backend. */
    public static JnaEasyTier create(Path dll) {
        Lib lib = Native.load(dll.toAbsolutePath().toString(), Lib.class);
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
    public void deleteNetworkInstance(String... names) {
        if (names == null || names.length == 0) return;
        if (lib.delete_network_instance(new StringArray(names), names.length) != 0) {
            throw new EasyTierException("delete_network_instance failed: " + lastError());
        }
    }

    @Override
    public Map<String, String> collectNetworkInfos(int max) {
        Memory mem = new Memory(max * KV_PAIR_BYTES);
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

    // ------------------------------------------------------------------ data plane (TCP)

    @Override
    public Bind tcpBind(String inst, int localPort, long timeoutMs) {
        PointerByReference outIp = new PointerByReference();
        ShortByReference outPort = new ShortByReference();
        long h = lib.data_plane_tcp_bind(inst, (short) localPort, timeoutMs, outIp, outPort);
        if (h == 0) throw new EasyTierException("data_plane_tcp_bind failed: " + lastError());
        return new Bind(h, takeString(outIp), outPort.getValue() & 0xFFFF);
    }

    @Override
    public Accept tcpAccept(long listener, long timeoutMs) {
        PointerByReference outLip = new PointerByReference();
        ShortByReference outLport = new ShortByReference();
        PointerByReference outPip = new PointerByReference();
        ShortByReference outPport = new ShortByReference();
        long h = lib.data_plane_tcp_accept(listener, timeoutMs, outLip, outLport, outPip, outPport);
        if (h == 0) return null;
        return new Accept(h, takeString(outLip), outLport.getValue() & 0xFFFF,
                takeString(outPip), outPport.getValue() & 0xFFFF);
    }

    @Override
    public Bind tcpConnect(String inst, String dstIp, int dstPort, long timeoutMs) {
        PointerByReference outIp = new PointerByReference();
        ShortByReference outPort = new ShortByReference();
        long h = lib.data_plane_tcp_connect(inst, dstIp, (short) dstPort, timeoutMs, outIp, outPort);
        if (h == 0) throw new EasyTierException("data_plane_tcp_connect failed: " + lastError());
        return new Bind(h, takeString(outIp), outPort.getValue() & 0xFFFF);
    }

    @Override
    public int tcpRead(long handle, byte[] buf, int len, long timeoutMs) {
        return lib.data_plane_tcp_read(handle, buf, len, timeoutMs);
    }

    @Override
    public int tcpWrite(long handle, byte[] data, int len, long timeoutMs) {
        return lib.data_plane_tcp_write(handle, data, len, timeoutMs);
    }

    @Override
    public void tcpClose(long handle) {
        if (handle != 0) lib.data_plane_tcp_close(handle);
    }

    @Override
    public void tcpListenerClose(long handle) {
        if (handle != 0) lib.data_plane_tcp_listener_close(handle);
    }

    // ------------------------------------------------------------------ helpers

    /** Reads the C string written into a {@code char**} out param and frees the native copy. */
    private String takeString(PointerByReference ref) {
        Pointer p = ref.getValue();
        if (p == null) return null;
        String s = p.getString(0, "UTF-8");
        lib.free_string(p);
        return s;
    }
}

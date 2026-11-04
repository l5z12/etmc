package dev.l5z12.etmc.ffi;

/**
 * One-to-one binding of EasyTier's native data-plane ABI v3 ({@code DATA_PLANE_ABI.md} in
 * easytier-ffi): session-scoped, submit-then-complete. Each backend implements only this marshalling
 * layer; {@link DataPlane} builds etmc's blocking stream API on top of it, once, for both.
 *
 * <p>Every method throws {@link EasyTierException} carrying the {@link DataPlaneError} kind when the
 * native call returns a negative code; the message is read from {@code get_error_msg} on the calling
 * thread, as the library requires.
 */
interface DataPlaneAbi {

    /** A stream or listener created by a finished connect/bind/accept. */
    record Opened(long resource, String localIp, int localPort, String peerIp, int peerPort) {}

    /** {@code DataPlaneCompletion} drain capacity used by {@link DataPlane}'s dispatcher. */
    int DRAIN_BATCH = 64;

    long sessionOpen(String instName);

    void sessionClose(long session);

    long tcpConnectSubmit(long session, byte[] ipv4, int port, long timeoutMs);

    long tcpBindSubmit(long session, int port, long timeoutMs);

    long tcpAcceptSubmit(long session, long listener, long timeoutMs);

    long tcpReadSubmit(long session, long stream, int maxLen);

    long tcpWriteSubmit(long session, long stream, byte[] data, int len);

    /** {@code 1} = completions ready, {@code 0} = timed out or the session is closing. */
    int completionWait(long session, long timeoutMs);

    /** Drains up to {@code ops.length} completions; fills {@code ops[i]} and {@code statuses[i]}. */
    int completionDrain(long session, long[] ops, int[] statuses);

    Opened tcpConnectResultTake(long session, long op);

    Opened tcpBindResultTake(long session, long op);

    Opened tcpAcceptResultTake(long session, long op);

    /** Copies a finished read into {@code buf}; returns its length ({@code 0} = end of stream). */
    int tcpReadResultTake(long session, long op, byte[] buf, int capacity);

    int tcpWriteResultTake(long session, long op);

    void operationCancel(long session, long op);

    void operationFree(long session, long op);

    void resourceClose(long session, long resource);

    // ------------------------------------------------------------------ DataPlaneSocketAddr

    /** {@code sizeof(DataPlaneSocketAddr)}: u16 family, u16 port, u8[16] address. */
    int SOCKADDR_BYTES = 20;

    /** Encodes an IPv4 socket address into a {@code DataPlaneSocketAddr} (native-endian ints). */
    static void putSockAddr(java.nio.ByteBuffer nativeOrder, byte[] ipv4, int port) {
        nativeOrder.putShort(0, (short) 4);
        nativeOrder.putShort(2, (short) port);
        for (int i = 0; i < 16; i++) nativeOrder.put(4 + i, i < 4 ? ipv4[i] : 0);
    }

    /** Decodes a {@code DataPlaneSocketAddr}'s IP as a dotted string ({@code null} when not IPv4). */
    static String sockAddrIp(java.nio.ByteBuffer nativeOrder) {
        if (nativeOrder.getShort(0) != 4) return null;
        return (nativeOrder.get(4) & 0xFF) + "." + (nativeOrder.get(5) & 0xFF) + "."
                + (nativeOrder.get(6) & 0xFF) + "." + (nativeOrder.get(7) & 0xFF);
    }

    static int sockAddrPort(java.nio.ByteBuffer nativeOrder) {
        return nativeOrder.getShort(2) & 0xFFFF;
    }
}

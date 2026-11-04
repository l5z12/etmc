package dev.l5z12.etmc.ffi;

/**
 * What the two native backends share: the blocking data-plane API is {@link DataPlane} driving the
 * backend's {@link DataPlaneAbi}, so each backend only marshals native calls.
 */
abstract class NativeEasyTier implements EasyTier, DataPlaneAbi {

    private final DataPlane dataPlane = new DataPlane(this);

    /** {@code delete_network_instance}, without the data-plane bookkeeping. */
    protected abstract void deleteInstances(String... names);

    @Override
    public final void deleteNetworkInstance(String... names) {
        if (names == null || names.length == 0) return;
        // Close our sessions first so their dispatchers stop on a clean close rather than discover
        // the instance vanished; the library would close them anyway.
        dataPlane.closeSessions(names);
        deleteInstances(names);
    }

    @Override
    public final Bind tcpBind(String inst, int localPort, long timeoutMs) {
        return dataPlane.tcpBind(inst, localPort, timeoutMs);
    }

    @Override
    public final Accept tcpAccept(long listener, long timeoutMs) {
        return dataPlane.tcpAccept(listener, timeoutMs);
    }

    @Override
    public final Bind tcpConnect(String inst, String dstIp, int dstPort, long timeoutMs) {
        return dataPlane.tcpConnect(inst, dstIp, dstPort, timeoutMs);
    }

    @Override
    public final int tcpRead(long handle, byte[] buf, int len, long timeoutMs) {
        return dataPlane.tcpRead(handle, buf, len, timeoutMs);
    }

    @Override
    public final int tcpWrite(long handle, byte[] data, int len, long timeoutMs) {
        return dataPlane.tcpWrite(handle, data, len, timeoutMs);
    }

    @Override
    public final void tcpClose(long handle) {
        dataPlane.close(handle);
    }

    @Override
    public final void tcpListenerClose(long handle) {
        dataPlane.close(handle);
    }

    /** The exception for a negative ABI return code, with the library's message for this thread. */
    protected final EasyTierException abiFailure(String fn, int rc) {
        DataPlaneError kind = DataPlaneError.of(rc);
        return new EasyTierException(fn + " failed: " + lastError(), kind);
    }
}

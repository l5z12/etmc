package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.DataPlaneError;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.EasyTierException;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bridges a loopback {@link Socket} (Minecraft's vanilla TCP) and an EasyTier data-plane stream
 * handle, pumping bytes both ways. This is what lets the unmodified Minecraft networking talk over
 * the P2P mesh without a TUN device: from Minecraft's point of view it is a plain localhost socket.
 *
 * <p>Each direction runs on its own daemon thread with its own confined native buffer. Closing the
 * data-plane handle cancels any in-flight blocking read on the mesh side, so a long read timeout is
 * safe — when one side ends we close the handle and the socket and both pumps unwind.
 */
public final class TcpBridge {

    private static final System.Logger LOG = System.getLogger("etmc.TcpBridge");
    private static final int BUF = 32 * 1024;
    /** Long: the read returns promptly on data or on close (handle cancellation), not on this timeout. */
    private static final long READ_TIMEOUT_MS = 3_600_000L;
    private static final long WRITE_TIMEOUT_MS = 60_000L;

    private final EasyTier et;
    private final Socket local;
    private final long stream;
    private final Consumer<TcpBridge> onClose;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile String name = "peer";

    /**
     * @param onClose run once when either direction ends, with this bridge as the argument, so an
     *                owner can drop it from its live-connection set
     */
    public TcpBridge(EasyTier et, Socket local, long stream, Consumer<TcpBridge> onClose) {
        this.et = et;
        this.local = local;
        this.stream = stream;
        this.onClose = onClose;
    }

    /** Starts both pump threads. Non-blocking. */
    public void start(String name) {
        this.name = name;
        Threads.start("etmc-bridge-rx-" + name, this::pumpMeshToLocal);
        Threads.start("etmc-bridge-tx-" + name, this::pumpLocalToMesh);
    }

    private void pumpMeshToLocal() {
        byte[] tmp = new byte[BUF];
        try {
            OutputStream out = local.getOutputStream();
            while (!closed.get()) {
                int n = et.tcpRead(stream, tmp, BUF, READ_TIMEOUT_MS);
                if (n <= 0) break; // end of stream
                out.write(tmp, 0, n);
                out.flush();
            }
        } catch (Throwable t) {
            ended("mesh -> local", t);
        } finally {
            close();
        }
    }

    private void pumpLocalToMesh() {
        byte[] tmp = new byte[BUF];
        try {
            InputStream in = local.getInputStream();
            while (!closed.get()) {
                int n = in.read(tmp);
                if (n < 0) break; // EOF
                if (n == 0) continue;
                et.tcpWrite(stream, tmp, n, WRITE_TIMEOUT_MS);
            }
        } catch (Throwable t) {
            ended("local -> mesh", t);
        } finally {
            close();
        }
    }

    /**
     * Logs why a pump stopped, unless it was this bridge being closed (the other pump finishing, or a
     * session teardown) — that is the normal way out, and every connection ends with one of those.
     */
    private void ended(String direction, Throwable t) {
        if (closed.get()) return;
        if (t instanceof EasyTierException e && e.is(DataPlaneError.HANDLE_CLOSED)) return;
        LOG.log(System.Logger.Level.INFO, "etmc connection with " + name + " dropped (" + direction + "): "
                + Errors.message(t));
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Io.closeStream(et, stream);
        Io.closeQuietly(local);
        if (onClose != null) {
            try {
                onClose.accept(this);
            } catch (Throwable ignored) {
            }
        }
    }
}

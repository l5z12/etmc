package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final int BUF = 32 * 1024;
    /** Long: the read returns promptly on data or on close (handle cancellation), not on this timeout. */
    private static final long READ_TIMEOUT_MS = 3_600_000L;
    private static final long WRITE_TIMEOUT_MS = 60_000L;

    private final EasyTier et;
    private final Socket local;
    private final long stream;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TcpBridge(EasyTier et, Socket local, long stream, Runnable onClose) {
        this.et = et;
        this.local = local;
        this.stream = stream;
        this.onClose = onClose;
    }

    /** Starts both pump threads. Non-blocking. */
    public void start(String name) {
        Thread a = new Thread(this::pumpMeshToLocal, "etmc-bridge-rx-" + name);
        Thread b = new Thread(this::pumpLocalToMesh, "etmc-bridge-tx-" + name);
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
    }

    private void pumpMeshToLocal() {
        byte[] tmp = new byte[BUF];
        try {
            OutputStream out = local.getOutputStream();
            while (!closed.get()) {
                int n = et.tcpRead(stream, tmp, BUF, READ_TIMEOUT_MS);
                if (n <= 0) break; // 0 = EOF, -1 = error/closed
                out.write(tmp, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
            // fall through to close
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
                int w = et.tcpWrite(stream, tmp, n, WRITE_TIMEOUT_MS);
                if (w < 0) break;
            }
        } catch (Throwable ignored) {
            // fall through to close
        } finally {
            close();
        }
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            et.tcpClose(stream);
        } catch (Throwable ignored) {
        }
        try {
            local.close();
        } catch (Throwable ignored) {
        }
        if (onClose != null) {
            try {
                onClose.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }
}

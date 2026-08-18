package dev.l5z12.etmc.core;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

/** Loopback socket helpers shared by the bridge tests. */
final class Sockets {

    private Sockets() {}

    static ServerSocket listen() throws IOException {
        return new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
    }

    static Socket connect(int port) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
        return s;
    }

    /**
     * True once the far end has gone away. Checked by reading rather than by {@code isClosed()},
     * which only reports whether <em>we</em> closed our own socket.
     */
    static boolean peerGone(Socket s) {
        try {
            s.setSoTimeout(50);
            return s.getInputStream().read() < 0;
        } catch (SocketTimeoutException stillOpen) {
            return false;
        } catch (IOException closed) {
            return true;
        }
    }

    static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    static void closeQuietly(ServerSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}

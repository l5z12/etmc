package dev.l5z12.etmc.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.l5z12.etmc.ffi.EasyTier;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Performs a Minecraft Server List Ping (status) over the EasyTier data plane to read the host
 * server's protocol version. This mirrors what ViaFabricPlus' own detector does, except it rides the
 * mesh ({@code et.tcp*}) instead of a raw {@code java.net.Socket} — which is exactly why VFP's
 * auto-detect can't do it for an {@code etmc://} join: VFP sockets the placeholder address and reaches
 * nothing. We detect the version here and hand it to VFP so it skips its own (doomed) probe.
 *
 * <p>Blocking; call off the render thread. Returns the protocol number, or {@code -1} on any failure.
 */
public final class StatusPing {

    private static final long IO_TIMEOUT_MS = 8_000L;
    private static final int MAX_FRAME = 1 << 23; // 8 MiB — status JSON with a favicon can be sizable

    private StatusPing() {}

    public static int protocolVersion(EasyTier et, String inst, String hostIp, int hostPort) {
        long stream = 0;
        try {
            EasyTier.Bind c = et.tcpConnect(inst, hostIp, hostPort, IO_TIMEOUT_MS);
            stream = c.handle();

            // Handshake (next state = 1 = status), then Status Request, sent back to back.
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0x00);            // packet id: handshake
            writeVarInt(hs, -1);              // protocol version (unknown; status is answered regardless)
            writeString(hs, hostIp);          // server address
            hs.write((hostPort >> 8) & 0xFF); // server port (unsigned short, big-endian)
            hs.write(hostPort & 0xFF);
            writeVarInt(hs, 1);               // next state: status
            writeFrame(et, stream, hs.toByteArray());

            ByteArrayOutputStream req = new ByteArrayOutputStream();
            writeVarInt(req, 0x00);           // packet id: status request (no fields)
            writeFrame(et, stream, req.toByteArray());

            // Read the Status Response frame: VarInt(len){ VarInt(packetId) VarInt(jsonLen) json }.
            Reader r = new Reader(et, stream);
            int frameLen = r.readVarInt();
            if (frameLen <= 0 || frameLen > MAX_FRAME) return -1;
            byte[] frame = r.readBytes(frameLen);

            int[] pos = {0};
            int packetId = readVarIntFrom(frame, pos);
            if (packetId != 0x00) return -1;
            int jsonLen = readVarIntFrom(frame, pos);
            if (jsonLen <= 0 || pos[0] + jsonLen > frame.length) return -1;
            String json = new String(frame, pos[0], jsonLen, StandardCharsets.UTF_8);
            return parseProtocol(json);
        } catch (Throwable t) {
            return -1;
        } finally {
            if (stream != 0) {
                try {
                    et.tcpClose(stream);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int parseProtocol(String json) {
        try {
            // Gson.fromJson(String, Class) is stable across the Gson versions bundled with every MC
            // release here; JsonParser.parseString is too new for 1.17.1's Gson.
            JsonObject root = new Gson().fromJson(json, JsonObject.class);
            JsonObject ver = root == null ? null : root.getAsJsonObject("version");
            if (ver != null && ver.has("protocol")) {
                return ver.get("protocol").getAsInt();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void writeFrame(EasyTier et, long stream, byte[] body) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, body.length);
        framed.write(body, 0, body.length);
        byte[] out = framed.toByteArray();
        if (et.tcpWrite(stream, out, out.length, IO_TIMEOUT_MS) < 0) {
            throw new IOException("etmc status-ping write failed");
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, b.length);
        out.write(b, 0, b.length);
    }

    private static int readVarIntFrom(byte[] a, int[] pos) {
        int value = 0, shift = 0, b;
        do {
            b = a[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IllegalStateException("VarInt too long");
        } while ((b & 0x80) != 0);
        return value;
    }

    /** Buffered byte source over the blocking {@code et.tcpRead} (which returns partial chunks). */
    private static final class Reader {
        private final EasyTier et;
        private final long stream;
        private final byte[] tmp = new byte[8192];
        private byte[] data = new byte[0];
        private int pos = 0;

        Reader(EasyTier et, long stream) {
            this.et = et;
            this.stream = stream;
        }

        int readByte() throws IOException {
            if (pos >= data.length) {
                int n = et.tcpRead(stream, tmp, tmp.length, IO_TIMEOUT_MS);
                if (n <= 0) throw new EOFException();
                int rem = data.length - pos;
                byte[] nd = new byte[rem + n];
                System.arraycopy(data, pos, nd, 0, rem);
                System.arraycopy(tmp, 0, nd, rem, n);
                data = nd;
                pos = 0;
            }
            return data[pos++] & 0xFF;
        }

        int readVarInt() throws IOException {
            int value = 0, shift = 0, b;
            do {
                b = readByte();
                value |= (b & 0x7F) << shift;
                shift += 7;
                if (shift > 35) throw new IOException("VarInt too long");
            } while ((b & 0x80) != 0);
            return value;
        }

        byte[] readBytes(int len) throws IOException {
            byte[] out = new byte[len];
            for (int i = 0; i < len; i++) {
                out[i] = (byte) readByte();
            }
            return out;
        }
    }
}

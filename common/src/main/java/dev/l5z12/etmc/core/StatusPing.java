package dev.l5z12.etmc.core;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.l5z12.etmc.ffi.DataPlaneError;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.EasyTierException;

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
 * <p>The same ping also tells a joiner whether the host's world is there at all, before Minecraft
 * connects: see {@link #probe}.
 *
 * <p>Blocking; call off the render thread.
 */
public final class StatusPing {

    /** What a ping found at the host's address. */
    public enum Outcome {
        /** A Minecraft server answered. */
        ANSWERED,
        /**
         * The stream opened but nothing ever came back. EasyTier reports a connect to a virtual port
         * nobody listens on as done and then stays silent, so this is what "the host's network is up but
         * nothing is hosted on that port" looks like.
         */
        SILENT,
        /** Something there hung up: the host's bridge couldn't reach its world, or status is turned off. */
        CLOSED,
        /** The host's address couldn't be reached over the network at all. */
        UNREACHABLE,
        /** Something answered, but not with a server-list reply. */
        BAD_REPLY
    }

    /** A ping's outcome, with the server's protocol number when it {@link Outcome#ANSWERED} (else -1). */
    public record Result(Outcome outcome, int protocol) {}

    private static final long IO_TIMEOUT_MS = 8_000L;
    private static final int MAX_FRAME = 1 << 23; // 8 MiB — status JSON with a favicon can be sizable

    private StatusPing() {}

    /** The host's protocol number, or {@code -1} on any failure. */
    public static int protocolVersion(EasyTier et, String inst, String hostIp, int hostPort) {
        return probe(et, inst, hostIp, hostPort, IO_TIMEOUT_MS).protocol();
    }

    /** Pings the host, waiting at most {@code timeoutMs} for each step, and says what it found. */
    public static Result probe(EasyTier et, String inst, String hostIp, int hostPort, long timeoutMs) {
        long stream;
        try {
            stream = et.tcpConnect(inst, hostIp, hostPort, timeoutMs).handle();
        } catch (Throwable t) {
            return new Result(Outcome.UNREACHABLE, -1);
        }
        try {

            // Handshake (next state = 1 = status), then Status Request, sent back to back.
            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, 0x00);            // packet id: handshake
            writeVarInt(hs, -1);              // protocol version (unknown; status is answered regardless)
            writeString(hs, hostIp);          // server address
            hs.write((hostPort >> 8) & 0xFF); // server port (unsigned short, big-endian)
            hs.write(hostPort & 0xFF);
            writeVarInt(hs, 1);               // next state: status
            writeFrame(et, stream, hs.toByteArray(), timeoutMs);

            ByteArrayOutputStream req = new ByteArrayOutputStream();
            writeVarInt(req, 0x00);           // packet id: status request (no fields)
            writeFrame(et, stream, req.toByteArray(), timeoutMs);

            // Read the Status Response frame: VarInt(len){ VarInt(packetId) VarInt(jsonLen) json }.
            Reader r = new Reader(et, stream, timeoutMs);
            int frameLen = readVarInt(r::readByte);
            if (frameLen <= 0 || frameLen > MAX_FRAME) return badReply();
            byte[] frame = r.readBytes(frameLen);

            Cursor body = new Cursor(frame);
            int packetId;
            int jsonLen;
            try {
                packetId = readVarInt(body);
                if (packetId != 0x00) return badReply();
                jsonLen = readVarInt(body);
            } catch (IOException e) {
                return badReply(); // the frame arrived whole; it's what's inside that's wrong
            }
            if (jsonLen <= 0 || body.pos + jsonLen > frame.length) return badReply();
            String json = new String(frame, body.pos, jsonLen, StandardCharsets.UTF_8);
            int protocol = parseProtocol(json);
            return protocol < 0 ? badReply() : new Result(Outcome.ANSWERED, protocol);
        } catch (EasyTierException e) {
            return new Result(e.is(DataPlaneError.DEADLINE_EXCEEDED) ? Outcome.SILENT : Outcome.CLOSED, -1);
        } catch (Throwable t) {
            return new Result(Outcome.CLOSED, -1); // end of stream, mid-frame or before a byte
        } finally {
            Io.closeStream(et, stream);
        }
    }

    private static Result badReply() {
        return new Result(Outcome.BAD_REPLY, -1);
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

    private static void writeFrame(EasyTier et, long stream, byte[] body, long timeoutMs) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, body.length);
        framed.write(body, 0, body.length);
        byte[] out = framed.toByteArray();
        if (et.tcpWrite(stream, out, out.length, timeoutMs) < 0) {
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

    /** One byte at a time, from either the live mesh stream or an already-buffered frame. */
    private interface ByteSource {
        int nextByte() throws IOException;
    }

    /**
     * Reads one Minecraft VarInt. Capped at the 5 bytes the protocol allows: a 6th byte would shift
     * past the width of an {@code int} (Java masks the shift distance, so it would silently corrupt
     * the value instead of overflowing) and no honest server sends one.
     */
    private static int readVarInt(ByteSource src) throws IOException {
        int value = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int b = src.nextByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("VarInt longer than 5 bytes");
    }

    /** A read position in an already-buffered frame. */
    private static final class Cursor implements ByteSource {
        private final byte[] data;
        private int pos = 0;

        Cursor(byte[] data) {
            this.data = data;
        }

        @Override
        public int nextByte() throws IOException {
            if (pos >= data.length) throw new EOFException("truncated VarInt");
            return data[pos++] & 0xFF;
        }
    }

    /** Buffered byte source over the blocking {@code et.tcpRead} (which returns partial chunks). */
    private static final class Reader {
        private static final int CHUNK = 8192;

        private final EasyTier et;
        private final long stream;
        private final long timeoutMs;
        private final byte[] buf = new byte[CHUNK];
        private int pos = 0;
        private int end = 0;

        Reader(EasyTier et, long stream, long timeoutMs) {
            this.et = et;
            this.stream = stream;
            this.timeoutMs = timeoutMs;
        }

        /** Refills the buffer; throws {@link EOFException} at end of stream. */
        private void fill() throws IOException {
            int n = et.tcpRead(stream, buf, buf.length, timeoutMs);
            if (n <= 0) throw new EOFException();
            pos = 0;
            end = n;
        }

        int readByte() throws IOException {
            if (pos >= end) fill();
            return buf[pos++] & 0xFF;
        }

        byte[] readBytes(int len) throws IOException {
            byte[] out = new byte[len];
            int got = 0;
            while (got < len) {
                if (pos >= end) fill();
                int n = Math.min(end - pos, len - got);
                System.arraycopy(buf, pos, out, got, n);
                pos += n;
                got += n;
            }
            return out;
        }
    }
}

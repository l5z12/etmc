package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Server List Ping etmc speaks over the data plane to learn a host's protocol version — the
 * thing ViaFabricPlus cannot probe for an {@code etmc://} join, because it would socket the
 * placeholder address. Driven against the in-memory FFI fake, so the VarInt framing is exercised
 * without a network or the native library.
 */
class StatusPingTest {

    private final FakeEasyTier et = new FakeEasyTier();
    private final AtomicReference<FakeEasyTier.Stream> peer = new AtomicReference<>();

    /** Scripts what the "host" does the moment etmc dials it. */
    private void host(Consumer<FakeEasyTier.Stream> script) {
        et.onConnect = s -> {
            peer.set(s);
            script.accept(s);
        };
    }

    private int ping() {
        return StatusPing.protocolVersion(et, "inst", "10.126.126.1", 25565);
    }

    @Test
    void readsTheProtocolVersionFromTheStatusResponse() {
        host(s -> s.deliver(statusFrame("{\"version\":{\"name\":\"1.21.10\",\"protocol\":774}}")));

        assertEquals(774, ping());
        assertTrue(peer.get().isClosed(), "the data-plane stream must be released even on success");
    }

    @Test
    void sendsAWellFormedHandshakeAndStatusRequest() {
        host(s -> s.deliver(statusFrame("{\"version\":{\"protocol\":47}}")));
        ping();

        byte[] sent = peer.get().written();
        int[] pos = {0};
        int handshakeLen = readVarInt(sent, pos);
        int handshakeEnd = pos[0] + handshakeLen;
        assertEquals(0x00, readVarInt(sent, pos), "packet id: handshake");
        readVarInt(sent, pos);                                  // protocol version (unknown)
        int addrLen = readVarInt(sent, pos);
        assertEquals("10.126.126.1", new String(sent, pos[0], addrLen, StandardCharsets.UTF_8));
        pos[0] += addrLen;
        assertEquals(25565, ((sent[pos[0]] & 0xFF) << 8) | (sent[pos[0] + 1] & 0xFF), "port, big-endian");
        pos[0] += 2;
        assertEquals(1, readVarInt(sent, pos), "next state: status");
        assertEquals(handshakeEnd, pos[0], "the frame length must match what follows it");

        assertEquals(1, readVarInt(sent, pos), "status request is one byte long");
        assertEquals(0x00, readVarInt(sent, pos), "packet id: status request");
        assertEquals(sent.length, pos[0], "nothing else may be sent");
    }

    @Test
    void aResponseSplitAcrossReadsIsReassembled() {
        byte[] full = statusFrame("{\"version\":{\"protocol\":765}}");
        host(s -> {
            for (int i = 0; i < full.length; i += 3) { // many partial reads, as a real stream gives
                s.deliver(java.util.Arrays.copyOfRange(full, i, Math.min(i + 3, full.length)));
            }
        });

        assertEquals(765, ping());
    }

    @Test
    void anUnreachableHostReportsUnknown() {
        et.connectFailure = new IllegalStateException("data_plane_tcp_connect failed: no route");
        assertEquals(-1, ping());
    }

    @Test
    void aHostThatHangsUpReportsUnknown() {
        host(FakeEasyTier.Stream::close);
        assertEquals(-1, ping());
    }

    @Test
    void anUnparseableResponseReportsUnknown() {
        host(s -> s.deliver(statusFrame("not json")));
        assertEquals(-1, ping());
    }

    @Test
    void aResponseThatIsNotTheStatusPacketReportsUnknown() {
        byte[] wrongId = frame(concat(varInt(0x01), varInt(2), "{}".getBytes(StandardCharsets.UTF_8)));
        host(s -> s.deliver(wrongId));
        assertEquals(-1, ping());
    }

    @Test
    void aVarIntLongerThanFiveBytesReportsUnknown() {
        // The packet id, padded out to six bytes. Uncapped, this still decodes to 0x00 — Java masks
        // the shift distance, so the sixth byte folds back onto the low bits instead of overflowing —
        // and the ping would go on to report 774 off a frame no honest server sends.
        byte[] overlongZero = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00};
        byte[] json = "{\"version\":{\"protocol\":774}}".getBytes(StandardCharsets.UTF_8);
        host(s -> s.deliver(frame(concat(overlongZero, varInt(json.length), json))));

        assertEquals(-1, ping());
    }

    @Test
    void aVarIntRunningOffTheEndOfTheFrameReportsUnknown() {
        // The frame's own length prefix is honest, but the JSON length inside it never terminates.
        // The body cursor has to stop at the frame boundary rather than read past it.
        host(s -> s.deliver(frame(concat(varInt(0x00), new byte[] {(byte) 0x80, (byte) 0x80}))));

        assertEquals(-1, ping());
        assertTrue(peer.get().isClosed(), "a malformed body must still release the data-plane stream");
    }

    @Test
    void aTruncatedFrameReportsUnknown() {
        // claims 64 bytes but only sends a few, then hangs up
        byte[] truncated = concat(varInt(64), varInt(0x00), varInt(60));
        host(s -> {
            s.deliver(truncated);
            s.close();
        });
        assertEquals(-1, ping());
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] statusFrame(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return frame(concat(varInt(0x00), varInt(body.length), body));
    }

    /** Wraps a packet body in its length prefix. */
    private static byte[] frame(byte[] body) {
        return concat(varInt(body.length), body);
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static int readVarInt(byte[] a, int[] pos) {
        int value = 0, shift = 0, b;
        do {
            b = a[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
}

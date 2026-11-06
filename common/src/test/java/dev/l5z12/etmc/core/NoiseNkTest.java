package dev.l5z12.etmc.core;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link NoiseNk} against the {@code Noise_NK_25519_ChaChaPoly_SHA256} vector from the cacophony test
 * suite, which other Noise libraries are checked against too. A round trip through this class alone
 * would pass with any self-consistent mistake; matching these bytes means it really is Noise.
 */
class NoiseNkTest {

    private static final HexFormat HEX = HexFormat.of();

    private static final byte[] PROLOGUE = HEX.parseHex("4a6f686e2047616c74");
    private static final byte[] INIT_EPHEMERAL =
            HEX.parseHex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a");
    private static final byte[] RESP_STATIC_PUBLIC =
            HEX.parseHex("31e0303fd6418d2f8c0e78b91f22e8caed0fbe48656dcf4767e4834f701b8f62");
    private static final byte[] RESP_STATIC =
            HEX.parseHex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893");
    private static final byte[] RESP_EPHEMERAL =
            HEX.parseHex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b");
    private static final byte[] HANDSHAKE_HASH =
            HEX.parseHex("2efa38a9c7c93ac98f3a097af25c2f58b9e7673787717bc27e98827118c2c1a5");

    /** Payload and expected ciphertext of each message, alternating initiator and responder. */
    private static final String[][] MESSAGES = {
            {"4c756477696720766f6e204d69736573",
                    "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c7944"
                            + "8134d00711fdb390a0d178fa008f6d47d2891e5ea18ae136c3b4c23ac384efb0"},
            {"4d757272617920526f746862617264",
                    "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843"
                            + "8ea16e3701bc0d77744f117bee22451c9afa7f4cdbbcff00c04a8ee0913c88"},
            {"462e20412e20486179656b", "a62de29ce27cb80245d440d986ed816c156e9d757d7008df2198b0"},
            {"4361726c204d656e676572", "174a35f11c689f4530d7208618e0564ae12f2f50ba8eb4df5382ff"},
            {"4a65616e2d426170746973746520536179",
                    "337e475ebb8eae60f91974c4e455a5af38d1d8628d1803b160d60442874b0a1777"},
            {"457567656e2042f6686d20766f6e2042617765726b",
                    "047e80e060b7bb08b53c5a23dfe9920cae135b9d1dc6302fc475003062723700366346ac9d"},
    };

    private static byte[] payload(int i) {
        return HEX.parseHex(MESSAGES[i][0]);
    }

    private static byte[] ciphertext(int i) {
        return HEX.parseHex(MESSAGES[i][1]);
    }

    @Test
    void aPublicKeyIsDerivedFromItsPrivateKey() throws Exception {
        assertArrayEquals(RESP_STATIC_PUBLIC, NoiseNk.publicKey(RESP_STATIC));
    }

    @Test
    void matchesThePublishedVector() throws Exception {
        NoiseNk initiator = NoiseNk.initiator(PROLOGUE, RESP_STATIC_PUBLIC, INIT_EPHEMERAL);
        NoiseNk responder = NoiseNk.responder(PROLOGUE, RESP_STATIC, RESP_EPHEMERAL);

        byte[] first = initiator.writeFirst(payload(0));
        assertArrayEquals(ciphertext(0), first);
        assertArrayEquals(payload(0), responder.readFirst(first));

        byte[] second = responder.writeSecond(payload(1));
        assertArrayEquals(ciphertext(1), second);
        assertArrayEquals(payload(1), initiator.readSecond(second));

        assertArrayEquals(HANDSHAKE_HASH, initiator.handshakeHash());
        assertArrayEquals(HANDSHAKE_HASH, responder.handshakeHash());

        NoiseNk.Transport joiner = initiator.split();
        NoiseNk.Transport host = responder.split();
        for (int i = 2; i < MESSAGES.length; i++) {
            NoiseNk.Transport from = i % 2 == 0 ? joiner : host;
            NoiseNk.Transport to = i % 2 == 0 ? host : joiner;
            byte[] plain = payload(i);
            byte[] sealed = from.send().seal(plain, 0, plain.length);
            assertArrayEquals(ciphertext(i), sealed, "transport message " + i);
            assertArrayEquals(plain, to.receive().open(sealed, 0, sealed.length));
        }
    }

    @Test
    void anImpostorWithTheWrongKeyCannotAnswer() throws Exception {
        byte[] prologue = "test".getBytes(StandardCharsets.UTF_8);
        NoiseNk joiner = NoiseNk.initiator(prologue, RESP_STATIC_PUBLIC);
        NoiseNk impostor = NoiseNk.responder(prologue, NoiseNk.newPrivateKey());

        byte[] first = joiner.writeFirst(new byte[0]);
        assertThrows(AEADBadTagException.class, () -> impostor.readFirst(first));
    }

    @Test
    void aReplayedFrameDoesNotOpen() throws Exception {
        byte[] prologue = "test".getBytes(StandardCharsets.UTF_8);
        NoiseNk initiator = NoiseNk.initiator(prologue, RESP_STATIC_PUBLIC);
        NoiseNk responder = NoiseNk.responder(prologue, RESP_STATIC);
        responder.readFirst(initiator.writeFirst(new byte[0]));
        initiator.readSecond(responder.writeSecond(new byte[0]));
        NoiseNk.Cipher send = initiator.split().send();
        NoiseNk.Cipher receive = responder.split().receive();

        byte[] plain = {1, 2, 3};
        byte[] sealed = send.seal(plain, 0, plain.length);
        assertEquals(plain.length, receive.open(sealed, 0, sealed.length).length);
        assertThrows(AEADBadTagException.class, () -> receive.open(sealed, 0, sealed.length));
    }
}

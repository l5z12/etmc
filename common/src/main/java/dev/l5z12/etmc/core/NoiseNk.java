package dev.l5z12.etmc.core;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;

/**
 * The {@code Noise_NK_25519_ChaChaPoly_SHA256} handshake (<a href="https://noiseprotocol.org/noise.html">
 * the Noise Protocol Framework</a>), on nothing but the JDK's own primitives.
 *
 * <p>NK fits an invite link exactly: the joiner already knows the host's static key, from the link,
 * and the host knows nothing about the joiner. Only the holder of that key can answer the first
 * message, and the {@link Cipher}s it leaves behind are what stop an impostor relaying the handshake
 * to the real host and keeping the connection for itself.
 *
 * <pre>
 *   &lt;- s
 *   ...
 *   -&gt; e, es
 *   &lt;- e, ee
 * </pre>
 *
 * <p>This does no I/O ({@link HostTunnel} does) and is checked against the published test vectors in
 * {@code NoiseNkTest}. Not thread-safe; a handshake belongs to one connection.
 */
final class NoiseNk {

    static final int KEY_LEN = 32;
    static final int TAG_LEN = 16;

    private static final byte[] PROTOCOL = "Noise_NK_25519_ChaChaPoly_SHA256".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NO_DATA = new byte[0];
    /** The X25519 base point, which turns a private key into its public key. */
    private static final byte[] BASE_POINT = basePoint();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final boolean initiator;
    private final byte[] localStatic;
    private final byte[] remoteStatic;
    private final byte[] ephemeral;
    private byte[] remoteEphemeral;

    private byte[] chainingKey;
    private byte[] hash;
    private byte[] key;

    private NoiseNk(boolean initiator, byte[] prologue, byte[] localStatic, byte[] remoteStatic,
                    byte[] ephemeral) throws GeneralSecurityException {
        this.initiator = initiator;
        this.localStatic = localStatic;
        this.remoteStatic = remoteStatic;
        this.ephemeral = ephemeral;
        // The protocol name is exactly HASHLEN long, so it is used as it stands rather than hashed.
        this.hash = PROTOCOL.clone();
        this.chainingKey = PROTOCOL.clone();
        mixHash(prologue);
        mixHash(initiator ? remoteStatic : publicKey(localStatic)); // the "<- s" pre-message
    }

    /** The joiner's side, knowing the host's public key. */
    static NoiseNk initiator(byte[] prologue, byte[] hostPublic) throws GeneralSecurityException {
        return initiator(prologue, hostPublic, newPrivateKey());
    }

    /** {@link #initiator(byte[], byte[])} with a chosen ephemeral key, for the test vectors. */
    static NoiseNk initiator(byte[] prologue, byte[] hostPublic, byte[] ephemeral) throws GeneralSecurityException {
        return new NoiseNk(true, prologue, null, hostPublic, ephemeral);
    }

    /** The host's side, holding its private key. */
    static NoiseNk responder(byte[] prologue, byte[] hostPrivate) throws GeneralSecurityException {
        return responder(prologue, hostPrivate, newPrivateKey());
    }

    /** {@link #responder(byte[], byte[])} with a chosen ephemeral key, for the test vectors. */
    static NoiseNk responder(byte[] prologue, byte[] hostPrivate, byte[] ephemeral) throws GeneralSecurityException {
        return new NoiseNk(false, prologue, hostPrivate, null, ephemeral);
    }

    /** The joiner's message, {@code e, es}. */
    byte[] writeFirst(byte[] payload) throws GeneralSecurityException {
        byte[] e = publicKey(ephemeral);
        mixHash(e);
        mixKey(dh(ephemeral, remoteStatic));
        return concat(e, encryptAndHash(payload));
    }

    /** Reads the joiner's message, returning its payload. Fails if it wasn't made for this host key. */
    byte[] readFirst(byte[] message) throws GeneralSecurityException {
        remoteEphemeral = Arrays.copyOf(message, KEY_LEN);
        mixHash(remoteEphemeral);
        mixKey(dh(localStatic, remoteEphemeral));
        return decryptAndHash(Arrays.copyOfRange(message, KEY_LEN, message.length));
    }

    /** The host's message, {@code e, ee}. */
    byte[] writeSecond(byte[] payload) throws GeneralSecurityException {
        byte[] e = publicKey(ephemeral);
        mixHash(e);
        mixKey(dh(ephemeral, remoteEphemeral));
        return concat(e, encryptAndHash(payload));
    }

    /** Reads the host's message, returning its payload. Failing here means this isn't the host. */
    byte[] readSecond(byte[] message) throws GeneralSecurityException {
        byte[] e = Arrays.copyOf(message, KEY_LEN);
        mixHash(e);
        mixKey(dh(ephemeral, e));
        return decryptAndHash(Arrays.copyOfRange(message, KEY_LEN, message.length));
    }

    /** The handshake's hash; both sides agree on it, and the test vectors pin it. */
    byte[] handshakeHash() {
        return hash.clone();
    }

    /** Ends the handshake with the two one-way ciphers for the connection. */
    Transport split() throws GeneralSecurityException {
        byte[][] keys = hkdf(chainingKey, NO_DATA);
        Cipher first = new Cipher(keys[0]);
        Cipher second = new Cipher(keys[1]);
        return initiator ? new Transport(first, second) : new Transport(second, first);
    }

    /** The two directions of a finished handshake. */
    record Transport(Cipher send, Cipher receive) {}

    // ------------------------------------------------------------------ symmetric state

    private void mixHash(byte[] data) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(hash);
        hash = sha.digest(data);
    }

    private void mixKey(byte[] input) throws GeneralSecurityException {
        byte[][] out = hkdf(chainingKey, input);
        chainingKey = out[0];
        key = out[1];
    }

    private byte[] encryptAndHash(byte[] plain) throws GeneralSecurityException {
        byte[] sealed = new Cipher(key).seal(hash, plain);
        mixHash(sealed);
        return sealed;
    }

    private byte[] decryptAndHash(byte[] sealed) throws GeneralSecurityException {
        byte[] plain = new Cipher(key).open(hash, sealed);
        mixHash(sealed);
        return plain;
    }

    private static byte[][] hkdf(byte[] salt, byte[] input) throws GeneralSecurityException {
        byte[] temp = hmac(salt, input);
        byte[] first = hmac(temp, new byte[]{1});
        byte[] second = hmac(temp, concat(first, new byte[]{2}));
        return new byte[][]{first, second};
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ------------------------------------------------------------------ X25519

    /** A fresh X25519 private key. Any 32 random bytes are one: the curve clamps them when used. */
    static byte[] newPrivateKey() {
        byte[] k = new byte[KEY_LEN];
        RANDOM.nextBytes(k);
        return k;
    }

    /** The public key of {@code privateKey}. */
    static byte[] publicKey(byte[] privateKey) throws GeneralSecurityException {
        return dh(privateKey, BASE_POINT);
    }

    /**
     * X25519. The JDK refuses a low-order public key (an all-zero result) by throwing, which is the
     * check Noise leaves to the implementation.
     */
    private static byte[] dh(byte[] privateKey, byte[] publicKey) throws GeneralSecurityException {
        KeyFactory keys = KeyFactory.getInstance("XDH");
        PrivateKey priv = keys.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, privateKey));
        PublicKey pub = keys.generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, littleEndian(publicKey)));
        KeyAgreement agreement = KeyAgreement.getInstance("XDH");
        agreement.init(priv);
        agreement.doPhase(pub, true);
        return agreement.generateSecret();
    }

    /** Reads a public key as RFC 7748 does: little-endian, with the top bit ignored. */
    private static BigInteger littleEndian(byte[] u) {
        byte[] big = new byte[KEY_LEN];
        for (int i = 0; i < KEY_LEN; i++) big[i] = u[KEY_LEN - 1 - i];
        big[0] &= 0x7F;
        return new BigInteger(1, big);
    }

    private static byte[] basePoint() {
        byte[] u = new byte[KEY_LEN];
        u[0] = 9;
        return u;
    }

    // ------------------------------------------------------------------ cipher

    /**
     * One direction of ChaCha20-Poly1305 with Noise's counting nonce. Encrypting and decrypting each
     * advance it, so a frame that was replayed, dropped or reordered fails to open.
     */
    static final class Cipher {

        private final SecretKeySpec key;
        private final javax.crypto.Cipher cipher;
        private long nonce;

        Cipher(byte[] key) throws GeneralSecurityException {
            this.key = new SecretKeySpec(key, "ChaCha20");
            this.cipher = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305");
        }

        byte[] seal(byte[] ad, byte[] plain) throws GeneralSecurityException {
            return run(javax.crypto.Cipher.ENCRYPT_MODE, ad, plain, 0, plain.length);
        }

        byte[] seal(byte[] plain, int offset, int length) throws GeneralSecurityException {
            return run(javax.crypto.Cipher.ENCRYPT_MODE, NO_DATA, plain, offset, length);
        }

        byte[] open(byte[] ad, byte[] sealed) throws GeneralSecurityException {
            return run(javax.crypto.Cipher.DECRYPT_MODE, ad, sealed, 0, sealed.length);
        }

        byte[] open(byte[] sealed, int offset, int length) throws GeneralSecurityException {
            return run(javax.crypto.Cipher.DECRYPT_MODE, NO_DATA, sealed, offset, length);
        }

        private byte[] run(int mode, byte[] ad, byte[] data, int offset, int length) throws GeneralSecurityException {
            // Four zero bytes, then the counter little-endian: Noise's ChaChaPoly nonce.
            byte[] iv = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putLong(nonce).array();
            cipher.init(mode, key, new IvParameterSpec(iv));
            cipher.updateAAD(ad);
            byte[] out = cipher.doFinal(data, offset, length);
            nonce++;
            return out;
        }
    }
}

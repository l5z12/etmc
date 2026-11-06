package dev.l5z12.etmc.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collection;
import java.util.UUID;

/**
 * A host's proof that it is a real Minecraft account, for hosts who choose to give one.
 *
 * <p>Two signatures do this. Mojang's, from the player certificate, ties a public key to an account
 * ID. The host's, made with that key, ties the account to this one {@link NoiseNk} handshake. The
 * handshake hash covers both sides' fresh keys and the host key, so a proof can't be replayed on
 * another connection or lifted onto another host key, and it never goes in a link, where it would
 * outlive the certificate.
 *
 * <p>The key signing here also signs the player's chat. {@link #DOMAIN} keeps the two apart: a chat
 * signature's data opens with a version number, so this can never be mistaken for one, and the rest
 * is a hash nobody chooses, so a joiner can't get anything else signed.
 */
final class HostAccount {

    private static final byte[] DOMAIN = "etmc host account proof v1\n".getBytes(StandardCharsets.US_ASCII);
    private static final String MOJANG_ALGORITHM = "SHA1withRSA";
    private static final String PROOF_ALGORITHM = "SHA256withRSA";

    private HostAccount() {}

    /** The proof for this handshake: the certificate's public parts, and a signature. */
    static byte[] prove(PlayerCert cert, byte[] handshakeHash) throws GeneralSecurityException {
        Signature signer = Signature.getInstance(PROOF_ALGORITHM);
        signer.initSign(cert.privateKey());
        signer.update(DOMAIN);
        signer.update(handshakeHash);
        byte[] proof = signer.sign();

        ByteBuffer out = ByteBuffer.allocate(24 + 6 + cert.publicKey().length + cert.mojangSignature().length + proof.length);
        out.putLong(cert.uuid().getMostSignificantBits());
        out.putLong(cert.uuid().getLeastSignificantBits());
        out.putLong(cert.expiresAtMs());
        putBlock(out, cert.publicKey());
        putBlock(out, cert.mojangSignature());
        putBlock(out, proof);
        return out.array();
    }

    /**
     * Checks a proof. The account comes back only if Mojang vouches for the key, the certificate is
     * still in date, and that key signed this very handshake.
     *
     * @return the proven account, or {@code null} if this proof doesn't hold
     */
    static UUID verify(byte[] claim, byte[] handshakeHash, Collection<PublicKey> mojangKeys, long nowMs) {
        try {
            ByteBuffer in = ByteBuffer.wrap(claim);
            UUID account = new UUID(in.getLong(), in.getLong());
            long expiresAtMs = in.getLong();
            byte[] publicKey = getBlock(in);
            byte[] mojangSignature = getBlock(in);
            byte[] proof = getBlock(in);

            if (expiresAtMs <= nowMs) return null;
            if (!mojangSigned(account, expiresAtMs, publicKey, mojangSignature, mojangKeys)) return null;

            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKey));
            Signature checker = Signature.getInstance(PROOF_ALGORITHM);
            checker.initVerify(key);
            checker.update(DOMAIN);
            checker.update(handshakeHash);
            return checker.verify(proof) ? account : null;
        } catch (GeneralSecurityException | RuntimeException e) {
            return null; // short or malformed: whoever sent it, it proves nothing
        }
    }

    /** What the game checks in {@code ProfilePublicKey.Data}: the account, the expiry and the key. */
    static byte[] certifiedBytes(UUID account, long expiresAtMs, byte[] publicKey) {
        return ByteBuffer.allocate(24 + publicKey.length)
                .putLong(account.getMostSignificantBits())
                .putLong(account.getLeastSignificantBits())
                .putLong(expiresAtMs)
                .put(publicKey)
                .array();
    }

    private static boolean mojangSigned(UUID account, long expiresAtMs, byte[] publicKey, byte[] signature,
                                        Collection<PublicKey> mojangKeys) throws GeneralSecurityException {
        byte[] certified = certifiedBytes(account, expiresAtMs, publicKey);
        for (PublicKey mojang : mojangKeys) {
            Signature checker = Signature.getInstance(MOJANG_ALGORITHM);
            checker.initVerify(mojang);
            checker.update(certified);
            try {
                if (checker.verify(signature)) return true;
            } catch (java.security.SignatureException wrongLength) {
                // Made with another key size: this key didn't sign it, but the next one might have.
            }
        }
        return false;
    }

    private static void putBlock(ByteBuffer out, byte[] data) {
        out.putShort((short) data.length);
        out.put(data);
    }

    private static byte[] getBlock(ByteBuffer in) {
        byte[] data = new byte[in.getShort() & 0xFFFF];
        in.get(data);
        return data;
    }
}

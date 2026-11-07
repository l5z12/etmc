package dev.l5z12.etmc.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.Base64;

/**
 * What proves a host is the host. The network secret can't: every joiner holds it, and EasyTier lets
 * any member claim any virtual IP, including {@link EtmcConfig#HOST_VIRTUAL_IP}, with the shorter
 * route winning. So a host also keeps this key pair, puts the public half in its invite link
 * ({@link JoinCode#hostKey}), and joiners only talk to whoever can prove they hold the private half
 * (see {@link HostTunnel}).
 *
 * <p>It is kept in a file so a link stays good across restarts. That file is the host's identity:
 * anyone who gets it can pose as this host, and losing it means handing out new links.
 */
public final class HostKey {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final byte[] privateKey;
    private final byte[] publicKey;

    private HostKey(byte[] privateKey) throws GeneralSecurityException {
        this.privateKey = privateKey;
        this.publicKey = NoiseNk.publicKey(privateKey);
    }

    /** A new key pair. */
    public static HostKey generate() {
        try {
            return new HostKey(NoiseNk.newPrivateKey());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(unavailable());
        }
    }

    /**
     * Reads the key in {@code file}, creating it the first time.
     *
     * <p>A file that can't be read is an error, not a reason to make a new key: that would quietly
     * break every link already shared, and the players would be told the host may be an impostor.
     *
     * @throws IOException if the file exists but isn't a key, or can't be read or written
     */
    public static HostKey loadOrCreate(Path file) throws IOException {
        if (Files.exists(file)) return load(file);
        HostKey key = generate();
        save(file, key);
        return key;
    }

    private static HostKey load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII).trim();
        try {
            byte[] raw = decode(text);
            if (raw == null) throw new IllegalArgumentException("wrong length");
            return new HostKey(raw);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException(Msg.tr("etmc.hostkey.bad_file", "the host key in %s is damaged. "
                    + "Restore it from a backup, or delete it and share a new link", file));
        }
    }

    private static void save(Path file, HostKey key) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "host-key", ".tmp");
        try {
            restrict(tmp);
            Files.writeString(tmp, ENCODER.encodeToString(key.privateKey) + "\n", StandardCharsets.US_ASCII);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Owner-only where the filesystem can say so; {@code createTempFile} already is on most. */
    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException notPosix) {
            // Windows: the file inherits the user's own config folder permissions.
        }
    }

    /** The public half, as it appears in a link. */
    public String publicText() {
        return ENCODER.encodeToString(publicKey);
    }

    /** The start of this key's public text, enough to tell keys apart in a log. */
    String shortText() {
        return shortText(publicKey);
    }

    static String shortText(byte[] publicKey) {
        return ENCODER.encodeToString(publicKey).substring(0, 8);
    }

    byte[] privateKey() {
        return privateKey;
    }

    /**
     * Whether {@code text} is a public key in link form. A link with a key this rejects must be
     * refused, never joined without the check.
     */
    public static boolean isValid(String text) {
        return decode(text) != null;
    }

    /** The bytes of a public key in link form, or {@code null} if it isn't one. */
    static byte[] decode(String text) {
        if (text == null) return null;
        try {
            byte[] raw = Base64.getUrlDecoder().decode(text.trim());
            return raw.length == NoiseNk.KEY_LEN ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String unavailable() {
        return Msg.tr("etmc.hostkey.unavailable", "this Java runtime can't make a host key (it has no X25519)");
    }
}

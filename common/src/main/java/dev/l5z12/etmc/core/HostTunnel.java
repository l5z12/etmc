package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.DataPlaneError;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.EasyTierException;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * An {@link EasyTier} whose streams are authenticated and encrypted end to end, so that the code
 * carrying Minecraft's bytes ({@link JoinProxy}, {@link EtmcChannel}, {@link StatusPing},
 * {@link TcpBridge}) gets the check without knowing about it.
 *
 * <p>A joiner that was given a {@link HostKey} dials through one of these, and {@link #tcpConnect}
 * returns only once the far end has proven it holds the private key. There is no fallback to a plain
 * stream: that's the whole point, since a fake host would simply ask for one.
 *
 * <p>The proof has to cover the stream, not just open it. Checking a signature and then carrying on
 * in the clear would let an impostor pass the challenge along to the real host and keep the
 * connection, so every byte afterwards is sealed with keys only the two ends have.
 *
 * <p>On the wire: {@link #MAGIC}, then the two {@link NoiseNk} messages, then frames of a big-endian
 * {@code uint16} length and that many sealed bytes. The host's first frame is its {@link HostAccount}
 * proof, empty if it chose not to give one; everything after that is the connection's own bytes.
 */
final class HostTunnel implements EasyTier {

    /**
     * Opens the joiner's first message, so a host can tell it from a plain connection (see
     * {@link HostShare}). It can't start a Minecraft connection: read as one, the length is followed
     * by packet id {@code 0x54} where every client sends {@code 0x00}, and legacy pings start with
     * {@code 0xFE}. It's also the handshake's prologue, so changing it makes a new, incompatible
     * version of the protocol.
     */
    static final byte[] MAGIC = {(byte) 0xE7, 'E', 'T', 'M', 'C', 'H', 'K', 1};

    private static final int FIRST_MESSAGE_LEN = NoiseNk.KEY_LEN + NoiseNk.TAG_LEN;
    private static final int SECOND_MESSAGE_LEN = NoiseNk.KEY_LEN + NoiseNk.TAG_LEN;
    private static final byte[] EMPTY = new byte[0];
    /** Noise caps a message at 65535 bytes; a frame's plain text stays well inside that. */
    private static final int MAX_PLAIN = 32 * 1024;
    private static final int MAX_SEALED = MAX_PLAIN + NoiseNk.TAG_LEN;
    private static final int HEADER_LEN = 2;

    private final EasyTier et;
    /** The key the far end must prove it holds. Only a dialing tunnel has one. */
    private final byte[] hostPublic;
    /** This host's key. Only an answering tunnel has one. */
    private final HostKey hostKey;
    /** The player the link says is hosting, whose account must be proven; {@code null} if it names nobody. */
    private final String expectedName;
    private final AccountDirectory directory;
    /** The account to prove on an answering tunnel; {@code null} unless the host opted in. */
    private final Supplier<PlayerCert> cert;
    private final Map<Long, Secured> streams = new ConcurrentHashMap<>();
    /** The host's name as Mojang gave it, once proven. */
    private volatile String confirmedName;

    private HostTunnel(EasyTier et, byte[] hostPublic, HostKey hostKey, String expectedName,
                       AccountDirectory directory, Supplier<PlayerCert> cert) {
        this.et = et;
        this.hostPublic = hostPublic;
        this.hostKey = hostKey;
        this.expectedName = expectedName;
        this.directory = directory;
        this.cert = cert;
    }

    /**
     * A tunnel for joining the host with this public key, in the link's text form.
     *
     * @throws IllegalArgumentException if it isn't a key, so a damaged link can't mean "skip the check"
     */
    static HostTunnel dialing(EasyTier et, String hostPublicKey) {
        return dialing(et, hostPublicKey, null, null);
    }

    /** {@link #dialing(EasyTier, String)} that also holds the host to the name the link gives. */
    static HostTunnel dialing(EasyTier et, String hostPublicKey, String expectedName, AccountDirectory directory) {
        byte[] key = HostKey.decode(hostPublicKey);
        if (key == null) throw new IllegalArgumentException(badKey());
        return new HostTunnel(et, key, null, expectedName, directory, null);
    }

    /**
     * A tunnel for answering joiners as the owner of {@code key}.
     *
     * @param cert the account to prove, asked for each connection because certificates expire;
     *             {@code null} unless the host opted in, and then nothing is signed or shared
     */
    static HostTunnel answering(EasyTier et, HostKey key, Supplier<PlayerCert> cert) {
        return new HostTunnel(et, null, key, null, null, cert);
    }

    /** The host's name once Mojang has confirmed it, or {@code null}. */
    String confirmedName() {
        return confirmedName;
    }

    static String badKey() {
        return Msg.tr("etmc.link.bad_host_key", "the link's host key is damaged");
    }

    // ------------------------------------------------------------------ handshake

    /** Dials the host and returns only once it has proven who it is. */
    @Override
    public Bind tcpConnect(String inst, String dstIp, int dstPort, long timeoutMs) {
        Bind bind = et.tcpConnect(inst, dstIp, dstPort, timeoutMs);
        try {
            streams.put(bind.handle(), proveHost(bind.handle(), timeoutMs));
            return bind;
        } catch (Throwable t) {
            Io.closeStream(et, bind.handle());
            throw t;
        }
    }

    private Secured proveHost(long stream, long timeoutMs) {
        try {
            NoiseNk noise = NoiseNk.initiator(MAGIC, hostPublic);
            byte[] first = noise.writeFirst(EMPTY);
            byte[] hello = Arrays.copyOf(MAGIC, MAGIC.length + first.length);
            System.arraycopy(first, 0, hello, MAGIC.length, first.length);
            et.tcpWrite(stream, hello, hello.length, timeoutMs);

            byte[] reply = readHandshake(stream, SECOND_MESSAGE_LEN, timeoutMs);
            noise.readSecond(reply);
            byte[] hash = noise.handshakeHash();
            Secured secured = new Secured(noise.split());
            byte[] claim = secured.firstFrame(stream, timeoutMs);
            if (claim == null) throw new WrongHost();
            if (expectedName != null) checkAccount(claim, hash);
            return secured;
        } catch (GeneralSecurityException e) {
            throw new WrongHost();
        } catch (WrongHost | NoAnswer e) {
            throw e;
        } catch (EasyTierException e) {
            // Hanging up before the proof is no proof, whether it lands on the write or the read.
            if (e.is(DataPlaneError.HANDLE_CLOSED)) throw new WrongHost();
            if (e.is(DataPlaneError.DEADLINE_EXCEEDED)) throw new NoAnswer();
            throw e;
        }
    }

    /**
     * Holds the host to the name in the link. A proof that fails, or none at all, stops the join: a
     * host only puts a name in a link if it has opted in to proving it, so this is a forged name. If
     * Mojang can't be reached there's no telling, so the join goes on with the name unconfirmed.
     */
    private void checkAccount(byte[] claim, byte[] handshakeHash) {
        if (claim.length == 0) throw new WrongHost(unproven());
        try {
            UUID account = HostAccount.verify(claim, handshakeHash, directory.certificateKeys(),
                    System.currentTimeMillis());
            if (account == null) throw new WrongHost(unproven());
            String name = directory.nameOf(account);
            if (!expectedName.equalsIgnoreCase(name)) {
                throw new WrongHost(Msg.tr("etmc.tunnel.wrong_account", "this link says it's from %s, but the "
                        + "host's Minecraft account is %s, so etmc didn't connect", expectedName, name));
            }
            confirmedName = name;
        } catch (java.io.IOException e) {
            confirmedName = null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            confirmedName = null;
        }
    }

    private String unproven() {
        return Msg.tr("etmc.tunnel.unproven", "this link says it's from %s, but the host couldn't prove it, so "
                + "etmc didn't connect", expectedName);
    }

    /**
     * Reads a handshake message. A stream that ends here counts as a failed proof: the real host hangs
     * up on a key that isn't its own, and nothing else closes it this early.
     */
    private byte[] readHandshake(long stream, int length, long timeoutMs) {
        byte[] out = new byte[length];
        byte[] chunk = new byte[length];
        int got = 0;
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (got < length) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            int n;
            try {
                if (left <= 0) throw new NoAnswer();
                n = et.tcpRead(stream, chunk, length - got, left);
            } catch (EasyTierException e) {
                if (e.is(DataPlaneError.DEADLINE_EXCEEDED)) throw new NoAnswer();
                throw e;
            }
            if (n <= 0) throw new WrongHost();
            System.arraycopy(chunk, 0, out, got, n);
            got += n;
        }
        return out;
    }

    /**
     * Answers a joiner whose {@link #MAGIC} has been read, leaving {@code stream} sealed. Only ever
     * called on the host; the stream's owner closes it if this throws.
     *
     * @throws GeneralSecurityException if the joiner dialed a different host key, one from an old link
     */
    void answer(long stream, long timeoutMs) throws GeneralSecurityException {
        NoiseNk noise = NoiseNk.responder(MAGIC, hostKey.privateKey());
        noise.readFirst(readHandshake(stream, FIRST_MESSAGE_LEN, timeoutMs));
        byte[] reply = noise.writeSecond(EMPTY);
        et.tcpWrite(stream, reply, reply.length, timeoutMs);
        byte[] hash = noise.handshakeHash();
        Secured secured = new Secured(noise.split());
        PlayerCert current = cert == null ? null : cert.get();
        byte[] claim = current == null ? EMPTY : HostAccount.prove(current, hash);
        secured.writeFrame(stream, claim, 0, claim.length, timeoutMs);
        streams.put(stream, secured);
    }

    // ------------------------------------------------------------------ sealed streams

    @Override
    public int tcpRead(long handle, byte[] buf, int len, long timeoutMs) {
        return secured(handle).read(handle, buf, len, timeoutMs);
    }

    @Override
    public int tcpWrite(long handle, byte[] data, int len, long timeoutMs) {
        return secured(handle).write(handle, data, len, timeoutMs);
    }

    @Override
    public void tcpClose(long handle) {
        streams.remove(handle);
        et.tcpClose(handle);
    }

    /**
     * A handle that didn't come through a handshake gets no access at all. Passing it to the plain
     * stream underneath would be exactly the unchecked traffic this class exists to prevent.
     */
    private Secured secured(long handle) {
        Secured s = streams.get(handle);
        if (s == null) throw new EasyTierException("the connection was closed", DataPlaneError.HANDLE_CLOSED);
        return s;
    }

    /** The sealed state of one stream. Each direction has its own lock, as they run on their own threads. */
    private final class Secured {
        private final NoiseNk.Cipher send;
        private final NoiseNk.Cipher receive;
        private final Object sendLock = new Object();
        private final Object receiveLock = new Object();

        /** Bytes off the wire, kept across a timed-out read so a retry resumes the same frame. */
        private final byte[] wire = new byte[HEADER_LEN + MAX_SEALED];
        private final byte[] chunk = new byte[wire.length];
        private int wireLen = 0;
        /** The opened frame being handed to the reader. */
        private byte[] plain = EMPTY;
        private int plainPos = 0;

        Secured(NoiseNk.Transport transport) {
            this.send = transport.send();
            this.receive = transport.receive();
        }

        int write(long handle, byte[] data, int len, long timeoutMs) {
            synchronized (sendLock) {
                int done = 0;
                while (done < len) {
                    int n = Math.min(MAX_PLAIN, len - done);
                    writeFrame(handle, data, done, n, timeoutMs);
                    done += n;
                }
                return len;
            }
        }

        /** Sends one frame, which may be empty. */
        void writeFrame(long handle, byte[] data, int offset, int length, long timeoutMs) {
            byte[] sealed;
            try {
                sealed = send.seal(data, offset, length);
            } catch (GeneralSecurityException e) {
                throw new EasyTierException("couldn't encrypt: " + e.getMessage(), e);
            }
            byte[] frame = new byte[HEADER_LEN + sealed.length];
            frame[0] = (byte) (sealed.length >>> 8);
            frame[1] = (byte) sealed.length;
            System.arraycopy(sealed, 0, frame, HEADER_LEN, sealed.length);
            et.tcpWrite(handle, frame, frame.length, timeoutMs);
        }

        /** The host's opening frame, or {@code null} if the stream ended first. */
        byte[] firstFrame(long handle, long timeoutMs) {
            if (!nextFrame(handle, timeoutMs)) return null;
            byte[] frame = plain;
            plain = EMPTY;
            plainPos = 0;
            return frame;
        }

        int read(long handle, byte[] buf, int len, long timeoutMs) {
            synchronized (receiveLock) {
                while (plainPos >= plain.length) {
                    if (!nextFrame(handle, timeoutMs)) return 0;
                }
                int n = Math.min(len, plain.length - plainPos);
                System.arraycopy(plain, plainPos, buf, 0, n);
                plainPos += n;
                return n;
            }
        }

        /** Opens the next frame into {@link #plain}. Returns false at end of stream. */
        private boolean nextFrame(long handle, long timeoutMs) {
            while (true) {
                if (wireLen >= HEADER_LEN) {
                    int sealedLen = (wire[0] & 0xFF) << 8 | wire[1] & 0xFF;
                    if (sealedLen < NoiseNk.TAG_LEN || sealedLen > MAX_SEALED) throw tampered();
                    if (wireLen >= HEADER_LEN + sealedLen) {
                        open(sealedLen);
                        return true;
                    }
                }
                int n = et.tcpRead(handle, chunk, wire.length - wireLen, timeoutMs);
                if (n <= 0) return false;
                System.arraycopy(chunk, 0, wire, wireLen, n);
                wireLen += n;
            }
        }

        private void open(int sealedLen) {
            try {
                plain = receive.open(wire, HEADER_LEN, sealedLen);
            } catch (GeneralSecurityException e) {
                throw tampered();
            }
            plainPos = 0;
            int frameLen = HEADER_LEN + sealedLen;
            wireLen -= frameLen;
            System.arraycopy(wire, frameLen, wire, 0, wireLen);
        }

        private EasyTierException tampered() {
            return new EasyTierException(Msg.tr("etmc.tunnel.tampered",
                    "someone tampered with the connection to the host"));
        }
    }

    // ------------------------------------------------------------------ failures

    /**
     * The far end couldn't prove it's the host this link names. Carries no cause on purpose:
     * {@link Errors#message} shows the innermost one, and "Tag mismatch!" tells a player nothing.
     */
    static final class WrongHost extends EasyTierException {
        WrongHost() {
            this(Msg.tr("etmc.tunnel.wrong_host", "couldn't confirm this is the real host, so etmc didn't "
                    + "connect. Someone may be posing as them, or they may have a new host key: ask them for a "
                    + "new link"));
        }

        WrongHost(String why) {
            super(why);
        }
    }

    /** Nothing answered the handshake, which is what a port with nobody hosting on it looks like. */
    static final class NoAnswer extends EasyTierException {
        NoAnswer() {
            super(DataPlaneError.DEADLINE_EXCEEDED.describe(), DataPlaneError.DEADLINE_EXCEEDED);
        }
    }

    // ------------------------------------------------------------------ everything else

    @Override
    public void parseConfig(String toml) {
        et.parseConfig(toml);
    }

    @Override
    public void runNetworkInstance(String toml) {
        et.runNetworkInstance(toml);
    }

    @Override
    public void deleteNetworkInstance(String... names) {
        et.deleteNetworkInstance(names);
    }

    @Override
    public Map<String, String> collectNetworkInfos(int max) {
        return et.collectNetworkInfos(max);
    }

    @Override
    public String lastError() {
        return et.lastError();
    }

    @Override
    public Bind tcpBind(String inst, int localPort, long timeoutMs) {
        return et.tcpBind(inst, localPort, timeoutMs);
    }

    @Override
    public Accept tcpAccept(long listener, long timeoutMs) {
        return et.tcpAccept(listener, timeoutMs);
    }

    @Override
    public void tcpListenerClose(long handle) {
        et.tcpListenerClose(handle);
    }
}

package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;
import lombok.Getter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Central state machine for an etmc session. Knows nothing about Minecraft: the client layer feeds
 * it the LAN port when hosting, and reads back the loopback port to point Minecraft at when joining.
 *
 * <p>Hosting and joining both run an EasyTier instance in no-TUN mode and then bridge the vanilla
 * Minecraft socket over the mesh via {@link HostShare} / {@link JoinProxy}.
 */
public final class EtmcSession {

    private static final System.Logger LOG = System.getLogger("etmc.EtmcSession");

    public enum State { IDLE, STARTING, HOSTING, JOINING, ERROR }
    public enum Mode { NONE, HOST, JOIN }

    private final EasyTier et;

    @Getter private volatile State state = State.IDLE;
    @Getter private volatile Mode mode = Mode.NONE;
    @Getter private volatile String instName;
    /** The device name peers see for this node (EasyTier {@code hostname}). */
    @Getter private volatile String hostname;
    /**
     * Whether this join rides {@link EtmcChannel}s directly (the {@code etmc://} flow) rather than the
     * loopback {@link JoinProxy}; such a session has no local port, and its connections are counted
     * by {@link EtmcConnect}.
     */
    @Getter private volatile boolean linkJoin;
    /** Why the last host/join failed; only meaningful in {@link State#ERROR}. */
    @Getter private volatile String lastError;

    private volatile HostShare hostShare;
    private volatile JoinProxy joinProxy;
    /** What this join talks to the host through: {@link #et}, or a {@link HostTunnel} if the link has a key. */
    private volatile EasyTier joinTransport;
    @Getter private volatile JoinCode currentCode;
    /** Port the LAN world is served on locally (host), and the loopback port to point MC at (join). */
    @Getter private volatile int lanPort;
    @Getter private volatile int localPort;
    private volatile int virtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    /** Preferred loopback port for joins (0 = ephemeral); helps mods persist per-server settings. */
    private volatile int preferredLocalPort = 0;
    /**
     * Local player's name, used to derive the EasyTier device name. Stays {@code null} on a dedicated
     * server (Paper), where there is no player — the host device is then just {@code server}.
     */
    private volatile String playerName = null;
    /** Proves this node is the host to joiners; {@code null} gives them nothing to check. */
    private volatile HostKey hostKey = null;
    /** The account hosting proves itself with, if the player opted in; {@code null} signs nothing. */
    private volatile Supplier<PlayerCert> accountProof = null;
    /** Where a join checks a host's account. Only contacted for a link that names its host. */
    private volatile AccountDirectory directory = new MojangDirectory();

    public EtmcSession(EasyTier et) {
        this.et = et;
    }

    /** Sets the preferred loopback port for subsequent joins (0 = ephemeral). */
    public void setPreferredLocalPort(int port) {
        this.preferredLocalPort = port;
    }

    /**
     * Sets the local player's name, used for the EasyTier device name (hostname) peers see: a client
     * shows as {@code <username>-host} while hosting and {@code <username>} when joining; a dedicated
     * server (Paper) never calls this and shows as {@code server}. Call before hosting/joining;
     * ignored when blank.
     */
    public void setPlayerName(String name) {
        if (name != null && !name.isBlank()) {
            this.playerName = name;
        }
    }

    /** Sets the key subsequent hosting proves itself with, which goes in the invite link. */
    public void setHostKey(HostKey key) {
        this.hostKey = key;
    }

    /**
     * Opts subsequent hosting in to proving the player's Minecraft account, by signing each joiner's
     * connection. {@code null} opts out: nothing is signed, and the link names nobody.
     *
     * @param cert the current certificate, asked for each connection; may answer {@code null}
     */
    public void setAccountProof(Supplier<PlayerCert> cert) {
        this.accountProof = cert;
    }

    void setDirectory(AccountDirectory directory) {
        this.directory = directory;
    }

    // ------------------------------------------------------------------ host

    /**
     * Starts hosting: runs the EasyTier instance and begins forwarding mesh connections to the
     * already-open LAN server on {@code 127.0.0.1:lanPort}. Returns what goes into the invite link.
     *
     * <p>Blocking; call off the render thread.
     */
    public synchronized JoinCode host(int lanPort, String networkName, String secret,
                                      List<String> relays, int virtualPort) {
        return host(lanPort, networkName, secret, relays, virtualPort, networkName);
    }

    /** {@link #host(int, String, String, List, int)} with the label joiners see for this world. */
    public synchronized JoinCode host(int lanPort, String networkName, String secret,
                                      List<String> relays, int virtualPort, String label) {
        requireIdle();
        state = State.STARTING;
        try {
            this.instName = newInstanceName("host");
            this.hostname = playerName != null ? sanitizeName(playerName) + "-host" : "server";
            this.lanPort = lanPort;
            this.virtualPort = virtualPort > 0 ? virtualPort : EtmcConfig.DEFAULT_VIRTUAL_PORT;

            String toml = EtmcConfig.hostToml(instName, hostname, networkName, secret, relays);
            et.runNetworkInstance(toml);

            HostKey key = hostKey;
            Supplier<PlayerCert> proof = key == null ? null : accountProof;
            HostShare share = new HostShare(et, instName, this.virtualPort, lanPort, key, proof);
            share.start();
            this.hostShare = share;

            this.currentCode = new JoinCode(networkName, secret, relays,
                    EtmcConfig.HOST_VIRTUAL_IP, this.virtualPort,
                    label == null || label.isBlank() ? networkName : label.trim());
            if (key != null) currentCode.hostKey = key.publicText();
            if (proof != null) currentCode.hostName = playerName; // a name is a promise to prove it
            this.mode = Mode.HOST;
            this.state = State.HOSTING;
            return currentCode;
        } catch (RuntimeException e) {
            fail(e);
            throw e;
        }
    }

    // ------------------------------------------------------------------ join

    /**
     * Starts joining a hosted world. Runs the EasyTier instance and opens a loopback listener that
     * tunnels to the host over the mesh. Returns the loopback port to connect Minecraft to.
     *
     * <p>Blocking; call off the render thread.
     */
    public synchronized int join(JoinCode code) {
        String inst = newInstanceName("join");
        String toml = EtmcConfig.joinToml(inst, joinerHostname(), code.networkName, code.networkSecret, code.relays);
        return startJoin(toml, inst, code.hostIp, code.hostPort, code);
    }

    /**
     * Starts only the EasyTier instance for the {@code etmc://} link flow — NO loopback proxy/port.
     * The connection rides the data plane directly via {@link EtmcChannel}. Returns the instance name.
     *
     * <p>Blocking; call off the render thread.
     */
    public synchronized String startLinkInstance(JoinCode code) {
        requireIdle();
        state = State.STARTING;
        try {
            String inst = newInstanceName("join");
            this.hostname = joinerHostname();
            String toml = EtmcConfig.joinToml(inst, hostname, code.networkName, code.networkSecret, code.relays);
            EasyTier transport = transportFor(code);
            et.runNetworkInstance(toml);
            this.instName = inst;
            this.joinTransport = transport;
            this.linkJoin = true;
            this.currentCode = code;
            this.mode = Mode.JOIN;
            this.state = State.JOINING;
            return inst;
        } catch (RuntimeException e) {
            fail(e);
            throw e;
        }
    }

    /**
     * The EasyTier binding for reaching the host (for {@link EtmcChannel} data-plane connects). During
     * a join whose link names a host key, its streams refuse anything that can't prove it holds that key.
     */
    public EasyTier easyTier() {
        EasyTier transport = joinTransport;
        return transport != null ? transport : et;
    }

    /** @throws IllegalArgumentException if the key is damaged: that must stop the join, not skip the check */
    private EasyTier transportFor(JoinCode code) {
        return code.hostKey == null ? et
                : HostTunnel.dialing(et, code.hostKey, code.namedHost(), directory);
    }

    /**
     * Joins using an imported EasyTier config (e.g. fetched from an HTTP(S) URL) and tunnels to the
     * Minecraft server at its mesh address. Returns the loopback port to connect Minecraft to.
     */
    public synchronized int joinWithConfig(ImportedConfig cfg) {
        JoinCode display = new JoinCode(cfg.label(), "", List.of(),
                cfg.serverIp(), cfg.serverPort(), cfg.label());
        display.hostKey = cfg.hostKey();
        return startJoin(cfg.toml(), cfg.instanceName(), cfg.serverIp(), cfg.serverPort(), display);
    }

    private synchronized int startJoin(String toml, String inst, String serverIp, int serverPort, JoinCode display) {
        requireIdle();
        state = State.STARTING;
        try {
            this.instName = inst;
            if (this.hostname == null) this.hostname = joinerHostname();
            EasyTier transport = transportFor(display);
            et.runNetworkInstance(toml);

            JoinProxy proxy = new JoinProxy(transport, inst, serverIp, serverPort, preferredLocalPort);
            this.localPort = proxy.start();
            this.joinProxy = proxy;
            this.joinTransport = transport;

            this.currentCode = display;
            this.mode = Mode.JOIN;
            this.state = State.JOINING;
            return localPort;
        } catch (RuntimeException | IOException e) {
            fail(e);
            throw new RuntimeException("join failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ teardown + status

    /** Stops hosting/joining and tears down the EasyTier instance. Safe to call repeatedly. */
    public synchronized void leave() {
        try {
            HostShare hs = hostShare;
            if (hs != null) hs.stop();
            JoinProxy jp = joinProxy;
            if (jp != null) jp.stop();
            String name = instName;
            if (name != null) {
                try {
                    et.deleteNetworkInstance(name);
                } catch (Throwable t) {
                    // Instance names are unique per session, so a leftover can't block the next host or
                    // join; it is still a network running in the background, though, so say so.
                    LOG.log(System.Logger.Level.WARNING, "couldn't stop EasyTier instance " + name, t);
                }
            }
        } finally {
            hostShare = null;
            joinProxy = null;
            joinTransport = null;
            currentCode = null;
            instName = null;
            hostname = null;
            linkJoin = false;
            mode = Mode.NONE;
            localPort = 0;
            lanPort = 0;
            state = State.IDLE;
        }
    }

    /** Snapshot of the running instance's live state (peers, virtual IP). */
    public NetworkStatus status() {
        String name = instName;
        if (name == null) return NetworkStatus.empty();
        try {
            // Only ever our own instance: falling back to "whichever network the library knows" could
            // show a leftover network's peers as if they were this session's.
            Map<String, String> infos = et.collectNetworkInfos(16);
            return NetworkStatus.parse(infos.get(name));
        } catch (Throwable t) {
            return NetworkStatus.empty();
        }
    }

    public int activeConnections() {
        HostShare hs = hostShare;
        if (hs != null) return hs.activeConnections();
        JoinProxy jp = joinProxy;
        if (jp != null) return jp.activeConnections();
        if (linkJoin) return EtmcConnect.openChannels();
        return 0;
    }

    /** Why the host stopped accepting joiners while the session is still up, or {@code null}. */
    public String hostFailure() {
        HostShare hs = hostShare;
        return hs == null ? null : hs.failure();
    }

    /** The host's name once Mojang has confirmed it for this join, or {@code null}. */
    public String confirmedHost() {
        return joinTransport instanceof HostTunnel tunnel ? tunnel.confirmedName() : null;
    }

    /** Why this join refused the far end as the host, or {@code null} if it hasn't. */
    public String joinFailure() {
        JoinProxy jp = joinProxy;
        return jp == null ? null : jp.failure();
    }

    // ------------------------------------------------------------------ getters
    // The plain field reads are generated (@Getter, fluent per lombok.config); these two are not
    // straight reads, so they stay here.

    /** Whether a host or join is in progress — the guard every UI entry point checks. */
    public boolean isActive() { return mode != Mode.NONE; }

    // ------------------------------------------------------------------ internals

    private void requireIdle() {
        if (mode != Mode.NONE) {
            throw new IllegalStateException(mode == Mode.HOST
                    ? Msg.tr("etmc.session.busy_host", "etmc is already hosting; leave first")
                    : Msg.tr("etmc.session.busy_join", "etmc is already joined; leave first"));
        }
    }

    private void fail(Throwable e) {
        this.lastError = e.getMessage();
        try {
            leave();
        } catch (Throwable ignored) {
        }
        this.state = State.ERROR;
    }

    /**
     * A fresh EasyTier instance name per session ({@code etmc-<role>-<random>}). Never reused: if a
     * leave ever fails to delete an instance, the library would refuse a second one with the same name
     * ("instance already exists") and every later host/join would fail until a restart.
     */
    private static String newInstanceName(String role) {
        return "etmc-" + role + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFL);
    }

    /** Device name peers see for a joiner: the player's name. */
    private String joinerHostname() {
        return sanitizeName(playerName);
    }

    /** Keeps only characters valid in an EasyTier hostname; falls back to {@code player} if empty. */
    private static String sanitizeName(String name) {
        if (name == null) return "player";
        StringBuilder b = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                b.append(c);
            }
        }
        return b.isEmpty() ? "player" : b.toString();
    }
}

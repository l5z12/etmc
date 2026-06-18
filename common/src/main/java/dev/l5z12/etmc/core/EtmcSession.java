package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Central state machine for an etmc session. Knows nothing about Minecraft: the client layer feeds
 * it the LAN port when hosting, and reads back the loopback port to point Minecraft at when joining.
 *
 * <p>Hosting and joining both run an EasyTier instance in no-TUN mode and then bridge the vanilla
 * Minecraft socket over the mesh via {@link HostShare} / {@link JoinProxy}.
 */
public final class EtmcSession {

    public enum State { IDLE, STARTING, HOSTING, JOINING, ERROR }
    public enum Mode { NONE, HOST, JOIN }

    private final EasyTier et;

    private volatile State state = State.IDLE;
    private volatile Mode mode = Mode.NONE;
    private volatile String instName;
    private volatile String lastError;

    private volatile HostShare hostShare;
    private volatile JoinProxy joinProxy;
    private volatile JoinCode currentCode;
    private volatile int lanPort;
    private volatile int localPort;
    private volatile int virtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    /** Preferred loopback port for joins (0 = ephemeral); helps mods persist per-server settings. */
    private volatile int preferredLocalPort = 0;
    /**
     * Local player's name, used to derive the EasyTier device name. Stays {@code null} on a dedicated
     * server (Paper), where there is no player — the host device is then just {@code etmc-host}.
     */
    private volatile String playerName = null;

    public EtmcSession(EasyTier et) {
        this.et = et;
    }

    /** Sets the preferred loopback port for subsequent joins (0 = ephemeral). */
    public void setPreferredLocalPort(int port) {
        this.preferredLocalPort = port;
    }

    /**
     * Sets the local player's name, used as the EasyTier device name (hostname) shown to peers. A
     * client carries a username, so its device is {@code etmc-host-<username>} when hosting and the
     * same when joining; a dedicated server (Paper) never calls this, so its host device is plain
     * {@code etmc-host}. Call before hosting/joining; ignored when blank.
     */
    public void setPlayerName(String name) {
        if (name != null && !name.isBlank()) {
            this.playerName = name;
        }
    }

    // ------------------------------------------------------------------ host

    /**
     * Starts hosting: runs the EasyTier instance and begins forwarding mesh connections to the
     * already-open LAN server on {@code 127.0.0.1:lanPort}. Returns the join code to share.
     *
     * <p>Blocking; call off the render thread.
     */
    public synchronized JoinCode host(int lanPort, String networkName, String secret,
                                      List<String> relays, int virtualPort) {
        requireIdle();
        state = State.STARTING;
        try {
            // Dedicated server (Paper) has no player → plain "etmc-host"; a client host appends its name.
            this.instName = playerName != null ? "etmc-host-" + sanitizeName(playerName) : "etmc-host";
            this.lanPort = lanPort;
            this.virtualPort = virtualPort > 0 ? virtualPort : EtmcConfig.DEFAULT_VIRTUAL_PORT;

            String toml = EtmcConfig.hostToml(instName, networkName, secret, relays);
            et.runNetworkInstance(toml);

            HostShare share = new HostShare(et, instName, this.virtualPort, lanPort);
            share.start();
            this.hostShare = share;

            this.currentCode = new JoinCode(networkName, secret, relays,
                    EtmcConfig.HOST_VIRTUAL_IP, this.virtualPort, networkName);
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
        String inst = joinerInstanceName();
        String toml = EtmcConfig.joinToml(inst, code.networkName, code.networkSecret, code.relays);
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
            String inst = joinerInstanceName();
            String toml = EtmcConfig.joinToml(inst, code.networkName, code.networkSecret, code.relays);
            et.runNetworkInstance(toml);
            this.instName = inst;
            this.currentCode = code;
            this.mode = Mode.JOIN;
            this.state = State.JOINING;
            return inst;
        } catch (RuntimeException e) {
            fail(e);
            throw e;
        }
    }

    /** The underlying EasyTier binding (for {@link EtmcChannel} data-plane connects). */
    public EasyTier easyTier() {
        return et;
    }

    /**
     * Joins using an imported EasyTier config (e.g. fetched from an HTTP(S) URL) and tunnels to the
     * Minecraft server at its mesh address. Returns the loopback port to connect Minecraft to.
     */
    public synchronized int joinWithConfig(ImportedConfig cfg) {
        JoinCode display = new JoinCode(cfg.label, "", java.util.List.of(),
                cfg.serverIp, cfg.serverPort, cfg.label);
        return startJoin(cfg.toml, cfg.instanceName, cfg.serverIp, cfg.serverPort, display);
    }

    private synchronized int startJoin(String toml, String inst, String serverIp, int serverPort, JoinCode display) {
        requireIdle();
        state = State.STARTING;
        try {
            this.instName = inst;
            et.runNetworkInstance(toml);

            JoinProxy proxy = new JoinProxy(et, inst, serverIp, serverPort, preferredLocalPort);
            this.localPort = proxy.start();
            this.joinProxy = proxy;

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
            if (name != null && et != null) {
                try {
                    et.deleteNetworkInstance(name);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            hostShare = null;
            joinProxy = null;
            currentCode = null;
            instName = null;
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
            Map<String, String> infos = et.collectNetworkInfos(16);
            String json = infos.get(name);
            if (json == null && !infos.isEmpty()) {
                json = infos.values().iterator().next();
            }
            return NetworkStatus.parse(json);
        } catch (Throwable t) {
            return NetworkStatus.empty();
        }
    }

    public int activeConnections() {
        HostShare hs = hostShare;
        if (hs != null) return hs.activeConnections();
        JoinProxy jp = joinProxy;
        if (jp != null) return jp.activeConnections();
        return 0;
    }

    // ------------------------------------------------------------------ getters

    public State state() { return state; }
    public Mode mode() { return mode; }
    public boolean isActive() { return mode != Mode.NONE; }
    public String instName() { return instName; }
    public JoinCode currentCode() { return currentCode; }
    public int lanPort() { return lanPort; }
    public int localPort() { return localPort; }
    public String lastError() { return lastError; }

    // ------------------------------------------------------------------ internals

    private void requireIdle() {
        if (mode != Mode.NONE) {
            throw new IllegalStateException("etmc is already " + mode + "; leave first");
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

    /** EasyTier device name for a joiner: {@code etmc-host-<sanitized username>}. */
    private String joinerInstanceName() {
        return "etmc-host-" + sanitizeName(playerName);
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

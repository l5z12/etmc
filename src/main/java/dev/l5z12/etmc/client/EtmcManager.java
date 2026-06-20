package dev.l5z12.etmc.client;

import dev.l5z12.etmc.client.screen.EtmcConnectingScreen;
import dev.l5z12.etmc.client.screen.EtmcNoticeScreen;
import dev.l5z12.etmc.core.EtmcConnect;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.ImportedConfig;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.core.RemoteConfig;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.NativeLoader;
import dev.l5z12.etmc.ffi.Panama;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide owner of the etmc {@link EtmcSession}: loads the native library, exposes async
 * host/join/leave operations, and caches a periodically-refreshed {@link NetworkStatus} for the HUD.
 */
public final class EtmcManager {

    private static final EtmcManager INSTANCE = new EtmcManager();

    /** Shared logger (slf4j is present on Fabric, NeoForge and Forge), so this stays loader-neutral. */
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("etmc");

    /** How long to wait for a direct (p2p) route before auto-joining over the available path (relay). */
    private static final long P2P_WAIT_TIMEOUT_MS = 8000;

    public static EtmcManager get() {
        return INSTANCE;
    }

    //? if fabric {
    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }
    //?} else {
    /*private static net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getInstance();
    }*/
    //?}

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "etmc-worker");
        t.setDaemon(true);
        return t;
    });

    private ModConfig config;
    private EtmcSession session;
    private boolean nativeReady;
    private String nativeError;

    private volatile NetworkStatus cachedStatus = NetworkStatus.empty();
    private long lastStatusPoll;
    private long lastReconnectAttempt;
    /** In-progress {@code etmc://} link join (null when none). */
    private LinkAttempt linkAttempt;

    private EtmcManager() {}

    private static String shortId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** Pushes the local player's name to the session so EasyTier device names read {@code etmc-host[-<username>]}. */
    private void syncPlayerName() {
        if (session == null) return;
        try {
            //? if fabric {
            var s = mc().getSession();
            if (s != null) session.setPlayerName(s.getUsername());
            //?} else {
            /*var u = mc().getUser();
            if (u != null) session.setPlayerName(u.getName());*/
            //?}
        } catch (Throwable ignored) {
        }
    }

    /** Loads config and the EasyTier native library. Call once on client init. */
    public void init() {
        this.config = ModConfig.load();
        try {
            if (!Panama.isAvailable()) {
                throw new IllegalStateException("FFM unavailable: " + Panama.initError());
            }
            //? if fabric {
            Path cacheRoot = FabricLoader.getInstance().getConfigDir().resolve("etmc");
            //?} else {
            /*Path cacheRoot = mc().gameDirectory.toPath().resolve("config").resolve("etmc");*/
            //?}
            NativeLoader.Native nat = NativeLoader.extract(cacheRoot);
            EasyTier et = EasyTier.load(nat.path());
            this.session = new EtmcSession(et);
            // When an etmc:// data-plane connection closes (disconnect / failure), leave the network —
            // but only if that channel's instance is still the active session (guards against a stale
            // channel from a previous join tearing down a newer one).
            EtmcConnect.setOnDisconnect(inst -> {
                EtmcSession s = this.session;
                if (s != null && inst != null && inst.equals(s.instName())) {
                    leaveAsync();
                }
            });
            this.nativeReady = true;
        } catch (Throwable t) {
            this.nativeReady = false;
            this.nativeError = String.valueOf(t.getMessage());
        }
    }

    public boolean isReady() {
        return nativeReady && session != null;
    }

    public String nativeError() {
        return nativeError;
    }

    public ModConfig config() {
        return config;
    }

    public EtmcSession session() {
        return session;
    }

    public NetworkStatus cachedStatus() {
        return cachedStatus;
    }

    // ------------------------------------------------------------------ operations

    public CompletableFuture<JoinCode> hostAsync(String networkName, String secret) {
        if (!isReady()) return CompletableFuture.failedFuture(new IllegalStateException("etmc native library not loaded"));
        syncPlayerName();
        List<String> relays = config.relays;
        int vport = config.defaultVirtualPort;
        config.lastNetworkName = networkName;
        config.lastSecret = secret;
        config.save();
        return McNet.ensureOpenToLan()
                .thenApplyAsync(lanPort -> session.host(lanPort, networkName, secret, relays, vport), worker);
    }

    public CompletableFuture<Integer> joinAsync(JoinCode code) {
        if (!isReady()) return CompletableFuture.failedFuture(new IllegalStateException("etmc native library not loaded"));
        syncPlayerName();
        session.setPreferredLocalPort(config.joinLocalPort);
        return CompletableFuture.supplyAsync(() -> session.join(code), worker)
                .thenApplyAsync(localPort -> {
                    String label = code.label == null || code.label.isBlank() ? code.networkName : code.label;
                    McNet.presentJoin(label, localPort);
                    return localPort;
                }, mc());
    }

    /**
     * Fetches an EasyTier config (or {@code ETMC1:} code) from an HTTP(S) URL and connects to the
     * Minecraft server it describes. {@code overrideServer} (nullable, {@code ip:port}) is used when
     * the config has no {@code [etmc] server} line.
     */
    public CompletableFuture<Integer> connectUrlAsync(String url, String overrideServer) {
        if (!isReady()) return CompletableFuture.failedFuture(new IllegalStateException("etmc native library not loaded"));
        syncPlayerName();
        session.setPreferredLocalPort(config.joinLocalPort);
        return CompletableFuture.supplyAsync(() -> {
            String body;
            try {
                body = RemoteConfig.fetch(url);
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
            String trimmed = body.trim();
            if (trimmed.startsWith(JoinCode.PREFIX)) {
                return session.join(JoinCode.decode(trimmed));
            }
            ImportedConfig cfg = ImportedConfig.parse(body, "etmc-cfg-" + shortId(), overrideServer);
            return session.joinWithConfig(cfg);
        }, worker).thenApplyAsync(localPort -> {
            JoinCode cc = session.currentCode();
            String label = cc != null && cc.label != null && !cc.label.isBlank() ? cc.label : "etmc server";
            McNet.presentJoin(label, localPort);
            return localPort;
        }, mc());
    }

    /**
     * Handles an {@code etmc://} link from Add Server / Direct Connect: starts the EasyTier instance
     * (no proxy/port) then triggers a vanilla connect that the ClientConnection mixin reroutes through
     * an {@link dev.l5z12.etmc.core.EtmcChannel} riding the data plane. Called on the client thread.
     */
    public void connectViaLink(Screen parent, String link) {
        if (!isReady()) {
            showError(parent, "Can't join", nativeError != null
                    ? "etmc native library failed to load: " + nativeError
                    : "etmc native library not loaded");
            return;
        }
        JoinCode code;
        try {
            code = JoinCode.decode(link);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[etmc] bad etmc:// link: {}", e.getMessage());
            showError(parent, "Invalid etmc:// link", e.getMessage());
            return;
        }
        syncPlayerName();
        String label = code.label != null && !code.label.isBlank() ? code.label : code.networkName;
        LinkAttempt a = new LinkAttempt(parent, code, label);
        this.linkAttempt = a;
        LOGGER.info("[etmc] etmc:// join: starting link instance for host {}:{} (network '{}')",
                code.hostIp, code.hostPort, code.networkName);
        mc().setScreen(new EtmcConnectingScreen(parent, label,
                () -> linkProceed(a), () -> linkCancel(a)));
        CompletableFuture.supplyAsync(() -> session.startLinkInstance(code), worker)
                .whenComplete((inst, err) -> mc().execute(() -> {
                    if (a != linkAttempt) {
                        // cancelled or superseded while starting — tear the instance back down
                        if (err == null) leaveAsync();
                        return;
                    }
                    if (err != null) {
                        Throwable cause = err instanceof java.util.concurrent.CompletionException && err.getCause() != null
                                ? err.getCause() : err;
                        LOGGER.warn("[etmc] etmc:// join failed: {}", String.valueOf(cause.getMessage()), cause);
                        showError(a.parent, "Couldn't start etmc network", cause.getMessage());
                        linkAttempt = null;
                        return;
                    }
                    a.instName = inst;
                    a.startedAt = System.currentTimeMillis();
                    a.instanceReady = true;
                    LOGGER.info("[etmc] link instance '{}' up — waiting up to {}s for a direct P2P route to {}",
                            inst, P2P_WAIT_TIMEOUT_MS / 1000, code.hostIp);
                    if (a.proceedRequested) linkDoProceed(a); // user already hit "Join now anyway"
                }));
    }

    /**
     * Drives the in-progress link join each tick: connects as soon as a direct (p2p) route to the host
     * appears, or falls back to whatever path exists once {@link #P2P_WAIT_TIMEOUT_MS} elapses (so it
     * never hangs when hole-punching can't establish a direct link). Logs progress for debugging.
     */
    private void driveLinkAttempt(long now) {
        LinkAttempt a = linkAttempt;
        if (a == null || !a.instanceReady || a.proceeded) return;
        boolean direct = false;
        boolean found = false;
        int hostCost = -1;
        for (NetworkStatus.Peer p : cachedStatus().peers()) {
            if (p.ipv4() != null && p.ipv4().equals(a.code.hostIp)) {
                found = true;
                hostCost = p.cost();
                if (!p.relay()) direct = true;
            }
        }
        if (now - a.lastLog > 1000) {
            a.lastLog = now;
            LOGGER.info("[etmc] P2P wait {}s: host {} {} | peers: {}",
                    (now - a.startedAt) / 1000, a.code.hostIp,
                    found ? "cost=" + hostCost + (direct ? " (p2p)" : " (relay)") : "not in route table yet",
                    peerSummary(cachedStatus()));
        }
        if (direct) {
            LOGGER.info("[etmc] direct P2P route to host ready — connecting");
            linkDoProceed(a);
        } else if (now - a.startedAt > P2P_WAIT_TIMEOUT_MS) {
            LOGGER.info("[etmc] no direct route after {}s — connecting over the available path (relay)",
                    P2P_WAIT_TIMEOUT_MS / 1000);
            linkDoProceed(a);
        }
    }

    private static String peerSummary(NetworkStatus st) {
        StringBuilder sb = new StringBuilder();
        for (NetworkStatus.Peer p : st.peers()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.ipv4() == null ? "?" : p.ipv4()).append('=').append(p.cost())
                    .append(p.relay() ? "(relay)" : "(p2p)");
        }
        return sb.length() == 0 ? "(none yet)" : sb.toString();
    }

    /** "Join now anyway" / auto-proceed. Queues if the instance is still starting. */
    private void linkProceed(LinkAttempt a) {
        if (a != linkAttempt || a.proceeded) return;
        if (!a.instanceReady) {
            a.proceedRequested = true;
            return;
        }
        linkDoProceed(a);
    }

    private void linkDoProceed(LinkAttempt a) {
        if (a.proceeded) return;
        a.proceeded = true;
        linkAttempt = null;
        EtmcConnect.setPending(new EtmcConnect.Target(session.easyTier(), a.instName, a.code.hostIp, a.code.hostPort));
        McNet.connectViaChannel(a.parent, a.label);
    }

    private void linkCancel(LinkAttempt a) {
        if (a != linkAttempt) return;
        linkAttempt = null;
        if (a.instanceReady) leaveAsync(); // started already → tear down (else the start callback will)
    }

    private static void showError(Screen parent, String title, String message) {
        mc().setScreen(new EtmcNoticeScreen(parent, title, message));
    }

    /** State for one in-progress {@code etmc://} link join (mutated only on the client thread). */
    private static final class LinkAttempt {
        final Screen parent;
        final JoinCode code;
        final String label;
        volatile String instName;
        volatile boolean instanceReady;
        volatile boolean proceeded;
        volatile boolean proceedRequested;
        volatile long startedAt;
        volatile long lastLog;

        LinkAttempt(Screen parent, JoinCode code, String label) {
            this.parent = parent;
            this.code = code;
            this.label = label;
        }
    }

    public CompletableFuture<Void> leaveAsync() {
        if (session == null) return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> session.leave(), worker)
                .thenRun(() -> cachedStatus = NetworkStatus.empty());
    }

    // ------------------------------------------------------------------ periodic tick

    /** Called every client tick. Refreshes status, drives a pending link join, handles auto-reconnect. */
    public void tick() {
        if (!isReady() || !session.isActive()) return;
        long now = System.currentTimeMillis();
        // Poll faster while waiting on a link join so a direct route is noticed (and the timeout fires) promptly.
        long interval = linkAttempt != null ? 600 : 1500;
        if (now - lastStatusPoll > interval) {
            lastStatusPoll = now;
            EtmcSession s = session;
            worker.submit(() -> {
                try {
                    cachedStatus = s.status();
                } catch (Throwable ignored) {
                }
            });
        }
        driveLinkAttempt(now);
    }
}

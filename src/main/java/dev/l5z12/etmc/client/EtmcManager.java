package dev.l5z12.etmc.client;

import dev.l5z12.etmc.client.screen.EtmcConnectingScreen;
import dev.l5z12.etmc.client.screen.EtmcNoticeScreen;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.EtmcConnect;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.ImportedConfig;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.core.RemoteConfig;
import dev.l5z12.etmc.core.StatusPing;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.NativeLoader;
import dev.l5z12.etmc.ffi.Panama;
import lombok.Getter;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?}
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-wide owner of the etmc {@link EtmcSession}: loads the native library, exposes async
 * host/join/leave operations, and caches a periodically-refreshed {@link NetworkStatus} for the HUD.
 */
public final class EtmcManager {

    private static final EtmcManager INSTANCE = new EtmcManager();

    /** Shared logger. log4j2 ships with every Minecraft version and loader (slf4j only lands at 1.17+),
     *  so keying off it stays version- and loader-neutral. */
    public static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("etmc");

    /** How long to wait for a direct (p2p) route before auto-joining over the available path (relay). */
    private static final long P2P_WAIT_TIMEOUT_MS = 8000;
    /** Status refresh cadence while a session is up. */
    private static final long STATUS_POLL_INTERVAL_MS = 1500;
    /** Faster cadence while an {@code etmc://} join waits for a route, so it reacts promptly. */
    private static final long LINK_POLL_INTERVAL_MS = 600;
    /** How often the P2P-wait progress line is logged while a link join is pending. */
    private static final long LINK_LOG_INTERVAL_MS = 1000;

    public static EtmcManager get() {
        return INSTANCE;
    }

    // The client handle and screen navigation live in McScreens: one edit per new Minecraft version,
    // rather than one in every class that opens a screen.

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "etmc-worker");
        t.setDaemon(true);
        return t;
    });

    // Set once by init() and then read from the render thread, the worker, and (on disconnect) a
    // netty event-loop thread — hence volatile.
    @Getter private volatile ModConfig config;
    @Getter private volatile EtmcSession session;
    private volatile boolean nativeReady;
    /** Why the native library failed to load, for the UI and {@code /etmc status}. */
    @Getter private volatile String nativeError;

    /** Latest status snapshot, refreshed off-thread; the HUD reads this every frame. */
    @Getter private volatile NetworkStatus cachedStatus = NetworkStatus.empty();
    private long lastStatusPoll;
    /** Guards against queueing another status poll behind one that hasn't come back yet. */
    private final AtomicBoolean statusPollInFlight = new AtomicBoolean();
    /** In-progress {@code etmc://} link join (null when none). Client thread only. */
    private LinkAttempt linkAttempt;

    private EtmcManager() {}

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Pushes the local player's name to the session so EasyTier device names read {@code etmc-host[-<username>]}. */
    private void syncPlayerName() {
        if (session == null) return;
        try {
            //? if yarn {
            var s = McScreens.mc().getSession();
            if (s != null) session.setPlayerName(s.getUsername());
            //?} else {
            /*var u = McScreens.mc().getUser();
            if (u != null) session.setPlayerName(u.getName());*/
            //?}
        } catch (Throwable ignored) {
        }
    }

    /** Loads config and the EasyTier native library. Call once on client init. */
    public void init() {
        this.config = ModConfig.load();
        try {
            //? if fabric {
            Path cacheRoot = FabricLoader.getInstance().getConfigDir().resolve("etmc");
            //?} else {
            /*Path cacheRoot = McScreens.mc().gameDirectory.toPath().resolve("config").resolve("etmc");*/
            //?}
            NativeLoader.Native nat = NativeLoader.extract(cacheRoot);
            // FFM where the runtime has it (Java 19+), JNA on the Java 17 versions — EasyTier.load
            // picks the backend, so this must NOT pre-check for java.lang.foreign.
            EasyTier et = EasyTier.load(nat.path());
            LOGGER.info("[etmc] EasyTier backend: {} ({})",
                    Panama.isAvailable() ? "FFM" : "JNA", nat.path());
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
            this.nativeError = t.getMessage() == null ? t.toString() : t.getMessage();
            LOGGER.warn("[etmc] EasyTier native library failed to load", t);
        }
    }

    /**
     * Whether etmc can host or join. Not a plain field read — the library can load while the session
     * still fails to build — so every entry point checks this rather than {@code nativeReady}.
     */
    public boolean isReady() {
        return nativeReady && session != null;
    }

    // config(), session(), cachedStatus() and nativeError() are generated (@Getter, fluent
    // per lombok.config) from the fields above.

    // ------------------------------------------------------------------ operations

    /** The failure every async entry point reports when the native library never loaded. */
    private static <T> CompletableFuture<T> notLoaded() {
        return CompletableFuture.failedFuture(new IllegalStateException("etmc native library not loaded"));
    }

    public CompletableFuture<JoinCode> hostAsync(String networkName, String secret) {
        if (!isReady()) return notLoaded();
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
        if (!isReady()) return notLoaded();
        syncPlayerName();
        session.setPreferredLocalPort(config.joinLocalPort);
        return CompletableFuture.supplyAsync(() -> session.join(code), worker)
                .thenApplyAsync(localPort -> {
                    McNet.presentJoin(code.displayLabel(), localPort);
                    return localPort;
                }, McScreens.mc());
    }

    /**
     * Fetches an EasyTier config (or {@code ETMC1:} code) from an HTTP(S) URL and connects to the
     * Minecraft server it describes. {@code overrideServer} (nullable, {@code ip:port}) is used when
     * the config has no {@code [etmc] server} line.
     */
    public CompletableFuture<Integer> connectUrlAsync(String url, String overrideServer) {
        if (!isReady()) return notLoaded();
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
            McNet.presentJoin(cc == null ? "etmc server" : cc.displayLabel(), localPort);
            return localPort;
        }, McScreens.mc());
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
        LinkAttempt a = new LinkAttempt(parent, code, code.displayLabel());
        this.linkAttempt = a;
        LOGGER.info("[etmc] etmc:// join: starting link instance for host {}:{} (network '{}')",
                code.hostIp, code.hostPort, code.networkName);
        McScreens.goTo(new EtmcConnectingScreen(parent, a.label,
                () -> linkProceed(a), () -> linkCancel(a)));
        CompletableFuture.supplyAsync(() -> session.startLinkInstance(code), worker)
                .whenComplete((inst, err) -> McScreens.mc().execute(() -> {
                    if (a != linkAttempt) {
                        // cancelled or superseded while starting — tear the instance back down
                        if (err == null) leaveAsync();
                        return;
                    }
                    if (err != null) {
                        LOGGER.warn("[etmc] etmc:// join failed: {}", Errors.message(err), Errors.root(err));
                        showError(a.parent, "Couldn't start etmc network", Errors.message(err));
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
        // One read of the volatile snapshot: the decision below and the line that logs it have to
        // describe the same set of peers.
        NetworkStatus st = cachedStatus();
        boolean direct = false;
        boolean found = false;
        int hostCost = -1;
        for (NetworkStatus.Peer p : st.peers()) {
            if (p.ipv4() != null && p.ipv4().equals(a.code.hostIp)) {
                found = true;
                hostCost = p.cost();
                if (!p.relay()) direct = true;
            }
        }
        if (now - a.lastLog > LINK_LOG_INTERVAL_MS) {
            a.lastLog = now;
            LOGGER.info("[etmc] P2P wait {}s: host {} {} | peers: {}",
                    (now - a.startedAt) / 1000, a.code.hostIp,
                    found ? "cost=" + hostCost + (direct ? " (p2p)" : " (relay)") : "not in route table yet",
                    peerSummary(st));
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
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(p.ipv4() == null ? "?" : p.ipv4()).append('=').append(p.cost())
                    .append(p.relay() ? "(relay)" : "(p2p)");
        }
        return sb.isEmpty() ? "(none yet)" : sb.toString();
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
        EtmcSession s = session;
        EtmcConnect.Target target =
                new EtmcConnect.Target(s.easyTier(), a.instName, a.code.hostIp, a.code.hostPort);
        if (!ViaHook.isPresent()) {
            armAndConnect(target, a.parent, a.label, -1);
            return;
        }
        // ViaFabricPlus is installed but can't auto-detect across an etmc:// join (it raw-sockets the
        // placeholder address). Detect the host's protocol ourselves over the mesh, then connect with it
        // pinned so VFP translates without probing. A failed probe (-1) just lets VFP fall back as before.
        // Pulled out of the attempt so the callback captures only what it needs, not the whole
        // LinkAttempt (which holds the parent screen); the destination comes off `target` itself.
        Screen parent = a.parent;
        String label = a.label;
        CompletableFuture
                .supplyAsync(() -> StatusPing.protocolVersion(
                        s.easyTier(), target.instanceName(), target.hostIp(), target.hostPort()), worker)
                .whenComplete((proto, err) -> McScreens.mc().execute(() ->
                        armAndConnect(target, parent, label, (err == null && proto != null) ? proto : -1)));
    }

    /**
     * Arms the pending target and immediately starts the vanilla connect that consumes it.
     *
     * <p>Arming belongs here and nowhere earlier. The Connection mixin hands the pending target to the
     * <em>next</em> connection Minecraft opens — whichever server that turns out to be. Arming it before
     * the protocol probe (which blocks for up to {@link StatusPing}'s timeout) would leave it live for
     * seconds, so a player who gave up waiting and joined an ordinary server would be silently routed
     * onto the mesh instead.
     */
    private static void armAndConnect(EtmcConnect.Target target, Screen parent, String label, int protocolVersion) {
        EtmcConnect.setPending(target);
        McNet.connectViaChannel(parent, label, protocolVersion);
    }

    private void linkCancel(LinkAttempt a) {
        if (a != linkAttempt) return;
        linkAttempt = null;
        if (a.instanceReady) leaveAsync(); // started already → tear down (else the start callback will)
    }

    private static void showError(Screen parent, String title, String message) {
        McScreens.goTo(new EtmcNoticeScreen(parent, title, message));
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

    /** Called every client tick. Refreshes the cached status and drives a pending link join. */
    public void tick() {
        if (!isReady() || !session.isActive()) return;
        long now = System.currentTimeMillis();
        // Poll faster while waiting on a link join so a direct route is noticed (and the timeout fires) promptly.
        long interval = linkAttempt != null ? LINK_POLL_INTERVAL_MS : STATUS_POLL_INTERVAL_MS;
        if (now - lastStatusPoll > interval) {
            lastStatusPoll = now;
            pollStatus();
        }
        driveLinkAttempt(now);
    }

    /**
     * Refreshes {@link #cachedStatus} on the worker. The worker also runs the blocking host/join
     * calls, so polls are skipped rather than queued while one is outstanding — otherwise every tick
     * during a slow join would pile up another stale poll behind it.
     */
    private void pollStatus() {
        if (!statusPollInFlight.compareAndSet(false, true)) return;
        EtmcSession s = session;
        worker.submit(() -> {
            try {
                cachedStatus = s.status();
            } catch (Throwable ignored) {
            } finally {
                statusPollInFlight.set(false);
            }
        });
    }
}

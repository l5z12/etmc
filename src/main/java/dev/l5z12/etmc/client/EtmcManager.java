package dev.l5z12.etmc.client;

import dev.l5z12.etmc.client.screen.EtmcConnectingScreen;
import dev.l5z12.etmc.client.screen.EtmcNoticeScreen;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.EtmcConnect;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.ImportedConfig;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.core.Relays;
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

    /** Once the host is reachable over a relay, how long to wait for a direct (P2P) link before joining anyway. */
    private static final long P2P_WAIT_TIMEOUT_MS = 8000;
    /**
     * How long an {@code etmc://} join waits for the host to show up at all (this node addressed, and
     * the host in the route table) before giving up with a diagnosis. Connecting before that only
     * produces EasyTier's opaque "data plane net is not ready" after another 15s.
     */
    private static final long HOST_FIND_TIMEOUT_MS = 45_000;
    /** How long the host's world gets to answer the ping before the join reports it isn't open. */
    private static final long WORLD_CHECK_TIMEOUT_MS = 8_000;
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
    /** Marks the newest {@code etmc://v1/config/} fetch (null when none), so it beats older ones. Client thread only. */
    private Object configFetch;

    // Session-lifetime bookkeeping for tick(); client thread only.
    /** The hosted world has been seen running, so its disappearing means the player left it. */
    private boolean hostWorldSeen;
    /** A proxy join has carried the player into a world, so leaving the world ends the join. */
    private boolean proxyJoinPlayed;
    /** The host-failure chat notice has been posted for this session. */
    private boolean hostFailureNotified;
    /** Stale "etmc: ..." server-list entries from an earlier run have been swept. */
    private boolean serverListSwept;

    private EtmcManager() {}

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Pushes the local player's name to the session, which derives the device name peers see. */
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

    /** The local player's name, or {@code "Player"} when it can't be read. */
    public String playerName() {
        try {
            //? if yarn {
            var s = McScreens.mc().getSession();
            if (s != null) return s.getUsername();
            //?} else {
            /*var u = McScreens.mc().getUser();
            if (u != null) return u.getName();*/
            //?}
        } catch (Throwable ignored) {
        }
        return "Player";
    }

    /** Loads config and the EasyTier native library. Call once on client init. */
    public void init() {
        // Player-facing text follows the game's language from here on (see Msg).
        Msg.setTranslator(Txt::i18n);
        this.config = ModConfig.load();
        try {
            //? if fabric {
            Path cacheRoot = FabricLoader.getInstance().getConfigDir().resolve("etmc");
            //?} else {
            /*Path cacheRoot = McScreens.mc().gameDirectory.toPath().resolve("config").resolve("etmc");*/
            //?}
            NativeLoader.Native nat = NativeLoader.extract(cacheRoot);
            // FFM where the runtime has it (Java 21+), JNA on the Java 17 versions — EasyTier.load
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
    private <T> CompletableFuture<T> notLoaded() {
        return CompletableFuture.failedFuture(new IllegalStateException(nativeError != null
                ? Msg.tr("etmc.error.no_native", "etmc couldn't load its EasyTier library: %s", nativeError)
                : Msg.tr("etmc.error.no_native_log", "etmc couldn't load its EasyTier library (see the game log).")));
    }

    /** Why hosting can't start right now, or {@code null} if it can. */
    public String hostBlocker() {
        if (!isReady()) return Msg.tr("etmc.error.no_native_log", "etmc couldn't load its EasyTier library (see the game log).");
        if (!McScreens.singleplayer()) {
            return Msg.tr("etmc.host.blocked.no_world",
                    "Open a singleplayer world first: etmc hosts the world you're playing.");
        }
        if (!config.hasRelay()) {
            return Msg.tr("etmc.host.blocked.no_relay", "Add a relay in Settings first: joiners reach you through it.");
        }
        String bad = Relays.firstProblem(config.relays);
        if (bad != null) return Msg.tr("etmc.host.blocked.bad_relay", "Fix the relay in Settings: %s", bad);
        return null;
    }

    /**
     * Hosts the current singleplayer world.
     *
     * @param gameMode joiners' game mode name ({@code SURVIVAL}, ...), or {@code null} for the host's own
     * @param cheats   whether cheats are allowed (for everyone, the host included — as in vanilla)
     * @param allowOffline whether joiners can skip Minecraft's account check
     */
    public CompletableFuture<JoinCode> hostAsync(String networkName, String secret, String label,
                                                 String gameMode, boolean cheats, boolean allowOffline) {
        String blocker = hostBlocker();
        if (blocker != null) return CompletableFuture.failedFuture(new IllegalStateException(blocker));
        syncPlayerName();
        List<String> relays = config.relays;
        int vport = config.defaultVirtualPort;
        config.lastNetworkName = networkName;
        config.lastSecret = secret;
        config.lastLabel = label;
        config.save();
        return McNet.ensureOpenToLan(gameMode, cheats, allowOffline)
                .thenApplyAsync(lanPort -> session.host(lanPort, networkName, secret, relays, vport, label), worker)
                .whenComplete((code, err) -> McScreens.mc().execute(() -> {
                    hostWorldSeen = err == null;
                    hostFailureNotified = false;
                }));
    }

    /**
     * Joins over the loopback proxy (the way to join from inside a world: it adds a server-list entry
     * to click once the player has left it). Outside a world, {@link #connectViaLink} is the better path.
     */
    public CompletableFuture<Integer> joinAsync(JoinCode code) {
        if (!isReady()) return notLoaded();
        String bad = Relays.firstProblem(code.relays);
        if (bad != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    Msg.tr("etmc.join.bad_relay", "This link's relay is wrong: %s", bad)));
        }
        syncPlayerName();
        session.setPreferredLocalPort(config.joinLocalPort);
        return CompletableFuture.supplyAsync(() -> session.join(code), worker)
                .thenApplyAsync(localPort -> {
                    proxyJoinPlayed = false;
                    McNet.presentJoin(code.displayLabel(), localPort);
                    return localPort;
                }, McScreens.mc());
    }

    /** Joins from an {@code etmc://v1/config/} link while in a world, as {@link #joinAsync} does. */
    public CompletableFuture<Integer> joinConfigAsync(String url) {
        if (!isReady()) return notLoaded();
        Object fetch = new Object();
        configFetch = fetch;
        return fetchConfig(url).thenComposeAsync(code -> {
            if (configFetch != fetch) {
                return CompletableFuture.<Integer>failedFuture(new IllegalStateException(
                        Msg.tr("etmc.join.superseded", "a newer join replaced this one")));
            }
            return joinAsync(code);
        }, McScreens.mc()).whenCompleteAsync((port, err) -> {
            if (configFetch == fetch) configFetch = null;
        }, McScreens.mc());
    }

    private CompletableFuture<JoinCode> fetchConfig(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return JoinCode.fromConfig(RemoteConfig.fetchPublic(url));
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, worker);
    }

    /**
     * Fetches an EasyTier config (or an {@code etmc://} link) from an HTTP(S) URL and connects to the
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
            if (JoinCode.isLink(trimmed)) {
                return session.join(JoinCode.decode(trimmed));
            }
            ImportedConfig cfg = ImportedConfig.parse(body, "etmc-cfg-" + shortId(), overrideServer);
            return session.joinWithConfig(cfg);
        }, worker).thenApplyAsync(localPort -> {
            proxyJoinPlayed = false;
            JoinCode cc = session.currentCode();
            McNet.presentJoin(cc == null ? "etmc server" : cc.displayLabel(), localPort);
            return localPort;
        }, McScreens.mc());
    }

    /**
     * Handles an {@code etmc://} link from Add Server / Direct Connect / the
     * Join screen: starts the EasyTier instance (no proxy/port), waits until the host is actually
     * reachable, then triggers a vanilla connect that the ClientConnection mixin reroutes through an
     * {@link dev.l5z12.etmc.core.EtmcChannel} riding the data plane. Client thread, no world loaded.
     */
    public void connectViaLink(Screen parent, String link) {
        if (!isReady()) {
            showError(parent, Msg.tr("etmc.join.cant_join", "Can't join"), nativeError != null
                    ? Msg.tr("etmc.error.no_native", "etmc couldn't load its EasyTier library: %s", nativeError)
                    : Msg.tr("etmc.error.no_native_log", "etmc couldn't load its EasyTier library (see the game log)."));
            return;
        }
        String configUrl = JoinCode.configUrl(link);
        if (configUrl != null) {
            connectViaConfig(parent, configUrl);
            return;
        }
        JoinCode code;
        try {
            code = JoinCode.decode(link);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[etmc] bad etmc:// link: {}", e.getMessage());
            showError(parent, Msg.tr("etmc.join.bad_link.title", "That etmc link doesn't work"),
                    Msg.tr("etmc.join.bad_link", "It couldn't be read (%s). Ask the host to copy it again.",
                            e.getMessage()));
            return;
        }
        String badRelay = code.relays.isEmpty()
                ? Msg.tr("etmc.join.link_no_relay", "It has no relay, so there's no way to reach the host. "
                        + "Ask the host to add a relay in etmc Settings and copy the link again.")
                : Relays.firstProblem(code.relays);
        if (badRelay != null) {
            showError(parent, Msg.tr("etmc.join.link_bad_relay.title", "That etmc link can't reach the host"), badRelay);
            return;
        }
        if (session.isActive()) {
            showError(parent, Msg.tr("etmc.join.already.title", "Already in an etmc network"),
                    Msg.tr("etmc.join.already",
                            "Leave the current etmc network first (etmc menu → Session status → Leave network)."));
            return;
        }
        syncPlayerName();
        LinkAttempt a = new LinkAttempt(parent, code, code.displayLabel());
        this.linkAttempt = a;
        LOGGER.info("[etmc] etmc:// join: starting link instance for host {}:{} (network '{}')",
                code.hostIp, code.hostPort, code.networkName);
        McScreens.goTo(new EtmcConnectingScreen(parent, a.label,
                () -> linkProceed(a), () -> linkCancel(a), () -> a.progress));
        CompletableFuture.supplyAsync(() -> session.startLinkInstance(code), worker)
                .whenComplete((inst, err) -> McScreens.mc().execute(() -> {
                    if (a != linkAttempt) {
                        // cancelled or superseded while starting — tear the instance back down
                        if (err == null) leaveAsync();
                        return;
                    }
                    if (err != null) {
                        LOGGER.warn("[etmc] etmc:// join failed: {}", Errors.message(err), Errors.root(err));
                        showError(a.parent, Msg.tr("etmc.join.start_failed.title", "Couldn't start the etmc network"),
                                Errors.message(err));
                        linkAttempt = null;
                        return;
                    }
                    a.instName = inst;
                    a.startedAt = System.currentTimeMillis();
                    a.instanceReady = true;
                    a.progress = Msg.tr("etmc.join.progress.relay", "Contacting the relay…");
                    LOGGER.info("[etmc] link instance '{}' up — waiting for host {} (P2P up to {}s once found)",
                            inst, code.hostIp, P2P_WAIT_TIMEOUT_MS / 1000);
                    if (a.proceedRequested) linkDoProceed(a); // user already hit "Join now anyway"
                }));
    }

    /** Fetches an {@code etmc://v1/config/} link's config, then carries on as an ordinary link join. */
    private void connectViaConfig(Screen parent, String url) {
        Object fetch = new Object();
        configFetch = fetch;
        String fetching = Msg.tr("etmc.url.fetching", "Fetching config…");
        McScreens.goTo(new EtmcConnectingScreen(parent, null, null, () -> {
            if (configFetch == fetch) configFetch = null;
        }, () -> fetching));
        fetchConfig(url).whenComplete((code, err) -> McScreens.mc().execute(() -> {
            if (configFetch != fetch) return; // cancelled while fetching
            configFetch = null;
            if (err != null) {
                LOGGER.warn("[etmc] couldn't load config link from {}: {}", RemoteConfig.hostOf(url), Errors.message(err));
                showError(parent, Msg.tr("etmc.join.bad_link.title", "That etmc link doesn't work"),
                        Msg.tr("etmc.join.config_failed", "Couldn't load its config: %s", Errors.message(err)));
                return;
            }
            connectViaLink(parent, code.encodeLink());
        }));
    }

    /**
     * Drives the in-progress link join each tick. It connects only once the connection can actually
     * work — this node has its virtual IP and the host is in the route table — taking a direct (P2P)
     * route as soon as one appears, else the relayed one after {@link #P2P_WAIT_TIMEOUT_MS}. If the
     * host never shows up it gives up after {@link #HOST_FIND_TIMEOUT_MS} with a reason, instead of
     * connecting into EasyTier's "data plane net is not ready".
     */
    private void driveLinkAttempt(long now) {
        LinkAttempt a = linkAttempt;
        if (a == null || !a.instanceReady || a.proceeded) return;
        // One read of the volatile snapshot: the decision below and the line that logs it have to
        // describe the same set of peers.
        NetworkStatus st = cachedStatus();
        NetworkStatus.Peer host = st.peerAt(a.code.hostIp);
        boolean addressed = st.virtualIp() != null;
        long waited = now - a.startedAt;

        if (now - a.lastLog > LINK_LOG_INTERVAL_MS) {
            a.lastLog = now;
            LOGGER.info("[etmc] link wait {}s: own ip {}, host {} {} | relays {} | peers: {}{}",
                    waited / 1000, addressed ? st.virtualIp() : "(none yet)", a.code.hostIp,
                    host == null ? "not in route table yet"
                            : "cost=" + host.cost() + (host.relay() ? " (relay)" : " (p2p)"),
                    st.relayNodes(), peerSummary(st),
                    st.errorMsg() == null ? "" : " | EasyTier: " + st.errorMsg());
        }

        if (addressed && host != null) {
            if (!host.relay()) {
                LOGGER.info("[etmc] direct P2P route to host ready — connecting");
                linkDoProceed(a);
                return;
            }
            if (a.hostFoundAt == 0) a.hostFoundAt = now;
            long left = P2P_WAIT_TIMEOUT_MS - (now - a.hostFoundAt);
            if (left <= 0) {
                LOGGER.info("[etmc] no direct route after {}s — connecting over the relay", P2P_WAIT_TIMEOUT_MS / 1000);
                linkDoProceed(a);
                return;
            }
            a.progress = Msg.tr("etmc.join.progress.p2p",
                    "Host found via the relay — trying for a direct link (%ss)…", left / 1000 + 1);
            return;
        }

        a.progress = waitingReason(st, addressed);
        if (waited > HOST_FIND_TIMEOUT_MS) {
            String why = diagnose(st, addressed, a.code);
            LOGGER.warn("[etmc] etmc:// join gave up after {}s: {}", waited / 1000, why);
            linkAttempt = null;
            leaveAsync();
            showError(a.parent, Msg.tr("etmc.join.unreachable.title", "Couldn't reach the host"), why);
        }
    }

    /**
     * What the join is waiting for, for the connecting screen. Note that having a virtual IP says
     * nothing here: EasyTier's DHCP hands out an address before any peer is reached.
     */
    private static String waitingReason(NetworkStatus st, boolean addressed) {
        if (!st.running()) return Msg.tr("etmc.join.progress.start", "Starting the EasyTier network…");
        if (st.relayNodes() == 0 && st.peers().isEmpty()) {
            return Msg.tr("etmc.join.progress.relay", "Contacting the relay…");
        }
        if (!addressed) return Msg.tr("etmc.join.progress.address", "Reached the network — getting an address…");
        return Msg.tr("etmc.join.progress.host", "Reached the network — waiting for the host to show up…");
    }

    /** Why the host was never reached, in terms the player can act on. */
    private static String diagnose(NetworkStatus st, boolean addressed, JoinCode code) {
        String easytier = st.errorMsg() == null ? ""
                : Msg.tr("etmc.join.easytier_says", " EasyTier says: %s", st.errorMsg());
        if (!st.running()) return Msg.tr("etmc.join.why.not_started", "The EasyTier network didn't start.") + easytier;
        if (st.relayNodes() == 0 && st.peers().isEmpty()) {
            return Msg.tr("etmc.join.why.no_relay", "The relay (%s) couldn't be reached. Check your internet "
                    + "connection, and ask the host whether the relay is online.", String.join(", ", code.relays))
                    + easytier;
        }
        return Msg.tr("etmc.join.why.no_host", "The relay answered, but the host (%s) isn't on the network. Make "
                + "sure they are hosting right now and that the link is from their current session (the network "
                + "name and secret must match).", code.hostIp) + easytier;
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

    /** "Join now anyway": proceeds over whatever path exists. Queues if the instance is still starting. */
    private void linkProceed(LinkAttempt a) {
        if (a != linkAttempt || a.proceeded) return;
        if (!a.instanceReady) {
            a.proceedRequested = true;
            return;
        }
        linkDoProceed(a);
    }

    /**
     * Pings the host's world over the mesh, then hands over to Minecraft. The ping comes first because
     * EasyTier reports a connect to a virtual port nobody listens on as done and then stays silent, so
     * without it a host who has closed their world would leave the joiner on "Connecting…" until
     * Minecraft's own 30s timeout. A silent port gets a clear error instead. Any other outcome still
     * connects: a server with status turned off hangs up on the ping but lets players in, and an
     * unreachable host ("Join now anyway") gets the connection's own error, as before.
     *
     * <p>The answer also carries the host's protocol, for ViaFabricPlus: it can't auto-detect across
     * an etmc:// join (it raw-sockets the placeholder address), so it gets the version pinned instead.
     */
    private void linkDoProceed(LinkAttempt a) {
        if (a.proceeded) return;
        a.proceeded = true;
        a.progress = Msg.tr("etmc.join.progress.world", "Host found — checking that their world is open…");
        EtmcSession s = session;
        EtmcConnect.Target target =
                new EtmcConnect.Target(s.easyTier(), a.instName, a.code.hostIp, a.code.hostPort);
        CompletableFuture
                .supplyAsync(() -> StatusPing.probe(s.easyTier(), target.instanceName(), target.hostIp(),
                        target.hostPort(), WORLD_CHECK_TIMEOUT_MS), worker)
                .whenComplete((ping, err) -> McScreens.mc().execute(() -> {
                    if (a != linkAttempt) return; // cancelled while checking; linkCancel left the network
                    linkAttempt = null;
                    StatusPing.Outcome outcome = err == null ? ping.outcome() : StatusPing.Outcome.UNREACHABLE;
                    LOGGER.info("[etmc] host world check: {}{}", outcome,
                            outcome == StatusPing.Outcome.ANSWERED ? " (protocol " + ping.protocol() + ")" : "");
                    if (outcome == StatusPing.Outcome.SILENT) {
                        leaveAsync();
                        showError(a.parent, Msg.tr("etmc.join.no_world.title", "The host's world isn't open"),
                                Msg.tr("etmc.join.no_world", "etmc reached the host, but no world answered on "
                                        + "port %s. They may have closed it or stopped hosting: ask them to host "
                                        + "again and send you the new link.", a.code.hostPort));
                        return;
                    }
                    armAndConnect(target, a.parent, a.label,
                            outcome == StatusPing.Outcome.ANSWERED ? ping.protocol() : -1);
                }));
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
        volatile long hostFoundAt;
        volatile long lastLog;
        /** What the connecting screen shows under the title. */
        volatile String progress = Msg.tr("etmc.join.progress.start", "Starting the EasyTier network…");

        LinkAttempt(Screen parent, JoinCode code, String label) {
            this.parent = parent;
            this.code = code;
            this.label = label;
        }
    }

    public CompletableFuture<Void> leaveAsync() {
        if (session == null) return CompletableFuture.completedFuture(null);
        boolean hadProxy = session.mode() == EtmcSession.Mode.JOIN && !session.linkJoin();
        return CompletableFuture.runAsync(() -> session.leave(), worker)
                .thenRun(() -> {
                    cachedStatus = NetworkStatus.empty();
                    McScreens.mc().execute(() -> {
                        hostWorldSeen = false;
                        proxyJoinPlayed = false;
                        hostFailureNotified = false;
                        McNet.requireAccounts();
                        // The proxy's server-list entry points at a port that just closed.
                        if (hadProxy) McNet.removeJoinEntries();
                    });
                });
    }

    // ------------------------------------------------------------------ periodic tick

    /** Called every client tick. Refreshes the cached status and drives a pending link join. */
    public void tick() {
        LaunchProbe.tick();
        if (!isReady()) return;
        if (!serverListSwept) {
            // Entries a crashed/killed game left behind point at proxy ports that no longer exist.
            serverListSwept = true;
            try {
                McNet.removeJoinEntries();
            } catch (Throwable t) {
                LOGGER.debug("[etmc] couldn't sweep stale server-list entries", t);
            }
        }
        if (!session.isActive()) return;
        long now = System.currentTimeMillis();
        // Poll faster while waiting on a link join so a direct route is noticed (and the timeout fires) promptly.
        long interval = linkAttempt != null ? LINK_POLL_INTERVAL_MS : STATUS_POLL_INTERVAL_MS;
        if (now - lastStatusPoll > interval) {
            lastStatusPoll = now;
            pollStatus();
        }
        driveLinkAttempt(now);
        endWithTheWorld();
        reportHostFailure();
    }

    /**
     * Ends a session whose world is gone: hosting outlives nothing once the hosted world closes (joiners
     * would be dropped silently while the menu still said "Hosting"), and a proxy join ends once the
     * player leaves the server it carried them to. Link joins end on their own when their channel closes.
     */
    private void endWithTheWorld() {
        EtmcSession s = session;
        if (s.mode() == EtmcSession.Mode.HOST) {
            if (McScreens.singleplayer()) {
                hostWorldSeen = true;
            } else if (hostWorldSeen) {
                LOGGER.info("[etmc] hosted world closed — leaving the etmc network");
                hostWorldSeen = false;
                leaveAsync();
            }
        } else if (s.mode() == EtmcSession.Mode.JOIN && !s.linkJoin()) {
            boolean inWorld = McScreens.inWorld();
            if (inWorld && s.activeConnections() > 0) {
                proxyJoinPlayed = true;
            } else if (!inWorld && proxyJoinPlayed && s.activeConnections() == 0) {
                LOGGER.info("[etmc] left the etmc server — leaving the etmc network");
                proxyJoinPlayed = false;
                leaveAsync();
            }
        }
    }

    /** Tells the host, once, when joiners can no longer get in although the world is still "hosted". */
    private void reportHostFailure() {
        if (hostFailureNotified) return;
        String failure = session.hostFailure();
        if (failure == null) return;
        hostFailureNotified = true;
        LOGGER.warn("[etmc] host stopped accepting joiners: {}", failure);
        McScreens.chat(Msg.tr("etmc.host.failed", "Joiners can't connect any more (%s). Stop and start hosting "
                + "again from the etmc menu.", failure));
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

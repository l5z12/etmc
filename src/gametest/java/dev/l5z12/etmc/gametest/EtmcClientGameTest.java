package dev.l5z12.etmc.gametest;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.screen.EtmcNoticeScreen;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import dev.l5z12.etmc.client.screen.SettingsScreen;
import dev.l5z12.etmc.client.screen.StatusScreen;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.EtmcConnect;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.core.StatusPing;
import dev.l5z12.etmc.ffi.EasyTier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.impl.client.gametest.context.TestClientWorldContextImpl;
import net.fabricmc.fabric.impl.client.gametest.context.TestServerConnectionImpl;
import net.fabricmc.fabric.impl.client.gametest.util.ClientGameTestImpl;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.DirectConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.server.MinecraftServer;

import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * etmc in a real Minecraft client (Fabric client gametest; this node only, see build.gradle.kts).
 *
 * <p>Out of a world: the etmc button on the Multiplayer screen opens the menu, and hosting is offered
 * only with a world open. In a world: hosting it for real — the world opens to LAN, the bundled
 * EasyTier library starts the host's network, and a second EasyTier node in this JVM (standing in for
 * both the relay and a joiner) reaches the host and gets a server-list answer from the world through
 * etmc's bridge. That is the whole host path a player's friend depends on.
 *
 * <p>Joining: a dedicated server in this JVM, hosted over EasyTier the way etmc hosts a world, and
 * this client pasting the invite link into Direct Connection, as a friend would. That runs the whole
 * join: the link mixins, the connecting screen, the wait for the host, the check that its world is
 * open, and Minecraft's own login riding the data plane. Then a link whose port nothing listens on,
 * which must fail with a clear error rather than hang on "Connecting…".
 *
 * <p>Screenshots of each step land in the run directory (CI uploads them).
 */
public class EtmcClientGameTest implements FabricClientGameTest {

    private static final int SECONDS = 20;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        menuFromTheMultiplayerScreen(ctx);
        menuInChinese(ctx);
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            hostTheWorldAndReachItOverEasyTier(ctx);
        }
        joinAServerThroughAnEtmcLink(ctx);
    }

    private static void menuFromTheMultiplayerScreen(ClientGameTestContext ctx) {
        // A server-list entry saved with an etmc link says what it is, rather than "Can't connect".
        String link = new JoinCode("gametest", "s", List.of("tcp://127.0.0.1:1"), EtmcConfig.HOST_VIRTUAL_IP,
                EtmcConfig.DEFAULT_VIRTUAL_PORT, "gametest").encodeLink();
        ctx.runOnClient(c -> {
            ServerList saved = new ServerList(c);
            saved.loadFile();
            saved.add(new ServerInfo("A saved etmc link", link, ServerInfo.ServerType.OTHER), false);
            saved.saveFile();
        });
        ctx.setScreen(() -> new MultiplayerScreen(new TitleScreen()));
        ctx.waitForScreen(MultiplayerScreen.class);
        ctx.waitFor(c -> savedEntry(c, link) != null && savedEntry(c, link).getStatus() == ServerInfo.Status.UNREACHABLE,
                30 * SECONDS);
        String label = ctx.computeOnClient(c -> savedEntry(c, link).label.getString());
        check(label.equals("etmc invite link: click Join Server to connect"), "the saved link's entry reads: " + label);
        ctx.takeScreenshot("etmc-server-list-link");
        ctx.runOnClient(c -> {
            ServerList saved = ((MultiplayerScreen) c.currentScreen).getServerList();
            saved.remove(saved.get(link));
            saved.saveFile();
        });

        ctx.setScreen(() -> new MultiplayerScreen(new TitleScreen()));
        ctx.waitForScreen(MultiplayerScreen.class);
        ctx.clickScreenButton("etmc"); // the button MultiplayerScreenMixin adds
        ctx.waitForScreen(EtmcScreen.class);
        ctx.takeScreenshot("etmc-menu-title-screen");
        check(ctx.computeOnClient(c -> !button(c.currentScreen, "Host this world").active),
                "hosting is offered with no world open");

        ctx.clickScreenButton("Settings & relays");
        ctx.waitForScreen(SettingsScreen.class);
        ctx.takeScreenshot("etmc-settings");
        ctx.setScreen(() -> null);

        // Mod Menu (on this node's dev runtime) finds etmc's config screen through EtmcModMenu.
        Screen fromModMenu = ctx.computeOnClient(c -> modMenuConfigScreen("etmc"));
        check(fromModMenu instanceof SettingsScreen, "Mod Menu's config screen for etmc is " + fromModMenu);
    }

    private static ServerInfo savedEntry(net.minecraft.client.MinecraftClient c, String address) {
        return c.currentScreen instanceof MultiplayerScreen m ? m.getServerList().get(address) : null;
    }

    private static Screen modMenuConfigScreen(String modId) {
        try {
            return (Screen) Class.forName("com.terraformersmc.modmenu.ModMenu")
                    .getMethod("getConfigScreen", String.class, Screen.class).invoke(null, modId, new TitleScreen());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("couldn't ask Mod Menu for " + modId + "'s config screen", e);
        }
    }

    /** The game's language picks etmc's text (zh_cn.json), through Msg's translator. */
    private static void menuInChinese(ClientGameTestContext ctx) {
        setLanguage(ctx, "zh_cn");
        try {
            ctx.setScreen(() -> new EtmcScreen(null));
            ctx.waitForScreen(EtmcScreen.class);
            ctx.computeOnClient(c -> button(c.currentScreen, "开放此世界")); // throws if still English
            ctx.takeScreenshot("etmc-menu-zh_cn");
            ctx.setScreen(() -> new SettingsScreen(null));
            ctx.waitForScreen(SettingsScreen.class);
            ctx.takeScreenshot("etmc-settings-zh_cn");
            ctx.setScreen(() -> null);
        } finally {
            setLanguage(ctx, "en_us");
        }
    }

    private static void setLanguage(ClientGameTestContext ctx, String code) {
        AtomicReference<CompletableFuture<Void>> reload = new AtomicReference<>();
        ctx.runOnClient(c -> {
            c.getLanguageManager().setLanguage(code);
            c.options.language = code;
            reload.set(c.reloadResources());
        });
        // The reload finishes before its loading overlay has faded out; wait for that too.
        ctx.waitFor(c -> reload.get().isDone() && c.getOverlay() == null, 60 * SECONDS);
    }

    private static void hostTheWorldAndReachItOverEasyTier(ClientGameTestContext ctx) {
        EtmcManager etmc = EtmcManager.get();
        check(etmc.isReady(), "EasyTier library didn't load: " + etmc.nativeError());
        EasyTier et = etmc.session().easyTier();

        String id = UUID.randomUUID().toString().substring(0, 8);
        String network = "etmc-gametest-" + id;
        String secret = "s-" + id;
        String peer = "gametest-peer-" + id;
        int port = freePort();
        String relay = "tcp://127.0.0.1:" + port;

        // The stand-in relay/joiner: an ordinary node of the same network listening on localhost.
        String peerToml = EtmcConfig.joinToml(peer, network, secret, List.of())
                .replace("listeners = []", "listeners = [\"" + relay + "\"]");
        check(peerToml.contains(relay), "join TOML layout changed; update this test");
        et.runNetworkInstance(peerToml);
        try {
            ctx.runOnClient(c -> etmc.config().relays = List.of(relay));
            check(ctx.computeOnClient(c -> etmc.hostBlocker()) == null,
                    "hosting is blocked in a singleplayer world: " + ctx.computeOnClient(c -> etmc.hostBlocker()));

            check(ctx.computeOnClient(c -> c.getServer().isOnlineMode()), "a new world doesn't check accounts");
            AtomicReference<CompletableFuture<JoinCode>> hosting = new AtomicReference<>();
            ctx.runOnClient(c -> hosting.set(etmc.hostAsync(network, secret, "gametest", null, false, true)));
            ctx.waitFor(c -> hosting.get().isDone(), 60 * SECONDS);
            JoinCode code = hosting.get().join();
            check(!ctx.computeOnClient(c -> c.getServer().isOnlineMode()), "the offline accounts switch had no effect");
            check(network.equals(code.networkName), "the invite link names another network: " + code.networkName);
            check(code.relays.contains(relay), "the invite link lacks the relay: " + code.relays);

            EtmcSession session = etmc.session();
            check(session.mode() == EtmcSession.Mode.HOST, "not hosting: " + session.mode());
            check(session.lanPort() > 0, "the world wasn't opened to LAN");
            ctx.waitFor(c -> {
                NetworkStatus st = etmc.cachedStatus();
                return st.running() && EtmcConfig.HOST_VIRTUAL_IP.equals(st.virtualIp());
            }, 60 * SECONDS);

            // The stand-in joiner reaches the host through EasyTier and pings the world's server.
            int expected = SharedConstants.getProtocolVersion();
            CompletableFuture<Integer> ping = CompletableFuture.supplyAsync(
                    () -> pingUntilAnswered(et, peer, code.hostIp, code.hostPort));
            ctx.waitFor(c -> ping.isDone(), 120 * SECONDS);
            int got = ping.join();
            check(got == expected, "the hosted world answered the ping over EasyTier with protocol " + got
                    + " (expected " + expected + "); host status: " + etmc.cachedStatus()
                    + "; host failure: " + session.hostFailure());

            ctx.waitFor(c -> etmc.cachedStatus().peers().stream().anyMatch(p -> p.ipv4() != null), 30 * SECONDS);
            ctx.takeScreenshot("etmc-hosting-hud");
            ctx.setScreen(() -> new StatusScreen(null));
            ctx.waitForScreen(StatusScreen.class);
            ctx.waitTicks(5);
            ctx.takeScreenshot("etmc-status-hosting");
            ctx.setScreen(() -> null);
            check(session.hostFailure() == null, "the host reported a failure: " + session.hostFailure());

            AtomicReference<CompletableFuture<Void>> leaving = new AtomicReference<>();
            ctx.runOnClient(c -> leaving.set(etmc.leaveAsync()));
            ctx.waitFor(c -> leaving.get().isDone(), 30 * SECONDS);
            leaving.get().join();
            check(!etmc.session().isActive(), "still in a session after leaving");
            // Leaving puts the check back on the client thread, and the world stays open to LAN.
            ctx.waitFor(c -> c.getServer().isOnlineMode(), 5 * SECONDS);
        } finally {
            et.deleteNetworkInstance(peer);
        }
    }

    private static void joinAServerThroughAnEtmcLink(ClientGameTestContext ctx) {
        EtmcManager etmc = EtmcManager.get();
        check(etmc.isReady(), "EasyTier library didn't load: " + etmc.nativeError());
        check(!etmc.session().isActive(), "the client is still in an etmc network: " + etmc.session().mode());
        EasyTier et = etmc.session().easyTier();

        String id = UUID.randomUUID().toString().substring(0, 8);
        String network = "etmc-gametest-join-" + id;
        String secret = "s-" + id;
        String relayInst = "gametest-relay-" + id;
        String relay = "tcp://127.0.0.1:" + freePort();
        String relayToml = EtmcConfig.joinToml(relayInst, network, secret, List.of())
                .replace("listeners = []", "listeners = [\"" + relay + "\"]");
        check(relayToml.contains(relay), "join TOML layout changed; update this test");
        et.runNetworkInstance(relayToml);

        Properties props = new Properties();
        props.setProperty("server-port", String.valueOf(freePort()));
        props.setProperty("motd", "etmc gametest server");
        EtmcSession host = new EtmcSession(et);
        try (TestDedicatedServerContext server = ctx.worldBuilder().createServer(props)) {
            int serverPort = server.computeOnServer(MinecraftServer::getServerPort);
            // The host side of etmc, for this server: what hosting a world does once it is open to LAN.
            host.setPlayerName("gametest");
            JoinCode code = host.host(serverPort, network, secret, List.of(relay),
                    EtmcConfig.DEFAULT_VIRTUAL_PORT, "gametest server");
            try {
                String name = ctx.computeOnClient(c -> c.getSession().getUsername());
                joinWithTheLink(ctx, code.encodeLink());
                check(ctx.computeOnClient(c -> c.world != null), "the join ended on "
                        + ctx.computeOnClient(c -> describe(c.currentScreen)) + " instead of the world");
                TestServerConnection connection = new TestServerConnectionImpl(ctx, new TestClientWorldContextImpl(ctx));
                try (connection) {
                    connection.getClientWorld().waitForChunksRender();
                    check(etmc.session().linkJoin(), "the join didn't go through the etmc:// link flow");
                    check(EtmcConnect.openChannels() == 1,
                            "the connection isn't riding the data plane: " + EtmcConnect.openChannels() + " channels");
                    String[] players = server.computeOnServer(s -> s.getPlayerManager().getPlayerNames());
                    check(Arrays.asList(players).contains(name),
                            "the server doesn't have " + name + " on it: " + Arrays.toString(players));
                    ctx.takeScreenshot("etmc-joined-over-link");
                }
                // Leaving the server ends the link join: its channel closing leaves the network.
                ctx.waitFor(c -> !etmc.session().isActive(), 30 * SECONDS);

                // The same host, but a port nothing listens on: the world check must catch it.
                JoinCode closed = JoinCode.decode(code.encodeLink());
                closed.hostPort = EtmcConfig.DEFAULT_VIRTUAL_PORT + 1;
                joinWithTheLink(ctx, closed.encodeLink());
                String shown = ctx.computeOnClient(c -> describe(c.currentScreen));
                check(shown.endsWith("The host's world isn't open"), "joining a closed port ended on " + shown);
                ctx.takeScreenshot("etmc-join-world-not-open");
                ctx.waitFor(c -> !etmc.session().isActive(), 30 * SECONDS);
                ctx.setScreen(() -> null);
            } finally {
                host.leave();
            }
        } finally {
            et.deleteNetworkInstance(relayInst);
        }
    }

    /**
     * Pastes {@code link} into Direct Connection and joins, as a player would. Returns in the loaded
     * world, or on etmc's error screen if the join failed.
     */
    private static void joinWithTheLink(ClientGameTestContext ctx, String link) {
        ctx.runOnClient(c -> c.options.lastServer = ""); // Direct Connection starts from the last address
        ctx.setScreen(() -> new MultiplayerScreen(new TitleScreen()));
        ctx.waitForScreen(MultiplayerScreen.class);
        ctx.clickScreenButton("Direct Connection");
        ctx.waitForScreen(DirectConnectScreen.class);
        ctx.getInput().typeChars(link);
        ctx.clickScreenButton("Join Server");
        // The connecting screen until the host is found and its world answers; then Minecraft's own
        // connect (and the world), or etmc's error screen.
        ctx.waitFor(c -> c.world != null || c.currentScreen instanceof EtmcNoticeScreen, 120 * SECONDS);
        if (ctx.computeOnClient(c -> c.world != null)) ClientGameTestImpl.waitForWorldLoad(ctx);
    }

    private static String describe(Screen screen) {
        return screen == null ? "no screen" : screen.getClass().getSimpleName() + ": " + screen.getTitle().getString();
    }

    /** {@link StatusPing} until the route to the host is up (it takes a moment to propagate). */
    private static int pingUntilAnswered(EasyTier et, String inst, String hostIp, int hostPort) {
        long deadline = System.nanoTime() + 100_000_000_000L;
        int got = -1;
        while (System.nanoTime() < deadline) {
            got = StatusPing.protocolVersion(et, inst, hostIp, hostPort);
            if (got > 0) return got;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return got;
            }
        }
        return got;
    }

    private static ClickableWidget button(Screen screen, String label) {
        for (var child : screen.children()) {
            if (child instanceof ClickableWidget w && label.equals(w.getMessage().getString())) return w;
        }
        throw new AssertionError("no '" + label + "' button on " + screen.getClass().getName());
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void check(boolean ok, String failure) {
        if (!ok) throw new AssertionError(failure);
    }
}

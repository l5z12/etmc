package dev.l5z12.etmc.gametest;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import dev.l5z12.etmc.client.screen.SettingsScreen;
import dev.l5z12.etmc.client.screen.StatusScreen;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.core.StatusPing;
import dev.l5z12.etmc.ffi.EasyTier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.net.ServerSocket;
import java.util.List;
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
 * etmc's bridge. That is the whole host path a player's friend depends on; only the joining client's
 * screens are left out, since one client can't join itself.
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
    }

    private static void menuFromTheMultiplayerScreen(ClientGameTestContext ctx) {
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

            AtomicReference<CompletableFuture<JoinCode>> hosting = new AtomicReference<>();
            ctx.runOnClient(c -> hosting.set(etmc.hostAsync(network, secret, "gametest", null, false)));
            ctx.waitFor(c -> hosting.get().isDone(), 60 * SECONDS);
            JoinCode code = hosting.get().join();
            check(network.equals(code.networkName), "the join code names another network: " + code.networkName);
            check(code.relays.contains(relay), "the join code lacks the relay: " + code.relays);

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
        } finally {
            et.deleteNetworkInstance(peer);
        }
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

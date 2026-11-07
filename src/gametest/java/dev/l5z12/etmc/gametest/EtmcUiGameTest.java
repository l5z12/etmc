package dev.l5z12.etmc.gametest;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.screen.ConnectUrlScreen;
import dev.l5z12.etmc.client.screen.EtmcAskScreen;
import dev.l5z12.etmc.client.screen.EtmcConnectingScreen;
import dev.l5z12.etmc.client.screen.EtmcNoticeScreen;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import dev.l5z12.etmc.client.screen.HostScreen;
import dev.l5z12.etmc.client.screen.JoinScreen;
import dev.l5z12.etmc.client.screen.LinkGeneratorScreen;
import dev.l5z12.etmc.client.screen.SettingsScreen;
import dev.l5z12.etmc.client.screen.StatusScreen;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Walks every etmc screen at the window sizes that matter, in English and in Chinese, and fails if a
 * widget is off screen, two widgets overlap, or a button's label doesn't fit it. Screenshots of each
 * land in the run directory (CI uploads them), which is how drawn text gets looked at.
 *
 * <p>640x480 is the smallest GUI the game ever gives a player at Auto scale (320x240); 1920x1080 at
 * Auto is 480x270, the size most players actually see.
 */
public class EtmcUiGameTest implements FabricClientGameTest {

    private static final int[][] WINDOWS = {{640, 480}, {1920, 1080}};
    private static final String LONG_TEXT = "etmc couldn't reach the host over the relay. Check that the relay address is "
            + "right and that it is running, then try again. If it keeps happening, ask the host to copy the link again.";

    private final List<String> problems = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext ctx) {
        EtmcManager etmc = EtmcManager.get();
        try {
            for (String language : List.of("en_us", "zh_cn")) {
                EtmcClientGameTest.setLanguage(ctx, language);
                for (int[] window : WINDOWS) {
                    ctx.getInput().resizeWindow(window[0], window[1]);
                    ctx.waitTicks(2);
                    String at = window[0] + "x" + window[1] + "-" + language;
                    ctx.runOnClient(c -> etmc.config().relays = List.of());
                    outsideAWorld(ctx, at);
                }
            }
            EtmcClientGameTest.setLanguage(ctx, "en_us");
            ctx.getInput().resizeWindow(1920, 1080);
            typedTextSurvivesAResize(ctx);
            joinFillsInAndChecksTheLink(ctx);
            enterSubmitsAForm(ctx);

            try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
                world.getClientWorld().waitForChunksRender();
                hostAsksForARelayAndKeepsChoices(ctx);
                pauseMenuReachesEtmc(ctx);
                for (int[] window : WINDOWS) {
                    ctx.getInput().resizeWindow(window[0], window[1]);
                    ctx.waitTicks(2);
                    inAWorld(ctx, window[0] + "x" + window[1]);
                }
            }
        } finally {
            ctx.getInput().resizeWindow(854, 480);
            EtmcClientGameTest.setLanguage(ctx, "en_us");
            ctx.runOnClient(c -> etmc.config().relays = List.of());
        }
        if (!problems.isEmpty()) throw new AssertionError("UI problems:\n  " + String.join("\n  ", problems));
    }

    private void outsideAWorld(ClientGameTestContext ctx, String at) {
        view(ctx, at, "menu", () -> new EtmcScreen(null));
        view(ctx, at, "host-no-world", () -> new HostScreen(null));
        view(ctx, at, "join", () -> new JoinScreen(null));
        ctx.getInput().typeChars("not a link");
        ctx.waitTicks(2);
        shot(ctx, at, "join-typed");
        view(ctx, at, "url", () -> new ConnectUrlScreen(null));
        view(ctx, at, "generator", () -> new LinkGeneratorScreen(null));
        view(ctx, at, "settings", () -> new SettingsScreen(null));
        view(ctx, at, "notice", () -> new EtmcNoticeScreen(null, "Couldn't reach the host", LONG_TEXT));
        view(ctx, at, "ask", () -> new EtmcAskScreen(null, "This link can't check the host", LONG_TEXT, () -> {}, () -> {}));
        view(ctx, at, "connecting", () -> new EtmcConnectingScreen(null, "Steve's world", () -> {}, () -> {},
                () -> "Reached the network — waiting for the host to show up…"));
        ctx.setScreen(() -> null);
    }

    private void inAWorld(ClientGameTestContext ctx, String at) {
        EtmcManager etmc = EtmcManager.get();
        ctx.runOnClient(c -> etmc.config().relays = List.of());
        view(ctx, at, "world-menu-no-relay", () -> new EtmcScreen(null));
        view(ctx, at, "world-host-no-relay", () -> new HostScreen(null));

        String relay = "tcp://127.0.0.1:" + freePort();
        ctx.runOnClient(c -> {
            etmc.config().relays = List.of(relay);
            etmc.config().lanAllowOffline = true;
            etmc.config().signHostAccount = true;
        });
        view(ctx, at, "world-host-warnings", () -> new HostScreen(null));
        ctx.clickScreenButton("More options");
        ctx.waitTicks(2);
        shot(ctx, at, "world-host-more-warnings");
        check(ctx, at, "world-host-more-warnings");
        ctx.runOnClient(c -> {
            etmc.config().lanAllowOffline = false;
            etmc.config().signHostAccount = false;
        });
        view(ctx, at, "world-host", () -> new HostScreen(null));
        hover(ctx, "Cheats: OFF");
        shot(ctx, at, "world-host-hint");
        ctx.getInput().setCursorPos(0, 0);
        view(ctx, at, "world-join", () -> new JoinScreen(null));

        AtomicReference<CompletableFuture<?>> hosting = new AtomicReference<>();
        ctx.runOnClient(c -> hosting.set(etmc.hostAsync("etmc-tour", "tour", "Steve's world", null, false, false)));
        ctx.waitFor(c -> hosting.get().isDone(), 60 * 20);
        hosting.get().join();
        try {
            view(ctx, at, "world-menu-hosting", () -> new EtmcScreen(null));
            holdStatus(ctx);
            ctx.setScreen(() -> new StatusScreen(null));
            ctx.waitForScreen(StatusScreen.class);
            fakeStatus(ctx, 0);
            shot(ctx, at, "status-nobody");
            check(ctx, at, "status");
            fakeStatus(ctx, 3);
            shot(ctx, at, "status-peers");
            fakeStatus(ctx, 14);
            shot(ctx, at, "status-many-peers");

            // With people connected, stopping asks first, and Cancel keeps the session.
            ctx.clickScreenButton("Stop hosting");
            ctx.waitForScreen(EtmcAskScreen.class);
            shot(ctx, at, "ask-stop");
            check(ctx, at, "ask-stop");
            ctx.clickScreenButton("Cancel");
            ctx.waitForScreen(StatusScreen.class);
            if (!etmc.session().isActive()) problems.add(at + ": cancelling Stop hosting still stopped");

            ctx.setScreen(() -> null);
            ctx.waitTick();
            fakeStatus(ctx, 3);
            shot(ctx, at, "hud");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F3);
            ctx.waitTicks(2);
            shot(ctx, at, "hud-under-f3");
            ctx.getInput().pressKey(GLFW.GLFW_KEY_F3);
        } finally {
            ctx.runOnClient(c -> setManagerField("lastStatusPoll", 0L));
            AtomicReference<CompletableFuture<Void>> leaving = new AtomicReference<>();
            ctx.runOnClient(c -> leaving.set(etmc.leaveAsync()));
            ctx.waitFor(c -> leaving.get().isDone(), 30 * 20);
        }
    }

    /** Resizing rebuilds a screen's widgets; what the player typed must come through it. */
    private void typedTextSurvivesAResize(ClientGameTestContext ctx) {
        for (Supplier<Screen> screen : List.<Supplier<Screen>>of(
                () -> new JoinScreen(null), () -> new ConnectUrlScreen(null), () -> new LinkGeneratorScreen(null))) {
            ctx.setScreen(screen);
            ctx.waitTick();
            ctx.getInput().typeChars("typed");
            ctx.waitTick();
            ctx.getInput().resizeWindow(1280, 720);
            ctx.waitTicks(2);
            ctx.getInput().resizeWindow(1920, 1080);
            ctx.waitTicks(2);
            String name = ctx.computeOnClient(c -> c.currentScreen.getClass().getSimpleName());
            boolean kept = ctx.computeOnClient(c -> c.currentScreen.children().stream()
                    .anyMatch(w -> w instanceof TextFieldWidget f && f.getText().contains("typed")));
            if (!kept) problems.add(name + ": a window resize lost what was typed");
        }
        ctx.setScreen(() -> null);
    }

    /** A link on the clipboard is waiting in the box; Join is off until there's something to join. */
    private void joinFillsInAndChecksTheLink(ClientGameTestContext ctx) {
        String link = new JoinCode("tour", "s", List.of("tcp://127.0.0.1:1"), EtmcConfig.HOST_VIRTUAL_IP,
                EtmcConfig.DEFAULT_VIRTUAL_PORT, "Steve's world").encodeLink();
        ctx.runOnClient(c -> McScreens.setClipboard(link));
        ctx.setScreen(() -> new JoinScreen(null));
        ctx.waitTicks(2);
        shot(ctx, "1920x1080", "join-from-clipboard");
        if (!ctx.computeOnClient(c -> EtmcClientGameTest.button(c.currentScreen, "Join").active)) {
            problems.add("Join is off with a good link from the clipboard");
        }
        ctx.runOnClient(c -> McScreens.setClipboard("not a link either")); // the game ignores an empty one
        ctx.setScreen(() -> new JoinScreen(null));
        ctx.waitTicks(2);
        if (ctx.computeOnClient(c -> EtmcClientGameTest.button(c.currentScreen, "Join").active)) {
            problems.add("Join is on with nothing to join");
        }
        ctx.getInput().typeChars("not a link");
        ctx.waitTicks(2);
        if (ctx.computeOnClient(c -> EtmcClientGameTest.button(c.currentScreen, "Join").active)) {
            problems.add("Join is on with something that isn't a link");
        }
        ctx.setScreen(() -> null);
    }

    /** Enter in a text box does what the screen's main button does. */
    private void enterSubmitsAForm(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new SettingsScreen(new EtmcScreen(null)));
        ctx.waitTicks(2);
        ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        ctx.waitForScreen(EtmcScreen.class);
        ctx.setScreen(() -> null);
    }

    /** With no relay, hosting asks for one right there; what the player picks comes through a resize. */
    private void hostAsksForARelayAndKeepsChoices(ClientGameTestContext ctx) {
        EtmcManager etmc = EtmcManager.get();
        ctx.runOnClient(c -> etmc.config().relays = List.of());
        ctx.setScreen(() -> new HostScreen(null));
        ctx.waitTicks(2);
        if (ctx.computeOnClient(c -> EtmcClientGameTest.button(c.currentScreen, "Start hosting").active)) {
            problems.add("Start hosting is on with no relay");
        }
        ctx.getInput().typeChars("tcp://127.0.0.1:1");
        ctx.clickScreenButton("Cheats: OFF");
        ctx.waitTicks(2);
        if (!ctx.computeOnClient(c -> EtmcClientGameTest.button(c.currentScreen, "Start hosting").active)) {
            problems.add("Start hosting stays off after typing a relay");
        }
        ctx.getInput().resizeWindow(1280, 720);
        ctx.waitTicks(2);
        ctx.getInput().resizeWindow(1920, 1080);
        ctx.waitTicks(2);
        boolean kept = ctx.computeOnClient(c -> c.currentScreen.children().stream()
                .anyMatch(w -> w instanceof TextFieldWidget f && f.getText().equals("tcp://127.0.0.1:1"))
                && c.currentScreen.children().stream()
                .anyMatch(w -> w instanceof ButtonWidget b && b.getMessage().getString().equals("Cheats: ON")));
        if (!kept) problems.add("HostScreen: a window resize lost the relay or the cheats choice");
        ctx.setScreen(() -> null);
        ctx.runOnClient(c -> etmc.config().relays = List.of());
    }

    private void pauseMenuReachesEtmc(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new GameMenuScreen(true));
        ctx.waitTicks(2);
        ctx.takeScreenshot("ui-pause-menu");
        ctx.clickScreenButton("etmc");
        ctx.waitForScreen(EtmcScreen.class);
        ctx.setScreen(() -> null);
    }

    /** Puts the mouse over a button, in window pixels, and gives its hint a frame to draw. */
    private static void hover(ClientGameTestContext ctx, String label) {
        double[] at = ctx.computeOnClient(c -> {
            ClickableWidget w = EtmcClientGameTest.button(c.currentScreen, label);
            double scale = c.getWindow().getScaleFactor();
            return new double[] {(w.getX() + w.getWidth() / 2.0) * scale, (w.getY() + w.getHeight() / 2.0) * scale};
        });
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(2);
    }

    private void view(ClientGameTestContext ctx, String at, String name, Supplier<Screen> screen) {
        ctx.setScreen(screen);
        ctx.waitTicks(2);
        shot(ctx, at, name);
        check(ctx, at, name);
    }

    private static void shot(ClientGameTestContext ctx, String at, String name) {
        ctx.takeScreenshot("ui-" + at + "-" + name);
    }

    private void check(ClientGameTestContext ctx, String at, String name) {
        for (String p : ctx.computeOnClient(EtmcUiGameTest::layoutProblems)) problems.add(at + " " + name + ": " + p);
    }

    private static List<String> layoutProblems(MinecraftClient c) {
        Screen s = c.currentScreen;
        List<String> out = new ArrayList<>();
        List<ClickableWidget> widgets = new ArrayList<>();
        for (var child : s.children()) {
            if (child instanceof ClickableWidget w && w.visible) widgets.add(w);
        }
        for (int i = 0; i < widgets.size(); i++) {
            ClickableWidget w = widgets.get(i);
            String name = "'" + w.getMessage().getString() + "'";
            if (w.getX() < 0 || w.getY() < 0 || w.getRight() > s.width || w.getBottom() > s.height) {
                out.add(name + " is off the " + s.width + "x" + s.height + " screen at " + w.getX() + "," + w.getY()
                        + " " + w.getWidth() + "x" + w.getHeight());
            }
            if (w instanceof ButtonWidget && c.textRenderer.getWidth(w.getMessage()) > w.getWidth() - 8) {
                out.add(name + " doesn't fit its " + w.getWidth() + "px button");
            }
            for (ClickableWidget o : widgets.subList(i + 1, widgets.size())) {
                if (w.getX() < o.getRight() && o.getX() < w.getRight()
                        && w.getY() < o.getBottom() && o.getY() < w.getBottom()) {
                    out.add(name + " overlaps '" + o.getMessage().getString() + "'");
                }
            }
        }
        return out;
    }

    /**
     * Stops the manager polling, so {@link #fakeStatus} lasts as long as a step takes. Software
     * rendering at 1080p can take longer than a poll, and the real status has nobody on the network.
     */
    private static void holdStatus(ClientGameTestContext ctx) {
        ctx.runOnClient(c -> setManagerField("lastStatusPoll", Long.MAX_VALUE));
        ctx.waitFor(c -> !((AtomicBoolean) managerField("statusPollInFlight")).get());
    }

    /** Puts {@code peers} made-up players in the status the screens read. */
    private static void fakeStatus(ClientGameTestContext ctx, int peers) {
        List<NetworkStatus.Peer> list = new ArrayList<>();
        for (int i = 0; i < peers; i++) {
            list.add(new NetworkStatus.Peer(i == 0 ? "Alex" : i == 1 ? "a_rather_long_player_name_1" : "Player" + i,
                    "10.126.126." + (i + 2), i == 0 ? 34 : 60 + i * 40, i % 2 == 1, 1));
        }
        NetworkStatus st = new NetworkStatus(true, "10.126.126.1", list, null, 1);
        ctx.runOnClient(c -> setManagerField("cachedStatus", st));
    }

    private static Object managerField(String name) {
        try {
            Field f = EtmcManager.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(EtmcManager.get());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setManagerField(String name, Object value) {
        try {
            Field f = EtmcManager.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(EtmcManager.get(), value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}

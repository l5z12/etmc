package dev.l5z12.etmc.client;

import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.NetworkStatus;
//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;*/
//?}

import java.util.ArrayList;
import java.util.List;

/**
 * A compact status overlay shown while an etmc session is active: a light for how the session is
 * doing, the role and player count, then each player's ping. It sits over the game, so it stays small
 * and leaves addresses and the like to the Status screen. Toggle with {@code /etmc hud} or in settings.
 * Loader/version-agnostic via the {@code Gfx}/{@code Txt} facades; only the client handle + font
 * lookup differ (yarn vs mojmap).
 */
public final class EtmcHud {

    private static final int BG = 0x90000000;
    private static final int GOOD = 0xFF55FF55;
    private static final int FAIR = 0xFFFFFF55;
    private static final int LABEL = 0xFFBDBDBD;
    private static final int VALUE = 0xFFFFFFFF;
    private static final int WARN = 0xFFFFAA00;
    private static final int BAD = 0xFFFF5555;
    /** Peers listed before collapsing the rest into a "+n more" line. */
    private static final int MAX_PEERS = 6;
    /** A Minecraft name, plus "-host"; anything longer is cut. */
    private static final int MAX_NAME_CHARS = 21;
    /** Overlay box: inset from the screen corner, and padding between its edge and the text. */
    private static final int MARGIN = 4;
    private static final int PADDING = 3;
    /** The health light on the title line, and the space between the columns. */
    private static final int LIGHT = 5;
    private static final int COLUMN_GAP = 10;
    /** Where ping turns yellow, then red: the game's own player list changes bars at the same places. */
    private static final int PING_FAIR = 150;
    private static final int PING_POOR = 300;
    /** Longest EasyTier error shown in the overlay; the Status screen shows it in full. */
    private static final int MAX_ERROR_CHARS = 60;

    /** False on versions whose loader offers no HUD hook (the overlay simply can't be drawn there). */
    private static volatile boolean available = true;

    /** Called by a loader entry point that has no way to register the overlay on this version. */
    public static void markUnavailable() {
        available = false;
    }

    /** Whether this build can draw the overlay at all (the Settings toggle says so when it can't). */
    public static boolean isAvailable() {
        return available;
    }

    /**
     * Last built line list, reused until something it shows actually changes. This runs on the render
     * thread every frame while the session is up, and the underlying status only refreshes about once
     * a second, so rebuilding the strings per frame would be pure allocation churn.
     */
    private static List<Line> cachedLines = List.of();
    private static NetworkStatus cachedFrom;
    private static EtmcSession.Mode cachedMode;
    private static String cachedFailure;
    private static int cachedLight;

    private EtmcHud() {}

    /**
     * Draws the overlay. Hidden with F1 like the rest of the HUD, and while F3 fills the corner it sits
     * in. {@code ctx} is whatever draw context this Minecraft build hands its HUD callback (nothing at
     * all before 1.16, hence {@code null} there); it is passed straight to {@link Gfx}, which is the
     * one place that knows its real type.
     */
    public static void render(Object ctx) {
        EtmcManager m = EtmcManager.get();
        if (!m.isReady()) return;
        ModConfig cfg = m.config();
        if (cfg == null || !cfg.hudEnabled) return;
        EtmcSession s = m.session();
        if (s == null || !s.isActive()) return;
        if (McScreens.debugScreenOpen()) return;

        // F1: Options.hideGui before 26.x; 26.x moved the flag onto the HUD itself (gui.hud.isHidden()).
        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;
        TextRenderer tr = client.textRenderer;
        //?} else if <26 {
        /*Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) return;
        Font tr = client.font;*/
        //?} else {
        /*Minecraft client = Minecraft.getInstance();
        if (client.gui.hud.isHidden()) return;
        Font tr = client.font;*/
        //?}
        List<Line> lines = lines(s, m.cachedStatus());

        //? if yarn {
        int lineH = tr.fontHeight + 1;
        //?} else {
        /*int lineH = tr.lineHeight + 1;*/
        //?}
        int leftW = LIGHT + 3;
        int rightW = 0;
        int wholeW = 0;
        for (Line l : lines) {
            if (l.right.isEmpty()) {
                wholeW = Math.max(wholeW, Wrap.width(tr, l.left));
            } else {
                leftW = Math.max(leftW, Wrap.width(tr, l.left));
                rightW = Math.max(rightW, Wrap.width(tr, l.right));
            }
        }
        int textW = Math.max(wholeW + LIGHT + 3, rightW == 0 ? leftW : leftW + COLUMN_GAP + rightW);
        int boxW = textW + PADDING * 2;
        int boxH = lines.size() * lineH + PADDING * 2 - 1;

        Gfx.fill(ctx, MARGIN, MARGIN, MARGIN + boxW, MARGIN + boxH, BG);
        int x = MARGIN + PADDING;
        int ty = MARGIN + PADDING;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (i == 0) {
                Gfx.fill(ctx, x, ty + 1, x + LIGHT, ty + 1 + LIGHT, cachedLight);
                Gfx.text(ctx, tr, Txt.literal(l.left), x + LIGHT + 3, ty, l.leftColor);
            } else {
                Gfx.text(ctx, tr, Txt.literal(l.left), x, ty, l.leftColor);
            }
            if (!l.right.isEmpty()) {
                Gfx.text(ctx, tr, Txt.literal(l.right), x + textW - Wrap.width(tr, l.right), ty, l.rightColor);
            }
            ty += lineH;
        }
    }

    /**
     * The overlay's lines for the current session state, rebuilt only when that state changes.
     * {@code status} is compared by identity: the manager swaps in a fresh snapshot per poll.
     */
    private static List<Line> lines(EtmcSession s, NetworkStatus st) {
        EtmcSession.Mode mode = s.mode();
        String failure = s.hostFailure();
        if (st == cachedFrom && mode == cachedMode && java.util.Objects.equals(failure, cachedFailure)) {
            return cachedLines;
        }

        List<Line> lines = new ArrayList<>();
        lines.add(new Line(mode == EtmcSession.Mode.HOST ? Msg.tr("etmc.hud.hosting", "etmc · Hosting · Players: %s", st.peerCount())
                : Msg.tr("etmc.hud.joined", "etmc · Joined · Players: %s", st.peerCount()), VALUE, "", 0));

        int light = GOOD;
        if (!st.running()) {
            lines.add(new Line(Msg.tr("etmc.hud.not_running", "network not running"), WARN, "", 0));
            light = WARN;
        } else if (st.peers().isEmpty() && st.relayNodes() == 0) {
            lines.add(new Line(Msg.tr("etmc.hud.no_relay", "relay not reached yet"), WARN, "", 0));
            light = WARN;
        }
        if (failure != null) {
            lines.add(new Line(Msg.tr("etmc.hud.host_failed", "Joiners can't connect — host again"), BAD, "", 0));
            light = BAD;
        }
        if (st.errorMsg() != null) {
            String e = st.errorMsg();
            lines.add(new Line(Msg.tr("etmc.status.easytier_error", "EasyTier: %s",
                    e.length() > MAX_ERROR_CHARS ? e.substring(0, MAX_ERROR_CHARS - 1) + "…" : e), BAD, "", 0));
            light = BAD;
        }

        List<NetworkStatus.Peer> peers = st.peers();
        for (int i = 0; i < peers.size(); i++) {
            if (i >= MAX_PEERS) {
                lines.add(new Line(Msg.tr("etmc.hud.more", "+%s more", peers.size() - MAX_PEERS), LABEL, "", 0));
                break;
            }
            NetworkStatus.Peer p = peers.get(i);
            String name = p.hostname().length() > MAX_NAME_CHARS
                    ? p.hostname().substring(0, MAX_NAME_CHARS - 1) + "…" : p.hostname();
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "?";
            String tag = p.relay() ? Msg.tr("etmc.hud.relay", " relay") : "";
            lines.add(new Line(name, LABEL, ping + tag, pingColor(p.latencyMs())));
        }

        cachedFrom = st;
        cachedMode = mode;
        cachedFailure = failure;
        cachedLight = light;
        cachedLines = List.copyOf(lines); // frozen: it is handed back on every frame until it changes
        return cachedLines;
    }

    private static int pingColor(int ms) {
        if (ms < 0) return LABEL;
        return ms < PING_FAIR ? GOOD : ms < PING_POOR ? FAIR : BAD;
    }

    private record Line(String left, int leftColor, String right, int rightColor) {}
}

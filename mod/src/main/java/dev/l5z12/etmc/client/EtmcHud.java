package dev.l5z12.etmc.client;

import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.NetworkStatus;
//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;*/
//?}

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** The longest Minecraft name; anything longer is cut. */
    private static final int MAX_NAME_CHARS = 16;
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
    /** The width vanilla's toasts take at the top right, which the overlay keeps clear of. */
    private static final int TOAST_W = 160;
    /** The narrowest the overlay's text gets, however little room the toasts leave. */
    private static final int MIN_TEXT_W = 100;
    /** Vanilla's boss bars: 182 wide and centred, 5 tall, the first at y 12 and each next one 19 lower. */
    private static final int BOSS_HALF_W = 91;
    private static final int BOSS_BAR_H = 5;
    private static final int BOSS_TOP = 12;
    private static final int BOSS_STEP = 19;

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
    /** {@link #cachedLines} laid out at {@link #cachedRowsW}. */
    private static List<Row> cachedRows = List.of();
    private static List<Line> cachedRowsFrom;
    private static int cachedRowsW;
    /** The boss overlay's map of bars, once found; render thread only. */
    private static Field bossBars;
    private static boolean bossBarsUnreadable;

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
        Object bosses = client.inGameHud.getBossBarHud();
        //?} else if <26 {
        /*Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) return;
        Font tr = client.font;
        Object bosses = client.gui.getBossOverlay();*/
        //?} else {
        /*Minecraft client = Minecraft.getInstance();
        if (client.gui.hud.isHidden()) return;
        Font tr = client.font;
        Object bosses = client.gui.hud.getBossOverlay();*/
        //?}
        // 1.14 has no getWindow(), only the field.
        //? if yarn && <1.15 {
        /*var window = client.window;*/
        //?} else {
        var window = client.getWindow();
        //?}
        //? if yarn {
        int screenW = window.getScaledWidth();
        int screenH = window.getScaledHeight();
        int lineH = tr.fontHeight + 1;
        //?} else {
        /*int screenW = window.getGuiScaledWidth();
        int screenH = window.getGuiScaledHeight();
        int lineH = tr.lineHeight + 1;*/
        //?}

        // Toasts slide in at the top right, and at the smallest GUI a wide overlay reached under them.
        int maxTextW = Math.max(MIN_TEXT_W, screenW - TOAST_W - 2 * MARGIN - 2 * PADDING);
        List<Row> rows = rows(tr, lines(s, m.cachedStatus()), maxTextW);
        int leftW = 0;
        int rightW = 0;
        int wholeW = 0;
        for (Row r : rows) {
            int w = Wrap.width(tr, r.left) + (r.light ? LIGHT + 3 : 0);
            if (r.right.isEmpty()) {
                wholeW = Math.max(wholeW, w);
            } else {
                leftW = Math.max(leftW, w);
                rightW = Math.max(rightW, Wrap.width(tr, r.right));
            }
        }
        int textW = Math.max(wholeW, rightW == 0 ? leftW : leftW + COLUMN_GAP + rightW);
        int boxW = textW + PADDING * 2;
        int boxH = rows.size() * lineH + PADDING * 2 - 1;
        // Boss bars run down the middle of the top: an overlay wide enough to reach them goes below.
        int top = MARGIN;
        if (MARGIN + boxW + MARGIN > screenW / 2 - BOSS_HALF_W) {
            top = Math.max(top, bossBarsBottom(bosses, screenH) + MARGIN);
        }

        Gfx.fill(ctx, MARGIN, top, MARGIN + boxW, top + boxH, BG);
        int x = MARGIN + PADDING;
        int ty = top + PADDING;
        for (Row r : rows) {
            int tx = x;
            if (r.light) {
                Gfx.fill(ctx, x, ty + 1, x + LIGHT, ty + 1 + LIGHT, cachedLight);
                tx += LIGHT + 3;
            }
            Gfx.text(ctx, tr, Txt.literal(r.left), tx, ty, r.leftColor);
            if (!r.right.isEmpty()) {
                Gfx.text(ctx, tr, Txt.literal(r.right), x + textW - Wrap.width(tr, r.right), ty, r.rightColor);
            }
            ty += lineH;
        }
    }

    /**
     * {@code lines} laid out to fit {@code maxW}: names cut short beside the widest ping, and notes
     * wrapped, since a cut warning would lose what it says. Cached like the lines themselves.
     */
    //? if yarn {
    private static List<Row> rows(TextRenderer tr, List<Line> lines, int maxW) {
    //?} else {
    /*private static List<Row> rows(Font tr, List<Line> lines, int maxW) {*/
    //?}
        if (lines == cachedRowsFrom && maxW == cachedRowsW) return cachedRows;
        int rightW = 0;
        for (Line l : lines) {
            if (!l.right.isEmpty()) rightW = Math.max(rightW, Wrap.width(tr, l.right));
        }
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (i == 0) {
                rows.add(new Row(Wrap.fit(tr, l.left, maxW - LIGHT - 3), l.leftColor, "", 0, true));
            } else if (l.right.isEmpty()) {
                for (String part : Wrap.lines(tr, l.left, maxW)) rows.add(new Row(part, l.leftColor, "", 0, false));
            } else {
                rows.add(new Row(Wrap.fit(tr, l.left, maxW - COLUMN_GAP - rightW), l.leftColor, l.right, l.rightColor, false));
            }
        }
        cachedRowsFrom = lines;
        cachedRowsW = maxW;
        cachedRows = List.copyOf(rows);
        return cachedRows;
    }

    /**
     * Where the boss bars end, or 0 with none showing. Vanilla draws the first at y 12 and each next
     * one 19 lower, and stops once the next would start below a third of the screen.
     */
    private static int bossBarsBottom(Object overlay, int screenH) {
        int bars = bossBarCount(overlay);
        if (bars == 0) return 0;
        int y = BOSS_TOP;
        for (int i = 0; i < bars; i++) {
            y += BOSS_STEP;
            if (y >= screenH / 3) break;
        }
        return y - BOSS_STEP + BOSS_BAR_H;
    }

    /**
     * How many boss bars are showing. Their map isn't public, and its name differs across versions
     * and mappings, so it's found by type. If it can't be read, the overlay just stays at the top.
     */
    private static int bossBarCount(Object overlay) {
        if (overlay == null || bossBarsUnreadable) return 0;
        try {
            if (bossBars == null) {
                for (Class<?> c = overlay.getClass(); c != null && bossBars == null; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || !Map.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        bossBars = f;
                        break;
                    }
                }
                if (bossBars == null) throw new NoSuchFieldException("no map of boss bars");
            }
            Map<?, ?> shown = (Map<?, ?>) bossBars.get(overlay);
            return shown == null ? 0 : shown.size();
        } catch (ReflectiveOperationException | RuntimeException e) {
            bossBarsUnreadable = true;
            EtmcManager.LOGGER.warn("[etmc] can't see the boss bars, so the overlay may cover them: {}", e.toString());
            return 0;
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

        // The host first, as on the Status screen. Who is in the world isn't shown here: it changes
        // without a new status, and the overlay is rebuilt only when the status changes.
        JoinCode code = s.currentCode();
        String hostIp = mode == EtmcSession.Mode.JOIN && code != null ? code.hostIp : null;
        List<NetworkStatus.Peer> peers = st.forDisplay(hostIp, Set.of());
        for (int i = 0; i < peers.size(); i++) {
            if (i >= MAX_PEERS) {
                lines.add(new Line(Msg.tr("etmc.hud.more", "+%s more", peers.size() - MAX_PEERS), LABEL, "", 0));
                break;
            }
            NetworkStatus.Peer p = peers.get(i);
            String name = p.name().length() > MAX_NAME_CHARS ? p.name().substring(0, MAX_NAME_CHARS - 1) + "…" : p.name();
            if (p.ipv4() != null && p.ipv4().equals(hostIp)) name = Msg.tr("etmc.status.peer.host", "%s (host)", name);
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

    /** One line as drawn: {@code light} puts the health light before it. */
    private record Row(String left, int leftColor, String right, int rightColor, boolean light) {}
}

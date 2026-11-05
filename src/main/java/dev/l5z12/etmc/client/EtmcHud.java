package dev.l5z12.etmc.client;

import dev.l5z12.etmc.core.EtmcSession;
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
 * A compact status overlay shown while an etmc session is active: role, virtual IP, peer count and
 * per-peer latency. Toggle with {@code /etmc hud} or in settings. Loader/version-agnostic via the
 * {@code Gfx}/{@code Txt} facades; only the client handle + font lookup differ (yarn vs mojmap).
 */
public final class EtmcHud {

    private static final int BG = 0xA0000000;
    private static final int TITLE = 0xFF55FF55;
    private static final int LABEL = 0xFFAAAAAA;
    private static final int VALUE = 0xFFFFFFFF;
    private static final int WARN = 0xFFFFAA00;
    private static final int BAD = 0xFFFF5555;
    /** Peers listed before collapsing the rest into a "+n more" line. */
    private static final int MAX_PEERS = 6;
    /** Overlay box: inset from the screen corner, and padding between its edge and the text. */
    private static final int MARGIN = 4;
    private static final int PADDING = 3;
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
    private static int cachedConnections = -1;
    private static String cachedFailure;

    private EtmcHud() {}

    /**
     * Draws the overlay. Hidden with F1 like the rest of the HUD, except on 26.x, whose options no
     * longer expose that flag to mods. {@code ctx} is whatever draw context this Minecraft build hands its HUD
     * callback (nothing at all before 1.16, hence {@code null} there); it is passed straight to
     * {@link Gfx}, which is the one place that knows its real type.
     */
    public static void render(Object ctx) {
        EtmcManager m = EtmcManager.get();
        if (!m.isReady()) return;
        ModConfig cfg = m.config();
        if (cfg == null || !cfg.hudEnabled) return;
        EtmcSession s = m.session();
        if (s == null || !s.isActive()) return;

        // 26.x dropped Options.hideGui; there the etmc HUD just always renders while a session is active.
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
        Font tr = client.font;*/
        //?}
        List<Line> lines = lines(s, m.cachedStatus());

        //? if yarn {
        int lineH = tr.fontHeight + 1;
        //?} else {
        /*int lineH = tr.lineHeight + 1;*/
        //?}
        int textW = 0;
        for (Line l : lines) {
            //? if yarn && >=1.16 {
            textW = Math.max(textW, tr.getWidth(l.text));
            //?} else if yarn {
            /*textW = Math.max(textW, tr.getStringWidth(l.text));*/
            //?} else {
            /*textW = Math.max(textW, tr.width(l.text));*/
            //?}
        }
        int boxW = textW + PADDING * 2;
        int boxH = lines.size() * lineH + PADDING * 2;

        Gfx.fill(ctx, MARGIN, MARGIN, MARGIN + boxW, MARGIN + boxH, BG);
        int ty = MARGIN + PADDING;
        for (Line l : lines) {
            Gfx.text(ctx, tr, Txt.literal(l.text), MARGIN + PADDING, ty, l.color);
            ty += lineH;
        }
    }

    /**
     * The overlay's lines for the current session state, rebuilt only when that state changes.
     * {@code status} is compared by identity: the manager swaps in a fresh snapshot per poll.
     */
    private static List<Line> lines(EtmcSession s, NetworkStatus st) {
        EtmcSession.Mode mode = s.mode();
        int connections = s.activeConnections();
        String failure = s.hostFailure();
        if (st == cachedFrom && mode == cachedMode && connections == cachedConnections
                && java.util.Objects.equals(failure, cachedFailure)) {
            return cachedLines;
        }

        List<Line> lines = new ArrayList<>();
        lines.add(new Line("etmc — " + (mode == EtmcSession.Mode.HOST ? "Hosting" : "Joined"), TITLE));

        if (st.virtualIp() != null) {
            lines.add(new Line("IP " + st.virtualIp(), VALUE));
        }
        // Open game connections (a server-list ping counts too), not a player count.
        lines.add(new Line("Connections: " + connections, VALUE));
        if (failure != null) {
            lines.add(new Line("Joiners can't connect — host again", BAD));
        }
        if (!st.running()) {
            lines.add(new Line("network not running", WARN));
        } else if (st.peers().isEmpty() && st.relayNodes() == 0) {
            lines.add(new Line("relay not reached yet", WARN));
        }
        if (st.errorMsg() != null) {
            String e = st.errorMsg();
            lines.add(new Line("EasyTier: " + (e.length() > MAX_ERROR_CHARS ? e.substring(0, MAX_ERROR_CHARS - 1) + "…" : e), BAD));
        }

        List<NetworkStatus.Peer> peers = st.peers();
        for (int i = 0; i < peers.size(); i++) {
            if (i >= MAX_PEERS) {
                lines.add(new Line("  +" + (peers.size() - MAX_PEERS) + " more", LABEL));
                break;
            }
            NetworkStatus.Peer p = peers.get(i);
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "?";
            String ip = p.ipv4() == null ? "" : " " + p.ipv4();
            String tag = p.relay() ? "  (relay)" : "";
            lines.add(new Line("  " + p.hostname() + ip + "  " + ping + tag, p.relay() ? WARN : LABEL));
        }

        cachedFrom = st;
        cachedMode = mode;
        cachedConnections = connections;
        cachedFailure = failure;
        cachedLines = List.copyOf(lines); // frozen: it is handed back on every frame until it changes
        return cachedLines;
    }

    private record Line(String text, int color) {}
}

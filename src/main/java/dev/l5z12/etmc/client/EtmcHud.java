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
//? if !yarn && <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//?} else if !yarn && <26 {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?} else if !yarn {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//?}
//? if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn && >=1.16 {
/*import net.minecraft.client.util.math.MatrixStack;
*///?}

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
    /** Peers listed before collapsing the rest into a "+n more" line. */
    private static final int MAX_PEERS = 6;
    /** Overlay box: inset from the screen corner, and padding between its edge and the text. */
    private static final int MARGIN = 4;
    private static final int PADDING = 3;

    /**
     * Last built line list, reused until something it shows actually changes. This runs on the render
     * thread every frame while the session is up, and the underlying status only refreshes about once
     * a second, so rebuilding the strings per frame would be pure allocation churn.
     */
    private static List<Line> cachedLines = List.of();
    private static NetworkStatus cachedFrom;
    private static EtmcSession.Mode cachedMode;
    private static int cachedConnections = -1;

    private EtmcHud() {}

    //? if yarn && >=1.20 {
    public static void render(DrawContext ctx)
    //?} else if yarn && >=1.16 {
    /*public static void render(MatrixStack ctx)
    *///?} else if yarn {
    /*public static void render(int ctx)*/
    //?} else if <1.20 {
    /*public static void render(PoseStack ctx)*/
    //?} else if <26 {
    /*public static void render(GuiGraphics ctx)*/
    //?} else {
    /*public static void render(GuiGraphicsExtractor ctx)*/
    //?}
    {
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
        if (st == cachedFrom && mode == cachedMode && connections == cachedConnections) {
            return cachedLines;
        }

        List<Line> lines = new ArrayList<>();
        lines.add(new Line("etmc — " + (mode == EtmcSession.Mode.HOST ? "Hosting" : "Joined"), TITLE));

        if (st.virtualIp() != null) {
            lines.add(new Line("IP " + st.virtualIp(), VALUE));
        }
        if (mode == EtmcSession.Mode.HOST) {
            lines.add(new Line("Players: " + connections, VALUE));
        } else if (connections > 0) {
            lines.add(new Line("Tunnel: up", VALUE));
        }
        if (!st.running()) {
            lines.add(new Line("connecting…", WARN));
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
        cachedLines = lines;
        return lines;
    }

    private record Line(String text, int color) {}
}

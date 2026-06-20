package dev.l5z12.etmc.client;

import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.NetworkStatus;
//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;*/
//?}
//? if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn {
/*import net.minecraft.client.util.math.MatrixStack;*/
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

    private EtmcHud() {}

    //? if yarn && >=1.20 {
    public static void render(DrawContext ctx)
    //?} else if yarn {
    /*public static void render(MatrixStack ctx)*/
    //?} else {
    /*public static void render(GuiGraphics ctx)*/
    //?}
    {
        EtmcManager m = EtmcManager.get();
        if (!m.isReady()) return;
        ModConfig cfg = m.config();
        if (cfg == null || !cfg.hudEnabled) return;
        EtmcSession s = m.session();
        if (s == null || !s.isActive()) return;

        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden) return;
        TextRenderer tr = client.textRenderer;
        //?} else {
        /*Minecraft client = Minecraft.getInstance();
        if (client.options.hideGui) return;
        Font tr = client.font;*/
        //?}
        NetworkStatus st = m.cachedStatus();

        List<Line> lines = new ArrayList<>();
        String role = s.mode() == EtmcSession.Mode.HOST ? "Hosting" : "Joined";
        lines.add(new Line("etmc — " + role, TITLE));

        if (st.virtualIp() != null) {
            lines.add(new Line("IP " + st.virtualIp(), VALUE));
        }
        if (s.mode() == EtmcSession.Mode.HOST) {
            lines.add(new Line("Players: " + s.activeConnections(), VALUE));
        } else if (s.activeConnections() > 0) {
            lines.add(new Line("Tunnel: up", VALUE));
        }

        if (!st.running()) {
            lines.add(new Line("connecting…", WARN));
        }

        List<NetworkStatus.Peer> peers = st.peers();
        int shown = 0;
        for (NetworkStatus.Peer p : peers) {
            if (shown++ >= 6) {
                lines.add(new Line("  +" + (peers.size() - 6) + " more", LABEL));
                break;
            }
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "?";
            String ip = p.ipv4() == null ? "" : " " + p.ipv4();
            String tag = p.relay() ? "  (relay)" : "";
            lines.add(new Line("  " + p.hostname() + ip + "  " + ping + tag, p.relay() ? WARN : LABEL));
        }

        int pad = 3;
        //? if yarn {
        int lineH = tr.fontHeight + 1;
        //?} else {
        /*int lineH = tr.lineHeight + 1;*/
        //?}
        int w = 0;
        for (Line l : lines) {
            //? if yarn {
            w = Math.max(w, tr.getWidth(l.text));
            //?} else {
            /*w = Math.max(w, tr.width(l.text));*/
            //?}
        }
        int boxW = w + pad * 2;
        int boxH = lines.size() * lineH + pad * 2;
        int x = 4;
        int y = 4;

        Gfx.fill(ctx, x, y, x + boxW, y + boxH, BG);
        int ty = y + pad;
        for (Line l : lines) {
            Gfx.text(ctx, tr, Txt.literal(l.text), x + pad, ty, l.color);
            ty += lineH;
        }
    }

    private record Line(String text, int color) {}
}

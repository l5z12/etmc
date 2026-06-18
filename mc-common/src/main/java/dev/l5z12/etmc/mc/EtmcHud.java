package dev.l5z12.etmc.mc;

import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.NetworkStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Shared status overlay for the Mojmap loaders. Loaders wire this to their HUD layer/overlay event. */
public final class EtmcHud {

    private static final int BG = 0xA0000000;
    private static final int TITLE = 0xFF55FF55;
    private static final int LABEL = 0xFFAAAAAA;
    private static final int VALUE = 0xFFFFFFFF;
    private static final int WARN = 0xFFFFAA00;

    private EtmcHud() {}

    public static void render(GuiGraphics g) {
        EtmcClientCore core = EtmcClientCore.get();
        if (!core.isReady()) return;
        McConfig cfg = core.config();
        if (cfg == null || !cfg.hudEnabled) return;
        EtmcSession s = core.session();
        if (s == null || !s.isActive()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        Font font = mc.font;
        NetworkStatus st = core.cachedStatus();

        List<Object[]> lines = new ArrayList<>();
        String role = s.mode() == EtmcSession.Mode.HOST ? "Hosting" : "Joined";
        lines.add(new Object[]{"etmc — " + role, TITLE});
        if (st.virtualIp() != null) lines.add(new Object[]{"IP " + st.virtualIp(), VALUE});
        if (s.mode() == EtmcSession.Mode.HOST) {
            lines.add(new Object[]{"Players: " + s.activeConnections(), VALUE});
        } else if (s.activeConnections() > 0) {
            lines.add(new Object[]{"Tunnel: up", VALUE});
        }
        if (!st.running()) lines.add(new Object[]{"connecting…", WARN});

        List<NetworkStatus.Peer> peers = st.peers();
        int shown = 0;
        for (NetworkStatus.Peer p : peers) {
            if (shown++ >= 6) {
                lines.add(new Object[]{"  +" + (peers.size() - 6) + " more", LABEL});
                break;
            }
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "?";
            String ip = p.ipv4() == null ? "" : " " + p.ipv4();
            String tag = p.relay() ? "  (relay)" : "";
            lines.add(new Object[]{"  " + p.hostname() + ip + "  " + ping + tag, p.relay() ? WARN : LABEL});
        }

        int pad = 3;
        int lineH = font.lineHeight + 1;
        int w = 0;
        for (Object[] l : lines) w = Math.max(w, font.width((String) l[0]));
        int boxW = w + pad * 2;
        int boxH = lines.size() * lineH + pad * 2;
        int x = 4, y = 4;

        g.fill(x, y, x + boxW, y + boxH, BG);
        int ty = y + pad;
        for (Object[] l : lines) {
            g.drawString(font, Component.literal((String) l[0]), x + pad, ty, (int) l[1]);
            ty += lineH;
        }
    }
}

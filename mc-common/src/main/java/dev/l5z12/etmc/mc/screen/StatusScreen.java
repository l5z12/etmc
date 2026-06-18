package dev.l5z12.etmc.mc.screen;

import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.mc.EtmcClientCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Live session status (Mojmap). */
public final class StatusScreen extends Screen {

    private final Screen parent;
    private volatile String toast = "";

    public StatusScreen(Screen parent) {
        super(Component.literal("etmc status"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        EtmcClientCore core = EtmcClientCore.get();
        int cx = this.width / 2;
        int w = 220;
        int y = this.height - 92;

        boolean host = core.session() != null && core.session().mode() == EtmcSession.Mode.HOST;
        if (host) {
            addRenderableWidget(Button.builder(Component.literal("Copy join code"), b -> copyCode(false))
                    .bounds(cx - w / 2, y, w / 2 - 4, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Copy etmc:// link"), b -> copyCode(true))
                    .bounds(cx + 4, y, w / 2 - 4, 20).build());
            y += 24;
        }
        addRenderableWidget(Button.builder(Component.literal("Leave network"), b -> leave())
                .bounds(cx - w / 2, y, w, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(cx - w / 2, y, w, 20).build());
    }

    private void copyCode(boolean link) {
        JoinCode code = EtmcClientCore.get().session().currentCode();
        if (code != null) {
            this.minecraft.keyboardHandler.setClipboard(link ? code.encodeLink() : code.encode());
            toast = link ? "Copied etmc:// link" : "Copied join code";
        }
    }

    private void leave() {
        EtmcClientCore.get().leaveAsync().whenComplete((v, err) ->
                this.minecraft.execute(() -> this.minecraft.setScreen(parent)));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        EtmcClientCore core = EtmcClientCore.get();
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);

        EtmcSession s = core.session();
        if (s == null || !s.isActive()) {
            g.drawCenteredString(this.font, Component.literal("No active session"), this.width / 2, 48, 0xAAAAAA);
            return;
        }
        NetworkStatus st = core.cachedStatus();
        int x = this.width / 2 - 110;
        int y = 44;
        String role = s.mode() == EtmcSession.Mode.HOST ? "Hosting" : "Joined";
        g.drawString(this.font, Component.literal("Role: " + role), x, y, 0x55FF55);
        y += 12;
        g.drawString(this.font, Component.literal("Virtual IP: " + (st.virtualIp() == null ? "(assigning…)" : st.virtualIp())), x, y, 0xFFFFFF);
        y += 12;
        g.drawString(this.font, Component.literal("Active connections: " + s.activeConnections()), x, y, 0xFFFFFF);
        y += 12;
        if (s.mode() == EtmcSession.Mode.JOIN) {
            g.drawString(this.font, Component.literal("Local proxy: 127.0.0.1:" + s.localPort()), x, y, 0xAAAAAA);
            y += 12;
        }
        g.drawString(this.font, Component.literal("Peers (" + st.peerCount()
                + ", P2P " + st.directPeerCount() + "):"), x, y, 0xAAAAAA);
        y += 12;
        for (NetworkStatus.Peer p : st.peers()) {
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + " ms" : "—";
            g.drawString(this.font, Component.literal("  " + p.hostname() + "  "
                    + (p.ipv4() == null ? "" : p.ipv4()) + "  " + ping + (p.relay() ? "  (relay)" : "")),
                    x, y, p.relay() ? 0xFFAA00 : 0xDDDDDD);
            y += 11;
        }
        if (!toast.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal(toast), this.width / 2, this.height - 16, 0x55FF55);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}

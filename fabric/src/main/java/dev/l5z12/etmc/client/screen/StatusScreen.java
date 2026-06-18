package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Live session status: virtual IP, peers/latency, the shareable code, and a Leave button. */
public final class StatusScreen extends Screen {

    private final Screen parent;
    private volatile String toast = "";

    public StatusScreen(Screen parent) {
        super(Text.literal("etmc status"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        EtmcManager m = EtmcManager.get();
        int cx = this.width / 2;
        int w = 220;
        int y = this.height - 92;

        boolean host = m.session() != null && m.session().mode() == EtmcSession.Mode.HOST;
        if (host) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Copy join code"), b -> copyCode(false))
                    .dimensions(cx - w / 2, y, w / 2 - 4, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("Copy etmc:// link"), b -> copyCode(true))
                    .dimensions(cx + 4, y, w / 2 - 4, 20).build());
            y += 24;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Leave network"), b -> leave())
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void copyCode(boolean link) {
        JoinCode code = EtmcManager.get().session().currentCode();
        if (code != null) {
            this.client.keyboard.setClipboard(link ? code.encodeLink() : code.encode());
            toast = link ? "Copied etmc:// link" : "Copied join code";
        }
    }

    private void leave() {
        EtmcManager.get().leaveAsync().whenComplete((v, err) ->
                this.client.execute(() -> this.client.setScreen(parent)));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        EtmcManager m = EtmcManager.get();
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFF);

        EtmcSession s = m.session();
        if (s == null || !s.isActive()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal("No active session"),
                    this.width / 2, 48, 0xFFAAAAAA);
            return;
        }
        NetworkStatus st = m.cachedStatus();
        int x = this.width / 2 - 110;
        int y = 44;
        String role = s.mode() == EtmcSession.Mode.HOST ? "Hosting" : "Joined";
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Role: " + role), x, y, 0xFF55FF55);
        y += 12;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Virtual IP: "
                + (st.virtualIp() == null ? "(assigning…)" : st.virtualIp())), x, y, 0xFFFFFFFF);
        y += 12;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Active connections: " + s.activeConnections()), x, y, 0xFFFFFFFF);
        y += 12;
        if (s.mode() == EtmcSession.Mode.JOIN) {
            ctx.drawTextWithShadow(this.textRenderer, Text.literal("Local proxy: 127.0.0.1:" + s.localPort()), x, y, 0xFFAAAAAA);
            y += 12;
        }
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Peers (" + st.peerCount()
                + ", P2P " + st.directPeerCount() + "):"), x, y, 0xFFAAAAAA);
        y += 12;
        for (NetworkStatus.Peer p : st.peers()) {
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + " ms" : "—";
            String line = "  " + p.hostname() + "  " + (p.ipv4() == null ? "" : p.ipv4()) + "  " + ping
                    + (p.relay() ? "  (relay)" : "");
            ctx.drawTextWithShadow(this.textRenderer, Text.literal(line), x, y, p.relay() ? 0xFFFFAA00 : 0xFFDDDDDD);
            y += 11;
        }
        if (!toast.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(toast), this.width / 2, this.height - 16, 0xFF55FF55);
        }
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}

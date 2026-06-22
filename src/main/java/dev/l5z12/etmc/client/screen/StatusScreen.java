package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}
//? if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn {
/*import net.minecraft.client.util.math.MatrixStack;*/
//?} else if <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//?} else if <26 {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//?}

/** Live session status: virtual IP, peers/latency, the shareable code, and a Leave button. */
public final class StatusScreen extends EtmcBaseScreen {

    private final Screen parent;
    private volatile String toast = "";

    public StatusScreen(Screen parent) {
        super(Txt.literal("etmc status"));
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
            add(Ui.button(Txt.literal("Copy join code"), b -> copyCode(false))
                    .dimensions(cx - w / 2, y, w / 2 - 4, 20).build());
            add(Ui.button(Txt.literal("Copy etmc:// link"), b -> copyCode(true))
                    .dimensions(cx + 4, y, w / 2 - 4, 20).build());
            y += 24;
        }

        add(Ui.button(Txt.literal("Leave network"), b -> leave())
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        add(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void copyCode(boolean link) {
        JoinCode code = EtmcManager.get().session().currentCode();
        if (code != null) {
            //? if yarn {
            mc().keyboard.setClipboard(link ? code.encodeLink() : code.encode());
            //?} else {
            /*mc().keyboardHandler.setClipboard(link ? code.encodeLink() : code.encode());*/
            //?}
            toast = link ? "Copied etmc:// link" : "Copied join code";
        }
    }

    private void leave() {
        EtmcManager.get().leaveAsync().whenComplete((v, err) ->
                mc().execute(() -> goTo(parent)));
    }

    @Override
    //? if yarn && >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else if yarn {
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)*/
    //?} else if <1.20 {
    /*public void render(PoseStack ctx, int mouseX, int mouseY, float delta)*/
    //?} else if <26 {
    /*public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta)*/
    //?} else {
    /*public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta)*/
    //?}
    {
        //? if >=26 {
        /*super.extractRenderState(ctx, mouseX, mouseY, delta);*/
        //?} else {
        super.render(ctx, mouseX, mouseY, delta);
        //?}
        EtmcManager m = EtmcManager.get();
        Gfx.centered(ctx, font(), this.title, this.width / 2, 18, 0xFFFFFF);

        EtmcSession s = m.session();
        if (s == null || !s.isActive()) {
            Gfx.centered(ctx, font(), Txt.literal("No active session"),
                    this.width / 2, 48, 0xFFAAAAAA);
            return;
        }
        NetworkStatus st = m.cachedStatus();
        int x = this.width / 2 - 110;
        int y = 44;
        String role = s.mode() == EtmcSession.Mode.HOST ? "Hosting" : "Joined";
        Gfx.text(ctx, font(), Txt.literal("Role: " + role), x, y, 0xFF55FF55);
        y += 12;
        Gfx.text(ctx, font(), Txt.literal("Virtual IP: "
                + (st.virtualIp() == null ? "(assigning…)" : st.virtualIp())), x, y, 0xFFFFFFFF);
        y += 12;
        Gfx.text(ctx, font(), Txt.literal("Active connections: " + s.activeConnections()), x, y, 0xFFFFFFFF);
        y += 12;
        if (s.mode() == EtmcSession.Mode.JOIN) {
            Gfx.text(ctx, font(), Txt.literal("Local proxy: 127.0.0.1:" + s.localPort()), x, y, 0xFFAAAAAA);
            y += 12;
        }
        Gfx.text(ctx, font(), Txt.literal("Peers (" + st.peerCount()
                + ", P2P " + st.directPeerCount() + "):"), x, y, 0xFFAAAAAA);
        y += 12;
        for (NetworkStatus.Peer p : st.peers()) {
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + " ms" : "—";
            String line = "  " + p.hostname() + "  " + (p.ipv4() == null ? "" : p.ipv4()) + "  " + ping
                    + (p.relay() ? "  (relay)" : "");
            Gfx.text(ctx, font(), Txt.literal(line), x, y, p.relay() ? 0xFFFFAA00 : 0xFFDDDDDD);
            y += 11;
        }
        if (!toast.isEmpty()) {
            Gfx.centered(ctx, font(), Txt.literal(toast), this.width / 2, this.height - 16, 0xFF55FF55);
        }
    }

    //? if yarn && >=1.18.2 {
    @Override
    //?}
    public void close() {
        goTo(parent);
    }

    //? if !yarn || <1.18.2 {
    /*@Override
    public void onClose() {
        this.close();
    }*/
    //?}
}

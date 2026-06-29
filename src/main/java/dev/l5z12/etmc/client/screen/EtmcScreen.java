package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcSession;
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

/** Main etmc menu: host, join, status/leave, settings. */
public final class EtmcScreen extends EtmcBaseScreen {

    private final Screen parent;

    public EtmcScreen(Screen parent) {
        super(Txt.literal("etmc"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        EtmcManager m = EtmcManager.get();
        boolean ready = m.isReady();
        boolean active = ready && m.session() != null && m.session().isActive();

        int cx = this.width / 2;
        int y = this.height / 4 + 8;
        int w = 200;

        var host = Ui.button(Txt.literal("Host this world"),
                b -> goTo(new HostScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        host.active = ready && !active;
        add(host);
        y += 24;

        var join = Ui.button(Txt.literal("Join with a code"),
                b -> goTo(new JoinScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        join.active = ready && !active;
        add(join);
        y += 24;

        var connectUrl = Ui.button(Txt.literal("Connect via config URL"),
                b -> goTo(new ConnectUrlScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        connectUrl.active = ready && !active;
        add(connectUrl);
        y += 24;

        if (active) {
            add(Ui.button(Txt.literal("Session status"),
                            b -> goTo(new StatusScreen(this)))
                    .dimensions(cx - w / 2, y, w, 20).build());
            y += 24;
        }

        add(Ui.button(Txt.literal("Settings & relays"),
                        b -> goTo(new SettingsScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        add(Ui.button(Txt.literal("Done"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
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
        // Pre-1.20.2 the base Screen.render() doesn't draw the menu background; draw it ourselves.
        //? if <1.20.2 {
        /*this.renderBackground(ctx);*/
        //?}
        //? if >=26 {
        /*super.extractRenderState(ctx, mouseX, mouseY, delta);*/
        //?} else {
        super.render(ctx, mouseX, mouseY, delta);
        //?}
        Gfx.centered(ctx, font(), this.title, this.width / 2, 28, 0xFFFFFF);

        EtmcManager m = EtmcManager.get();
        String sub;
        int color = 0xFFAAAAAA;
        if (!m.isReady()) {
            sub = "Native library not loaded — see logs";
            color = 0xFFFF5555;
        } else if (m.session() != null && m.session().isActive()) {
            sub = m.session().mode() == EtmcSession.Mode.HOST ? "Currently hosting" : "Currently joined";
            color = 0xFF55FF55;
        } else if (!m.config().hasRelay()) {
            sub = "No relay set — add one in Settings";
            color = 0xFFFFAA00;
        } else {
            sub = "EasyTier P2P multiplayer, in-game";
        }
        Gfx.centered(ctx, font(), Txt.literal(sub), this.width / 2, 44, color);
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

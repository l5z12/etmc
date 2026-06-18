package dev.l5z12.etmc.mc.screen;

import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.mc.EtmcClientCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Main etmc menu (Mojmap, shared by NeoForge/Forge). */
public final class EtmcScreen extends Screen {

    private final Screen parent;

    public EtmcScreen(Screen parent) {
        super(Component.literal("etmc"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        EtmcClientCore core = EtmcClientCore.get();
        boolean ready = core.isReady();
        boolean active = ready && core.session() != null && core.session().isActive();

        int cx = this.width / 2;
        int y = this.height / 4 + 8;
        int w = 200;

        Button host = Button.builder(Component.literal("Host this world"),
                        b -> this.minecraft.setScreen(new HostScreen(this)))
                .bounds(cx - w / 2, y, w, 20).build();
        host.active = ready && !active;
        addRenderableWidget(host);
        y += 24;

        Button join = Button.builder(Component.literal("Join with a code"),
                        b -> this.minecraft.setScreen(new JoinScreen(this)))
                .bounds(cx - w / 2, y, w, 20).build();
        join.active = ready && !active;
        addRenderableWidget(join);
        y += 24;

        Button connectUrl = Button.builder(Component.literal("Connect via config URL"),
                        b -> this.minecraft.setScreen(new ConnectUrlScreen(this)))
                .bounds(cx - w / 2, y, w, 20).build();
        connectUrl.active = ready && !active;
        addRenderableWidget(connectUrl);
        y += 24;

        if (active) {
            addRenderableWidget(Button.builder(Component.literal("Session status"),
                            b -> this.minecraft.setScreen(new StatusScreen(this)))
                    .bounds(cx - w / 2, y, w, 20).build());
            y += 24;
        }

        addRenderableWidget(Button.builder(Component.literal("Settings & relays"),
                        b -> this.minecraft.setScreen(new SettingsScreen(this)))
                .bounds(cx - w / 2, y, w, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
                .bounds(cx - w / 2, y, w, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 28, 0xFFFFFF);

        EtmcClientCore core = EtmcClientCore.get();
        String sub;
        int color;
        if (!core.isReady()) {
            sub = "Native library not loaded — see logs";
            color = 0xFF5555;
        } else if (core.session() != null && core.session().isActive()) {
            sub = core.session().mode() == EtmcSession.Mode.HOST ? "Currently hosting" : "Currently joined";
            color = 0x55FF55;
        } else if (!core.config().hasRelay()) {
            sub = "No relay set — add one in Settings";
            color = 0xFFAA00;
        } else {
            sub = "EasyTier P2P multiplayer, in-game";
            color = 0xAAAAAA;
        }
        g.drawCenteredString(this.font, Component.literal(sub), this.width / 2, 44, color);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}

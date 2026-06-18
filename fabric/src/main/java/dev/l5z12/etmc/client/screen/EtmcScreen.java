package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.EtmcSession;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Main etmc menu: host, join, status/leave, settings. */
public final class EtmcScreen extends Screen {

    private final Screen parent;

    public EtmcScreen(Screen parent) {
        super(Text.literal("etmc"));
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

        ButtonWidget host = ButtonWidget.builder(Text.literal("Host this world"),
                b -> this.client.setScreen(new HostScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        host.active = ready && !active;
        addDrawableChild(host);
        y += 24;

        ButtonWidget join = ButtonWidget.builder(Text.literal("Join with a code"),
                b -> this.client.setScreen(new JoinScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        join.active = ready && !active;
        addDrawableChild(join);
        y += 24;

        ButtonWidget connectUrl = ButtonWidget.builder(Text.literal("Connect via config URL"),
                b -> this.client.setScreen(new ConnectUrlScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        connectUrl.active = ready && !active;
        addDrawableChild(connectUrl);
        y += 24;

        if (active) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Session status"),
                            b -> this.client.setScreen(new StatusScreen(this)))
                    .dimensions(cx - w / 2, y, w, 20).build());
            y += 24;
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Settings & relays"),
                        b -> this.client.setScreen(new SettingsScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 28, 0xFFFFFF);

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
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(sub), this.width / 2, 44, color);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}

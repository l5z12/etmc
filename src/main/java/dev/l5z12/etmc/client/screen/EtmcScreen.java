package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.Msg;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

/** Main etmc menu: host, join, status/leave, settings. */
public final class EtmcScreen extends EtmcBaseScreen {

    public EtmcScreen(Screen parent) {
        super(Txt.literal("etmc"), parent);
    }

    @Override
    protected void init() {
        EtmcManager m = EtmcManager.get();
        boolean ready = m.isReady();
        boolean active = ready && m.session() != null && m.session().isActive();

        int cx = this.width / 2;
        int y = this.height / 4 + 8;
        int w = 200;

        var host = Ui.button(Txt.tr("etmc.menu.host", "Host this world"),
                b -> goTo(new HostScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        // Hosting shares the singleplayer world you're in; on a server (or the title screen) there's none.
        host.active = ready && !active && McScreens.singleplayer();
        add(host);
        y += 24;

        var join = Ui.button(Txt.tr("etmc.menu.join", "Join with a link or code"),
                b -> goTo(new JoinScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        join.active = ready && !active;
        add(join);
        y += 24;

        var connectUrl = Ui.button(Txt.tr("etmc.menu.connect_url", "Connect via config URL"),
                b -> goTo(new ConnectUrlScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build();
        connectUrl.active = ready && !active;
        add(connectUrl);
        y += 24;

        add(Ui.button(Txt.tr("etmc.menu.generate", "Generate join link"),
                        b -> goTo(new LinkGeneratorScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        if (active) {
            add(Ui.button(Txt.tr("etmc.menu.status", "Session status"),
                            b -> goTo(new StatusScreen(this)))
                    .dimensions(cx - w / 2, y, w, 20).build());
            y += 24;
        }

        add(Ui.button(Txt.tr("etmc.menu.settings", "Settings & relays"),
                        b -> goTo(new SettingsScreen(this)))
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        add(Ui.button(Txt.translatable("gui.done"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        Gfx.centered(ctx, font(), this.title, this.width / 2, 28, COLOR_TEXT);

        EtmcManager m = EtmcManager.get();
        String sub;
        int color = COLOR_MUTED;
        if (!m.isReady()) {
            sub = Msg.tr("etmc.menu.sub.no_native", "Native library not loaded — see logs");
            color = COLOR_BAD;
        } else if (m.session() != null && m.session().isActive()) {
            sub = m.session().mode() == EtmcSession.Mode.HOST
                    ? Msg.tr("etmc.menu.sub.hosting", "Currently hosting")
                    : Msg.tr("etmc.menu.sub.joined", "Currently joined");
            color = COLOR_GOOD;
        } else if (!McScreens.singleplayer() && !McScreens.inWorld()) {
            sub = Msg.tr("etmc.menu.sub.title", "Open a singleplayer world to host it, or join someone's");
        } else if (!McScreens.singleplayer()) {
            sub = Msg.tr("etmc.menu.sub.server", "You're on a server: only singleplayer worlds can be hosted");
        } else if (!m.config().hasRelay()) {
            sub = Msg.tr("etmc.menu.sub.no_relay", "To host, add a relay in Settings first");
            color = COLOR_WARN;
        } else {
            sub = Msg.tr("etmc.menu.sub.idle", "EasyTier P2P multiplayer, in-game");
        }
        Gfx.centered(ctx, font(), Txt.literal(sub), this.width / 2, 44, color);
    }
}

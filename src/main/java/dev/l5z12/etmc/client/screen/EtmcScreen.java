package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.Msg;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

/**
 * Main etmc menu. It offers what the player can do right now: hosting or joining when idle, and the
 * session and its invite link once they're in one.
 */
public final class EtmcScreen extends EtmcBaseScreen {

    private static final int W = 240;
    private static final int SUBTITLE_W = 320;

    /** The state {@link #build} last saw: the menu rebuilds when it changes. */
    private String shown;

    public EtmcScreen(Screen parent) {
        super(Txt.literal("etmc"), parent);
    }

    /** What the menu's contents depend on. */
    private static String state() {
        EtmcManager m = EtmcManager.get();
        EtmcSession s = m.session();
        return m.isReady() + "/" + (s != null && s.isActive() ? s.mode() : null) + "/" + McScreens.singleplayer()
                + "/" + McScreens.inWorld();
    }

    @Override
    protected void build() {
        EtmcManager m = EtmcManager.get();
        boolean ready = m.isReady();
        EtmcSession s = m.session();
        boolean active = ready && s != null && s.isActive();
        boolean hosting = active && s.mode() == EtmcSession.Mode.HOST;

        int w = col(W);
        int x = left(w);
        shown = state();
        live(() -> {
            if (!state().equals(shown)) rebuild();
        });

        String sub = subtitle(m);
        int subW = col(SUBTITLE_W);
        int subH = Wrap.lines(font(), sub, subW).size() * LINE_H;
        int y = formTop(subH + 8 + (active && !hosting ? 1 : 2) * (ROW + GAP) + 6 + ROW, 2 * LINE_H);
        y = paragraph(sub, y, subW, subtitleColor(m)) + 8;

        if (active) {
            if (hosting) {
                add(cell(Ui.button(Txt.tr("etmc.status.copy_link", "Copy invite link"), b -> copyInvite()), x, y, w, 0, 1)
                        .build());
                y += ROW + GAP;
            }
            add(cell(Ui.button(Txt.tr("etmc.menu.status", "Session status"), b -> goTo(new StatusScreen(this))), x, y, w, 0, 1)
                    .build());
            y += ROW + GAP;
        } else {
            Ui.Builder host = cell(Ui.button(Txt.tr("etmc.menu.host", "Host this world"),
                    b -> goTo(new HostScreen(this))), x, y, w, 0, 1);
            // Hosting shares the singleplayer world you're in; on a server (or the title screen) there's none.
            String blocker = m.worldBlocker();
            if (blocker != null) hint(host, blocker);
            var hostButton = host.build();
            hostButton.active = blocker == null;
            add(hostButton);
            y += ROW + GAP;

            var join = cell(Ui.button(Txt.tr("etmc.menu.join", "Join with a link"), b -> goTo(new JoinScreen(this))),
                    x, y, w, 0, 1).build();
            join.active = ready;
            add(join);
            y += ROW + GAP;
        }
        y += 6;

        add(cell(Ui.button(Txt.tr("etmc.menu.generate", "Generate join link"),
                b -> goTo(new LinkGeneratorScreen(this))), x, y, w, 0, 2).build());
        add(cell(Ui.button(Txt.tr("etmc.menu.settings", "Settings & relays"),
                b -> goTo(new SettingsScreen(this))), x, y, w, 1, 2).build());

        add(footer(Ui.button(Txt.translatable("gui.done"), b -> this.close()), 0, 1).build());
    }

    private void copyInvite() {
        if (StatusScreen.copyInvite()) {
            setMessage(Msg.tr("etmc.status.link_copied",
                    "Invite link copied. It contains the secret: send it only to people you want in."), COLOR_GOOD);
        }
    }

    private static String subtitle(EtmcManager m) {
        EtmcSession s = m.session();
        if (!m.isReady()) return Msg.tr("etmc.error.no_native_log", "etmc couldn't load its EasyTier library (see the game log).");
        if (s != null && s.isActive()) {
            return s.mode() == EtmcSession.Mode.HOST ? Msg.tr("etmc.menu.sub.hosting", "Currently hosting")
                    : Msg.tr("etmc.menu.sub.joined", "Currently joined");
        }
        if (!McScreens.singleplayer() && !McScreens.inWorld()) {
            return Msg.tr("etmc.menu.sub.title", "Open a singleplayer world to host it, or join someone's");
        }
        if (!McScreens.singleplayer()) {
            return Msg.tr("etmc.menu.sub.server", "You're on a server: only singleplayer worlds can be hosted");
        }
        return Msg.tr("etmc.menu.sub.idle", "Host this world for your friends, or join theirs");
    }

    private static int subtitleColor(EtmcManager m) {
        if (!m.isReady()) return COLOR_BAD;
        return m.session() != null && m.session().isActive() ? COLOR_GOOD : COLOR_MUTED;
    }
}

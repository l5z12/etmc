package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.NetworkStatus;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.util.ArrayList;
import java.util.List;

/**
 * Main etmc menu. It offers what the player can do right now: hosting or joining when idle, and the
 * session and its invite link once they're in one.
 */
public final class EtmcScreen extends EtmcBaseScreen {

    private static final int W = 240;
    private static final int SUBTITLE_W = 320;
    /** How far each subtitle line may wrap: a long EasyTier error would push the buttons off a small screen. */
    private static final int SUBTITLE_LINES = 2;

    /** The state {@link #build} last saw: the menu rebuilds when it changes. */
    private String shown;

    public EtmcScreen(Screen parent) {
        super(Txt.literal("etmc"), parent);
    }

    /**
     * What the menu's contents depend on. The session's lines are in it, so the menu keeps up with
     * the session, but it rebuilds only when what they say changes, not on every status refresh.
     */
    private static String state() {
        EtmcManager m = EtmcManager.get();
        EtmcSession s = m.session();
        StringBuilder b = new StringBuilder().append(m.isReady()).append('/')
                .append(s != null && s.isActive() ? s.mode() : null).append('/')
                .append(McScreens.singleplayer()).append('/').append(McScreens.inWorld());
        for (StatusScreen.Line l : subtitle(m)) b.append('/').append(l.color()).append(l.text());
        return b.toString();
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

        List<StatusScreen.Line> sub = subtitle(m);
        int subW = col(SUBTITLE_W);
        int subH = 0;
        for (StatusScreen.Line l : sub) subH += wrapped(l.text(), subW, SUBTITLE_LINES).size() * LINE_H;
        int y = formTop(subH + 8 + (active && !hosting ? 1 : 2) * (ROW + GAP) + 6 + ROW, 2 * LINE_H);
        for (StatusScreen.Line l : sub) y = paragraph(l.text(), y, subW, l.color(), SUBTITLE_LINES);
        y += 8;

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

    /**
     * The lines under the title. In a session they're what the Status screen leads with: what this is,
     * in the colour of its health light, and then, if that isn't green, the problem that turned it.
     */
    private static List<StatusScreen.Line> subtitle(EtmcManager m) {
        EtmcSession s = m.session();
        if (!m.isReady()) {
            return List.of(new StatusScreen.Line(Msg.tr("etmc.error.no_native_log",
                    "etmc couldn't load its EasyTier library (see the game log)."), COLOR_BAD));
        }
        if (s != null && s.isActive()) {
            NetworkStatus st = m.cachedStatus();
            List<StatusScreen.Line> lines = StatusScreen.lines(s, st);
            int health = StatusScreen.health(lines);
            boolean hosting = s.mode() == EtmcSession.Mode.HOST;
            String what = StatusScreen.headline(s.currentCode(), hosting);
            if (hosting) {
                what = Msg.tr("etmc.menu.sub.in_world", "%s · %s in your world", what, s.connectedPeers().size());
            }
            List<StatusScreen.Line> out = new ArrayList<>();
            out.add(new StatusScreen.Line(what, health));
            if (health != COLOR_GOOD) {
                lines.stream().filter(l -> l.color() == health).findFirst().ifPresent(out::add);
            }
            return out;
        }
        String idle;
        if (!McScreens.singleplayer() && !McScreens.inWorld()) {
            idle = Msg.tr("etmc.menu.sub.title", "Open a singleplayer world to host it, or join someone's");
        } else if (!McScreens.singleplayer()) {
            idle = Msg.tr("etmc.menu.sub.server", "You're on a server: only singleplayer worlds can be hosted");
        } else {
            idle = Msg.tr("etmc.menu.sub.idle", "Host this world for your friends, or join theirs");
        }
        return List.of(new StatusScreen.Line(idle, COLOR_MUTED));
    }
}

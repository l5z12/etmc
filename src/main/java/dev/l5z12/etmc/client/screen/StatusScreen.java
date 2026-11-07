package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
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
 * Live session status. It leads with what the player needs at a glance: what this session is, any
 * problem, and who's on the network and how well they're connected. The network's details sit below
 * that in muted text, for when something needs working out.
 */
public final class StatusScreen extends EtmcBaseScreen {

    private static final int W = 300;
    private static final int DOT = 6;
    /** Width of the table's ping and route columns. */
    private static final int PING_W = 50;
    private static final int ROUTE_W = 56;
    /** Where ping turns yellow, then red: the game's own player list changes bars at the same places. */
    private static final int PING_FAIR = 150;
    private static final int PING_POOR = 300;

    /** What {@link #build} last saw, so the buttons change when the session does. */
    private EtmcSession.Mode shownMode;
    private boolean leaving;

    public StatusScreen(Screen parent) {
        this(parent, null);
    }

    /** @param notice opens with this in the status line */
    public StatusScreen(Screen parent, String notice) {
        super(Txt.tr("etmc.status.title", "etmc status"), parent);
        if (notice != null) setMessage(notice, COLOR_GOOD);
    }

    private static EtmcSession.Mode mode() {
        EtmcSession s = EtmcManager.get().session();
        return s != null && s.isActive() ? s.mode() : null;
    }

    @Override
    protected void build() {
        shownMode = mode();
        boolean host = shownMode == EtmcSession.Mode.HOST;
        int cells = shownMode == null ? 1 : host ? 3 : 2;
        int cell = 0;
        if (host) {
            add(footer(Ui.button(Txt.tr("etmc.status.copy_link", "Copy invite link"), b -> copyLink()), cell++, cells).build());
        }
        if (shownMode != null) {
            var leave = add(footer(Ui.button(host ? Txt.tr("etmc.status.stop", "Stop hosting")
                    : Txt.tr("etmc.status.leave", "Leave network"), b -> askToLeave()), cell++, cells).build());
            live(() -> leave.active = !leaving);
        }
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), cell, cells).build());
        live(() -> {
            if (mode() != shownMode) rebuild();
        });
    }

    /** Copies the invite link to the clipboard; {@code false} if there's no session to invite to. */
    static boolean copyInvite() {
        EtmcSession s = EtmcManager.get().session();
        JoinCode code = s == null ? null : s.currentCode();
        if (code == null) return false;
        McScreens.setClipboard(code.encodeLink());
        return true;
    }

    private void copyLink() {
        if (copyInvite()) {
            setMessage(Msg.tr("etmc.status.link_copied",
                    "Invite link copied. It contains the secret: send it only to people you want in."), COLOR_GOOD);
        } else {
            setMessage(Msg.tr("etmc.status.nothing_to_copy", "Nothing to copy: the session ended."), COLOR_WARN);
        }
    }

    /** Stopping drops everyone at once, so with anyone connected it takes a second click. */
    private void askToLeave() {
        EtmcManager m = EtmcManager.get();
        EtmcSession s = m.session();
        boolean connected = s != null && (s.activeConnections() > 0 || !m.cachedStatus().peers().isEmpty());
        if (!connected) {
            leave();
        } else if (shownMode == EtmcSession.Mode.HOST) {
            goTo(new EtmcAskScreen(this, Msg.tr("etmc.ask.stop.title", "Stop hosting?"),
                    Msg.tr("etmc.ask.stop", "Everyone in your world will be disconnected."),
                    Msg.tr("etmc.status.stop", "Stop hosting"), this::leave, () -> {}));
        } else {
            goTo(new EtmcAskScreen(this, Msg.tr("etmc.ask.leave.title", "Leave the network?"),
                    Msg.tr("etmc.ask.leave", "You'll be disconnected from the host's world."),
                    Msg.tr("etmc.status.leave", "Leave network"), this::leave, () -> {}));
        }
    }

    private void leave() {
        leaving = true;
        EtmcManager.get().leaveAsync().whenComplete((v, err) -> mc().execute(this::close));
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        EtmcManager m = EtmcManager.get();
        EtmcSession s = m.session();
        if (s == null || !s.isActive()) {
            Gfx.centered(ctx, font(), Txt.tr("etmc.status.none", "Not in an etmc network."),
                    this.width / 2, HEADER + 16, COLOR_MUTED);
            return;
        }
        NetworkStatus st = m.cachedStatus();
        boolean hosting = s.mode() == EtmcSession.Mode.HOST;
        JoinCode code = s.currentCode();
        int w = col(W);
        int x = left(w);
        int y = HEADER + 7;

        List<Line> problems = problems(s, st, hosting);
        boolean relayUp = st.running() && !(st.peers().isEmpty() && st.relayNodes() == 0);

        // What this session is, and whether it's healthy.
        int health = problems.stream().anyMatch(p -> p.color == COLOR_BAD) ? COLOR_BAD
                : problems.isEmpty() ? COLOR_GOOD : COLOR_WARN;
        Gfx.fill(ctx, x, y + 1, x + DOT, y + 1 + DOT, health);
        String relayState = relayUp ? Msg.tr("etmc.status.relay_ok", "Relay connected") : "";
        int headlineW = w - DOT - 4 - (relayState.isEmpty() ? 0 : Wrap.width(font(), relayState) + 8);
        Gfx.text(ctx, font(), Txt.literal(Wrap.fit(font(), headline(code, hosting), headlineW)), x + DOT + 4, y, COLOR_TEXT);
        if (!relayState.isEmpty()) {
            Gfx.text(ctx, font(), Txt.literal(relayState), x + w - Wrap.width(font(), relayState), y, COLOR_MUTED);
        }
        y += LINE_H + 3;

        for (Line p : problems) y = wrapped(ctx, p.text, x, y, w, p.color);
        for (Line d : details(s, st, code, hosting)) y = wrapped(ctx, d.text, x, y, w, d.color);
        y += 5;

        players(ctx, st, hosting, x, y, w, messageTop() - 2);
    }

    private static String headline(JoinCode code, boolean hosting) {
        String network = code == null ? "?" : code.networkName;
        String label = code == null ? null : code.displayLabel();
        boolean labelled = label != null && !label.equals(network);
        if (hosting) {
            return labelled ? Msg.tr("etmc.status.hosting_label", "Hosting '%s'", label) : Msg.tr("etmc.status.hosting", "Hosting");
        }
        return labelled ? Msg.tr("etmc.status.joined_label", "Joined '%s'", label) : Msg.tr("etmc.status.joined", "Joined");
    }

    /** What's wrong, worst first; a session with nothing in here is healthy. */
    private static List<Line> problems(EtmcSession s, NetworkStatus st, boolean hosting) {
        List<Line> lines = new ArrayList<>();
        if (!st.running()) {
            lines.add(new Line(Msg.tr("etmc.status.not_running", "The EasyTier network isn't running."), COLOR_BAD));
        } else if (st.peers().isEmpty() && st.relayNodes() == 0) {
            lines.add(new Line(Msg.tr("etmc.status.relay_waiting",
                    "Relay: not reached yet (check its address in Settings and your internet)."), COLOR_WARN));
        }
        if (st.errorMsg() != null) lines.add(new Line(Msg.tr("etmc.status.easytier_error", "EasyTier: %s", st.errorMsg()), COLOR_BAD));
        String failure = s.hostFailure();
        if (failure != null) {
            lines.add(new Line(Msg.tr("etmc.status.host_failed",
                    "Joiners can't connect any more (%s). Stop hosting and host again.", failure), COLOR_BAD));
        }
        if (s.joinFailure() != null) lines.add(new Line(s.joinFailure(), COLOR_BAD));
        if (hosting && st.peerAt(EtmcConfig.HOST_VIRTUAL_IP) != null) {
            lines.add(new Line(Msg.tr("etmc.status.ip_clash", "Another host on this network uses the same address, "
                    + "so joiners may reach the wrong world. Stop and host with a different network name."), COLOR_BAD));
        }
        return lines;
    }

    /** Who the host is, how this session is connected, and the network's own details. */
    private static List<Line> details(EtmcSession s, NetworkStatus st, JoinCode code, boolean hosting) {
        List<Line> lines = new ArrayList<>();
        if (!hosting && code != null) {
            String confirmed = s.confirmedHost();
            if (confirmed != null) {
                lines.add(new Line(Msg.tr("etmc.status.host_confirmed", "Host: %s, confirmed by Mojang", confirmed), COLOR_GOOD));
            } else if (code.namedHost() != null) {
                lines.add(new Line(Msg.tr("etmc.status.host_name_unconfirmed", "Host: checked with the key in your "
                        + "link, but Mojang couldn't be reached to confirm they're %s", code.namedHost()), COLOR_WARN));
            } else {
                lines.add(code.hostKey != null
                        ? new Line(Msg.tr("etmc.status.host_checked", "Host: checked with the key in your link"), COLOR_MUTED)
                        : new Line(Msg.tr("etmc.status.host_unchecked", "Host: not checked, because your link has no "
                                + "host key. Ask the host for a new link."), COLOR_WARN));
            }
        }
        if (hosting) {
            lines.add(new Line(Msg.tr("etmc.status.connections", "Game connections: %s", s.activeConnections()), COLOR_MUTED));
        } else if (s.linkJoin()) {
            lines.add(new Line(Msg.tr("etmc.status.link_join",
                    "Connected directly over the network (%s connection(s))", s.activeConnections()), COLOR_MUTED));
        } else {
            lines.add(new Line(Msg.tr("etmc.status.proxy_join", "Server-list entry: 'etmc: %s' → 127.0.0.1:%s "
                    + "(%s connection(s))", code == null ? "?" : code.displayLabel(), s.localPort(), s.activeConnections()),
                    COLOR_MUTED));
        }
        lines.add(new Line(Msg.tr("etmc.status.address", "Network %s · Address %s · Name %s",
                code == null ? "?" : code.networkName,
                st.virtualIp() == null ? Msg.tr("etmc.status.address.waiting", "(waiting for one…)") : st.virtualIp(),
                s.hostname() == null ? "?" : s.hostname()), COLOR_MUTED));
        return lines;
    }

    /** The player table, stopping at {@code bottom}. */
    private void players(Object ctx, NetworkStatus st, boolean hosting, int x, int y, int w, int bottom) {
        Gfx.text(ctx, font(), Txt.tr("etmc.status.peers", "Players on the network (%s, direct %s):",
                st.peerCount(), st.directPeerCount()), x, y, COLOR_TEXT);
        y += LINE_H + 2;
        List<NetworkStatus.Peer> peers = st.peers();
        if (peers.isEmpty()) {
            Gfx.text(ctx, font(), hosting ? Txt.tr("etmc.status.peers.none_host", "Nobody yet: share your invite link")
                    : Txt.tr("etmc.status.peers.none", "Nobody yet"), x, y, COLOR_MUTED);
            return;
        }
        int room = Math.max(1, (bottom - y) / LINE_H);
        int routeRight = x + w;
        int pingRight = routeRight - ROUTE_W;
        int nameW = w - ROUTE_W - PING_W - 4;
        for (int i = 0; i < peers.size(); i++) {
            if (i == room - 1 && peers.size() > room) {
                Gfx.text(ctx, font(), Txt.tr("etmc.status.peers.more", "… and %s more", peers.size() - i), x, y, COLOR_MUTED);
                return;
            }
            NetworkStatus.Peer p = peers.get(i);
            Gfx.text(ctx, font(), Txt.literal(Wrap.fit(font(), p.hostname(), nameW)), x, y, COLOR_TEXT);
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + " ms" : "—";
            Gfx.text(ctx, font(), Txt.literal(ping), pingRight - Wrap.width(font(), ping), y, pingColor(p.latencyMs()));
            String route = p.relay() ? Msg.tr("etmc.status.peer.relay", "via relay") : Msg.tr("etmc.status.peer.direct", "direct");
            Gfx.text(ctx, font(), Txt.literal(route), routeRight - Wrap.width(font(), route), y, p.relay() ? COLOR_WARN : COLOR_MUTED);
            y += LINE_H;
        }
    }

    private static int pingColor(int ms) {
        if (ms < 0) return COLOR_MUTED;
        return ms < PING_FAIR ? COLOR_GOOD : ms < PING_POOR ? COLOR_BUSY : COLOR_BAD;
    }

    private int wrapped(Object ctx, String text, int x, int y, int w, int color) {
        for (String part : Wrap.lines(font(), text, w)) {
            Gfx.text(ctx, font(), Txt.literal(part), x, y, color);
            y += LINE_H;
        }
        return y;
    }

    private record Line(String text, int color) {}
}

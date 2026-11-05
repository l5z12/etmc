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

/** Live session status: network, relay, peers/latency, problems, the invite, and Leave. */
public final class StatusScreen extends EtmcBaseScreen {

    private static final int W = 240;

    public StatusScreen(Screen parent) {
        super(Txt.tr("etmc.status.title", "etmc status"), parent);
    }

    @Override
    protected void init() {
        EtmcManager m = EtmcManager.get();
        int cx = this.width / 2;
        int y = this.height - 76;

        boolean host = m.session() != null && m.session().mode() == EtmcSession.Mode.HOST;
        if (host) {
            add(Ui.button(Txt.tr("etmc.status.copy_link", "Copy invite link"), b -> copyLink())
                    .dimensions(cx - W / 2, y, W, 20).build());
        }
        y += 24;

        add(Ui.button((host ? Txt.tr("etmc.status.stop", "Stop hosting") : Txt.tr("etmc.status.leave", "Leave network")), b -> leave())
                .dimensions(cx - W / 2, y, W / 2 - 2, 20).build());
        add(Ui.button(Txt.translatable("gui.back"), b -> this.close())
                .dimensions(cx + 2, y, W / 2 - 2, 20).build());
    }

    private void copyLink() {
        EtmcSession s = EtmcManager.get().session();
        JoinCode code = s == null ? null : s.currentCode();
        if (code == null) {
            setMessage(Msg.tr("etmc.status.nothing_to_copy", "Nothing to copy: the session ended."), COLOR_WARN);
            return;
        }
        McScreens.setClipboard(code.encodeLink());
        setMessage(Msg.tr("etmc.status.link_copied",
                "Invite link copied. It contains the secret: send it only to people you want in."), COLOR_GOOD);
    }

    private void leave() {
        EtmcManager.get().leaveAsync().whenComplete((v, err) -> mc().execute(this::close));
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        EtmcManager m = EtmcManager.get();
        int cx = this.width / 2;
        Gfx.centered(ctx, font(), this.title, cx, 12, COLOR_TEXT);

        EtmcSession s = m.session();
        if (s == null || !s.isActive()) {
            Gfx.centered(ctx, font(), Txt.tr("etmc.status.none", "Not in an etmc network."), cx, 48, COLOR_MUTED);
            return;
        }
        NetworkStatus st = m.cachedStatus();
        boolean hosting = s.mode() == EtmcSession.Mode.HOST;
        int x = cx - W / 2;
        int bottom = this.height - 82;   // the buttons start below this
        int wrapW = Math.min(W + 60, this.width - 20);
        List<Line> lines = new ArrayList<>();

        JoinCode code = s.currentCode();
        String network = code == null ? "?" : code.networkName;
        String label = code == null ? null : code.displayLabel();
        boolean labelled = label != null && !label.equals(network);
        String what = hosting
                ? (labelled ? Msg.tr("etmc.status.hosting_label", "Hosting '%s' on network %s", label, network)
                        : Msg.tr("etmc.status.hosting", "Hosting on network %s", network))
                : (labelled ? Msg.tr("etmc.status.joined_label", "Joined '%s' on network %s", label, network)
                        : Msg.tr("etmc.status.joined", "Joined network %s", network));
        lines.add(new Line(what, COLOR_GOOD));
        lines.add(new Line(Msg.tr("etmc.status.address", "Your address: %s   You appear as: %s",
                st.virtualIp() == null ? Msg.tr("etmc.status.address.waiting", "(waiting for one…)") : st.virtualIp(),
                s.hostname() == null ? "?" : s.hostname()), COLOR_TEXT));

        if (!st.running()) {
            lines.add(new Line(Msg.tr("etmc.status.not_running", "The EasyTier network isn't running."), COLOR_BAD));
        } else if (st.peers().isEmpty() && st.relayNodes() == 0) {
            lines.add(new Line(Msg.tr("etmc.status.relay_waiting",
                    "Relay: not reached yet (check its address in Settings and your internet)."), COLOR_WARN));
        } else {
            lines.add(new Line(Msg.tr("etmc.status.relay_ok", "Relay: connected"), COLOR_TEXT));
        }
        if (st.errorMsg() != null) lines.add(new Line(Msg.tr("etmc.status.easytier_error", "EasyTier: %s", st.errorMsg()), COLOR_BAD));
        String failure = s.hostFailure();
        if (failure != null) {
            lines.add(new Line(Msg.tr("etmc.status.host_failed",
                    "Joiners can't connect any more (%s). Stop hosting and host again.", failure), COLOR_BAD));
        }
        if (hosting && st.peerAt(EtmcConfig.HOST_VIRTUAL_IP) != null) {
            lines.add(new Line(Msg.tr("etmc.status.ip_clash", "Another host on this network uses the same address, "
                    + "so joiners may reach the wrong world. Stop and host with a different network name."), COLOR_BAD));
        }

        if (hosting) {
            lines.add(new Line(Msg.tr("etmc.status.connections", "Game connections: %s", s.activeConnections()), COLOR_TEXT));
        } else if (s.linkJoin()) {
            lines.add(new Line(Msg.tr("etmc.status.link_join",
                    "Connected directly over the network (%s connection(s))", s.activeConnections()), COLOR_TEXT));
        } else {
            lines.add(new Line(Msg.tr("etmc.status.proxy_join", "Server-list entry: 'etmc: %s' → 127.0.0.1:%s "
                    + "(%s connection(s))", label, s.localPort(), s.activeConnections()), COLOR_TEXT));
        }

        int y = 30;
        for (Line l : lines) {
            for (String part : Wrap.lines(font(), l.text, wrapW)) {
                Gfx.text(ctx, font(), Txt.literal(part), cx - wrapW / 2, y, l.color);
                y += 11;
            }
        }

        y += 4;
        Gfx.text(ctx, font(), Txt.tr("etmc.status.peers", "Players on the network (%s, direct %s):",
                st.peerCount(), st.directPeerCount()), x, y, COLOR_MUTED);
        y += 12;
        List<NetworkStatus.Peer> peers = st.peers();
        int room = Math.max(1, (bottom - y) / 11);
        for (int i = 0; i < peers.size(); i++) {
            if (i == room - 1 && peers.size() > room) {
                Gfx.text(ctx, font(), Txt.tr("etmc.status.peers.more", "  … and %s more", peers.size() - i), x, y, COLOR_MUTED);
                break;
            }
            NetworkStatus.Peer p = peers.get(i);
            String ping = p.latencyMs() >= 0 ? p.latencyMs() + " ms" : "—";
            String line = "  " + p.hostname() + "  " + (p.ipv4() == null ? "" : p.ipv4()) + "  " + ping
                    + (p.relay() ? Msg.tr("etmc.status.peer.relay", "  (via relay)")
                            : Msg.tr("etmc.status.peer.direct", "  (direct)"));
            Gfx.text(ctx, font(), Txt.literal(line), x, y, p.relay() ? COLOR_WARN : 0xFFDDDDDD);
            y += 11;
        }
        if (peers.isEmpty()) {
            Gfx.text(ctx, font(), (hosting ? Txt.tr("etmc.status.peers.none_host", "  nobody yet — share your invite link")
                    : Txt.tr("etmc.status.peers.none", "  nobody yet")), x, y, COLOR_MUTED);
        }
        if (!message.isEmpty()) {
            int my = this.height - 26 + 11;
            for (String l : Wrap.lines(font(), message, wrapW)) {
                Gfx.centered(ctx, font(), Txt.literal(l), cx, my, messageColor);
                my += 11;
            }
        }
    }

    private record Line(String text, int color) {}
}

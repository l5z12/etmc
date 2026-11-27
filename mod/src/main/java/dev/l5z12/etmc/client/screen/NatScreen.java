package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.NatInfo;
import dev.l5z12.etmc.core.NatType;
import dev.l5z12.etmc.core.NetworkStatus;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * This machine's NAT type, and what it means for playing together. It checks as soon as it opens
 * when etmc doesn't know yet.
 *
 * <p>It has two views. The summary is for everyone: who you get direct connections with, what that
 * means, and what to do about it, with no NAT words. The details are for whoever knows NATs: the type
 * by its name, how it maps and filters, the TCP type, which NAT types it punches through to, and every
 * player's type. A part that doesn't fit the screen is left out rather than squeezed in.
 */
public final class NatScreen extends EtmcBaseScreen {

    private static final int W = 300;
    private static final int DOT = 6;
    /** Width of the player table's route column, and at most its NAT column's. */
    private static final int ROUTE_W = 56;
    private static final int NAT_W = 120;
    /** The rows of the "who you connect to" table, best first. */
    private static final List<NatType.Grade> GRADES = List.of(NatType.Grade.OPEN, NatType.Grade.MODERATE,
            NatType.Grade.STRICT);

    /** Whether the details are showing. It lasts the game, so whoever wants them only asks once. */
    private static boolean details;

    /** This screen has started a check. {@link #build} runs again on every resize, and must not start another. */
    private boolean started;

    public NatScreen(Screen parent) {
        super(Txt.tr("etmc.nat.title", "NAT type"), parent);
    }

    @Override
    protected void build() {
        EtmcManager m = EtmcManager.get();
        if (!started && m.isReady() && m.natAt() == 0 && !m.natChecking()) check();
        var again = add(footer(Ui.button(Txt.tr("etmc.nat.check_again", "Check again"), b -> check()), 0, 3).build());
        add(footer(Ui.button(details ? Txt.tr("etmc.nat.show_summary", "Summary") : Txt.tr("etmc.nat.show_details",
                "Details"), b -> {
            details = !details;
            rebuild();
        }), 1, 3).build());
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), 2, 3).build());
        live(() -> again.active = m.isReady() && !m.natChecking());
    }

    private void check() {
        started = true;
        setMessage("", COLOR_MUTED);
        EtmcManager.get().checkNatAsync().whenComplete((found, err) -> {
            if (err == null) return;
            mc().execute(() -> setMessage(Msg.tr("etmc.nat.failed", "Couldn't check your NAT type: %s",
                    Errors.message(err)), COLOR_BAD));
        });
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        EtmcManager m = EtmcManager.get();
        int w = col(W);
        int x = left(w);
        int y = HEADER + 7;
        int bottom = messageTop() - 2;
        if (!m.isReady()) {
            wrapped(ctx, Msg.tr("etmc.error.no_native_log", "etmc couldn't load its EasyTier library (see the game log)."),
                    x, y, w, COLOR_BAD);
            return;
        }
        if (m.natChecking()) {
            headline(ctx, x, y, w, COLOR_BUSY, Msg.tr("etmc.nat.checking", "Checking your NAT type…"),
                    Msg.tr("etmc.nat.checking.time", "%s s", m.natCheckSeconds()));
            wrapped(ctx, Msg.tr("etmc.nat.checking.how", "etmc asks public servers how your connection looks from "
                    + "the internet. This takes a few seconds."), x, y + LINE_H + 3, w, COLOR_MUTED);
            return;
        }
        if (m.natAt() == 0) {
            wrapped(ctx, Msg.tr("etmc.nat.not_checked", "Not checked yet."), x, y, w, COLOR_MUTED);
            return;
        }
        if (details) {
            details(ctx, m, x, y, w, bottom);
        } else {
            summary(ctx, m, x, y, w, bottom);
        }
    }

    /** Who you connect to directly, what it means, and what to do about it, in plain words. */
    private void summary(Object ctx, EtmcManager m, int x, int y, int w, int bottom) {
        NatType.Grade grade = m.nat().grade();
        headline(ctx, x, y, w, color(grade), grade.summary(), age(m.natAt()));
        y = wrapped(ctx, grade.meaning(), x, y + LINE_H + 3, w, COLOR_TEXT);
        y = players(ctx, m, false, x, y, w, bottom);
        String tip = tip(grade);
        if (y + 5 + Wrap.lines(font(), tip, w).size() * LINE_H <= bottom) wrapped(ctx, tip, x, y + 5, w, COLOR_MUTED);
    }

    /** The NAT type by its name and behaviour, the players' types, and what it punches through to. */
    private void details(Object ctx, EtmcManager m, int x, int y, int w, int bottom) {
        NatInfo info = m.nat();
        NatType nat = info.udp();
        headline(ctx, x, y, w, color(nat.grade()), nat.label(), age(m.natAt()));
        y += LINE_H + 3;

        List<String[]> rows = new ArrayList<>();
        if (nat.mapping() != null) rows.add(new String[] {Msg.tr("etmc.nat.row.mapping", "Mapping"), nat.mapping()});
        if (nat.filtering() != null) {
            rows.add(new String[] {Msg.tr("etmc.nat.row.filtering", "Filtering"), nat.filtering()});
        }
        if (info.ports() != null) rows.add(new String[] {Msg.tr("etmc.nat.row.ports", "Mapped ports"), info.ports()});
        if (info.tcp().known()) rows.add(new String[] {Msg.tr("etmc.nat.row.tcp", "TCP"), info.tcp().label()});
        if (nat.known()) rows.add(new String[] {Msg.tr("etmc.nat.row.easytier", "EasyTier"), nat.easyTierName()});
        int keyW = 0;
        for (String[] r : rows) keyW = Math.max(keyW, Wrap.width(font(), r[0]));
        int vx = x + DOT + 4 + keyW + 8;
        for (String[] r : rows) {
            Gfx.text(ctx, font(), Txt.literal(r[0]), x + DOT + 4, y, COLOR_MUTED);
            Gfx.text(ctx, font(), Txt.literal(Wrap.fit(font(), r[1], x + w - vx)), vx, y, COLOR_TEXT);
            y += LINE_H;
        }
        if (!nat.known()) {
            wrapped(ctx, nat.grade().meaning(), x, y + 3, w, COLOR_MUTED);
            return;
        }
        y = players(ctx, m, true, x, y, w, bottom);
        if (y + 6 + (GRADES.size() + 1) * LINE_H + 2 <= bottom) reach(ctx, nat.grade(), x, y + 6, w);
    }

    /** The light, {@code main} in its color, and {@code right} muted at the far end if there's room for it. */
    private void headline(Object ctx, int x, int y, int w, int color, String main, String right) {
        Gfx.fill(ctx, x, y + 1, x + DOT, y + 1 + DOT, color);
        int tx = x + DOT + 4;
        int rightW = Wrap.width(font(), right);
        if (tx + Wrap.width(font(), main) + 8 + rightW <= x + w) {
            Gfx.text(ctx, font(), Txt.literal(right), x + w - rightW, y, COLOR_MUTED);
        }
        Gfx.text(ctx, font(), Txt.literal(Wrap.fit(font(), main, x + w - tx)), tx, y, color);
    }

    /** How {@code mine} connects to each grade's NAT types: directly, or through the relay. */
    private void reach(Object ctx, NatType.Grade mine, int x, int y, int w) {
        Gfx.text(ctx, font(), Txt.literal(Wrap.fit(font(), Msg.tr("etmc.nat.reach",
                "Direct connections, by the other player's NAT type:"), w)), x, y, COLOR_TEXT);
        y += LINE_H + 2;
        for (NatType.Grade other : GRADES) {
            boolean relayed = mine.blocksDirect(other);
            String route = relayed ? Msg.tr("etmc.status.peer.relay", "via relay") : Msg.tr("etmc.status.peer.direct", "direct");
            int routeW = Wrap.width(font(), route);
            Gfx.fill(ctx, x + 2, y + 2, x + 6, y + 6, color(other));
            Gfx.text(ctx, font(), Txt.literal(Wrap.fit(font(), other.types(), w - 10 - routeW - 8)), x + 10, y, COLOR_TEXT);
            Gfx.text(ctx, font(), Txt.literal(route), x + w - routeW, y, relayed ? COLOR_WARN : COLOR_GOOD);
            y += LINE_H;
        }
    }

    /**
     * The players on the network, the host first, with how each is connected and, with {@code types},
     * their NAT types, stopping at {@code bottom}. Nothing outside a session, or when it doesn't fit.
     *
     * @return the y below the table
     */
    private int players(Object ctx, EtmcManager m, boolean types, int x, int y, int w, int bottom) {
        EtmcSession s = m.session();
        NetworkStatus st = m.cachedStatus();
        if (s == null || !s.isActive() || st.peers().isEmpty() || y + 5 + 2 * LINE_H + 2 > bottom) return y;
        y += 5;
        boolean hosting = s.mode() == EtmcSession.Mode.HOST;
        JoinCode code = s.currentCode();
        String hostIp = hosting || code == null ? null : code.hostIp;
        Gfx.text(ctx, font(), Txt.tr("etmc.status.peers", "Players on the network (%s):", st.peerCount()), x, y, COLOR_TEXT);
        y += LINE_H + 2;
        List<NetworkStatus.Peer> peers = st.forDisplay(hostIp, hosting ? s.connectedPeers() : Set.of());
        int room = Math.max(1, (bottom - y) / LINE_H);
        int natW = types ? Math.min(NAT_W, (w - ROUTE_W) / 2) : 0;
        int nameW = w - ROUTE_W - natW - 8;
        int natX = x + nameW + 4;
        for (int i = 0; i < peers.size(); i++) {
            if (i == room - 1 && peers.size() > room) {
                Gfx.text(ctx, font(), Txt.tr("etmc.status.peers.more", "… and %s more", peers.size() - i), x, y, COLOR_MUTED);
                return y + LINE_H;
            }
            NetworkStatus.Peer p = peers.get(i);
            Gfx.text(ctx, font(), Txt.literal(name(p, hostIp, nameW)), x, y, COLOR_TEXT);
            if (types) {
                String nat = p.nat().known() ? p.nat().label() : "—";
                Gfx.text(ctx, font(), Txt.literal(Wrap.fit(font(), nat, natW)), natX, y,
                        p.nat().known() ? color(p.nat().grade()) : COLOR_MUTED);
            }
            String route = p.relay() ? Msg.tr("etmc.status.peer.relay", "via relay") : Msg.tr("etmc.status.peer.direct", "direct");
            Gfx.text(ctx, font(), Txt.literal(route), x + w - Wrap.width(font(), route), y, p.relay() ? COLOR_WARN : COLOR_MUTED);
            y += LINE_H;
        }
        return y;
    }

    /** A peer's name, marked when it's the host, cutting the name rather than the mark to fit {@code w}. */
    private String name(NetworkStatus.Peer p, String hostIp, int w) {
        String name = p.name(hostIp);
        if (!p.at(hostIp)) return Wrap.fit(font(), name, w);
        String full = Msg.tr("etmc.status.peer.host", "%s (host)", name);
        if (Wrap.width(font(), full) <= w) return full;
        int mark = Wrap.width(font(), Msg.tr("etmc.status.peer.host", "%s (host)", ""));
        return Msg.tr("etmc.status.peer.host", "%s (host)", Wrap.fit(font(), name, Math.max(0, w - mark)));
    }

    /** What to do about it, or what it's good for. */
    private static String tip(NatType.Grade grade) {
        return switch (grade) {
            case OPEN -> Msg.tr("etmc.nat.tip.open", "Everyone can connect to you directly, so you make a good host.");
            case MODERATE -> Msg.tr("etmc.nat.tip.moderate",
                    "If a friend gets direct connections with everyone, they make a better host.");
            case STRICT -> Msg.tr("etmc.nat.tip.strict", "Turning on UPnP in your router can help. If a friend gets "
                    + "direct connections with everyone, let them host.");
            case UNKNOWN -> Msg.tr("etmc.nat.tip.unknown",
                    "A wss:// relay looks like web traffic, so it gets through more networks.");
        };
    }

    /** When the NAT type was found, as a muted note. */
    private static String age(long at) {
        long minutes = Math.max(0, System.currentTimeMillis() - at) / 60_000;
        if (minutes < 1) return Msg.tr("etmc.nat.age.now", "checked just now");
        if (minutes < 60) return Msg.tr("etmc.nat.age.minutes", "checked %s min ago", minutes);
        return Msg.tr("etmc.nat.age.hours", "checked %s h ago", minutes / 60);
    }

    /** A grade's color: green when it's open, then yellow, then orange. */
    static int color(NatType.Grade grade) {
        return switch (grade) {
            case OPEN -> COLOR_GOOD;
            case MODERATE -> COLOR_BUSY;
            case STRICT, UNKNOWN -> COLOR_WARN;
        };
    }

    private int wrapped(Object ctx, String text, int x, int y, int w, int color) {
        for (String part : Wrap.lines(font(), text, w)) {
            Gfx.text(ctx, font(), Txt.literal(part), x, y, color);
            y += LINE_H;
        }
        return y;
    }
}

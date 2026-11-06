package dev.l5z12.etmc.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.core.Relays;
import dev.l5z12.etmc.core.Secrets;
//? if fabric && >=26 {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;*/
//?} else if fabric && >=1.19 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//?} else if fabric {
/*import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;*/
//?}
//? if !fabric {
/*import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;*/
//?}

/**
 * {@code /etmc ...} client-side commands as a GUI alternative: host, join, leave, status, invite,
 * relay management, hud toggle, menu.
 *
 * <p>The command source is a different type per loader (Fabric {@code FabricClientCommandSource} vs
 * mojmap {@code CommandSourceStack}) and answers the player through differently-named methods
 * ({@code sendFeedback}/{@code sendError} vs {@code sendSuccess}/{@code sendFailure}, the latter
 * taking a supplier from 1.20). All of that is absorbed once by {@link Src}, so every handler below
 * is plain Java; only {@code register}, {@code Src}, the builder helpers and {@code reply} carry the
 * split.
 */
public final class EtmcCommands {

    private EtmcCommands() {}

    //? if fabric {
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
    //?} else {
    /*public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {*/
    //?}
        dispatcher.register(lit("etmc")
                .executes(ctx -> status(src(ctx)))
                .then(lit("menu").executes(ctx -> menu()))
                .then(lit("status").executes(ctx -> status(src(ctx))))
                .then(lit("leave").executes(ctx -> leave(src(ctx))))
                .then(lit("invite").executes(ctx -> invite(src(ctx))))
                .then(lit("hud").executes(ctx -> toggleHud(src(ctx))))
                .then(lit("host")
                        .executes(ctx -> host(src(ctx), defaultNetworkName(), ""))
                        .then(arg("network", StringArgumentType.word())
                                .executes(ctx -> host(src(ctx), StringArgumentType.getString(ctx, "network"), ""))
                                .then(arg("secret", StringArgumentType.greedyString())
                                        .executes(ctx -> host(src(ctx),
                                                StringArgumentType.getString(ctx, "network"),
                                                StringArgumentType.getString(ctx, "secret"))))))
                .then(lit("join")
                        .then(arg("link", StringArgumentType.greedyString())
                                .executes(ctx -> join(src(ctx), StringArgumentType.getString(ctx, "link")))))
                .then(lit("connect")
                        .then(arg("url", StringArgumentType.word())
                                .executes(ctx -> connect(src(ctx), StringArgumentType.getString(ctx, "url"), null))
                                .then(arg("server", StringArgumentType.greedyString())
                                        .executes(ctx -> connect(src(ctx),
                                                StringArgumentType.getString(ctx, "url"),
                                                StringArgumentType.getString(ctx, "server"))))))
                .then(lit("relay")
                        .then(lit("list").executes(ctx -> relayList(src(ctx))))
                        .then(lit("clear").executes(ctx -> relayClear(src(ctx))))
                        .then(lit("add")
                                .then(arg("uri", StringArgumentType.greedyString())
                                        .executes(ctx -> relayAdd(src(ctx), StringArgumentType.getString(ctx, "uri")))))));
    }

    // ------------------------------------------------------------------ handlers

    private static int menu() {
        McScreens.mc().execute(() -> McScreens.goTo(new EtmcScreen(null)));
        return 1;
    }

    private static int host(Src src, String network, String secret) {
        EtmcManager m = EtmcManager.get();
        String blocker = m.hostBlocker();
        if (blocker != null) {
            src.error(blocker);
            return 0;
        }
        if (network == null || network.isBlank()) {
            src.error(Msg.tr("etmc.cmd.host.usage", "Usage: /etmc host <network> [secret]"));
            return 0;
        }
        // No secret given: make one up rather than host an open network anyone can stumble into.
        String sec = secret == null || secret.isBlank() ? Secrets.random() : secret;
        ModConfig cfg = m.config();
        String label = cfg.lastLabel == null || cfg.lastLabel.isBlank() 
                ? Msg.tr("etmc.host.default_label", "%s's world", m.playerName()) : cfg.lastLabel;
        String mode = cfg.lanGameMode == null || cfg.lanGameMode.isBlank() ? null : cfg.lanGameMode;
        src.feedback(Msg.tr("etmc.cmd.host.starting", "Hosting on network '%s'…", network));
        m.hostAsync(network, sec, label, mode, cfg.lanCheats, cfg.lanAllowOffline).whenComplete((code, err) -> {
            if (err != null) {
                reply(Msg.tr("etmc.host.failed_start", "Couldn't start hosting: %s", Errors.message(err)));
                return;
            }
            // The link carries the secret, so it goes to the clipboard, not into chat (where it would
            // sit in the chat log and on any stream or screenshot).
            McScreens.mc().execute(() -> McScreens.setClipboard(code.encodeLink()));
            reply(Msg.tr("etmc.cmd.host.done", "Hosting! Invite link copied to your clipboard: send it to the "
                    + "friends you want in (it contains the secret). /etmc invite copies it again."));
        });
        return 1;
    }

    private static int join(Src src, String code) {
        if (!checkReady(src)) return 0;
        JoinCode jc;
        try {
            jc = JoinCode.decode(code);
        } catch (IllegalArgumentException e) {
            src.error(Msg.tr("etmc.cmd.join.bad_link", "Bad etmc link: %s", e.getMessage()));
            return 0;
        }
        src.feedback(Msg.tr("etmc.join.starting", "Starting the network for '%s'…", jc.displayLabel()));
        EtmcManager.get().joinAsync(jc).whenComplete((port, err) -> {
            if (err != null) reply(Msg.tr("etmc.join.failed", "Couldn't join: %s", Errors.message(err)));
        });
        return 1;
    }

    private static int connect(Src src, String url, String server) {
        if (!checkReady(src)) return 0;
        src.feedback(Msg.tr("etmc.cmd.connect.fetching", "Fetching config from %s …", url));
        EtmcManager.get().connectUrlAsync(url, server == null || server.isBlank() ? null : server.trim())
                .whenComplete((port, err) -> {
                    if (err != null) reply(Msg.tr("etmc.cmd.connect.failed", "Connect failed: %s", Errors.message(err)));
                });
        return 1;
    }

    private static int leave(Src src) {
        EtmcManager m = EtmcManager.get();
        if (m.session() == null || !m.session().isActive()) {
            src.error(Msg.tr("etmc.cmd.not_in_session", "Not in an etmc session."));
            return 0;
        }
        src.feedback(Msg.tr("etmc.cmd.leaving", "Leaving…"));
        m.leaveAsync().whenComplete((v, err) ->
                reply(err == null ? Msg.tr("etmc.cmd.left", "Left the network.")
                        : Msg.tr("etmc.cmd.leave_failed", "Leave error: %s", Errors.message(err))));
        return 1;
    }

    private static int status(Src src) {
        if (!checkReady(src)) return 0;
        EtmcManager m = EtmcManager.get();
        EtmcSession s = m.session();
        if (s == null || !s.isActive()) {
            src.feedback(Msg.tr("etmc.cmd.status.idle", "etmc idle. /etmc host <network> or /etmc join <link>."));
            return 1;
        }
        NetworkStatus st = m.cachedStatus();
        String addr = st.virtualIp() == null ? Msg.tr("etmc.cmd.status.no_address", "(none yet)") : st.virtualIp();
        src.feedback(s.mode() == EtmcSession.Mode.HOST
                ? Msg.tr("etmc.cmd.status.hosting", "etmc hosting — your address %s, players %s (direct %s), "
                        + "game connections %s", addr, st.peerCount(), st.directPeerCount(), s.activeConnections())
                : Msg.tr("etmc.cmd.status.joined", "etmc joined — your address %s, players %s (direct %s), "
                        + "game connections %s", addr, st.peerCount(), st.directPeerCount(), s.activeConnections()));
        if (!st.running()) src.error(Msg.tr("etmc.status.not_running", "The EasyTier network isn't running."));
        else if (st.peers().isEmpty() && st.relayNodes() == 0) src.error(Msg.tr("etmc.cmd.status.no_relay", "The relay hasn't been reached yet."));
        if (st.errorMsg() != null) src.error(Msg.tr("etmc.status.easytier_error", "EasyTier: %s", st.errorMsg()));
        if (s.hostFailure() != null) src.error(Msg.tr("etmc.cmd.status.host_failed", "Joiners can't connect any more: %s", s.hostFailure()));
        int shown = 0;
        for (NetworkStatus.Peer p : st.peers()) {
            if (++shown > 10) {
                src.feedback(Msg.tr("etmc.cmd.status.more", "  … and %s more (etmc menu → Session status)", st.peerCount() - 10));
                break;
            }
            src.feedback("  • " + p.hostname() + " " + (p.ipv4() == null ? "" : p.ipv4())
                    + " " + (p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "")
                    + (p.relay() ? Msg.tr("etmc.status.peer.relay", "  (via relay)") : Msg.tr("etmc.status.peer.direct", "  (direct)")));
        }
        return 1;
    }

    private static int invite(Src src) {
        EtmcSession s = EtmcManager.get().session();
        JoinCode jc = s == null ? null : s.currentCode();
        if (jc == null) {
            src.error(Msg.tr("etmc.cmd.invite.none", "No active host session to invite to. Use /etmc host first."));
            return 0;
        }
        McScreens.setClipboard(jc.encodeLink());
        src.feedback(Msg.tr("etmc.cmd.invite.copied", "Invite link copied to your clipboard. Friends paste it into "
                + "Direct Connect (or etmc → Join). It contains the secret: send it only to people you want in."));
        return 1;
    }

    private static int toggleHud(Src src) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.hudEnabled = !cfg.hudEnabled;
        cfg.save();
        src.feedback(cfg.hudEnabled ? Msg.tr("etmc.cmd.hud.shown", "Top-left status overlay shown.")
                : Msg.tr("etmc.cmd.hud.hidden", "Top-left status overlay hidden."));
        return 1;
    }

    private static int relayList(Src src) {
        ModConfig cfg = EtmcManager.get().config();
        if (cfg.relays.isEmpty()) {
            src.feedback(Msg.tr("etmc.cmd.relay.none", "No relays configured. /etmc relay add <uri>"));
        } else {
            src.feedback(Msg.tr("etmc.cmd.relay.list", "Relays:"));
            for (String r : cfg.relays) src.feedback("  • " + r);
        }
        return 1;
    }

    private static int relayAdd(Src src, String uri) {
        ModConfig cfg = EtmcManager.get().config();
        java.util.List<String> add = Relays.parse(uri);
        if (add.isEmpty()) {
            src.error(Msg.tr("etmc.cmd.relay.usage", "Usage: /etmc relay add <uri>   (e.g. tcp://relay.example.com:11010)"));
            return 0;
        }
        String bad = Relays.firstProblem(add);
        if (bad != null) {
            src.error(bad);
            return 0;
        }
        for (String relay : add) {
            if (cfg.relays.contains(relay)) {
                src.feedback(Msg.tr("etmc.cmd.relay.exists", "Relay already configured: %s", relay));
            } else {
                cfg.relays.add(relay);
                src.feedback(Msg.tr("etmc.cmd.relay.added", "Added relay: %s", relay));
            }
        }
        cfg.save();
        return 1;
    }

    private static int relayClear(Src src) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.relays.clear();
        cfg.save();
        src.feedback(Msg.tr("etmc.cmd.relay.cleared", "Cleared relays."));
        return 1;
    }

    // ------------------------------------------------------------------ the loader split

    /** The command source, wrapped so the handlers above never name a loader-specific type. */
    //? if fabric {
    private record Src(FabricClientCommandSource raw) {

        void feedback(String msg) {
            raw.sendFeedback(Txt.literal(msg));
        }

        void error(String msg) {
            raw.sendError(Txt.literal(msg));
        }
    }

    private static Src src(CommandContext<FabricClientCommandSource> ctx) {
        return new Src(ctx.getSource());
    }
    //?} else if <1.20 {
    /*private record Src(CommandSourceStack raw) {

        void feedback(String msg) {
            raw.sendSuccess(Txt.literal(msg), false);
        }

        void error(String msg) {
            raw.sendFailure(Txt.literal(msg));
        }
    }

    private static Src src(CommandContext<CommandSourceStack> ctx) {
        return new Src(ctx.getSource());
    }
    *///?} else {
    /*private record Src(CommandSourceStack raw) {

        void feedback(String msg) {
            // 1.20+ takes the message as a supplier, so it is only built when someone can see it.
            raw.sendSuccess(() -> Txt.literal(msg), false);
        }

        void error(String msg) {
            raw.sendFailure(Txt.literal(msg));
        }
    }

    private static Src src(CommandContext<CommandSourceStack> ctx) {
        return new Src(ctx.getSource());
    }
    *///?}

    //? if fabric && >=26 {
    /*private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return ClientCommands.literal(name);
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(String name, ArgumentType<T> type) {
        return ClientCommands.argument(name, type);
    }
    *///?} else if fabric {
    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return ClientCommandManager.literal(name);
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(String name, ArgumentType<T> type) {
        return ClientCommandManager.argument(name, type);
    }
    //?} else {
    /*private static LiteralArgumentBuilder<CommandSourceStack> lit(String name) {
        return Commands.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> arg(String name, ArgumentType<T> type) {
        return Commands.argument(name, type);
    }
    *///?}

    /** Posts a chat line from an async callback (back on the client thread). */
    private static void reply(String msg) {
        McScreens.chat(msg);
    }

    // ------------------------------------------------------------------ shared helpers

    private static boolean checkReady(Src src) {
        if (!EtmcManager.get().isReady()) {
            src.error(Msg.tr("etmc.error.no_native", "etmc couldn't load its EasyTier library: %s",
                    EtmcManager.get().nativeError()));
            return false;
        }
        return true;
    }

    private static String defaultNetworkName() {
        String last = EtmcManager.get().config().lastNetworkName;
        return last == null || last.isBlank()
                ? "etmc-" + Integer.toHexString((int) (System.nanoTime() & 0xFFFFFF))
                : last;
    }
}

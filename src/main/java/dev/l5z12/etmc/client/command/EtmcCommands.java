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
import dev.l5z12.etmc.core.NetworkStatus;
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
                        .then(arg("code", StringArgumentType.greedyString())
                                .executes(ctx -> join(src(ctx), StringArgumentType.getString(ctx, "code")))))
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
        if (!checkReady(src) || !checkRelay(src)) return 0;
        if (network == null || network.isBlank()) {
            src.error("Usage: /etmc host <network> [secret]");
            return 0;
        }
        src.feedback("Hosting on network '" + network + "'…");
        EtmcManager.get().hostAsync(network, secret).whenComplete((code, err) -> reply(err == null
                ? "Hosting! Share: " + (code != null ? code.encode() : "?")
                : "Host failed: " + Errors.message(err)));
        return 1;
    }

    private static int join(Src src, String code) {
        if (!checkReady(src)) return 0;
        JoinCode jc;
        try {
            jc = JoinCode.decode(code);
        } catch (IllegalArgumentException e) {
            src.error("Bad join code: " + e.getMessage());
            return 0;
        }
        src.feedback("Joining '" + jc.networkName + "'…");
        EtmcManager.get().joinAsync(jc).whenComplete((port, err) -> {
            if (err != null) reply("Join failed: " + Errors.message(err));
        });
        return 1;
    }

    private static int connect(Src src, String url, String server) {
        if (!checkReady(src)) return 0;
        src.feedback("Fetching config from " + url + " …");
        EtmcManager.get().connectUrlAsync(url, server == null || server.isBlank() ? null : server.trim())
                .whenComplete((port, err) -> {
                    if (err != null) reply("Connect failed: " + Errors.message(err));
                });
        return 1;
    }

    private static int leave(Src src) {
        EtmcManager m = EtmcManager.get();
        if (m.session() == null || !m.session().isActive()) {
            src.error("Not in an etmc session.");
            return 0;
        }
        src.feedback("Leaving…");
        m.leaveAsync().whenComplete((v, err) ->
                reply(err == null ? "Left the network." : "Leave error: " + Errors.message(err)));
        return 1;
    }

    private static int status(Src src) {
        if (!checkReady(src)) return 0;
        EtmcManager m = EtmcManager.get();
        EtmcSession s = m.session();
        if (s == null || !s.isActive()) {
            src.feedback("etmc idle. /etmc host <network> or /etmc join <code>.");
            return 1;
        }
        NetworkStatus st = m.cachedStatus();
        src.feedback("etmc " + (s.mode() == EtmcSession.Mode.HOST ? "hosting" : "joined")
                + " — ip " + (st.virtualIp() == null ? "?" : st.virtualIp())
                + ", peers " + st.peerCount() + " (P2P " + st.directPeerCount() + ")"
                + ", connections " + s.activeConnections());
        for (NetworkStatus.Peer p : st.peers()) {
            src.feedback("  • " + p.hostname() + " " + (p.ipv4() == null ? "" : p.ipv4())
                    + " " + (p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "")
                    + (p.relay() ? " (relay)" : ""));
        }
        return 1;
    }

    private static int invite(Src src) {
        EtmcSession s = EtmcManager.get().session();
        JoinCode jc = s == null ? null : s.currentCode();
        if (jc == null) {
            src.error("No active host session to invite to. Use /etmc host first.");
            return 0;
        }
        String link = jc.encodeLink();
        McScreens.setClipboard(link);
        src.feedback("etmc:// link copied to clipboard (paste into Add Server / Direct Connect):");
        src.feedback(link);
        src.feedback("Join code: " + jc.encode());
        return 1;
    }

    private static int toggleHud(Src src) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.hudEnabled = !cfg.hudEnabled;
        cfg.save();
        src.feedback("HUD " + (cfg.hudEnabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    private static int relayList(Src src) {
        ModConfig cfg = EtmcManager.get().config();
        if (cfg.relays.isEmpty()) {
            src.feedback("No relays configured. /etmc relay add <uri>");
        } else {
            src.feedback("Relays:");
            for (String r : cfg.relays) src.feedback("  • " + r);
        }
        return 1;
    }

    private static int relayAdd(Src src, String uri) {
        ModConfig cfg = EtmcManager.get().config();
        String relay = uri.trim();
        if (relay.isEmpty()) {
            src.error("Usage: /etmc relay add <uri>   (e.g. tcp://my.relay:11010)");
            return 0;
        }
        if (cfg.relays.contains(relay)) {
            src.feedback("Relay already configured: " + relay);
            return 1;
        }
        cfg.relays.add(relay);
        cfg.save();
        src.feedback("Added relay: " + relay);
        return 1;
    }

    private static int relayClear(Src src) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.relays.clear();
        cfg.save();
        src.feedback("Cleared relays.");
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
        var client = McScreens.mc();
        client.execute(() -> {
            if (client.player == null) return;
            //? if yarn {
            client.player.sendMessage(Txt.literal("[etmc] " + msg), false);
            //?} else if <26 {
            /*client.player.displayClientMessage(Txt.literal("[etmc] " + msg), false);*/
            //?} else {
            /*client.player.sendSystemMessage(Txt.literal("[etmc] " + msg));*/
            //?}
        });
    }

    // ------------------------------------------------------------------ shared helpers

    private static boolean checkReady(Src src) {
        if (!EtmcManager.get().isReady()) {
            src.error("etmc native library not loaded: " + EtmcManager.get().nativeError());
            return false;
        }
        return true;
    }

    private static boolean checkRelay(Src src) {
        if (!EtmcManager.get().config().hasRelay()) {
            src.error("No relay configured. Add one with /etmc relay add <uri>.");
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

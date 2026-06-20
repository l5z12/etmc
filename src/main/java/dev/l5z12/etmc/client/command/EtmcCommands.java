package dev.l5z12.etmc.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
//? if fabric && >=1.19 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
//?} else if fabric {
/*import net.fabricmc.fabric.api.client.command.v1.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v1.FabricClientCommandSource;*/
//?}
//? if fabric {
import net.minecraft.client.MinecraftClient;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;*/
//?}

/**
 * {@code /etmc ...} client-side commands as a GUI alternative: host, join, leave, status, invite,
 * relay management, hud toggle, menu. The command-source type and builders differ by loader (Fabric
 * {@code FabricClientCommandSource}/{@code ClientCommandManager} vs mojmap {@code CommandSourceStack}/
 * {@code Commands}); the handler bodies stay uniform via {@code var src} + the lit/arg/feedback/error helpers.
 */
public final class EtmcCommands {

    private EtmcCommands() {}

    //? if fabric {
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
    //?} else {
    /*public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {*/
    //?}
        dispatcher.register(lit("etmc")
                .executes(EtmcCommands::status)
                .then(lit("menu").executes(EtmcCommands::menu))
                .then(lit("status").executes(EtmcCommands::status))
                .then(lit("leave").executes(EtmcCommands::leave))
                .then(lit("invite").executes(EtmcCommands::invite))
                .then(lit("hud").executes(EtmcCommands::toggleHud))
                .then(lit("host")
                        .executes(ctx -> host(ctx, defaultNetworkName(), ""))
                        .then(arg("network", StringArgumentType.word())
                                .executes(ctx -> host(ctx, StringArgumentType.getString(ctx, "network"), ""))
                                .then(arg("secret", StringArgumentType.greedyString())
                                        .executes(ctx -> host(ctx,
                                                StringArgumentType.getString(ctx, "network"),
                                                StringArgumentType.getString(ctx, "secret"))))))
                .then(lit("join")
                        .then(arg("code", StringArgumentType.greedyString())
                                .executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "code")))))
                .then(lit("connect")
                        .then(arg("url", StringArgumentType.word())
                                .executes(ctx -> connect(ctx, StringArgumentType.getString(ctx, "url"), null))
                                .then(arg("server", StringArgumentType.greedyString())
                                        .executes(ctx -> connect(ctx,
                                                StringArgumentType.getString(ctx, "url"),
                                                StringArgumentType.getString(ctx, "server"))))))
                .then(lit("relay")
                        .then(lit("list").executes(EtmcCommands::relayList))
                        .then(lit("clear").executes(EtmcCommands::relayClear))
                        .then(lit("add")
                                .then(arg("uri", StringArgumentType.greedyString())
                                        .executes(ctx -> relayAdd(ctx, StringArgumentType.getString(ctx, "uri")))))));
    }

    //? if fabric {
    private static int menu(CommandContext<FabricClientCommandSource> ctx)
    //?} else {
    /*private static int menu(CommandContext<CommandSourceStack> ctx)*/
    //?}
    {
        //? if fabric {
        MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(new EtmcScreen(null)));
        //?} else {
        /*Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new EtmcScreen(null)));*/
        //?}
        return 1;
    }

    //? if fabric {
    private static int host(CommandContext<FabricClientCommandSource> ctx, String network, String secret)
    //?} else {
    /*private static int host(CommandContext<CommandSourceStack> ctx, String network, String secret)*/
    //?}
    {
        var src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!checkReady(src) || !checkRelay(src)) return 0;
        if (network == null || network.isBlank()) {
            error(src, "Usage: /etmc host <network> [secret]");
            return 0;
        }
        feedback(src, "Hosting on network '" + network + "'…");
        m.hostAsync(network, secret).whenComplete((code, err) -> reply(err == null
                ? "Hosting! Share: " + (code != null ? code.encode() : "?")
                : "Host failed: " + rootMessage(err)));
        return 1;
    }

    //? if fabric {
    private static int join(CommandContext<FabricClientCommandSource> ctx, String code)
    //?} else {
    /*private static int join(CommandContext<CommandSourceStack> ctx, String code)*/
    //?}
    {
        var src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!checkReady(src)) return 0;
        JoinCode jc;
        try {
            jc = JoinCode.decode(code);
        } catch (IllegalArgumentException e) {
            error(src, "Bad join code: " + e.getMessage());
            return 0;
        }
        feedback(src, "Joining '" + jc.networkName + "'…");
        m.joinAsync(jc).whenComplete((port, err) -> {
            if (err != null) reply("Join failed: " + rootMessage(err));
        });
        return 1;
    }

    //? if fabric {
    private static int connect(CommandContext<FabricClientCommandSource> ctx, String url, String server)
    //?} else {
    /*private static int connect(CommandContext<CommandSourceStack> ctx, String url, String server)*/
    //?}
    {
        var src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!checkReady(src)) return 0;
        feedback(src, "Fetching config from " + url + " …");
        m.connectUrlAsync(url, server == null || server.isBlank() ? null : server.trim())
                .whenComplete((port, err) -> {
                    if (err != null) reply("Connect failed: " + rootMessage(err));
                });
        return 1;
    }

    //? if fabric {
    private static int leave(CommandContext<FabricClientCommandSource> ctx)
    //?} else {
    /*private static int leave(CommandContext<CommandSourceStack> ctx)*/
    //?}
    {
        var src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (m.session() == null || !m.session().isActive()) {
            error(src, "Not in an etmc session.");
            return 0;
        }
        feedback(src, "Leaving…");
        m.leaveAsync().whenComplete((v, err) -> reply(err == null ? "Left the network." : "Leave error: " + rootMessage(err)));
        return 1;
    }

    //? if fabric {
    private static int status(CommandContext<FabricClientCommandSource> ctx)
    //?} else {
    /*private static int status(CommandContext<CommandSourceStack> ctx)*/
    //?}
    {
        var src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!m.isReady()) {
            error(src, "etmc native library not loaded: " + m.nativeError());
            return 0;
        }
        EtmcSession s = m.session();
        if (s == null || !s.isActive()) {
            feedback(src, "etmc idle. /etmc host <network> or /etmc join <code>.");
            return 1;
        }
        NetworkStatus st = m.cachedStatus();
        feedback(src, "etmc " + (s.mode() == EtmcSession.Mode.HOST ? "hosting" : "joined")
                + " — ip " + (st.virtualIp() == null ? "?" : st.virtualIp())
                + ", peers " + st.peerCount() + " (P2P " + st.directPeerCount() + ")"
                + ", connections " + s.activeConnections());
        for (NetworkStatus.Peer p : st.peers()) {
            feedback(src, "  • " + p.hostname() + " " + (p.ipv4() == null ? "" : p.ipv4())
                    + " " + (p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "")
                    + (p.relay() ? " (relay)" : ""));
        }
        return 1;
    }

    //? if fabric {
    private static int invite(CommandContext<FabricClientCommandSource> ctx)
    //?} else {
    /*private static int invite(CommandContext<CommandSourceStack> ctx)*/
    //?}
    {
        var src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (m.session() == null || m.session().currentCode() == null) {
            error(src, "No active host session to invite to. Use /etmc host first.");
            return 0;
        }
        JoinCode jc = m.session().currentCode();
        String link = jc.encodeLink();
        //? if fabric {
        MinecraftClient.getInstance().keyboard.setClipboard(link);
        //?} else {
        /*Minecraft.getInstance().keyboardHandler.setClipboard(link);*/
        //?}
        feedback(src, "etmc:// link copied to clipboard (paste into Add Server / Direct Connect):");
        feedback(src, link);
        feedback(src, "Join code: " + jc.encode());
        return 1;
    }

    //? if fabric {
    private static int toggleHud(CommandContext<FabricClientCommandSource> ctx)
    //?} else {
    /*private static int toggleHud(CommandContext<CommandSourceStack> ctx)*/
    //?}
    {
        ModConfig cfg = EtmcManager.get().config();
        cfg.hudEnabled = !cfg.hudEnabled;
        cfg.save();
        feedback(ctx.getSource(), "HUD " + (cfg.hudEnabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    //? if fabric {
    private static int relayList(CommandContext<FabricClientCommandSource> ctx)
    //?} else {
    /*private static int relayList(CommandContext<CommandSourceStack> ctx)*/
    //?}
    {
        ModConfig cfg = EtmcManager.get().config();
        if (cfg.relays.isEmpty()) {
            feedback(ctx.getSource(), "No relays configured. /etmc relay add <uri>");
        } else {
            feedback(ctx.getSource(), "Relays:");
            for (String r : cfg.relays) feedback(ctx.getSource(), "  • " + r);
        }
        return 1;
    }

    //? if fabric {
    private static int relayAdd(CommandContext<FabricClientCommandSource> ctx, String uri)
    //?} else {
    /*private static int relayAdd(CommandContext<CommandSourceStack> ctx, String uri)*/
    //?}
    {
        ModConfig cfg = EtmcManager.get().config();
        cfg.relays.add(uri.trim());
        cfg.save();
        feedback(ctx.getSource(), "Added relay: " + uri.trim());
        return 1;
    }

    //? if fabric {
    private static int relayClear(CommandContext<FabricClientCommandSource> ctx)
    //?} else {
    /*private static int relayClear(CommandContext<CommandSourceStack> ctx)*/
    //?}
    {
        ModConfig cfg = EtmcManager.get().config();
        cfg.relays.clear();
        cfg.save();
        feedback(ctx.getSource(), "Cleared relays.");
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    //? if fabric {
    private static LiteralArgumentBuilder<FabricClientCommandSource> lit(String name) {
        return ClientCommandManager.literal(name);
    }

    private static <T> RequiredArgumentBuilder<FabricClientCommandSource, T> arg(String name, ArgumentType<T> type) {
        return ClientCommandManager.argument(name, type);
    }

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Txt.literal(msg));
    }

    private static void error(FabricClientCommandSource src, String msg) {
        src.sendError(Txt.literal(msg));
    }

    private static boolean checkReady(FabricClientCommandSource src) {
    //?} else {
    /*private static LiteralArgumentBuilder<CommandSourceStack> lit(String name) {
        return Commands.literal(name);
    }

    private static <T> RequiredArgumentBuilder<CommandSourceStack, T> arg(String name, ArgumentType<T> type) {
        return Commands.argument(name, type);
    }

    private static void feedback(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Txt.literal(msg), false);
    }

    private static void error(CommandSourceStack src, String msg) {
        src.sendFailure(Txt.literal(msg));
    }

    private static boolean checkReady(CommandSourceStack src) {*/
    //?}
        if (!EtmcManager.get().isReady()) {
            error(src, "etmc native library not loaded: " + EtmcManager.get().nativeError());
            return false;
        }
        return true;
    }

    //? if fabric {
    private static boolean checkRelay(FabricClientCommandSource src) {
    //?} else {
    /*private static boolean checkRelay(CommandSourceStack src) {*/
    //?}
        if (!EtmcManager.get().config().hasRelay()) {
            error(src, "No relay configured. Add one with /etmc relay add <uri>.");
            return false;
        }
        return true;
    }

    private static String defaultNetworkName() {
        String last = EtmcManager.get().config().lastNetworkName;
        return last == null || last.isBlank() ? "etmc-" + Integer.toHexString((int) (System.nanoTime() & 0xFFFFFF)) : last;
    }

    /** Posts a chat line from an async callback (back on the client thread). */
    private static void reply(String msg) {
        //? if fabric {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Txt.literal("[etmc] " + msg), false);
            }
        });
        //?} else {
        /*Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.displayClientMessage(Txt.literal("[etmc] " + msg), false);
            }
        });*/
        //?}
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

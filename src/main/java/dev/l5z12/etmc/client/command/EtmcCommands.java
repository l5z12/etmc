package dev.l5z12.etmc.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * {@code /etmc ...} client-side commands as an alternative to the GUI: host, join, leave, status,
 * invite (copy code), relay management, hud toggle, and menu.
 */
public final class EtmcCommands {

    private EtmcCommands() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("etmc")
                .executes(EtmcCommands::status)
                .then(ClientCommandManager.literal("menu").executes(EtmcCommands::menu))
                .then(ClientCommandManager.literal("status").executes(EtmcCommands::status))
                .then(ClientCommandManager.literal("leave").executes(EtmcCommands::leave))
                .then(ClientCommandManager.literal("invite").executes(EtmcCommands::invite))
                .then(ClientCommandManager.literal("hud").executes(EtmcCommands::toggleHud))
                .then(ClientCommandManager.literal("host")
                        .executes(ctx -> host(ctx, defaultNetworkName(), ""))
                        .then(ClientCommandManager.argument("network", StringArgumentType.word())
                                .executes(ctx -> host(ctx, StringArgumentType.getString(ctx, "network"), ""))
                                .then(ClientCommandManager.argument("secret", StringArgumentType.greedyString())
                                        .executes(ctx -> host(ctx,
                                                StringArgumentType.getString(ctx, "network"),
                                                StringArgumentType.getString(ctx, "secret"))))))
                .then(ClientCommandManager.literal("join")
                        .then(ClientCommandManager.argument("code", StringArgumentType.greedyString())
                                .executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "code")))))
                .then(ClientCommandManager.literal("connect")
                        .then(ClientCommandManager.argument("url", StringArgumentType.word())
                                .executes(ctx -> connect(ctx, StringArgumentType.getString(ctx, "url"), null))
                                .then(ClientCommandManager.argument("server", StringArgumentType.greedyString())
                                        .executes(ctx -> connect(ctx,
                                                StringArgumentType.getString(ctx, "url"),
                                                StringArgumentType.getString(ctx, "server"))))))
                .then(ClientCommandManager.literal("relay")
                        .then(ClientCommandManager.literal("list").executes(EtmcCommands::relayList))
                        .then(ClientCommandManager.literal("clear").executes(EtmcCommands::relayClear))
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("uri", StringArgumentType.greedyString())
                                        .executes(ctx -> relayAdd(ctx, StringArgumentType.getString(ctx, "uri")))))));
    }

    private static int menu(CommandContext<FabricClientCommandSource> ctx) {
        MinecraftClient.getInstance().execute(() ->
                MinecraftClient.getInstance().setScreen(new EtmcScreen(null)));
        return 1;
    }

    private static int host(CommandContext<FabricClientCommandSource> ctx, String network, String secret) {
        FabricClientCommandSource src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!checkReady(src) || !checkRelay(src)) return 0;
        if (network == null || network.isBlank()) {
            src.sendError(Text.literal("Usage: /etmc host <network> [secret]"));
            return 0;
        }
        feedback(src, "Hosting on network '" + network + "'…");
        m.hostAsync(network, secret).whenComplete((code, err) -> reply(err == null
                ? "Hosting! Share: " + (code != null ? code.encode() : "?")
                : "Host failed: " + rootMessage(err)));
        return 1;
    }

    private static int join(CommandContext<FabricClientCommandSource> ctx, String code) {
        FabricClientCommandSource src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!checkReady(src)) return 0;
        JoinCode jc;
        try {
            jc = JoinCode.decode(code);
        } catch (IllegalArgumentException e) {
            src.sendError(Text.literal("Bad join code: " + e.getMessage()));
            return 0;
        }
        feedback(src, "Joining '" + jc.networkName + "'…");
        m.joinAsync(jc).whenComplete((port, err) -> {
            if (err != null) reply("Join failed: " + rootMessage(err));
        });
        return 1;
    }

    private static int connect(CommandContext<FabricClientCommandSource> ctx, String url, String server) {
        FabricClientCommandSource src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!checkReady(src)) return 0;
        feedback(src, "Fetching config from " + url + " …");
        m.connectUrlAsync(url, server == null || server.isBlank() ? null : server.trim())
                .whenComplete((port, err) -> {
                    if (err != null) reply("Connect failed: " + rootMessage(err));
                });
        return 1;
    }

    private static int leave(CommandContext<FabricClientCommandSource> ctx) {
        EtmcManager m = EtmcManager.get();
        if (m.session() == null || !m.session().isActive()) {
            ctx.getSource().sendError(Text.literal("Not in an etmc session."));
            return 0;
        }
        feedback(ctx.getSource(), "Leaving…");
        m.leaveAsync().whenComplete((v, err) -> reply(err == null ? "Left the network." : "Leave error: " + rootMessage(err)));
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (!m.isReady()) {
            src.sendError(Text.literal("etmc native library not loaded: " + m.nativeError()));
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

    private static int invite(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource src = ctx.getSource();
        EtmcManager m = EtmcManager.get();
        if (m.session() == null || m.session().currentCode() == null) {
            src.sendError(Text.literal("No active host session to invite to. Use /etmc host first."));
            return 0;
        }
        JoinCode jc = m.session().currentCode();
        String link = jc.encodeLink();
        MinecraftClient.getInstance().keyboard.setClipboard(link);
        feedback(src, "etmc:// link copied to clipboard (paste into Add Server / Direct Connect):");
        feedback(src, link);
        feedback(src, "Join code: " + jc.encode());
        return 1;
    }

    private static int toggleHud(CommandContext<FabricClientCommandSource> ctx) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.hudEnabled = !cfg.hudEnabled;
        cfg.save();
        feedback(ctx.getSource(), "HUD " + (cfg.hudEnabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    private static int relayList(CommandContext<FabricClientCommandSource> ctx) {
        ModConfig cfg = EtmcManager.get().config();
        if (cfg.relays.isEmpty()) {
            feedback(ctx.getSource(), "No relays configured. /etmc relay add <uri>");
        } else {
            feedback(ctx.getSource(), "Relays:");
            for (String r : cfg.relays) feedback(ctx.getSource(), "  • " + r);
        }
        return 1;
    }

    private static int relayAdd(CommandContext<FabricClientCommandSource> ctx, String uri) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.relays.add(uri.trim());
        cfg.save();
        feedback(ctx.getSource(), "Added relay: " + uri.trim());
        return 1;
    }

    private static int relayClear(CommandContext<FabricClientCommandSource> ctx) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.relays.clear();
        cfg.save();
        feedback(ctx.getSource(), "Cleared relays.");
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static String defaultNetworkName() {
        String last = EtmcManager.get().config().lastNetworkName;
        return last == null || last.isBlank() ? "etmc-" + Integer.toHexString((int) (System.nanoTime() & 0xFFFFFF)) : last;
    }

    private static boolean checkReady(FabricClientCommandSource src) {
        if (!EtmcManager.get().isReady()) {
            src.sendError(Text.literal("etmc native library not loaded: " + EtmcManager.get().nativeError()));
            return false;
        }
        return true;
    }

    private static boolean checkRelay(FabricClientCommandSource src) {
        if (!EtmcManager.get().config().hasRelay()) {
            src.sendError(Text.literal("No relay configured. Add one with /etmc relay add <uri>."));
            return false;
        }
        return true;
    }

    private static void feedback(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal(msg));
    }

    /** Posts a chat line from an async callback (back on the client thread). */
    private static void reply(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[etmc] " + msg), false);
            } else {
                EtmcManager.get();
            }
        });
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

package dev.l5z12.etmc.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import dev.l5z12.etmc.mc.screen.EtmcScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /etmc ...} client commands for the Mojmap loaders, built on vanilla brigadier. Loaders call
 * {@link #register} from their RegisterClientCommands event (the dispatcher is the client one).
 */
public final class EtmcCommands {

    private EtmcCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("etmc")
                .executes(EtmcCommands::status)
                .then(Commands.literal("menu").executes(EtmcCommands::menu))
                .then(Commands.literal("status").executes(EtmcCommands::status))
                .then(Commands.literal("leave").executes(EtmcCommands::leave))
                .then(Commands.literal("invite").executes(EtmcCommands::invite))
                .then(Commands.literal("hud").executes(EtmcCommands::toggleHud))
                .then(Commands.literal("host")
                        .then(Commands.argument("network", StringArgumentType.word())
                                .executes(ctx -> host(ctx, StringArgumentType.getString(ctx, "network"), ""))
                                .then(Commands.argument("secret", StringArgumentType.greedyString())
                                        .executes(ctx -> host(ctx,
                                                StringArgumentType.getString(ctx, "network"),
                                                StringArgumentType.getString(ctx, "secret"))))))
                .then(Commands.literal("join")
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(ctx -> join(ctx, StringArgumentType.getString(ctx, "code")))))
                .then(Commands.literal("connect")
                        .then(Commands.argument("url", StringArgumentType.word())
                                .executes(ctx -> connect(ctx, StringArgumentType.getString(ctx, "url"), null))
                                .then(Commands.argument("server", StringArgumentType.greedyString())
                                        .executes(ctx -> connect(ctx,
                                                StringArgumentType.getString(ctx, "url"),
                                                StringArgumentType.getString(ctx, "server"))))))
                .then(Commands.literal("relay")
                        .then(Commands.literal("list").executes(EtmcCommands::relayList))
                        .then(Commands.literal("clear").executes(EtmcCommands::relayClear))
                        .then(Commands.literal("add")
                                .then(Commands.argument("uri", StringArgumentType.greedyString())
                                        .executes(ctx -> relayAdd(ctx, StringArgumentType.getString(ctx, "uri")))))));
    }

    private static int menu(CommandContext<CommandSourceStack> ctx) {
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().setScreen(new EtmcScreen(null)));
        return 1;
    }

    private static int host(CommandContext<CommandSourceStack> ctx, String network, String secret) {
        CommandSourceStack src = ctx.getSource();
        EtmcClientCore core = EtmcClientCore.get();
        if (!checkReady(src)) return 0;
        if (!core.config().hasRelay()) {
            src.sendFailure(Component.literal("No relay configured. /etmc relay add <uri>"));
            return 0;
        }
        if (network == null || network.isBlank()) {
            src.sendFailure(Component.literal("Usage: /etmc host <network> [secret]"));
            return 0;
        }
        ok(src, "Hosting on '" + network + "'…");
        core.hostAsync(network, secret).whenComplete((code, err) -> reply(err == null
                ? "Hosting! Share: " + (code != null ? code.encode() : "?")
                : "Host failed: " + root(err)));
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> ctx, String code) {
        CommandSourceStack src = ctx.getSource();
        if (!checkReady(src)) return 0;
        JoinCode jc;
        try {
            jc = JoinCode.decode(code);
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Bad join code: " + e.getMessage()));
            return 0;
        }
        ok(src, "Joining '" + jc.networkName + "'…");
        EtmcClientCore.get().joinAsync(jc).whenComplete((p, err) -> {
            if (err != null) reply("Join failed: " + root(err));
        });
        return 1;
    }

    private static int connect(CommandContext<CommandSourceStack> ctx, String url, String server) {
        CommandSourceStack src = ctx.getSource();
        if (!checkReady(src)) return 0;
        ok(src, "Fetching config from " + url + " …");
        EtmcClientCore.get().connectUrlAsync(url, server == null || server.isBlank() ? null : server.trim())
                .whenComplete((p, err) -> {
                    if (err != null) reply("Connect failed: " + root(err));
                });
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) {
        EtmcClientCore core = EtmcClientCore.get();
        if (core.session() == null || !core.session().isActive()) {
            ctx.getSource().sendFailure(Component.literal("Not in an etmc session."));
            return 0;
        }
        ok(ctx.getSource(), "Leaving…");
        core.leaveAsync().whenComplete((v, err) -> reply(err == null ? "Left the network." : "Leave error: " + root(err)));
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EtmcClientCore core = EtmcClientCore.get();
        if (!core.isReady()) {
            src.sendFailure(Component.literal("etmc native library not loaded: " + core.nativeError()));
            return 0;
        }
        EtmcSession s = core.session();
        if (s == null || !s.isActive()) {
            ok(src, "etmc idle. /etmc host <network> or /etmc join <code>.");
            return 1;
        }
        NetworkStatus st = core.cachedStatus();
        ok(src, "etmc " + (s.mode() == EtmcSession.Mode.HOST ? "hosting" : "joined")
                + " — ip " + (st.virtualIp() == null ? "?" : st.virtualIp())
                + ", peers " + st.peerCount() + " (P2P " + st.directPeerCount() + ")"
                + ", connections " + s.activeConnections());
        for (NetworkStatus.Peer p : st.peers()) {
            ok(src, "  • " + p.hostname() + " " + (p.ipv4() == null ? "" : p.ipv4())
                    + " " + (p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "")
                    + (p.relay() ? " (relay)" : ""));
        }
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        EtmcClientCore core = EtmcClientCore.get();
        if (core.session() == null || core.session().currentCode() == null) {
            src.sendFailure(Component.literal("No active host session. /etmc host first."));
            return 0;
        }
        JoinCode jc = core.session().currentCode();
        String link = jc.encodeLink();
        Minecraft.getInstance().keyboardHandler.setClipboard(link);
        ok(src, "etmc:// link copied (paste into Add Server / Direct Connect):");
        ok(src, link);
        ok(src, "Join code: " + jc.encode());
        return 1;
    }

    private static int toggleHud(CommandContext<CommandSourceStack> ctx) {
        McConfig cfg = EtmcClientCore.get().config();
        cfg.hudEnabled = !cfg.hudEnabled;
        cfg.save();
        ok(ctx.getSource(), "HUD " + (cfg.hudEnabled ? "enabled" : "disabled") + ".");
        return 1;
    }

    private static int relayList(CommandContext<CommandSourceStack> ctx) {
        McConfig cfg = EtmcClientCore.get().config();
        if (cfg.relays.isEmpty()) {
            ok(ctx.getSource(), "No relays. /etmc relay add <uri>");
        } else {
            ok(ctx.getSource(), "Relays:");
            for (String r : cfg.relays) ok(ctx.getSource(), "  • " + r);
        }
        return 1;
    }

    private static int relayAdd(CommandContext<CommandSourceStack> ctx, String uri) {
        McConfig cfg = EtmcClientCore.get().config();
        cfg.relays.add(uri.trim());
        cfg.save();
        ok(ctx.getSource(), "Added relay: " + uri.trim());
        return 1;
    }

    private static int relayClear(CommandContext<CommandSourceStack> ctx) {
        McConfig cfg = EtmcClientCore.get().config();
        cfg.relays.clear();
        cfg.save();
        ok(ctx.getSource(), "Cleared relays.");
        return 1;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean checkReady(CommandSourceStack src) {
        if (!EtmcClientCore.get().isReady()) {
            src.sendFailure(Component.literal("etmc native library not loaded: " + EtmcClientCore.get().nativeError()));
            return false;
        }
        return true;
    }

    private static void ok(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }

    private static void reply(String msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(Component.literal("[etmc] " + msg), false);
        });
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

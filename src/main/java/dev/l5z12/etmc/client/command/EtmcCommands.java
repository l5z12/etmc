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
import dev.l5z12.etmc.core.Relays;
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
        EtmcManager m = EtmcManager.get();
        String blocker = m.hostBlocker();
        if (blocker != null) {
            src.error(blocker);
            return 0;
        }
        if (network == null || network.isBlank()) {
            src.error("Usage: /etmc host <network> [secret]");
            return 0;
        }
        // No secret given: make one up rather than host an open network anyone can stumble into.
        String sec = secret == null || secret.isBlank() ? randomSecret() : secret;
        ModConfig cfg = m.config();
        String label = cfg.lastLabel == null || cfg.lastLabel.isBlank() ? m.playerName() + "'s world" : cfg.lastLabel;
        String mode = cfg.lanGameMode == null || cfg.lanGameMode.isBlank() ? null : cfg.lanGameMode;
        src.feedback("Hosting on network '" + network + "'…");
        m.hostAsync(network, sec, label, mode, cfg.lanCheats).whenComplete((code, err) -> {
            if (err != null) {
                reply("Couldn't start hosting: " + Errors.message(err));
                return;
            }
            // The link carries the secret, so it goes to the clipboard, not into chat (where it would
            // sit in the chat log and on any stream or screenshot).
            McScreens.mc().execute(() -> McScreens.setClipboard(code.encodeLink()));
            reply("Hosting! Invite link copied to your clipboard: send it to the friends you want in"
                    + " (it contains the secret). /etmc invite copies it again.");
        });
        return 1;
    }

    private static String randomSecret() {
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder(12);
        for (int i = 0; i < 12; i++) b.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return b.toString();
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
        src.feedback("Starting the network for '" + jc.displayLabel() + "'…");
        EtmcManager.get().joinAsync(jc).whenComplete((port, err) -> {
            if (err != null) reply("Couldn't join: " + Errors.message(err));
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
                + " — your address " + (st.virtualIp() == null ? "(none yet)" : st.virtualIp())
                + ", players " + st.peerCount() + " (direct " + st.directPeerCount() + ")"
                + ", game connections " + s.activeConnections());
        if (!st.running()) src.error("The EasyTier network isn't running.");
        else if (st.peers().isEmpty() && st.relayNodes() == 0) src.error("The relay hasn't been reached yet.");
        if (st.errorMsg() != null) src.error("EasyTier: " + st.errorMsg());
        if (s.hostFailure() != null) src.error("Joiners can't connect any more: " + s.hostFailure());
        int shown = 0;
        for (NetworkStatus.Peer p : st.peers()) {
            if (++shown > 10) {
                src.feedback("  … and " + (st.peerCount() - 10) + " more (etmc menu → Session status)");
                break;
            }
            src.feedback("  • " + p.hostname() + " " + (p.ipv4() == null ? "" : p.ipv4())
                    + " " + (p.latencyMs() >= 0 ? p.latencyMs() + "ms" : "")
                    + (p.relay() ? " (via relay)" : " (direct)"));
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
        McScreens.setClipboard(jc.encodeLink());
        src.feedback("Invite link copied to your clipboard. Friends paste it into Direct Connect (or"
                + " etmc → Join). It contains the secret: send it only to people you want in.");
        return 1;
    }

    private static int toggleHud(Src src) {
        ModConfig cfg = EtmcManager.get().config();
        cfg.hudEnabled = !cfg.hudEnabled;
        cfg.save();
        src.feedback("Top-left status overlay " + (cfg.hudEnabled ? "shown" : "hidden") + ".");
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
        java.util.List<String> add = Relays.parse(uri);
        if (add.isEmpty()) {
            src.error("Usage: /etmc relay add <uri>   (e.g. tcp://relay.example.com:11010)");
            return 0;
        }
        String bad = Relays.firstProblem(add);
        if (bad != null) {
            src.error(bad);
            return 0;
        }
        for (String relay : add) {
            if (cfg.relays.contains(relay)) {
                src.feedback("Relay already configured: " + relay);
            } else {
                cfg.relays.add(relay);
                src.feedback("Added relay: " + relay);
            }
        }
        cfg.save();
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
        McScreens.chat(msg);
    }

    // ------------------------------------------------------------------ shared helpers

    private static boolean checkReady(Src src) {
        if (!EtmcManager.get().isReady()) {
            src.error("etmc native library not loaded: " + EtmcManager.get().nativeError());
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

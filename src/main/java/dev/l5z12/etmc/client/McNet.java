package dev.l5z12.etmc.client;

import dev.l5z12.etmc.core.Msg;

//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.GameMode;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;*/
//?}
// ServerAddress moved net.minecraft.network -> net.minecraft.client.network, and ServerList
// net.minecraft.client.options -> net.minecraft.client.option, both at yarn 1.16.5.
//? if yarn && >=1.16.5 {
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.option.ServerList;
//?} else if yarn {
/*import net.minecraft.network.ServerAddress;
import net.minecraft.client.options.ServerList;*/
//?}
//? if yarn && >=1.20.3 {
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
//?} else if yarn {
/*import net.minecraft.client.gui.screen.ConnectScreen;
*///?} else {
/*import net.minecraft.client.gui.screens.ConnectScreen;*/
//?}

import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Thin glue to Minecraft's own networking: publishing the current singleplayer world to LAN, and
 * surfacing the etmc loopback proxy as a Multiplayer server entry. All the yarn-vs-mojmap (and
 * per-version) mapping differences live here in one place.
 */
public final class McNet {

    /** @see dev.l5z12.etmc.core.EtmcConnect#PLACEHOLDER_ADDRESS */
    private static final String PLACEHOLDER_ADDRESS = dev.l5z12.etmc.core.EtmcConnect.PLACEHOLDER_ADDRESS;

    /** Name prefix of the server-list entries {@link #presentJoin} adds (and {@link #removeJoinEntries} removes). */
    private static final String JOIN_ENTRY_PREFIX = "etmc: ";

    private McNet() {}

    /**
     * Ensures the current singleplayer world is open to LAN and completes with its port. Runs on the
     * client thread.
     *
     * <p>If the world is already open to LAN (the player used vanilla's dialog), that is reused as is.
     * Otherwise it is opened with {@code gameMode} for joiners ({@code null} = the host's current mode)
     * and {@code cheats} — which, as in vanilla's dialog, applies to the host too.
     * {@code allowOffline} applies either way, because the login check reads it for each joiner.
     */
    public static CompletableFuture<Integer> ensureOpenToLan(String gameMode, boolean cheats, boolean allowOffline) {
        CompletableFuture<Integer> cf = new CompletableFuture<>();
        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                IntegratedServer server = client.getServer();
                if (server == null) {
                    cf.completeExceptionally(new IllegalStateException(Msg.tr("etmc.lan.no_world", "Open a singleplayer world first.")));
                    return;
                }
                allowOfflineAccounts(server, allowOffline);
                int port = server.getServerPort();
                if (port > 0) {
                    cf.complete(port);
                    return;
                }
                int chosen = freePort();
                GameMode mode = gameMode != null ? GameMode.valueOf(gameMode)
                        : client.interactionManager != null
                        ? client.interactionManager.getCurrentGameMode() : GameMode.SURVIVAL;
                boolean ok = server.openToLan(mode, cheats, chosen);
                if (!ok) {
                    cf.completeExceptionally(new IllegalStateException(Msg.tr("etmc.lan.failed", "Failed to open the world to LAN.")));
                    return;
                }
                cf.complete(server.getServerPort());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        //?} else if <26 {
        /*Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                IntegratedServer server = client.getSingleplayerServer();
                if (server == null) {
                    cf.completeExceptionally(new IllegalStateException(Msg.tr("etmc.lan.no_world", "Open a singleplayer world first.")));
                    return;
                }
                allowOfflineAccounts(server, allowOffline);
                int port = server.getPort();
                if (port > 0) {
                    cf.complete(port);
                    return;
                }
                int chosen = freePort();
                GameType mode = gameMode != null ? GameType.valueOf(gameMode)
                        : client.gameMode != null ? client.gameMode.getPlayerMode() : GameType.SURVIVAL;
                boolean ok = server.publishServer(mode, cheats, chosen);
                if (!ok) {
                    cf.completeExceptionally(new IllegalStateException(Msg.tr("etmc.lan.failed", "Failed to open the world to LAN.")));
                    return;
                }
                cf.complete(server.getPort());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });*/
        //?} else {
        /*Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                IntegratedServer server = client.getSingleplayerServer();
                if (server == null) {
                    cf.completeExceptionally(new IllegalStateException(Msg.tr("etmc.lan.no_world", "Open a singleplayer world first.")));
                    return;
                }
                allowOfflineAccounts(server, allowOffline);
                int port = server.getPort();
                if (port > 0) {
                    cf.complete(port);
                    return;
                }
                int chosen = freePort();
                GameType mode = gameMode != null ? GameType.valueOf(gameMode)
                        : client.gameMode != null ? client.gameMode.getPlayerMode() : GameType.SURVIVAL;
                boolean ok = server.publishServer(
                        net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, mode, cheats, chosen);
                if (!ok) {
                    cf.completeExceptionally(new IllegalStateException(Msg.tr("etmc.lan.failed", "Failed to open the world to LAN.")));
                    return;
                }
                cf.complete(server.getPort());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });*/
        //?}
        return cf;
    }

    /** Minecraft turns this check on for every LAN world, and only skips it for in-memory connections. */
    private static void allowOfflineAccounts(IntegratedServer server, boolean allow) {
        //? if yarn {
        server.setOnlineMode(!allow);
        //?} else {
        /*server.setUsesAuthentication(!allow);*/
        //?}
    }

    /** Puts account checks back when hosting stops, because the world stays open to LAN afterwards. */
    public static void requireAccounts() {
        //? if yarn {
        IntegratedServer server = MinecraftClient.getInstance().getServer();
        //?} else {
        /*IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();*/
        //?}
        if (server != null) allowOfflineAccounts(server, false);
    }

    /**
     * Surfaces the etmc loopback proxy ({@code 127.0.0.1:port}) as a Multiplayer server entry, then —
     * only if no world is loaded — opens the Multiplayer screen so the player can connect.
     *
     * <p>We deliberately do NOT auto-connect (connecting while a world is loaded triggers a "Saving
     * world" hang); etmc joining behaves like "Multiplayer → Add Server" + "Direct connect". Client thread.
     */
    public static void presentJoin(String label, int port) {
        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        //?} else {
        /*Minecraft client = Minecraft.getInstance();*/
        //?}
        String address = "127.0.0.1:" + port;
        String name = JOIN_ENTRY_PREFIX + label;

        ServerList list = new ServerList(client);
        //? if yarn {
        list.loadFile();
        //?} else {
        /*list.load();*/
        //?}
        for (int i = list.size() - 1; i >= 0; i--) {
            //? if yarn {
            ServerInfo s = list.get(i);
            //?} else {
            /*ServerData s = list.get(i);*/
            //?}
            if (s != null && name.equals(s.name)) list.remove(s);
        }
        //? if yarn && >=1.20.2 {
        list.add(new ServerInfo(name, address, ServerInfo.ServerType.OTHER), true);
        //?} else if yarn && >=1.19 {
        /*list.add(new ServerInfo(name, address, false), true);
        *///?} else if yarn {
        /*list.add(new ServerInfo(name, address, false));*/
        //?} else if >=1.19 && <1.20.2 {
        /*list.add(new ServerData(name, address, false), true);*/
        //?} else if <1.19 {
        /*list.add(new ServerData(name, address, false));*/
        //?} else {
        /*list.add(new ServerData(name, address, ServerData.Type.OTHER), true);*/
        //?}
        //? if yarn {
        list.saveFile();
        //?} else {
        /*list.save();*/
        //?}

        //? if yarn && >=1.17 {
        if (client.world == null) {
            client.setScreen(new MultiplayerScreen(new TitleScreen()));
        } else {
            client.setScreen(null);
            if (client.player != null) {
                client.player.sendMessage(Txt.literal(addedHint(name, address)), false);
            }
        }
        //?} else if yarn && >=1.16 {
        /*if (client.world == null) {
            client.openScreen(new MultiplayerScreen(new TitleScreen()));
        } else {
            client.openScreen(null);
            if (client.player != null) {
                client.player.sendMessage(Txt.literal(addedHint(name, address)), false);
            }
        }*/
        //?} else if yarn {
        /*if (client.world == null) {
            client.openScreen(new MultiplayerScreen(new TitleScreen()));
        } else {
            client.openScreen(null);
            if (client.player != null) {
                client.player.sendMessage(Txt.literal(addedHint(name, address)));
            }
        }*/
        //?} else if <26 {
        /*if (client.level == null) {
            client.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        } else {
            client.setScreen(null);
            if (client.player != null) {
                client.player.displayClientMessage(Txt.literal(addedHint(name, address)), false);
            }
        }*/
        //?} else {
        /*if (client.level == null) {
            client.setScreenAndShow(new JoinMultiplayerScreen(new TitleScreen()));
        } else {
            client.setScreenAndShow(null);
            if (client.player != null) {
                client.player.sendSystemMessage(Txt.literal(addedHint(name, address)));
            }
        }*/
        //?}
    }

    /**
     * Removes the {@code etmc: ...} entries {@link #presentJoin} added. They point at a loopback port
     * that only works while that join is running, so once it ends (or after a restart) they are dead
     * entries that just show "Can't connect". Client thread.
     */
    public static void removeJoinEntries() {
        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        //?} else {
        /*Minecraft client = Minecraft.getInstance();*/
        //?}
        ServerList list = new ServerList(client);
        //? if yarn {
        list.loadFile();
        //?} else {
        /*list.load();*/
        //?}
        boolean changed = false;
        for (int i = list.size() - 1; i >= 0; i--) {
            //? if yarn {
            ServerInfo s = list.get(i);
            String address = s == null ? null : s.address;
            //?} else {
            /*ServerData s = list.get(i);
            String address = s == null ? null : s.ip;*/
            //?}
            if (s != null && s.name != null && s.name.startsWith(JOIN_ENTRY_PREFIX)
                    && address != null && address.startsWith("127.0.0.1:")) {
                list.remove(s);
                changed = true;
            }
        }
        if (!changed) return;
        //? if yarn {
        list.saveFile();
        //?} else {
        /*list.save();*/
        //?}
    }

    /** Chat line shown when a world is loaded, so the player knows where the entry went. */
    private static String addedHint(String name, String address) {
        return "[etmc] " + Msg.tr("etmc.join.added", "Added '%s' to Multiplayer. Leave this world, then join it "
                + "from there (or Direct Connect %s). It stays valid until you leave the etmc network.", name, address);
    }

    /**
     * Kicks off a vanilla connect for the {@code etmc://} link flow. The address is a placeholder —
     * {@code EtmcConnect} has a pending target and the Connection mixin swaps in an EtmcChannel, so
     * nothing actually connects to this address. When {@code protocolVersion > 0} (detected over the
     * mesh because ViaFabricPlus can't probe the placeholder address itself), it is pinned onto the
     * server entry via {@link ViaHook} so VFP translates without auto-detecting. Client thread;
     * instance must already be started.
     */
    public static void connectViaChannel(Screen parent, String label, int protocolVersion) {
        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        //?} else {
        /*Minecraft client = Minecraft.getInstance();*/
        //?}
        //? if yarn && >=1.20.2 {
        ServerInfo info = new ServerInfo("etmc: " + label, PLACEHOLDER_ADDRESS, ServerInfo.ServerType.OTHER);
        //?} else if yarn {
        /*ServerInfo info = new ServerInfo("etmc: " + label, PLACEHOLDER_ADDRESS, false);
        *///?} else if <1.20.2 {
        /*ServerData info = new ServerData("etmc: " + label, PLACEHOLDER_ADDRESS, false);*/
        //?} else {
        /*ServerData info = new ServerData("etmc: " + label, PLACEHOLDER_ADDRESS, ServerData.Type.OTHER);*/
        //?}
        // Pin the host's protocol so ViaFabricPlus skips its own (unreachable) auto-detect probe.
        ViaHook.forceVersion(info, protocolVersion);
        //? if yarn {
        ServerAddress addr = ServerAddress.parse(PLACEHOLDER_ADDRESS);
        //?} else {
        /*ServerAddress addr = ServerAddress.parseString(PLACEHOLDER_ADDRESS);*/
        //?}
        //? if yarn && >=1.20.5 {
        ConnectScreen.connect(parent, client, addr, info, false, null);
        //?} else if yarn && >=1.20 {
        /*ConnectScreen.connect(parent, client, addr, info, false);*/
        //?} else if yarn && >=1.17 {
        /*ConnectScreen.connect(parent, client, addr, info);
        *///?} else if yarn {
        /*// 1.16 has no static connect: the ConnectScreen(parent, client, ServerInfo) ctor connects itself.
        client.openScreen(new ConnectScreen(parent, client, info));*/
        //?} else if <1.20 {
        /*ConnectScreen.startConnecting(parent, client, addr, info);*/
        //?} else if <1.20.5 {
        /*ConnectScreen.startConnecting(parent, client, addr, info, false);*/
        //?} else {
        /*ConnectScreen.startConnecting(parent, client, addr, info, false, null);*/
        //?}
    }

    /**
     * Picks a free port for "Open to LAN" by binding and releasing an ephemeral one. Inherently
     * racy (something else could take it in between), but it is what vanilla's own LAN dialog does,
     * and {@code openToLan} reports the failure if it loses the race.
     */
    private static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        } catch (Exception e) {
            // Handing 0 to openToLan would fail with a far less obvious message than this.
            throw new IllegalStateException(Msg.tr("etmc.lan.no_port", "Couldn't reserve a local port for Open to LAN."), e);
        }
    }
}

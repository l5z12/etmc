package dev.l5z12.etmc.client;

//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
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
//? if yarn && >=1.20.2 {
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
//?} else if yarn {
/*import net.minecraft.client.gui.screen.ConnectScreen;*/
//?} else {
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

    private McNet() {}

    /**
     * Ensures the current singleplayer world is open to LAN and completes with its port. Runs on the
     * client thread.
     */
    public static CompletableFuture<Integer> ensureOpenToLan() {
        CompletableFuture<Integer> cf = new CompletableFuture<>();
        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            try {
                IntegratedServer server = client.getServer();
                if (server == null) {
                    cf.completeExceptionally(new IllegalStateException("Open a singleplayer world first."));
                    return;
                }
                int port = server.getServerPort();
                if (port > 0) {
                    cf.complete(port);
                    return;
                }
                int chosen = freePort();
                GameMode mode = client.interactionManager != null
                        ? client.interactionManager.getCurrentGameMode() : GameMode.SURVIVAL;
                boolean ok = server.openToLan(mode, false, chosen);
                if (!ok) {
                    cf.completeExceptionally(new IllegalStateException("Failed to open the world to LAN."));
                    return;
                }
                cf.complete(server.getServerPort());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        //?} else {
        /*Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            try {
                IntegratedServer server = client.getSingleplayerServer();
                if (server == null) {
                    cf.completeExceptionally(new IllegalStateException("Open a singleplayer world first."));
                    return;
                }
                int port = server.getPort();
                if (port > 0) {
                    cf.complete(port);
                    return;
                }
                int chosen = freePort();
                GameType mode = client.gameMode != null ? client.gameMode.getPlayerMode() : GameType.SURVIVAL;
                boolean ok = server.publishServer(mode, false, chosen);
                if (!ok) {
                    cf.completeExceptionally(new IllegalStateException("Failed to open the world to LAN."));
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
        String name = "etmc: " + label;

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
        /*list.add(new ServerInfo(name, address, false), true);*/
        //?} else if yarn {
        /*list.add(new ServerInfo(name, address, false));*/
        //?} else {
        /*list.add(new ServerData(name, address, ServerData.Type.OTHER), true);*/
        //?}
        //? if yarn {
        list.saveFile();
        //?} else {
        /*list.save();*/
        //?}

        //? if yarn {
        if (client.world == null) {
            client.setScreen(new MultiplayerScreen(new TitleScreen()));
        } else {
            client.setScreen(null);
            if (client.player != null) {
                client.player.sendMessage(Txt.literal("[etmc] Added '" + name
                        + "' to Multiplayer. Leave your world, then connect there (or Direct Connect "
                        + address + ")."), false);
            }
        }
        //?} else {
        /*if (client.level == null) {
            client.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        } else {
            client.setScreen(null);
            if (client.player != null) {
                client.player.displayClientMessage(Txt.literal("[etmc] Added '" + name
                        + "' to Multiplayer. Leave your world, then connect there (or Direct Connect "
                        + address + ")."), false);
            }
        }*/
        //?}
    }

    /**
     * Kicks off a vanilla connect for the {@code etmc://} link flow. The address is a placeholder —
     * {@code EtmcConnect} has a pending target and the Connection mixin swaps in an EtmcChannel, so
     * nothing actually connects to this address. Client thread; instance must already be started.
     */
    public static void connectViaChannel(Screen parent, String label) {
        //? if yarn {
        MinecraftClient client = MinecraftClient.getInstance();
        //?} else {
        /*Minecraft client = Minecraft.getInstance();*/
        //?}
        //? if yarn && >=1.20.2 {
        ServerInfo info = new ServerInfo("etmc: " + label, "127.0.0.1:25565", ServerInfo.ServerType.OTHER);
        //?} else if yarn {
        /*ServerInfo info = new ServerInfo("etmc: " + label, "127.0.0.1:25565", false);*/
        //?} else {
        /*ServerData info = new ServerData("etmc: " + label, "127.0.0.1:25565", ServerData.Type.OTHER);*/
        //?}
        //? if yarn {
        ServerAddress addr = ServerAddress.parse("127.0.0.1:25565");
        //?} else {
        /*ServerAddress addr = ServerAddress.parseString("127.0.0.1:25565");*/
        //?}
        //? if yarn && >=1.20.5 {
        ConnectScreen.connect(parent, client, addr, info, false, null);
        //?} else if yarn && >=1.20 {
        /*ConnectScreen.connect(parent, client, addr, info, false);*/
        //?} else if yarn {
        /*ConnectScreen.connect(parent, client, addr, info);*/
        //?} else {
        /*ConnectScreen.startConnecting(parent, client, addr, info, false, null);*/
        //?}
    }

    private static int freePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        } catch (Exception e) {
            return 0;
        }
    }
}

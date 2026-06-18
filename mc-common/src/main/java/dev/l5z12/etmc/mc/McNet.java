package dev.l5z12.etmc.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Minecraft networking glue for the Mojmap loaders: publish the singleplayer world to LAN and
 * surface the etmc loopback proxy as a Multiplayer server entry. Vanilla-only, so shared by
 * NeoForge and Forge.
 */
public final class McNet {

    private McNet() {}

    /** Ensures the current world is open to LAN and completes with its port. Runs on the client thread. */
    public static CompletableFuture<Integer> ensureOpenToLan() {
        CompletableFuture<Integer> cf = new CompletableFuture<>();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                IntegratedServer server = mc.getSingleplayerServer();
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
                GameType mode = mc.gameMode != null ? mc.gameMode.getPlayerMode() : GameType.SURVIVAL;
                boolean ok = server.publishServer(mode, false, chosen);
                if (!ok) {
                    cf.completeExceptionally(new IllegalStateException("Failed to open the world to LAN."));
                    return;
                }
                cf.complete(server.getPort());
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    /**
     * Surfaces the etmc loopback proxy ({@code 127.0.0.1:port}) as a Multiplayer server entry, then —
     * only if no world is loaded — opens the Multiplayer screen so the player can connect.
     *
     * <p>We deliberately do NOT auto-connect: connecting via {@code ConnectScreen} while a singleplayer
     * world is loaded triggers a "Saving world" hang (vanilla only ever connects from the menu). So
     * etmc joining behaves like "Multiplayer → Add Server" + "Direct connect". Must run on the client thread.
     */
    public static void presentJoin(String label, int port) {
        Minecraft mc = Minecraft.getInstance();
        String address = "127.0.0.1:" + port;
        String name = "etmc: " + label;

        ServerList list = new ServerList(mc);
        list.load();
        for (int i = list.size() - 1; i >= 0; i--) {
            ServerData s = list.get(i);
            if (s != null && name.equals(s.name)) list.remove(s);
        }
        list.add(new ServerData(name, address, ServerData.Type.OTHER), true);
        list.save();

        if (mc.level == null) {
            mc.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
        } else {
            mc.setScreen(null);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[etmc] Added '" + name
                        + "' to Multiplayer. Leave your world, then connect there (or Direct Connect "
                        + address + ")."), false);
            }
        }
    }

    /**
     * Kicks off a vanilla connect for the {@code etmc://} link flow. The address is a placeholder —
     * {@code EtmcConnect} has a pending target and the Connection mixin swaps in an EtmcChannel, so
     * nothing actually connects to this address. Client thread; instance must already be started.
     */
    public static void connectViaChannel(Screen parent, String label) {
        Minecraft mc = Minecraft.getInstance();
        ServerData data = new ServerData("etmc: " + label, "127.0.0.1:25565", ServerData.Type.OTHER);
        ServerAddress addr = ServerAddress.parseString("127.0.0.1:25565");
        ConnectScreen.startConnecting(parent, mc, addr, data, false, null);
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

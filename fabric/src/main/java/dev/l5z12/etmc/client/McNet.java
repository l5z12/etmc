package dev.l5z12.etmc.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;

import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Thin glue to Minecraft's own networking: publishing the current singleplayer world to LAN, and
 * surfacing the etmc loopback proxy as a Multiplayer server entry. Kept isolated so version-specific
 * mappings live in one place.
 */
public final class McNet {

    private McNet() {}

    /**
     * Ensures the current singleplayer world is open to LAN and completes with its port. Runs on the
     * client thread.
     */
    public static CompletableFuture<Integer> ensureOpenToLan() {
        CompletableFuture<Integer> cf = new CompletableFuture<>();
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
        return cf;
    }

    /**
     * Surfaces the etmc loopback proxy ({@code 127.0.0.1:port}) as a Multiplayer server entry, then —
     * only if no world is loaded — opens the Multiplayer screen so the player can connect.
     *
     * <p>We deliberately do NOT auto-connect: connecting via {@code ConnectScreen} while a singleplayer
     * world is loaded triggers a "Saving world" hang (vanilla only ever connects from the menu). So
     * etmc joining behaves like "Multiplayer → Add Server" + "Direct connect": the entry is added to
     * the server list and the player connects from the Multiplayer menu after leaving any open world.
     *
     * <p>Must be called on the client thread.
     */
    public static void presentJoin(String label, int port) {
        MinecraftClient client = MinecraftClient.getInstance();
        String address = "127.0.0.1:" + port;
        String name = "etmc: " + label;

        ServerList list = new ServerList(client);
        list.loadFile();
        for (int i = list.size() - 1; i >= 0; i--) {
            ServerInfo s = list.get(i);
            if (s != null && name.equals(s.name)) list.remove(s);
        }
        list.add(new ServerInfo(name, address, ServerInfo.ServerType.OTHER), true);
        list.saveFile();

        if (client.world == null) {
            client.setScreen(new MultiplayerScreen(new TitleScreen()));
        } else {
            client.setScreen(null);
            if (client.player != null) {
                client.player.sendMessage(Text.literal("[etmc] Added '" + name
                        + "' to Multiplayer. Leave your world, then connect there (or Direct Connect "
                        + address + ")."), false);
            }
        }
    }

    /**
     * Kicks off a vanilla connect for the {@code etmc://} link flow. The address is a placeholder —
     * {@code EtmcConnect} has a pending target and the ClientConnection mixin swaps in an EtmcChannel,
     * so nothing actually connects to this address. Must run on the client thread, with the EasyTier
     * instance already started and the target set.
     */
    public static void connectViaChannel(Screen parent, String label) {
        MinecraftClient client = MinecraftClient.getInstance();
        ServerInfo info = new ServerInfo("etmc: " + label, "127.0.0.1:25565", ServerInfo.ServerType.OTHER);
        ServerAddress addr = ServerAddress.parse("127.0.0.1:25565");
        ConnectScreen.connect(parent, client, addr, info, false, null);
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

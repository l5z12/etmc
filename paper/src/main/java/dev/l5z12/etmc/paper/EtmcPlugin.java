package dev.l5z12.etmc.paper;

import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.NativeLoader;
import dev.l5z12.etmc.ffi.Panama;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;

/**
 * Paper plugin: exposes this dedicated server over an EasyTier P2P mesh — no port-forwarding.
 *
 * <p>On enable it starts an EasyTier instance (no-TUN) on the configured network and binds a
 * userspace data-plane listener on the mesh, forwarding each connection to the local server port
 * ({@code 127.0.0.1:<server port>}). Players using the etmc client (or any EasyTier peer on the same
 * network) reach the server at its mesh address. The server itself needs no etmc client.
 */
public final class EtmcPlugin extends JavaPlugin {

    private EasyTier et;
    private EtmcSession session;
    private volatile boolean ready;
    private volatile String startError;
    private volatile JoinCode joinCode;
    private volatile String network = "";
    private volatile int virtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    private volatile List<String> relays = List.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginCommand cmd = getCommand("etmc");
        if (cmd != null) {
            EtmcCommand exec = new EtmcCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }

        // Native load + mesh bind can block briefly; keep it off the main thread.
        getServer().getScheduler().runTaskAsynchronously(this, this::startMesh);
    }

    private void startMesh() {
        try {
            if (!Panama.isAvailable()) {
                throw new IllegalStateException("java.lang.foreign (FFM) unavailable: " + Panama.initError());
            }
            reloadConfig();
            network = getConfig().getString("network", "etmc-server");
            String secret = getConfig().getString("secret", "");
            relays = getConfig().getStringList("relays");
            virtualPort = getConfig().getInt("virtual-port", EtmcConfig.DEFAULT_VIRTUAL_PORT);
            int serverPort = getServer().getPort();

            if (relays.isEmpty()) {
                throw new IllegalStateException("No relays configured. Set 'relays' in plugins/etmc/config.yml.");
            }

            Path cacheRoot = getDataFolder().toPath();
            NativeLoader.Native nat = NativeLoader.extract(cacheRoot);
            et = EasyTier.load(nat.path());
            session = new EtmcSession(et);

            joinCode = session.host(serverPort, network, secret, relays, virtualPort);

            ready = true;
            getLogger().info("Mesh up — network '" + network + "', reachable on the mesh at "
                    + EtmcConfig.HOST_VIRTUAL_IP + ":" + virtualPort + " (-> local :" + serverPort + ").");
            getLogger().info("Share this join code with players:");
            getLogger().info(joinCode.encode());
            getLogger().info("…or this link (paste into Add Server / Direct Connect):");
            getLogger().info(joinCode.encodeLink());
        } catch (Throwable t) {
            startError = String.valueOf(t.getMessage());
            getLogger().warning("etmc failed to start: " + startError);
        }
    }

    /** Restart the mesh with the current config.yml (called by /etmc reload). */
    public synchronized void restart() {
        try {
            if (session != null) session.leave();
        } catch (Throwable ignored) {
        }
        ready = false;
        startError = null;
        joinCode = null;
        getServer().getScheduler().runTaskAsynchronously(this, this::startMesh);
    }

    @Override
    public void onDisable() {
        try {
            if (session != null) session.leave();
        } catch (Throwable ignored) {
        }
    }

    public boolean isReady() {
        return ready;
    }

    public String startError() {
        return startError;
    }

    public EtmcSession session() {
        return session;
    }

    public JoinCode joinCode() {
        return joinCode;
    }

    public String network() {
        return network;
    }

    public int virtualPort() {
        return virtualPort;
    }

    public List<String> relays() {
        return relays;
    }
}

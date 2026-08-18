package dev.l5z12.etmc.paper;

import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.EtmcSession;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.ffi.EasyTier;
import dev.l5z12.etmc.ffi.NativeLoader;
import dev.l5z12.etmc.ffi.Panama;
import lombok.Getter;
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

    /**
     * Serializes mesh starts. Each one runs on its own Bukkit async worker, so two {@code /etmc
     * reload}s in quick succession would otherwise build two EasyTier instances under the same name
     * and strand whichever one lost the assignment to {@link #session}.
     */
    private final Object meshLock = new Object();

    // Written by startMesh() on an async worker, read by the command handlers on the main thread.
    @Getter private volatile EtmcSession session;
    private volatile boolean ready;
    /** Set by {@link #onDisable()} so a start still in flight tears itself back down. */
    private volatile boolean disabled;
    /** Why the mesh failed to come up, for {@code /etmc status}; null while it is still starting. */
    @Getter private volatile String startError;
    @Getter private volatile JoinCode joinCode;
    @Getter private volatile String network = "";
    @Getter private volatile int virtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    @Getter private volatile List<String> relays = List.of();

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

    /**
     * Brings the mesh up, replacing whatever was running. Always called on a Bukkit async worker —
     * the native load, the instance start and the mesh bind all block for seconds.
     */
    private void startMesh() {
        synchronized (meshLock) {
            stopMesh();
            startError = null;
            joinCode = null;
            if (disabled) return; // the plugin went away while this task was queued
            doStartMesh();
            // A disable that landed mid-start would have found nothing to tear down; undo it here.
            if (disabled) stopMesh();
        }
    }

    private void doStartMesh() {
        try {
            reloadConfig();
            network = getConfig().getString("network", "etmc-server");
            String secret = getConfig().getString("secret", "");
            // Frozen: this list is published to the command thread via relays().
            relays = List.copyOf(getConfig().getStringList("relays"));
            virtualPort = getConfig().getInt("virtual-port", EtmcConfig.DEFAULT_VIRTUAL_PORT);
            int serverPort = getServer().getPort();

            if (relays.isEmpty()) {
                throw new IllegalStateException("No relays configured. Set 'relays' in plugins/etmc/config.yml.");
            }

            Path cacheRoot = getDataFolder().toPath();
            NativeLoader.Native nat = NativeLoader.extract(cacheRoot);
            // FFM on Java 19+, JNA below it — EasyTier.load picks the backend and reports if neither fits.
            EasyTier et = EasyTier.load(nat.path());
            getLogger().info("EasyTier backend: " + (Panama.isAvailable() ? "FFM" : "JNA"));
            EtmcSession fresh = new EtmcSession(et);

            joinCode = fresh.host(serverPort, network, secret, relays, virtualPort);
            // Published only once it is actually hosting, so `ready` never leads a half-built session.
            session = fresh;

            ready = true;
            getLogger().info("Mesh up — network '" + network + "', reachable on the mesh at "
                    + EtmcConfig.HOST_VIRTUAL_IP + ":" + virtualPort + " (-> local :" + serverPort + ").");
            getLogger().info("Share this join code with players:");
            getLogger().info(joinCode.encode());
            getLogger().info("…or this link (paste into Add Server / Direct Connect):");
            getLogger().info(joinCode.encodeLink());
        } catch (Throwable t) {
            startError = Errors.message(t);
            getLogger().log(java.util.logging.Level.WARNING, "etmc failed to start: " + startError, t);
        }
    }

    /**
     * Restarts the mesh with the current config.yml (called by {@code /etmc reload}).
     *
     * <p>The teardown runs on the worker too, not here: {@link #startMesh()} holds {@link #meshLock}
     * for as long as a bind takes, and waiting for it on the command thread would stall the server.
     */
    public void restart() {
        getServer().getScheduler().runTaskAsynchronously(this, this::startMesh);
    }

    @Override
    public void onDisable() {
        // Best-effort and deliberately un-synchronized: a start may be holding meshLock for another
        // few seconds, and shutdown must not wait on it. `disabled` makes that start tear itself back
        // down when it finishes; leave() here handles the (normal) case where nothing is starting.
        disabled = true;
        stopMesh();
    }

    /** Tears the running instance down, if any. Idempotent. */
    private void stopMesh() {
        ready = false;
        try {
            EtmcSession s = session;
            if (s != null) s.leave();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ getters
    // The rest are generated (@Getter, fluent per lombok.config).

    /** Whether the mesh is up. Named {@code isReady} because it reads as a state, not a field. */
    public boolean isReady() { return ready; }
}

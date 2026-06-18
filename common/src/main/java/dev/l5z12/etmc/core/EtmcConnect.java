package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Hand-off point for the {@code etmc://} link flow: the loader's ConnectScreen mixin starts an
 * EasyTier instance, sets the pending {@link Target} here, then triggers Minecraft's normal connect.
 * The ClientConnection mixin consumes the target and swaps in an {@link EtmcChannel} so the
 * connection rides the EasyTier data plane directly — no loopback socket, no port.
 *
 * <p>Only one connection is being set up at a time (a single user action), so a single slot suffices.
 */
public final class EtmcConnect {

    /** Everything {@link EtmcChannel} needs to open a data-plane stream to the host. */
    public record Target(EasyTier et, String instanceName, String hostIp, int hostPort) {}

    private static final AtomicReference<Target> PENDING = new AtomicReference<>();

    /** Set by the client manager; invoked (with the closing channel's instance name) on disconnect. */
    private static volatile Consumer<String> onDisconnect;

    private EtmcConnect() {}

    public static void setPending(Target t) {
        PENDING.set(t);
    }

    /** Returns and clears the pending target (the ClientConnection mixin calls this). */
    public static Target takePending() {
        return PENDING.getAndSet(null);
    }

    /** Registers the teardown hook run when an {@link EtmcChannel} closes (disconnect / failure). */
    public static void setOnDisconnect(Consumer<String> cb) {
        onDisconnect = cb;
    }

    /**
     * Invoked by {@link EtmcChannel} when its data-plane stream closes. The argument is the channel's
     * EasyTier instance name so the manager can leave only if that instance is still the active one.
     */
    public static void fireDisconnect(String instanceName) {
        Consumer<String> cb = onDisconnect;
        if (cb != null) cb.accept(instanceName);
    }
}

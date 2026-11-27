package dev.l5z12.etmc.core;

import dev.l5z12.etmc.ffi.EasyTier;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Finds this machine's NAT type without being in a network. EasyTier's STUN check needs no peers, so
 * this starts an instance on a network of its own, reads the type the check reports (about five
 * seconds after it starts), and removes the instance again.
 */
public final class NatCheck {

    /**
     * How long a check waits. EasyTier tries again 10 seconds after a STUN check nobody answered, so
     * this leaves room for a second try.
     */
    public static final long TIMEOUT_MS = 20_000;
    private static final long POLL_MS = 250;

    private NatCheck() {}

    /**
     * Runs a check on an instance of its own.
     *
     * @return the type, or {@link NatType#UNKNOWN} if EasyTier found none within {@code timeoutMs}
     * @throws dev.l5z12.etmc.ffi.EasyTierException if the instance won't start
     */
    public static NatType run(EasyTier et, long timeoutMs) throws InterruptedException {
        return run(et, "etmc-nat-" + UUID.randomUUID().toString().substring(0, 8), timeoutMs);
    }

    static NatType run(EasyTier et, String inst, long timeoutMs) throws InterruptedException {
        et.runNetworkInstance(EtmcConfig.natCheckToml(inst));
        try {
            return await(() -> NetworkStatus.parse(et.collectNetworkInfos(16).get(inst)).nat(), timeoutMs);
        } finally {
            try {
                et.deleteNetworkInstance(inst);
            } catch (RuntimeException e) {
                EtmcLog.warn("couldn't remove the NAT check's instance " + inst, e);
            }
        }
    }

    /** Polls {@code nat} until it reports a type, or {@code timeoutMs} passes ({@link NatType#UNKNOWN}). */
    static NatType await(Supplier<NatType> nat, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            NatType t = nat.get();
            if (t.known()) return t;
            long left = deadline - System.nanoTime();
            if (left <= 0) return NatType.UNKNOWN;
            Thread.sleep(Math.min(POLL_MS, TimeUnit.NANOSECONDS.toMillis(left) + 1));
        }
    }
}

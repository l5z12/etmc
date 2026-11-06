package dev.l5z12.etmc.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Who gets a host's time. Anyone on the network can open connections, and on a public server that's
 * anyone at all. Each one costs a thread and some crypto before it's known to be a real player, and a
 * signature on top if the host proves its account, so they're rationed in two ways:
 * <ul>
 *   <li>each peer has a bucket of connections that refills slowly, roomy enough for a real join
 *       (one to ping, one to play) and for refreshing the server list;</li>
 *   <li>only so many connections may be getting started at once, whoever they're from, so several
 *       peers together can't pile up unfinished handshakes.</li>
 * </ul>
 * Connections already playing don't count: this is about arriving, not about staying.
 */
final class ConnectionLimiter {

    /** Connections a peer can open at once before it has to wait. */
    static final int BURST = 10;
    private static final long REFILL_MS = 1_000L;
    /** Connections allowed to be mid-start at a time. */
    static final int MAX_STARTING = 64;
    /** Past this many tracked peers, the ones that have refilled completely are forgotten. */
    private static final int MAX_TRACKED = 4096;

    private final LongSupplier clockMs;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private int starting;

    ConnectionLimiter() {
        this(System::currentTimeMillis);
    }

    ConnectionLimiter(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /** Whether to take this connection. If so, call {@link #started()} once it's past its handshake. */
    synchronized boolean admit(String peer) {
        if (starting >= MAX_STARTING) return false;
        long now = clockMs.getAsLong();
        if (buckets.size() >= MAX_TRACKED) buckets.values().removeIf(b -> b.level(now) >= BURST);
        Bucket bucket = buckets.computeIfAbsent(peer == null ? "?" : peer, p -> new Bucket(now));
        if (!bucket.take(now)) return false;
        starting++;
        return true;
    }

    synchronized void started() {
        if (starting > 0) starting--;
    }

    private static final class Bucket {
        private double tokens = BURST;
        private long updatedAt;

        Bucket(long now) {
            this.updatedAt = now;
        }

        double level(long now) {
            return Math.min(BURST, tokens + (double) (now - updatedAt) / REFILL_MS);
        }

        boolean take(long now) {
            tokens = level(now);
            updatedAt = now;
            if (tokens < 1) return false;
            tokens--;
            return true;
        }
    }
}

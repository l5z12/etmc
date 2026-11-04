package dev.l5z12.etmc.ffi;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * etmc's blocking stream API ({@link EasyTier#tcpBind}, {@code tcpAccept}, {@code tcpConnect},
 * {@code tcpRead}, {@code tcpWrite}, {@code tcpClose}) built on EasyTier's asynchronous data-plane
 * ABI v3 ({@link DataPlaneAbi}). Both backends share it, so the protocol is implemented once.
 *
 * <p>How the ABI is driven:
 * <ul>
 *   <li>One native <b>session</b> per EasyTier instance, opened on first use (the ABI allows one).
 *       A daemon dispatcher thread waits on the session's completion queue, drains it, and completes
 *       the {@link CompletableFuture} registered for each operation ID.</li>
 *   <li>A blocking call submits its operation, waits on that future, then takes (on success) or frees
 *       (on failure) the result. A completion can land before the caller registers its future; both
 *       sides use {@code computeIfAbsent}, so whichever comes first creates it.</li>
 *   <li>Reads and writes have no per-operation timeout in the ABI (only persistent per-stream
 *       deadlines), so their timeouts are enforced here: on expiry the operation is cancelled and the
 *       cancellation awaited — a read that completed in the meantime still hands back its data, so
 *       nothing is lost to the race.</li>
 *   <li>Stream/listener handles given to callers are etmc's own, mapped to (session, resource ID):
 *       resource IDs only mean something together with their session.</li>
 * </ul>
 *
 * <p>Failures throw {@link EasyTierException} carrying the {@link DataPlaneError} kind.
 */
final class DataPlane {

    private static final System.Logger LOG = System.getLogger("etmc.DataPlane");

    /** Completion-wait slice: bounds how long the dispatcher takes to notice its session closing. */
    private static final long WAIT_SLICE_MS = 1_000L;
    /** How long past an operation's own deadline we wait for its completion before abandoning it. */
    private static final long GRACE_MS = 5_000L;
    /** Opening a session right after {@code run_network_instance} can race the instance coming up. */
    private static final int OPEN_ATTEMPTS = 30;
    private static final long OPEN_RETRY_MS = 100L;

    private final DataPlaneAbi abi;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Resource> resources = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1);

    DataPlane(DataPlaneAbi abi) {
        this.abi = abi;
    }

    private record Resource(Session session, long id) {}

    // ------------------------------------------------------------------ blocking operations

    EasyTier.Bind tcpBind(String inst, int port, long timeoutMs) {
        Session s = session(inst);
        long op = abi.tcpBindSubmit(s.handle, port, timeoutMs);
        check(s, op, complete(s, op, timeoutMs + GRACE_MS), "couldn't listen on EasyTier port " + port);
        DataPlaneAbi.Opened r = abi.tcpBindResultTake(s.handle, op);
        return new EasyTier.Bind(register(s, r.resource()), r.localIp(), r.localPort());
    }

    /** Returns {@code null} when nobody connected within {@code timeoutMs}. */
    EasyTier.Accept tcpAccept(long listener, long timeoutMs) {
        Resource l = resource(listener, "listener");
        Session s = l.session;
        long op = abi.tcpAcceptSubmit(s.handle, l.id, timeoutMs);
        int status = complete(s, op, timeoutMs + GRACE_MS);
        if (status == DataPlaneError.DEADLINE_EXCEEDED.code()) {
            freeQuietly(s, op);
            return null;
        }
        check(s, op, status, "couldn't accept a connection");
        DataPlaneAbi.Opened r = abi.tcpAcceptResultTake(s.handle, op);
        return new EasyTier.Accept(register(s, r.resource()), r.localIp(), r.localPort(),
                r.peerIp(), r.peerPort());
    }

    EasyTier.Bind tcpConnect(String inst, String ip, int port, long timeoutMs) {
        byte[] ipv4 = parseIpv4(ip);
        Session s = session(inst);
        long op = abi.tcpConnectSubmit(s.handle, ipv4, port, timeoutMs);
        check(s, op, complete(s, op, timeoutMs + GRACE_MS), "couldn't connect to " + ip + ":" + port);
        DataPlaneAbi.Opened r = abi.tcpConnectResultTake(s.handle, op);
        return new EasyTier.Bind(register(s, r.resource()), r.localIp(), r.localPort());
    }

    /** Reads up to {@code len} bytes; returns the count, {@code 0} at end of stream. */
    int tcpRead(long stream, byte[] buf, int len, long timeoutMs) {
        if (len <= 0) throw new IllegalArgumentException("read length must be positive");
        Resource r = resource(stream, "stream");
        Session s = r.session;
        long op = abi.tcpReadSubmit(s.handle, r.id, len);
        check(s, op, complete(s, op, timeoutMs), "connection read failed");
        return abi.tcpReadResultTake(s.handle, op, buf, len);
    }

    /** Writes all {@code len} bytes; returns {@code len}. */
    int tcpWrite(long stream, byte[] data, int len, long timeoutMs) {
        Resource r = resource(stream, "stream");
        Session s = r.session;
        long op = abi.tcpWriteSubmit(s.handle, r.id, data, len);
        check(s, op, complete(s, op, timeoutMs), "connection write failed");
        return abi.tcpWriteResultTake(s.handle, op);
    }

    /** Closes a stream or listener handle; pending operations on it finish with HANDLE_CLOSED. */
    void close(long handle) {
        Resource r = resources.remove(handle);
        if (r == null || r.session.closed) return;
        try {
            abi.resourceClose(r.session.handle, r.id);
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.DEBUG, "data_plane_resource_close failed", t);
        }
    }

    /** Closes the sessions of instances about to be deleted, so their dispatchers stop cleanly. */
    void closeSessions(String... instNames) {
        for (String name : instNames) {
            Session s = sessions.remove(name);
            if (s == null) continue;
            s.closed = true;
            try {
                abi.sessionClose(s.handle);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.DEBUG, "data_plane_session_close failed", t);
            }
            s.ended();
        }
    }

    // ------------------------------------------------------------------ completion plumbing

    /**
     * Waits for {@code op} to finish and returns its status. If it doesn't finish in time it is
     * cancelled and the cancellation awaited; a cancelled-for-timeout operation reports
     * {@link DataPlaneError#DEADLINE_EXCEEDED}. An interrupt cancels it the same way and re-asserts
     * the interrupt flag (session teardown interrupts blocked pumps).
     */
    private int complete(Session s, long op, long waitMs) {
        CompletableFuture<Integer> f = s.future(op);
        boolean interrupted = false;
        try {
            Integer st;
            try {
                st = f.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                st = null; // futures here are only ever completed normally
            } catch (InterruptedException e) {
                interrupted = true;
                st = null;
            }
            if (st != null) return st;

            cancelQuietly(s, op);
            st = getUninterruptibly(f, GRACE_MS);
            if (st == null) {
                // The operation never reported back (session wedged); abandon it.
                freeQuietly(s, op);
                return (interrupted ? DataPlaneError.CANCELLED : DataPlaneError.DEADLINE_EXCEEDED).code();
            }
            if (st == DataPlaneError.CANCELLED.code() && !interrupted) {
                return DataPlaneError.DEADLINE_EXCEEDED.code();
            }
            return st;
        } finally {
            s.ops.remove(op);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Throws for a failed status, releasing the operation's result first. */
    private void check(Session s, long op, int status, String what) {
        if (status == 0) return;
        freeQuietly(s, op);
        DataPlaneError kind = DataPlaneError.of(status);
        throw new EasyTierException(what + ": " + kind.describe(), kind);
    }

    private static Integer getUninterruptibly(CompletableFuture<Integer> f, long waitMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        boolean interrupted = false;
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return f.getNow(null);
                try {
                    return f.get(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (TimeoutException e) {
                    return null;
                } catch (ExecutionException e) {
                    return null;
                }
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void cancelQuietly(Session s, long op) {
        try {
            abi.operationCancel(s.handle, op);
        } catch (Throwable ignored) {
            // the session is gone; its operations are gone with it
        }
    }

    private void freeQuietly(Session s, long op) {
        try {
            abi.operationFree(s.handle, op);
        } catch (Throwable ignored) {
            // already consumed, or the session is gone
        }
    }

    // ------------------------------------------------------------------ sessions + handles

    private Session session(String inst) {
        Session s = sessions.get(inst);
        if (s != null && !s.closed) return s;
        synchronized (this) {
            s = sessions.get(inst);
            if (s != null && !s.closed) return s;
            EasyTierException last = null;
            for (int i = 0; i < OPEN_ATTEMPTS; i++) {
                try {
                    s = new Session(inst, abi.sessionOpen(inst));
                    sessions.put(inst, s);
                    s.start();
                    return s;
                } catch (EasyTierException e) {
                    last = e;
                    // Only "not there yet" is worth waiting out; anything else won't fix itself.
                    if (!e.is(DataPlaneError.HANDLE_CLOSED) && !e.is(DataPlaneError.INSTANCE_STOPPED)) break;
                    if (!sleep(OPEN_RETRY_MS)) break;
                }
            }
            throw new EasyTierException("the EasyTier network isn't running (" + last.getMessage() + ")",
                    last.kind());
        }
    }

    private long register(Session s, long resourceId) {
        long h = nextHandle.getAndIncrement();
        resources.put(h, new Resource(s, resourceId));
        return h;
    }

    private Resource resource(long handle, String what) {
        Resource r = resources.get(handle);
        if (r == null || r.session.closed) {
            throw new EasyTierException("the " + what + " is closed", DataPlaneError.HANDLE_CLOSED);
        }
        return r;
    }

    private static byte[] parseIpv4(String ip) {
        String[] parts = ip == null ? new String[0] : ip.trim().split("\\.", -1);
        if (parts.length == 4) {
            byte[] out = new byte[4];
            try {
                for (int i = 0; i < 4; i++) {
                    int v = Integer.parseInt(parts[i]);
                    if (v < 0 || v > 255) throw new NumberFormatException();
                    out[i] = (byte) v;
                }
                return out;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        throw new EasyTierException("'" + ip + "' is not an IPv4 address",
                DataPlaneError.ADDRESS_FAMILY_UNSUPPORTED);
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** One native data-plane session plus the dispatcher that turns its completions into futures. */
    private final class Session {
        final String inst;
        final long handle;
        final Map<Long, CompletableFuture<Integer>> ops = new ConcurrentHashMap<>();
        volatile boolean closed;

        Session(String inst, long handle) {
            this.inst = inst;
            this.handle = handle;
        }

        CompletableFuture<Integer> future(long op) {
            CompletableFuture<Integer> f = ops.computeIfAbsent(op, k -> new CompletableFuture<>());
            if (closed) f.complete(DataPlaneError.INSTANCE_STOPPED.code());
            return f;
        }

        void start() {
            Thread t = new Thread(this::dispatch, "etmc-dataplane-" + inst);
            t.setDaemon(true);
            t.start();
        }

        private void dispatch() {
            long[] opIds = new long[DataPlaneAbi.DRAIN_BATCH];
            int[] statuses = new int[DataPlaneAbi.DRAIN_BATCH];
            try {
                while (!closed) {
                    long t0 = System.nanoTime();
                    if (abi.completionWait(handle, WAIT_SLICE_MS) != 1) {
                        // 0 straight away means the instance is stopping: don't spin while it does.
                        if (System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(5)) sleep(50);
                        continue;
                    }
                    int n;
                    do {
                        n = abi.completionDrain(handle, opIds, statuses);
                        for (int i = 0; i < n; i++) {
                            future(opIds[i]).complete(statuses[i]);
                        }
                    } while (n == opIds.length);
                }
            } catch (Throwable t) {
                if (!closed) {
                    LOG.log(System.Logger.Level.DEBUG, "data-plane session for " + inst + " ended", t);
                }
            } finally {
                closed = true;
                sessions.remove(inst, this);
                ended();
            }
        }

        /** Fails every outstanding operation and drops this session's handles. */
        void ended() {
            for (CompletableFuture<Integer> f : ops.values()) {
                f.complete(DataPlaneError.INSTANCE_STOPPED.code());
            }
            resources.values().removeIf(r -> r.session == this);
        }
    }
}

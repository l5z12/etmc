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
    /** How long a bind/connect waits out a data-plane runtime that is still starting. */
    private static final long STARTUP_WAIT_MS = 10_000L;

    private final DataPlaneAbi abi;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<Long, Resource> resources = new ConcurrentHashMap<>();
    private final AtomicLong nextHandle = new AtomicLong(1);

    DataPlane(DataPlaneAbi abi) {
        this.abi = abi;
    }

    /** A stream or listener: its session and resource ID, plus a stream's read carry-over. */
    private static final class Resource {
        final Session session;
        final long id;
        /** A read that outlived its caller's timeout, left running for the next read to collect. */
        long pendingRead;
        int pendingReadLen;
        /** Bytes a collected read returned beyond what that caller asked for. */
        byte[] leftover;
        int leftoverPos;
        int leftoverEnd;

        Resource(Session session, long id) {
            this.session = session;
            this.id = id;
        }
    }

    // ------------------------------------------------------------------ blocking operations

    EasyTier.Bind tcpBind(String inst, int port, long timeoutMs) {
        Session s = session(inst);
        long op = whileStarting(() -> abi.tcpBindSubmit(s.handle, port, timeoutMs));
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
        long op = whileStarting(() -> abi.tcpConnectSubmit(s.handle, ipv4, port, timeoutMs));
        check(s, op, complete(s, op, timeoutMs + GRACE_MS), "couldn't connect to " + ip + ":" + port);
        DataPlaneAbi.Opened r = abi.tcpConnectResultTake(s.handle, op);
        return new EasyTier.Bind(register(s, r.resource()), r.localIp(), r.localPort());
    }

    /**
     * Reads up to {@code len} bytes; returns the count, {@code 0} at end of stream.
     *
     * <p>A read that times out is <b>not</b> cancelled: cancelling a read in the library doesn't stop
     * it, so the bytes it goes on to receive are thrown away with the cancelled result (found by
     * NativeSmokeTest). Instead it stays pending and the next read on this stream collects it; bytes
     * beyond what that caller asked for are kept for the read after. One reader per stream, as both
     * pumps and the channel use it.
     */
    int tcpRead(long stream, byte[] buf, int len, long timeoutMs) {
        if (len <= 0) throw new IllegalArgumentException("read length must be positive");
        Resource r = resource(stream, "stream");
        synchronized (r) {
            if (r.leftover != null) return drainLeftover(r, buf, len);
            Session s = r.session;
            long op;
            int opLen;
            if (r.pendingRead != 0) {
                op = r.pendingRead;
                opLen = r.pendingReadLen;
            } else {
                op = abi.tcpReadSubmit(s.handle, r.id, len);
                opLen = len;
            }
            Integer status = awaitKeepingPending(s, op, timeoutMs);
            if (status == null) {
                r.pendingRead = op;
                r.pendingReadLen = opLen;
                throw new EasyTierException("connection read failed: " + DataPlaneError.DEADLINE_EXCEEDED.describe(),
                        DataPlaneError.DEADLINE_EXCEEDED);
            }
            r.pendingRead = 0;
            check(s, op, status, "connection read failed");
            if (opLen <= len) return abi.tcpReadResultTake(s.handle, op, buf, len);
            // The collected read was submitted for more than this caller wants: keep the surplus.
            byte[] all = new byte[opLen];
            int n = abi.tcpReadResultTake(s.handle, op, all, opLen);
            if (n <= len) {
                System.arraycopy(all, 0, buf, 0, n);
                return n;
            }
            r.leftover = all;
            r.leftoverPos = 0;
            r.leftoverEnd = n;
            return drainLeftover(r, buf, len);
        }
    }

    private static int drainLeftover(Resource r, byte[] buf, int len) {
        int n = Math.min(len, r.leftoverEnd - r.leftoverPos);
        System.arraycopy(r.leftover, r.leftoverPos, buf, 0, n);
        r.leftoverPos += n;
        if (r.leftoverPos >= r.leftoverEnd) {
            r.leftover = null;
            r.leftoverEnd = 0;
        }
        return n;
    }

    /**
     * Waits up to {@code waitMs} for {@code op}; {@code null} if it is still running (it is left
     * running, and its future registered, for a later call to collect). An interrupt leaves it the
     * same way and reports CANCELLED.
     */
    private Integer awaitKeepingPending(Session s, long op, long waitMs) {
        CompletableFuture<Integer> f = s.future(op);
        try {
            Integer st = f.get(waitMs, TimeUnit.MILLISECONDS);
            s.ops.remove(op);
            return st;
        } catch (TimeoutException | ExecutionException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EasyTierException("connection read failed: " + DataPlaneError.CANCELLED.describe(),
                    DataPlaneError.CANCELLED);
        }
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

    /**
     * Submits, retrying while the instance's data-plane runtime is still starting. The runtime comes
     * up asynchronously after {@code run_network_instance} returns, and a submit in that window fails
     * with INSTANCE_STOPPED "data-plane runtime is not running" — the same kind a stopped instance
     * reports, so the retry is time-bounded rather than open-ended.
     */
    private static long whileStarting(java.util.function.LongSupplier submit) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_WAIT_MS);
        while (true) {
            try {
                return submit.getAsLong();
            } catch (EasyTierException e) {
                boolean starting = e.is(DataPlaneError.INSTANCE_STOPPED)
                        && String.valueOf(e.getMessage()).contains("not running");
                if (!starting || System.nanoTime() > deadline || !sleep(OPEN_RETRY_MS)) throw e;
            }
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

package dev.l5z12.etmc.core;

/**
 * Thread helpers for the etmc I/O paths.
 *
 * <p>Every thread etmc starts must be a <b>daemon</b>: the EasyTier data-plane calls block for as
 * long as their timeout allows, so a non-daemon pump left behind by a torn-down session would keep
 * the whole game (or server) JVM from exiting.
 */
final class Threads {

    private Threads() {}

    /** Creates an unstarted daemon thread. */
    static Thread daemon(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    /** Creates and starts a daemon thread. */
    static Thread start(String name, Runnable body) {
        Thread t = daemon(name, body);
        t.start();
        return t;
    }
}

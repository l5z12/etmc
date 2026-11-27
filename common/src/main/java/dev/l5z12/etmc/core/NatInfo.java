package dev.l5z12.etmc.core;

/**
 * What EasyTier's STUN check found out about this machine's NATs ({@code StunInfo}).
 *
 * @param udp     the UDP NAT type: the one that decides direct connections, since EasyTier punches
 *                through NATs over UDP
 * @param tcp     the TCP NAT type, which EasyTier checks too
 * @param minPort the lowest public port the STUN servers saw, 0 if none
 * @param maxPort the highest; for a cone NAT it's the same port, for a symmetric one the spread shows
 *                how far apart its ports land
 */
public record NatInfo(NatType udp, NatType tcp, int minPort, int maxPort) {

    public static final NatInfo UNKNOWN = new NatInfo(NatType.UNKNOWN, NatType.UNKNOWN, 0, 0);

    public NatInfo {
        if (udp == null) udp = NatType.UNKNOWN;
        if (tcp == null) tcp = NatType.UNKNOWN;
    }

    /** Only a UDP type, as in tests. */
    public NatInfo(NatType udp) {
        this(udp, NatType.UNKNOWN, 0, 0);
    }

    public boolean known() {
        return udp.known();
    }

    public NatType.Grade grade() {
        return udp.grade();
    }

    /** Whether EasyTier won't even try to connect directly to a peer whose UDP NAT type is {@code other}. */
    public boolean blocksDirect(NatType other) {
        return udp.blocksDirect(other);
    }

    /** The public ports the STUN servers saw, such as "27623" or "40100–40112"; {@code null} if none. */
    public String ports() {
        if (minPort <= 0 && maxPort <= 0) return null;
        if (minPort <= 0 || maxPort <= minPort) return String.valueOf(Math.max(minPort, maxPort));
        return minPort + "–" + maxPort;
    }
}

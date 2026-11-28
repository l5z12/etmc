package dev.l5z12.etmc.core;

import java.util.List;

/**
 * What EasyTier's STUN check found out about this machine's NATs ({@code StunInfo}).
 *
 * @param udp       the UDP NAT type: the one that decides direct connections, since EasyTier punches
 *                  through NATs over UDP
 * @param tcp       the TCP NAT type, which EasyTier checks too
 * @param minPort   the lowest public port the STUN servers saw, 0 if none
 * @param maxPort   the highest; for a cone NAT it's the same port, for a symmetric one the spread shows
 *                  how far apart its ports land
 * @param publicIps the public addresses the STUN servers saw, IPv4 and IPv6; more than one IPv4 means
 *                  the NAT sends each connection out from an address of a pool
 */
public record NatInfo(NatType udp, NatType tcp, int minPort, int maxPort, List<String> publicIps) {

    public static final NatInfo UNKNOWN = new NatInfo(NatType.UNKNOWN, NatType.UNKNOWN, 0, 0, List.of());

    public NatInfo {
        if (udp == null) udp = NatType.UNKNOWN;
        if (tcp == null) tcp = NatType.UNKNOWN;
        publicIps = publicIps == null ? List.of() : List.copyOf(publicIps);
    }

    /** No public addresses known. */
    public NatInfo(NatType udp, NatType tcp, int minPort, int maxPort) {
        this(udp, tcp, minPort, maxPort, List.of());
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

    /** The public IPv4 addresses, comma-separated; {@code null} if none. */
    public String ipv4() {
        return join(false);
    }

    /** The public IPv6 addresses, comma-separated; {@code null} if none was found. */
    public String ipv6() {
        return join(true);
    }

    private String join(boolean v6) {
        List<String> ips = publicIps.stream().filter(ip -> ip.contains(":") == v6).toList();
        return ips.isEmpty() ? null : String.join(", ", ips);
    }
}

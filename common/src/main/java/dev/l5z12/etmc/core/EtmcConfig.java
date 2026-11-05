package dev.l5z12.etmc.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds EasyTier TOML configuration strings for etmc's two roles.
 *
 * <p>etmc runs EasyTier in <b>no-TUN</b> mode ({@code flags.no_tun = true}) and reaches peers
 * purely through the userspace data plane, so no virtual network adapter, driver, or admin rights
 * are needed. The host takes a fixed virtual IP ({@link #HOST_VIRTUAL_IP}) so joiners always know
 * where to connect without decoding the runtime DHCP assignment; joiners use DHCP.
 */
public final class EtmcConfig {

    /** Virtual network the mesh uses internally (data-plane addressing only; never touches the host OS). */
    private static final String NETWORK_CIDR_BASE = "10.126.126";
    /** Fixed address the host takes, so joiners know where to connect without decoding DHCP. */
    public static final String HOST_VIRTUAL_IP = NETWORK_CIDR_BASE + ".1";
    /** Virtual port the host's data-plane listener binds; joiners connect here. */
    public static final int DEFAULT_VIRTUAL_PORT = 25565;

    /**
     * What a host listens on for direct connections from joiners: TCP and UDP on random free ports
     * (EasyTier binds port 0 to a free port, and IPv6 alongside, and advertises the real ports to
     * peers). Without listeners a joiner could only reach the host through the relay or by hole
     * punching, so joiners on the same LAN, or a host with a public address, still went through
     * the relay. Opening the world to LAN already listens on every interface from this same
     * process, so this asks for no new firewall permission. Joiners keep none (see build).
     */
    static final String HOST_LISTENERS = "[\"tcp://0.0.0.0:0\", \"udp://0.0.0.0:0\"]";

    private EtmcConfig() {}

    public static String hostToml(String instName, String networkName, String secret, List<String> relays) {
        return hostToml(instName, instName, networkName, secret, relays);
    }

    /** @param hostname the device name peers see (the instance name is internal and unique per session) */
    public static String hostToml(String instName, String hostname, String networkName, String secret,
                                  List<String> relays) {
        return build(instName, hostname, networkName, secret, relays, HOST_VIRTUAL_IP, false, HOST_LISTENERS);
    }

    public static String joinToml(String instName, String networkName, String secret, List<String> relays) {
        return joinToml(instName, instName, networkName, secret, relays);
    }

    public static String joinToml(String instName, String hostname, String networkName, String secret,
                                  List<String> relays) {
        return build(instName, hostname, networkName, secret, relays, null, true, "[]");
    }

    private static String build(String instName, String hostname, String networkName, String secret,
                                List<String> relays, String staticIpv4, boolean dhcp, String listeners) {
        StringBuilder sb = new StringBuilder();
        sb.append("instance_name = ").append(Toml.quote(instName)).append('\n');
        String device = hostname == null || hostname.isBlank() ? instName : hostname;
        sb.append("hostname = ").append(Toml.quote(device)).append('\n');
        if (dhcp) {
            sb.append("dhcp = true\n");
        } else {
            sb.append("ipv4 = ").append(Toml.quote(staticIpv4)).append('\n');
        }
        // A joiner listens on nothing: it only ever dials out (to the relay and the host), so joining
        // never needs a firewall exception. The host listens; see HOST_LISTENERS.
        sb.append("listeners = ").append(listeners).append('\n');
        sb.append('\n');

        sb.append("[network_identity]\n");
        sb.append("network_name = ").append(Toml.quote(networkName)).append('\n');
        sb.append("network_secret = ").append(Toml.quote(secret)).append('\n');
        sb.append('\n');

        // Deduplicated: the same URI listed twice would have EasyTier dial the relay twice.
        Set<String> seen = new LinkedHashSet<>();
        if (relays != null) {
            for (String relay : relays) {
                String r = relay == null ? "" : relay.trim();
                if (!r.isEmpty()) seen.add(r);
            }
            for (String r : seen) {
                sb.append("[[peer]]\n");
                sb.append("uri = ").append(Toml.quote(r)).append('\n');
            }
            sb.append('\n');
        }

        sb.append("[flags]\n");
        sb.append("no_tun = true\n");
        // EasyTier binds its outgoing sockets to the physical network interfaces, and a socket bound
        // there can't reach this machine's loopback address (Windows refuses every such dial with
        // WSAEADDRNOTAVAIL). So a relay running on the player's own machine was unreachable.
        if (seen.stream().anyMatch(EtmcConfig::isLoopback)) sb.append("bind_device = false\n");
        return sb.toString();
    }

    /** Whether a relay URI points at this machine ({@code 127.x}, {@code localhost}, {@code ::1}). */
    static boolean isLoopback(String uri) {
        String host;
        try {
            host = java.net.URI.create(uri).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return host.equals("localhost") || host.startsWith("127.") || host.equals("::1");
    }
}

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

    private EtmcConfig() {}

    public static String hostToml(String instName, String networkName, String secret, List<String> relays) {
        return build(instName, networkName, secret, relays, HOST_VIRTUAL_IP, false);
    }

    public static String joinToml(String instName, String networkName, String secret, List<String> relays) {
        return build(instName, networkName, secret, relays, null, true);
    }

    private static String build(String instName, String networkName, String secret,
                                List<String> relays, String staticIpv4, boolean dhcp) {
        StringBuilder sb = new StringBuilder();
        sb.append("instance_name = ").append(Toml.quote(instName)).append('\n');
        sb.append("hostname = ").append(Toml.quote(instName)).append('\n');
        if (dhcp) {
            sb.append("dhcp = true\n");
        } else {
            sb.append("ipv4 = ").append(Toml.quote(staticIpv4)).append('\n');
        }
        // No inbound listeners: we are a pure client of the user-provided relay and rely on the
        // relay (plus hole punching coordinated through it) for connectivity.
        sb.append("listeners = []\n");
        sb.append('\n');

        sb.append("[network_identity]\n");
        sb.append("network_name = ").append(Toml.quote(networkName)).append('\n');
        sb.append("network_secret = ").append(Toml.quote(secret)).append('\n');
        sb.append('\n');

        if (relays != null) {
            // Deduplicated: the same URI listed twice would have EasyTier dial the relay twice.
            Set<String> seen = new LinkedHashSet<>();
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
        return sb.toString();
    }
}

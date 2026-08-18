package dev.l5z12.etmc.core;

/**
 * An {@code ip:port} pair on the EasyTier mesh — where a Minecraft server is reachable once the
 * network is up. Parsed from the {@code [etmc] server = "…"} config extension and from the
 * user-supplied override on the "connect via config URL" screen.
 */
public record MeshAddress(String ip, int port) {

    /**
     * Parses {@code ip[:port]}, falling back to {@code defaultPort} when the port is absent or
     * unusable. A value with several colons is treated as a bare IPv6 literal and left whole, since
     * splitting on the last colon would corrupt it.
     *
     * @return the parsed address, or {@code null} if {@code s} carries no host at all
     */
    public static MeshAddress parse(String s, int defaultPort) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        int colon = t.lastIndexOf(':');
        if (colon > 0 && t.indexOf(':') == colon) {
            String host = t.substring(0, colon).trim();
            if (!host.isEmpty()) return new MeshAddress(host, parsePort(t.substring(colon + 1), defaultPort));
        }
        return new MeshAddress(t, defaultPort);
    }

    private static int parsePort(String s, int defaultPort) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p > 0 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
            // not a number — fall through to the default
        }
        return defaultPort;
    }

    @Override
    public String toString() {
        return ip + ":" + port;
    }
}

package dev.l5z12.etmc.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parsing and checking of relay (EasyTier peer) URIs, shared by every place a player can type one —
 * the Settings screen, {@code /etmc relay add}, and the link generator — so they all accept the same
 * separators and catch the same mistakes before EasyTier silently fails to dial them.
 *
 * <p>The classic mistake this exists for: pasting an {@code etmc://} join link (or another relay's
 * whole link) where a relay address goes. EasyTier can't dial that, so the joiner never reaches the
 * host and the join eventually fails with "data plane net is not ready".
 */
public final class Relays {

    /** Schemes EasyTier dials as peers. */
    private static final Set<String> SCHEMES =
            Set.of("tcp", "udp", "ws", "wss", "wg", "quic", "faketcp", "txt", "srv", "http", "https");
    /** Schemes whose URI must carry an explicit port. */
    private static final Set<String> NEEDS_PORT = Set.of("tcp", "udp", "wg", "quic", "faketcp");

    private Relays() {}

    /**
     * Splits relay text on commas, semicolons, whitespace or newlines — every separator a player might
     * use — trimming and dropping blanks and repeats, in order.
     */
    public static List<String> parse(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text != null) {
            for (String part : text.split("[\\s,;]+")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    /** What is wrong with {@code uri} as a relay address, or {@code null} if it looks dialable. */
    public static String problem(String uri) {
        String u = uri == null ? "" : uri.trim();
        if (u.isEmpty()) return "empty relay address";
        if (JoinCode.isLink(u) || u.startsWith(JoinCode.PREFIX)) {
            return "'" + abbreviate(u) + "' is an etmc join link, not a relay. A relay looks like "
                    + "tcp://relay.example.com:11010";
        }
        int sep = u.indexOf("://");
        if (sep <= 0) {
            return "'" + abbreviate(u) + "' has no protocol. Write it like tcp://" + u
                    + (u.contains(":") ? "" : ":11010");
        }
        String scheme = u.substring(0, sep).toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) {
            return "'" + scheme + "://' isn't a relay protocol EasyTier knows (use tcp://, udp://, ws://, "
                    + "wss://, quic:// or wg://)";
        }
        URI parsed;
        try {
            parsed = new URI(u);
        } catch (Exception e) {
            return "'" + abbreviate(u) + "' isn't a valid address";
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) return "'" + abbreviate(u) + "' has no host name";
        int port = parsed.getPort();
        if (port == -1 && NEEDS_PORT.contains(scheme)) {
            return "'" + abbreviate(u) + "' has no port (EasyTier relays usually listen on 11010)";
        }
        if (port == 0 || port > 65535) return "'" + abbreviate(u) + "' has an invalid port";
        return null;
    }

    /** The first problem among {@code relays}, or {@code null} if every entry looks dialable. */
    public static String firstProblem(List<String> relays) {
        if (relays == null) return null;
        for (String r : relays) {
            String p = problem(r);
            if (p != null) return p;
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 48 ? s : s.substring(0, 45) + "...";
    }
}

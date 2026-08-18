package dev.l5z12.etmc.core;

import java.util.Locale;

/**
 * Minimal, line-oriented TOML helpers shared by the config writers ({@link EtmcConfig}) and the
 * line-based readers ({@link JoinCode#fromToml}, {@link ImportedConfig#parse}).
 *
 * <p>This is deliberately <em>not</em> a TOML parser: etmc only ever needs to read a handful of
 * scalar keys out of an EasyTier config while preserving every other line verbatim, and to emit
 * correctly escaped strings. A real parser would round-trip badly (losing comments and ordering)
 * and pull in a dependency that has to exist on every Minecraft version back to 1.14.4.
 */
public final class Toml {

    private Toml() {}

    /** Splits a config into lines, normalizing CRLF/CR and keeping trailing empties. */
    static String[] lines(String raw) {
        return raw.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
    }

    /**
     * Renders a TOML basic string (surrounding quotes included) with the necessary escaping. Also
     * valid for a double-quoted YAML scalar, whose escapes are a superset — the Paper plugin emits
     * its {@code config.yml} sample with it.
     */
    public static String quote(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * The table name of a header line, lower-cased and stripped of brackets, so {@code [[peer]]} and
     * {@code [network_identity]  # note} both read as their bare name.
     */
    static String tableName(String trimmedLine) {
        String s = trimmedLine;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        s = s.trim();
        while (s.startsWith("[")) s = s.substring(1);
        while (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** The lower-cased key of a {@code key = value} line, or {@code ""} for anything else. */
    static String keyName(String trimmedLine) {
        if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) return "";
        int eq = trimmedLine.indexOf('=');
        if (eq < 0) return "";
        return trimmedLine.substring(0, eq).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The value of a {@code key = value} line as a plain string: quotes stripped (without
     * un-escaping — etmc only reads names, URIs and addresses), trailing comments dropped.
     */
    static String stringValue(String trimmedLine) {
        int eq = trimmedLine.indexOf('=');
        if (eq < 0) return "";
        String v = trimmedLine.substring(eq + 1).trim();
        if (v.startsWith("\"") || v.startsWith("'")) {
            char quote = v.charAt(0);
            int end = v.indexOf(quote, 1);
            return end > 0 ? v.substring(1, end) : v.substring(1);
        }
        int hash = v.indexOf('#');
        if (hash >= 0) v = v.substring(0, hash).trim();
        return v;
    }
}

package dev.l5z12.etmc.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A Minecraft-server-over-EasyTier descriptor parsed from an imported EasyTier TOML config (for
 * example fetched from an HTTP(S) URL a server admin publishes).
 *
 * <p>The raw config supplies the mesh identity, relay(s) and local addressing. etmc layers two
 * things on top, both handled here so the import "just works" in-game:
 * <ul>
 *   <li>forces {@code [flags] no_tun = true} (etmc never uses a TUN device);</li>
 *   <li>sets a unique {@code instance_name} so the data-plane functions can find the instance.</li>
 * </ul>
 *
 * <p>The Minecraft server's mesh address comes from an optional etmc extension table:
 * <pre>
 *   [etmc]
 *   server = "10.144.144.1:25565"
 *   label  = "My SMP"
 * </pre>
 * The {@code [etmc]} table is stripped before the config reaches EasyTier. If it is absent the
 * caller must supply the server address out-of-band.
 */
public final class ImportedConfig {

    public final String toml;
    public final String instanceName;
    public final String serverIp;
    public final int serverPort;
    public final String label;

    private ImportedConfig(String toml, String instanceName, String serverIp, int serverPort, String label) {
        this.toml = toml;
        this.instanceName = instanceName;
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.label = label;
    }

    /**
     * Parses and normalizes a raw EasyTier TOML config.
     *
     * @param raw            the fetched config text
     * @param instanceName   the unique instance name to force
     * @param overrideServer optional {@code ip:port} used when the config has no {@code [etmc] server}
     * @throws IllegalArgumentException if no server address can be determined
     */
    public static ImportedConfig parse(String raw, String instanceName, String overrideServer) {
        String[] lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        List<String> kept = new ArrayList<>();
        String table = "";
        boolean inEtmc = false;
        boolean hasFlags = false;
        int flagsHeaderIdx = -1;
        String serverFromCfg = null;
        String labelFromCfg = null;

        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("[")) {
                String name = tableName(t);
                if ("etmc".equals(name)) {
                    inEtmc = true;
                    table = "etmc";
                    continue; // drop the [etmc] header
                }
                inEtmc = false;
                table = name;
                kept.add(line);
                if ("flags".equals(name)) {
                    hasFlags = true;
                    flagsHeaderIdx = kept.size() - 1;
                }
                continue;
            }
            if (inEtmc) {
                String key = keyName(t);
                if ("server".equals(key)) serverFromCfg = stringValue(t);
                else if ("label".equals(key)) labelFromCfg = stringValue(t);
                continue; // drop [etmc] body
            }
            String key = keyName(t);
            if (table.isEmpty() && "instance_name".equals(key)) continue; // re-added below
            if ("flags".equals(table) && "no_tun".equals(key)) continue;  // re-added below
            kept.add(line);
        }

        if (hasFlags) {
            kept.add(flagsHeaderIdx + 1, "no_tun = true");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("instance_name = \"").append(escape(instanceName)).append("\"\n");
        for (String l : kept) sb.append(l).append('\n');
        if (!hasFlags) sb.append("\n[flags]\nno_tun = true\n");

        String server = (serverFromCfg != null && !serverFromCfg.isBlank()) ? serverFromCfg : overrideServer;
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException(
                    "No server address. Add `[etmc]\\nserver = \"ip:port\"` to the config or fill the Server field.");
        }
        String ip;
        int port;
        String s = server.trim();
        int colon = s.lastIndexOf(':');
        // Only split host:port for IPv4/hostnames (a single colon). Leave bare IPv6 untouched.
        if (colon > 0 && s.indexOf(':') == colon) {
            ip = s.substring(0, colon).trim();
            port = parsePort(s.substring(colon + 1).trim());
        } else {
            ip = s;
            port = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        }

        String label = labelFromCfg != null && !labelFromCfg.isBlank() ? labelFromCfg : ip + ":" + port;
        return new ImportedConfig(sb.toString(), instanceName, ip, port, label);
    }

    private static String tableName(String t) {
        String s = t;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        s = s.trim();
        while (s.startsWith("[")) s = s.substring(1);
        while (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        return s.trim().toLowerCase();
    }

    private static String keyName(String t) {
        if (t.isEmpty() || t.startsWith("#")) return "";
        int eq = t.indexOf('=');
        if (eq < 0) return "";
        return t.substring(0, eq).trim().toLowerCase();
    }

    private static String stringValue(String t) {
        int eq = t.indexOf('=');
        if (eq < 0) return "";
        String v = t.substring(eq + 1).trim();
        if (v.startsWith("\"")) {
            int end = v.indexOf('"', 1);
            if (end > 0) return v.substring(1, end);
            return v.substring(1);
        }
        if (v.startsWith("'")) {
            int end = v.indexOf('\'', 1);
            if (end > 0) return v.substring(1, end);
            return v.substring(1);
        }
        int hash = v.indexOf('#');
        if (hash >= 0) v = v.substring(0, hash).trim();
        return v;
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s.trim());
            if (p > 0 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
        }
        return EtmcConfig.DEFAULT_VIRTUAL_PORT;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

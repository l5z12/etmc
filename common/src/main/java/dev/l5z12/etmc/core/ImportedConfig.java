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
 *
 * @param toml         the normalized config, ready for {@code run_network_instance}
 * @param instanceName the unique instance name forced into it
 * @param serverIp     the Minecraft server's address on the mesh
 * @param serverPort   the Minecraft server's port on the mesh
 * @param label        what to call this server in the UI; never blank
 */
public record ImportedConfig(String toml, String instanceName, String serverIp, int serverPort, String label) {

    /**
     * Parses and normalizes a raw EasyTier TOML config.
     *
     * @param raw            the fetched config text
     * @param instanceName   the unique instance name to force
     * @param overrideServer optional {@code ip:port} used when the config has no {@code [etmc] server}
     * @throws IllegalArgumentException if the config is empty or no server address can be determined
     */
    public static ImportedConfig parse(String raw, String instanceName, String overrideServer) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Empty config.");
        }
        List<String> kept = new ArrayList<>();
        String table = "";
        int flagsHeaderIdx = -1;
        String serverFromCfg = null;
        String labelFromCfg = null;

        for (String line : Toml.lines(raw)) {
            String t = line.trim();
            if (t.startsWith("[")) {
                table = Toml.tableName(t);
                if ("etmc".equals(table)) continue; // drop the [etmc] header
                kept.add(line);
                if ("flags".equals(table)) flagsHeaderIdx = kept.size() - 1;
                continue;
            }
            String key = Toml.keyName(t);
            if ("etmc".equals(table)) {
                // drop the [etmc] body, keeping what it tells us about the Minecraft server
                if ("server".equals(key)) serverFromCfg = Toml.stringValue(t);
                else if ("label".equals(key)) labelFromCfg = Toml.stringValue(t);
                continue;
            }
            if (table.isEmpty() && "instance_name".equals(key)) continue; // re-added below
            if ("flags".equals(table) && "no_tun".equals(key)) continue;  // re-added below
            kept.add(line);
        }

        boolean hasFlags = flagsHeaderIdx >= 0;
        if (hasFlags) {
            kept.add(flagsHeaderIdx + 1, "no_tun = true");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("instance_name = ").append(Toml.quote(instanceName)).append('\n');
        for (String l : kept) sb.append(l).append('\n');
        if (!hasFlags) sb.append("\n[flags]\nno_tun = true\n");

        String server = (serverFromCfg != null && !serverFromCfg.isBlank()) ? serverFromCfg : overrideServer;
        MeshAddress addr = MeshAddress.parse(server, EtmcConfig.DEFAULT_VIRTUAL_PORT);
        if (addr == null) {
            throw new IllegalArgumentException(
                    "No server address. Add `[etmc]\\nserver = \"ip:port\"` to the config or fill the Server field.");
        }

        String label = labelFromCfg != null && !labelFromCfg.isBlank() ? labelFromCfg : addr.toString();
        return new ImportedConfig(sb.toString(), instanceName, addr.ip(), addr.port(), label);
    }
}

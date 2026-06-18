package dev.l5z12.etmc.paper;

import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.NetworkStatus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.stream.Stream;

/** {@code /etmc <status|code|config|reload>} — admin controls for the mesh. */
public final class EtmcCommand implements CommandExecutor, TabCompleter {

    private final EtmcPlugin plugin;

    public EtmcCommand(EtmcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "status" -> status(sender);
            case "code" -> code(sender);
            case "config" -> config(sender);
            case "reload" -> {
                sender.sendMessage("[etmc] Restarting mesh from config.yml…");
                plugin.restart();
            }
            default -> sender.sendMessage("[etmc] Usage: /etmc <status|code|config|reload>");
        }
        return true;
    }

    private void status(CommandSender sender) {
        if (!plugin.isReady()) {
            String err = plugin.startError();
            sender.sendMessage(err != null ? "[etmc] Not running: " + err : "[etmc] Starting…");
            return;
        }
        NetworkStatus st = plugin.session().status();
        sender.sendMessage("[etmc] Mesh up — network '" + plugin.network() + "'");
        sender.sendMessage("  mesh address: " + EtmcConfig.HOST_VIRTUAL_IP + ":" + plugin.virtualPort());
        sender.sendMessage("  virtual ip: " + (st.virtualIp() == null ? "(assigning)" : st.virtualIp())
                + "   peers: " + st.peerCount() + " (P2P " + st.directPeerCount() + ")"
                + "   connections: " + plugin.session().activeConnections());
        for (NetworkStatus.Peer p : st.peers()) {
            sender.sendMessage("   - " + p.hostname() + " " + (p.ipv4() == null ? "" : p.ipv4())
                    + (p.latencyMs() >= 0 ? " " + p.latencyMs() + "ms" : "")
                    + (p.relay() ? " (relay)" : ""));
        }
    }

    private void code(CommandSender sender) {
        JoinCode jc = plugin.joinCode();
        if (jc == null) {
            sender.sendMessage("[etmc] No join code yet (mesh not started).");
            return;
        }
        sender.sendMessage("[etmc] Join code (players paste in 'Join with a code'):");
        sender.sendMessage(jc.encode());
        sender.sendMessage("[etmc] …or link (paste into Add Server / Direct Connect):");
        sender.sendMessage(jc.encodeLink());
    }

    private void config(CommandSender sender) {
        if (!plugin.isReady()) {
            sender.sendMessage("[etmc] Mesh not started yet.");
            return;
        }
        sender.sendMessage("[etmc] Host this as a file; players use 'Connect via config URL':");
        for (String line : buildConfigFile().split("\n")) {
            sender.sendMessage(line);
        }
    }

    /** EasyTier config + [etmc] server, mirroring the site generator output. */
    private String buildConfigFile() {
        StringBuilder sb = new StringBuilder();
        sb.append("dhcp = true\n");
        sb.append("listeners = []\n\n");
        sb.append("[network_identity]\n");
        sb.append("network_name = ").append(q(plugin.network())).append('\n');
        sb.append("network_secret = ").append(q(secretOf())).append("\n\n");
        for (String r : plugin.relays()) {
            if (r == null || r.isBlank()) continue;
            sb.append("[[peer]]\nuri = ").append(q(r.trim())).append('\n');
        }
        sb.append("\n[flags]\nno_tun = true\n\n");
        sb.append("[etmc]\n");
        sb.append("server = ").append(q(EtmcConfig.HOST_VIRTUAL_IP + ":" + plugin.virtualPort())).append('\n');
        sb.append("label = ").append(q(plugin.network())).append('\n');
        return sb.toString();
    }

    private String secretOf() {
        return plugin.getConfig().getString("secret", "");
    }

    private static String q(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String p = args[0].toLowerCase();
            return Stream.of("status", "code", "config", "reload").filter(s -> s.startsWith(p)).toList();
        }
        return List.of();
    }
}

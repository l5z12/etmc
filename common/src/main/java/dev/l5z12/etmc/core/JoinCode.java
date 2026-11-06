package dev.l5z12.etmc.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A portable descriptor of "a Minecraft server driven by EasyTier" — everything a peer needs to
 * join a host: the network identity, the user-provided relay(s), and where the host's data-plane
 * listener lives.
 *
 * <p>Shared as an {@code etmc://v1/<base64url(json)>} link: one string that survives copy/paste and
 * goes straight into Minecraft's Add Server / Direct Connect field. An admin who hosts a config can
 * share {@code etmc://v1/config/<url>} instead, which goes in the same field and is read on each join.
 */
public final class JoinCode {

    /** URL form usable in Minecraft's Add Server / Direct Connect address field: {@code etmc://v1/<b64>}. */
    public static final String LINK_PREFIX = "etmc://v1/";
    /** Link that loads a hosted config when joined: {@code etmc://v1/config/https://example.com/etmc.toml}. */
    public static final String CONFIG_PREFIX = LINK_PREFIX + "config/";
    /** A config link of any version: unlike the JSON payload, a newer form can't be read as this one. */
    private static final Pattern CONFIG_LINK =
            Pattern.compile("etmc://([^/]+)/config/(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** Scheme every link form shares; {@link #decode} accepts any {@code etmc://<version>/<b64>}. */
    private static final String SCHEME = "etmc://";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public String networkName;
    public String networkSecret;
    public List<String> relays = new ArrayList<>();
    public String hostIp = EtmcConfig.HOST_VIRTUAL_IP;
    public int hostPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    /** Optional human label shown in the UI / server list. */
    public String label = "";

    public JoinCode() {}

    public JoinCode(String networkName, String networkSecret, List<String> relays,
                    String hostIp, int hostPort, String label) {
        this.networkName = networkName;
        this.networkSecret = networkSecret;
        this.relays = relays == null ? new ArrayList<>() : new ArrayList<>(relays);
        this.hostIp = hostIp;
        this.hostPort = hostPort;
        this.label = label == null ? "" : label;
    }

    /** Serializes to the {@code etmc://v1/<b64>} URL form (pasteable into Add Server / Direct Connect). */
    public String encodeLink() {
        return LINK_PREFIX + payload();
    }

    private String payload() {
        String json = GSON.toJson(this);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** True if the string looks like an etmc:// address. */
    public static boolean isLink(String s) {
        return s != null && s.trim().regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    /**
     * The URL in an {@code etmc://v1/config/<url>} link, or {@code null} for any other text. A config
     * link of another version is {@code null} too, so {@link #decode} can say what's wrong with it.
     */
    public static String configUrl(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.regionMatches(true, 0, CONFIG_PREFIX, 0, CONFIG_PREFIX.length())
                ? t.substring(CONFIG_PREFIX.length()) : null;
    }

    /**
     * Reads what an {@code etmc://v1/config/} link points at: an {@code etmc://} link, or an EasyTier
     * config that names its server. Only the join details are kept, so a link saved in the server
     * list can't set other EasyTier options on the player's client.
     *
     * @throws IllegalArgumentException if the body can't be joined, including another config link
     */
    public static JoinCode fromConfig(String body) {
        String s = body == null ? "" : body.trim();
        JoinCode jc = isLink(s) ? decode(s) : fromHostedToml(s);
        if (jc.relays.isEmpty()) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.config_no_relay",
                    "the config has no relay, so there's no way to reach the server. "
                            + "Ask the server admin to add a [[peer]] line"));
        }
        return jc;
    }

    private static JoinCode fromHostedToml(String s) {
        ImportedConfig cfg;
        try {
            cfg = ImportedConfig.parse(s, "etmc", null);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.config_no_server",
                    "the config doesn't name a server. Ask the server admin to add an [etmc] server line"));
        }
        if (!MeshAddress.isValid(cfg.server())) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.config_bad_server",
                    "the config's server '%s' isn't an IPv4 address and port. "
                            + "Ask the server admin to fix its [etmc] server line", cfg.server()));
        }
        JoinCode jc = fromToml(s);
        if (!jc.isValidCandidate()) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.no_network", "etmc link without a network name"));
        }
        if (jc.networkSecret == null) jc.networkSecret = "";
        jc.hostIp = cfg.serverIp();
        jc.hostPort = cfg.serverPort();
        jc.label = cfg.label();
        return jc;
    }

    /**
     * Parses an {@code etmc://} link. Tolerates surrounding whitespace and a bare payload (no prefix).
     *
     * @throws IllegalArgumentException if the link is malformed
     */
    public static JoinCode decode(String code) {
        if (code == null) throw new IllegalArgumentException(Msg.tr("etmc.link.empty", "empty etmc link"));
        String s = code.trim();
        if (configUrl(s) != null) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.is_config",
                    "this link loads a config from a URL, so it has no details of its own"));
        }
        Matcher config = CONFIG_LINK.matcher(s);
        if (config.matches()) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.config_version",
                    "this link needs a newer version of etmc"));
        }
        if (isLink(s)) {
            // etmc://v1/<b64> (or any etmc://<ver>/<b64>) — the payload is the segment after the last '/'.
            int slash = s.lastIndexOf('/');
            s = slash >= 0 ? s.substring(slash + 1) : s;
        }
        s = s.trim();
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.invalid", "not a valid etmc link"));
        }
        JoinCode jc;
        try {
            jc = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JoinCode.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.corrupt", "corrupt etmc link"));
        }
        if (jc == null || jc.networkName == null || jc.networkName.isBlank()) {
            throw new IllegalArgumentException(Msg.tr("etmc.link.no_network", "etmc link without a network name"));
        }
        if (jc.relays == null) jc.relays = new ArrayList<>();
        if (jc.hostIp == null || jc.hostIp.isBlank()) jc.hostIp = EtmcConfig.HOST_VIRTUAL_IP;
        if (jc.hostPort <= 0 || jc.hostPort > 65535) jc.hostPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        return jc;
    }

    public boolean isValidCandidate() {
        return networkName != null && !networkName.isBlank();
    }

    /**
     * What to call this network in the UI: the host's label, falling back to the network name.
     * Never blank, so callers can use it directly in a screen title or server-list entry.
     */
    public String displayLabel() {
        if (label != null && !label.isBlank()) return label;
        if (networkName != null && !networkName.isBlank()) return networkName;
        return "etmc";
    }

    /**
     * Best-effort extraction of a JoinCode from an EasyTier TOML config. Reads
     * {@code [network_identity] network_name / network_secret}, each {@code [[peer]] uri}, and the
     * optional {@code [etmc] server = "ip:port"} / {@code label} extension. If no {@code [etmc]}
     * server is present, tries to derive it from a top-level {@code ipv4 = "10.x.y.z/24"} key
     * (the host's fixed virtual IP). Fields not present in the TOML are left at their defaults.
     */
    public static JoinCode fromToml(String raw) {
        JoinCode jc = new JoinCode();
        if (raw == null) return jc;
        String table = "";
        String rootIpv4 = null;
        for (String line : Toml.lines(raw)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("[")) {
                table = Toml.tableName(t);
                continue;
            }
            String key = Toml.keyName(t);
            if (key.isEmpty()) continue;
            String val = Toml.stringValue(t);
            switch (table) {
                case "" -> {
                    if ("ipv4".equals(key)) rootIpv4 = val;
                }
                case "network_identity" -> {
                    if ("network_name".equals(key)) jc.networkName = val;
                    else if ("network_secret".equals(key)) jc.networkSecret = val;
                }
                case "peer" -> {
                    if ("uri".equals(key) && val != null && !val.isBlank()) jc.relays.add(val);
                }
                case "etmc" -> {
                    if ("server".equals(key)) applyServer(jc, val);
                    else if ("label".equals(key)) jc.label = val == null ? "" : val;
                }
                default -> { /* any other table is irrelevant to an etmc link */ }
            }
        }
        // No [etmc] server line: derive from the root ipv4 (host's fixed virtual IP), stripping /cidr.
        if ((jc.hostIp == null || jc.hostIp.equals(EtmcConfig.HOST_VIRTUAL_IP)) && rootIpv4 != null) {
            int slash = rootIpv4.indexOf('/');
            String ip = slash > 0 ? rootIpv4.substring(0, slash).trim() : rootIpv4.trim();
            if (!ip.isEmpty()) jc.hostIp = ip;
        }
        return jc;
    }

    private static void applyServer(JoinCode jc, String server) {
        MeshAddress addr = MeshAddress.parse(server, jc.hostPort);
        if (addr == null) return;
        jc.hostIp = addr.ip();
        jc.hostPort = addr.port();
    }
}

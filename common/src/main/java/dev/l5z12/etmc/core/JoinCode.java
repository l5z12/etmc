package dev.l5z12.etmc.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;

/**
 * A portable descriptor of "a Minecraft server driven by EasyTier" — everything a peer needs to
 * join a host: the network identity, the user-provided relay(s), and where the host's data-plane
 * listener lives.
 *
 * <p>Wire form is {@code ETMC1:<base64url(json)>} so it survives copy/paste in chat and can be
 * imported/exported as a single string or file.
 */
public final class JoinCode {

    public static final String PREFIX = "ETMC1:";
    /** URL form usable in Minecraft's Add Server / Direct Connect address field: {@code etmc://v1/<b64>}. */
    public static final String LINK_PREFIX = "etmc://v1/";
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

    /** Serializes to the {@code ETMC1:...} wire form. */
    public String encode() {
        return PREFIX + payload();
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
        return s != null && s.trim().regionMatches(true, 0, "etmc://", 0, 7);
    }

    /**
     * Parses a join code. Tolerates surrounding whitespace and a missing/extra prefix.
     *
     * @throws IllegalArgumentException if the code is malformed
     */
    public static JoinCode decode(String code) {
        if (code == null) throw new IllegalArgumentException("empty join code");
        String s = code.trim();
        if (s.regionMatches(true, 0, "etmc://", 0, 7)) {
            // etmc://v1/<b64> (or any etmc://<ver>/<b64>) — the payload is the segment after the last '/'.
            int slash = s.lastIndexOf('/');
            s = slash >= 0 ? s.substring(slash + 1) : s;
        } else if (s.startsWith(PREFIX)) {
            s = s.substring(PREFIX.length());
        }
        s = s.trim();
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a valid etmc join code");
        }
        JoinCode jc;
        try {
            jc = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JoinCode.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("corrupt etmc join code");
        }
        if (jc == null || jc.networkName == null || jc.networkName.isBlank()) {
            throw new IllegalArgumentException("join code missing network name");
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
     * Best-effort extraction of a JoinCode from an EasyTier TOML config. Reads
     * {@code [network_identity] network_name / network_secret}, each {@code [[peer]] uri}, and the
     * optional {@code [etmc] server = "ip:port"} / {@code label} extension. If no {@code [etmc]}
     * server is present, tries to derive it from a top-level {@code ipv4 = "10.x.y.z/24"} key
     * (the host's fixed virtual IP). Fields not present in the TOML are left at their defaults.
     */
    public static JoinCode fromToml(String raw) {
        JoinCode jc = new JoinCode();
        if (raw == null) return jc;
        String[] lines = raw.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        String table = "";
        boolean inPeer = false;
        String rootIpv4 = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("[")) {
                String name = tableName(t);
                inPeer = "peer".equals(name);
                table = name;
                continue;
            }
            String key = keyName(t);
            if (key.isEmpty()) continue;
            String val = stringValue(t);
            if (table.isEmpty()) {
                if ("ipv4".equals(key)) rootIpv4 = val;
            } else if ("network_identity".equals(table)) {
                if ("network_name".equals(key)) jc.networkName = val;
                else if ("network_secret".equals(key)) jc.networkSecret = val;
            } else if (inPeer) {
                if ("uri".equals(key) && val != null && !val.isBlank()) jc.relays.add(val);
            } else if ("etmc".equals(table)) {
                if ("server".equals(key)) {
                    applyServer(jc, val);
                } else if ("label".equals(key)) {
                    jc.label = val == null ? "" : val;
                }
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
        if (server == null) return;
        String s = server.trim();
        if (s.isEmpty()) return;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon) {
            jc.hostIp = s.substring(0, colon).trim();
            try {
                int p = Integer.parseInt(s.substring(colon + 1).trim());
                if (p > 0 && p <= 65535) jc.hostPort = p;
            } catch (NumberFormatException ignored) {
            }
        } else {
            jc.hostIp = s;
        }
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
}

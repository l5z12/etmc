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
}

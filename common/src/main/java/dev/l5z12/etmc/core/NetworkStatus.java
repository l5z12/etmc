package dev.l5z12.etmc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * A loose, defensive view of an EasyTier instance's live state, parsed from the JSON returned by
 * {@code collect_network_infos}. We only pull out what the UI shows (virtual IP, peers, latency,
 * running flag) and tolerate missing/renamed fields rather than failing.
 */
public record NetworkStatus(boolean running, String virtualIp, List<Peer> peers, String errorMsg) {

    /**
     * A reachable peer. {@code relay} = traffic is routed through another node (not a direct/P2P link);
     * {@code cost} is EasyTier's route cost (1 = direct/p2p, &gt;1 = relayed) for diagnostics.
     */
    public record Peer(String hostname, String ipv4, int latencyMs, boolean relay, int cost) {}

    public static NetworkStatus empty() {
        return new NetworkStatus(false, null, List.of(), null);
    }

    public int peerCount() {
        return peers.size();
    }

    /** Peers reachable over a direct (P2P) link — relayed peers are excluded. */
    public int directPeerCount() {
        int n = 0;
        for (Peer p : peers) {
            if (!p.relay()) n++;
        }
        return n;
    }

    public static NetworkStatus parse(String json) {
        if (json == null || json.isBlank()) return empty();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            boolean running = optBool(root, "running", true);
            String errorMsg = optString(root, "error_msg");

            String vip = null;
            JsonObject myNode = optObject(root, "my_node_info");
            if (myNode != null) {
                vip = decodeIpv4Inet(optObject(myNode, "virtual_ipv4"));
            }

            List<Peer> peers = new ArrayList<>();
            JsonArray pairs = optArray(root, "peer_route_pairs");
            if (pairs != null) {
                for (JsonElement el : pairs) {
                    if (!el.isJsonObject()) continue;
                    JsonObject pair = el.getAsJsonObject();
                    JsonObject route = optObject(pair, "route");
                    if (route == null) continue;
                    String ip = decodeIpv4Inet(optObject(route, "ipv4_addr"));
                    // Skip infrastructure peers with no virtual IP (public relay nodes): they aren't game
                    // peers, so they'd just clutter the HUD as a no-name "?" entry.
                    if (ip == null) continue;
                    // EasyTier: route.cost <= 1 is a direct (p2p) link; > 1 is relayed (cost == hops).
                    int cost = optInt(route, "cost", 1);
                    boolean relay = cost > 1;
                    // Real latency lives in the direct connection stats; route.path_latency is a routing
                    // cost (reads ~1000 for relayed peers), so derive ms from peer.conns[].stats instead.
                    int latency = latencyFromConns(optObject(pair, "peer"));
                    String hostname = optString(route, "hostname");
                    if (hostname == null || hostname.isEmpty()) {
                        hostname = "peer-" + optInt(route, "peer_id", 0); // hostname not propagated yet
                    }
                    peers.add(new Peer(hostname, ip, latency, relay, cost));
                }
            }
            return new NetworkStatus(running, vip, peers, errorMsg);
        } catch (Exception e) {
            return empty();
        }
    }

    /**
     * Average round-trip latency (ms) over a peer's direct connections, from {@code peer.conns[].stats
     * .latency_us}. Returns -1 when there is no direct connection (relayed peers) — callers render "—"
     * rather than the bogus routing cost in {@code route.path_latency}.
     */
    private static int latencyFromConns(JsonObject peer) {
        if (peer == null) return -1;
        JsonArray conns = optArray(peer, "conns");
        if (conns == null) return -1;
        long sumUs = 0;
        int n = 0;
        for (JsonElement c : conns) {
            if (!c.isJsonObject()) continue;
            JsonObject stats = optObject(c.getAsJsonObject(), "stats");
            if (stats == null) continue;
            long us = optLong(stats, "latency_us", -1);
            if (us >= 0) {
                sumUs += us;
                n++;
            }
        }
        return n > 0 ? (int) Math.round((sumUs / (double) n) / 1000.0) : -1;
    }

    /** Decodes {@code { "address": { "addr": <u32> }, "network_length": n }} into a dotted string. */
    private static String decodeIpv4Inet(JsonObject inet) {
        if (inet == null) return null;
        JsonObject addr = optObject(inet, "address");
        if (addr == null) return null;
        if (!addr.has("addr")) return null;
        try {
            long a = addr.get("addr").getAsLong() & 0xFFFFFFFFL;
            if (a == 0) return null;
            return ((a >> 24) & 0xFF) + "." + ((a >> 16) & 0xFF) + "." + ((a >> 8) & 0xFF) + "." + (a & 0xFF);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject optObject(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String optString(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
        } catch (Exception ex) {
            return def;
        }
    }

    private static int optInt(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
        } catch (Exception ex) {
            return def;
        }
    }

    /** Like {@link #optInt} but for 64-bit values (prost serializes u64 as a JSON string). */
    private static long optLong(JsonObject o, String k, long def) {
        JsonElement e = o.get(k);
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsLong() : def;
        } catch (Exception ex) {
            return def;
        }
    }
}

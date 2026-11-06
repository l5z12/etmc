package dev.l5z12.etmc.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mojang's own endpoints, the ones the game itself checks certificates against. These fixed public
 * URLs are all this contacts, and only for a link that names its host. Answers are kept for the
 * session: every connection of a join checks the same host.
 */
final class MojangDirectory implements AccountDirectory {

    private static final String KEYS_URL = "https://api.minecraftservices.com/publickeys";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";

    private volatile List<PublicKey> keys;
    private final Map<UUID, String> names = new ConcurrentHashMap<>();

    @Override
    public Collection<PublicKey> certificateKeys() throws IOException, InterruptedException {
        List<PublicKey> known = keys;
        if (known == null) {
            known = parseKeys(RemoteConfig.fetch(KEYS_URL));
            keys = known;
        }
        return known;
    }

    @Override
    public String nameOf(UUID account) throws IOException, InterruptedException {
        String known = names.get(account);
        if (known != null) return known;
        String name = parseName(RemoteConfig.fetch(PROFILE_URL + account.toString().replace("-", "")));
        names.put(account, name);
        return name;
    }

    /** Reads {@code playerCertificateKeys} from the keys endpoint's reply. */
    static List<PublicKey> parseKeys(String json) throws IOException {
        try {
            JsonArray list = new Gson().fromJson(json, JsonObject.class).getAsJsonArray("playerCertificateKeys");
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            List<PublicKey> out = new ArrayList<>();
            for (JsonElement e : list) {
                byte[] der = Base64.getDecoder().decode(e.getAsJsonObject().get("publicKey").getAsString());
                out.add(rsa.generatePublic(new X509EncodedKeySpec(der)));
            }
            if (out.isEmpty()) throw new IOException("Mojang listed no certificate keys");
            return out;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IOException("unreadable certificate keys", e);
        }
    }

    /** Reads the name from the profile endpoint's reply. */
    static String parseName(String json) throws IOException {
        try {
            return new Gson().fromJson(json, JsonObject.class).get("name").getAsString();
        } catch (RuntimeException e) {
            throw new IOException("unreadable profile", e);
        }
    }
}

package dev.l5z12.etmc.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.JoinCode;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted etmc settings for the Mojmap loaders (NeoForge/Forge). Stored as JSON under
 * {@code <game dir>/config/etmc.json} using only vanilla Minecraft + gson, so it is shared by both
 * loaders without any loader-specific API.
 */
public final class McConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public List<String> relays = new ArrayList<>();
    public List<JoinCode> presets = new ArrayList<>();
    public String lastNetworkName = "";
    public String lastSecret = "";
    public int defaultVirtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    public int joinLocalPort = 0;
    public boolean hudEnabled = true;
    public boolean autoReconnect = true;

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("etmc.json");
    }

    public static McConfig load() {
        Path file = file();
        McConfig cfg;
        try {
            if (Files.exists(file)) {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), McConfig.class);
                if (cfg == null) cfg = new McConfig();
            } else {
                cfg = new McConfig();
            }
        } catch (Exception e) {
            cfg = new McConfig();
        }
        if (cfg.relays == null) cfg.relays = new ArrayList<>();
        if (cfg.presets == null) cfg.presets = new ArrayList<>();
        if (cfg.defaultVirtualPort <= 0 || cfg.defaultVirtualPort > 65535) {
            cfg.defaultVirtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        }
        return cfg;
    }

    public void save() {
        try {
            Path file = file();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public boolean hasRelay() {
        for (String r : relays) {
            if (r != null && !r.isBlank()) return true;
        }
        return false;
    }

    public void setRelaysFromText(String text) {
        List<String> out = new ArrayList<>();
        if (text != null) {
            for (String line : text.split("[\\r\\n,]+")) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        relays = out;
    }
}

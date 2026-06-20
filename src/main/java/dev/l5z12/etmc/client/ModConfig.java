package dev.l5z12.etmc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.JoinCode;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?}

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Persisted etmc settings (relays the user provides, saved network presets, HUD/auto-reconnect
 * preferences). Stored as JSON in the Fabric config dir.
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** User-provided relay node URIs (e.g. {@code tcp://my.relay:11010}). Required to connect. */
    public List<String> relays = new ArrayList<>();
    /** Saved networks for quick host/join. */
    public List<JoinCode> presets = new ArrayList<>();

    public String lastNetworkName = "";
    public String lastSecret = "";
    public int defaultVirtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    /** Loopback port used when joining (0 = ephemeral). A fixed port helps mods like ViaFabricPlus
     *  remember the per-server protocol version across sessions. */
    public int joinLocalPort = 0;

    public boolean hudEnabled = true;
    public boolean autoReconnect = true;

    private transient Path file;

    /** {@code <config>/etmc.json}: Fabric resolves it via FabricLoader; NeoForge/Forge via the game dir. */
    private static Path configFile() {
        //? if fabric {
        return FabricLoader.getInstance().getConfigDir().resolve("etmc.json");
        //?} else {
        /*return net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("etmc.json");*/
        //?}
    }

    public static ModConfig load() {
        Path file = configFile();
        ModConfig cfg;
        if (Files.exists(file)) {
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                cfg = GSON.fromJson(json, ModConfig.class);
                if (cfg == null) cfg = new ModConfig();
            } catch (Exception e) {
                cfg = new ModConfig();
            }
        } else {
            cfg = new ModConfig();
        }
        if (cfg.relays == null) cfg.relays = new ArrayList<>();
        if (cfg.presets == null) cfg.presets = new ArrayList<>();
        if (cfg.defaultVirtualPort <= 0 || cfg.defaultVirtualPort > 65535) {
            cfg.defaultVirtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        }
        cfg.file = file;
        return cfg;
    }

    public void save() {
        if (file == null) {
            file = configFile();
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // non-fatal
        }
    }

    public boolean hasRelay() {
        for (String r : relays) {
            if (r != null && !r.isBlank()) return true;
        }
        return false;
    }

    /** Returns relays as a comma/newline editable single string. */
    public String relaysAsText() {
        return String.join("\n", relays);
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

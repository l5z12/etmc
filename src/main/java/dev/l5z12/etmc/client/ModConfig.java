package dev.l5z12.etmc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.l5z12.etmc.core.EtmcConfig;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?}

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persisted etmc settings (the relays the user provides, the last network they hosted, HUD
 * preference). Stored as JSON in the config dir; unknown keys from older versions are ignored.
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** User-provided relay node URIs (e.g. {@code tcp://my.relay:11010}). Required to connect. */
    public List<String> relays = new ArrayList<>();

    public String lastNetworkName = "";
    public String lastSecret = "";
    public int defaultVirtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
    /** Loopback port used when joining (0 = ephemeral). A fixed port helps mods like ViaFabricPlus
     *  remember the per-server protocol version across sessions. */
    public int joinLocalPort = 0;

    public boolean hudEnabled = true;
    /**
     * NOT IMPLEMENTED YET: the Settings screen toggles and persists this, but nothing reads it —
     * a dropped session is not retried. Wire it in {@code EtmcManager.tick()} (or drop the toggle)
     * before advertising auto-reconnect as a feature.
     */
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
        // A hand-edited or older config can be missing/garbage in any field: normalize rather than
        // let a bad value reach EasyTier (or, for the port, silently bind somewhere unexpected).
        if (cfg.relays == null) cfg.relays = new ArrayList<>();
        if (cfg.defaultVirtualPort <= 0 || cfg.defaultVirtualPort > 65535) {
            cfg.defaultVirtualPort = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        }
        if (cfg.joinLocalPort < 0 || cfg.joinLocalPort > 65535) {
            cfg.joinLocalPort = 0; // 0 = ephemeral
        }
        cfg.file = file;
        return cfg;
    }

    /** Writes the config. Best-effort: a failure here must never break a host/join in progress. */
    public void save() {
        if (file == null) {
            file = configFile();
        }
        try {
            Files.createDirectories(file.getParent());
            // Write-then-rename: a crash mid-write would otherwise truncate the config to nothing.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
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

    /** Replaces the relay list from editable text; entries are trimmed, blanks and repeats dropped. */
    public void setRelaysFromText(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text != null) {
            for (String line : text.split("[\\r\\n,]+")) {
                String t = line.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        relays = new ArrayList<>(out);
    }
}

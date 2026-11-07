package dev.l5z12.etmc.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.Relays;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?}

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

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

    /** The label joiners see for the hosted world (blank = the network name). */
    public String lastLabel = "";
    /** Joiners' game mode when etmc opens the world to LAN ({@code null}/blank = the host's own). */
    public String lanGameMode = "";
    /** Whether cheats are allowed when etmc opens the world to LAN (applies to the host too). */
    public boolean lanCheats = false;
    /** Whether joiners can skip Minecraft's account check while this world is hosted. */
    public boolean lanAllowOffline = false;
    /**
     * Whether hosting signs each joiner's connection with the player's Minecraft account, so the link
     * can name them. Off unless they turn it on: it uses their chat key, and shows joiners their account.
     */
    public boolean signHostAccount = false;

    /** Whether the top-left status overlay is shown while a session is up. */
    public boolean hudEnabled = true;
    // (An "autoReconnect" key from older versions is ignored: it was never implemented.)

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
        if (cfg.lastLabel == null) cfg.lastLabel = "";
        if (cfg.lanGameMode == null) cfg.lanGameMode = "";
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

    /**
     * The relay list as one editable line, in the form {@link #setRelaysFromText} reads back — the
     * two are a pair, so the Settings screen's field round-trips.
     */
    public String relaysAsText() {
        return String.join(", ", relays);
    }

    /**
     * Replaces the relay list from editable text — commas, spaces or newlines all separate entries —
     * trimmed, blanks and repeats dropped (the same rules as everywhere else, see {@link Relays}).
     */
    public void setRelaysFromText(String text) {
        relays = Relays.parse(text);
    }
}

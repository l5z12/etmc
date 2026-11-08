package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.McIdentity;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.Relays;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.security.SecureRandom;
import java.util.List;

/**
 * Host the current singleplayer world. The first view is all most players need: the world's label,
 * plus the LAN options vanilla's own "Open to LAN" dialog offers (joiners' game mode, cheats), and a
 * relay if they haven't set one up. <b>More options</b> shows the rest.
 *
 * <p>The network name and secret default to random values rather than a shared "my-world": on a
 * public relay everyone using the same name and secret lands in the same network, and every etmc host
 * takes the same virtual address, so joiners could end up in a stranger's world.
 */
public final class HostScreen extends EtmcBaseScreen {

    /** Joiner game-mode choices: blank = whatever mode the host is in now. */
    private static final String[] MODES = {"", "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR"};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int W = 260;
    private static final int NEW_W = 36;

    /** All of this is the player's until they leave the screen; {@link #build} only shows it. */
    private Ui.Field network;
    private Ui.Field secret;
    private Ui.Field worldLabel;
    private Ui.Field relay;
    private int modeIndex;
    private boolean cheats;
    private boolean offline;
    private boolean sign;
    /** Whether hosting needs a relay fixed first, so the relay box is shown. */
    private boolean needsRelay;
    private boolean moreOptions;
    private boolean starting;
    private String error;

    public HostScreen(Screen parent) {
        super(Txt.tr("etmc.menu.host", "Host this world"), parent);
    }

    private void load() {
        EtmcManager m = EtmcManager.get();
        ModConfig cfg = m.config();
        network = new Ui.Field(64, orDefault(cfg.lastNetworkName, "etmc-" + random(6)));
        secret = new Ui.Field(128, orDefault(cfg.lastSecret, random(12)));
        worldLabel = new Ui.Field(64, orDefault(cfg.lastLabel, Msg.tr("etmc.host.default_label", "%s's world", m.playerName())));
        relay = new Ui.Field(2048, cfg.relaysAsText());
        needsRelay = !cfg.hasRelay() || Relays.firstProblem(cfg.relays) != null;
        modeIndex = indexOf(cfg.lanGameMode);
        cheats = cfg.lanCheats;
        offline = cfg.lanAllowOffline;
        sign = cfg.signHostAccount && McIdentity.supported();
    }

    @Override
    protected void build() {
        if (network == null) load();
        int w = col(W);
        int x = left(w);
        int rows = moreOptions ? 2 : needsRelay ? 2 : 1;
        int y = formTop(rows * (LINE_H + ROW + GAP) + ROW, 5 * LINE_H);
        if (moreOptions) {
            buildMoreOptions(x, y, w);
        } else {
            buildBasics(x, y, w);
        }

        var start = add(footer(Ui.button(Txt.tr("etmc.host.start", "Start hosting"), b -> startHosting()), 0, 3).build());
        add(footer(Ui.button(moreOptions ? Txt.tr("etmc.host.fewer", "Fewer options") : Txt.tr("etmc.host.more", "More options"), b -> {
            moreOptions = !moreOptions;
            rebuild();
        }), 1, 3).build());
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), 2, 3).build());

        live(() -> {
            start.active = !starting && blocker() == null;
            if (!starting) showNotice();
        });
    }

    private void buildBasics(int x, int y, int w) {
        label(Msg.tr("etmc.host.label.hint", "World label (what joiners see)"), x, y, w, COLOR_MUTED);
        var labelBox = add(worldLabel.widget(font(), x, y + LINE_H, w, ROW, Txt.tr("etmc.host.label", "World label")));
        y += LINE_H + ROW + GAP;

        if (needsRelay) {
            label(Msg.tr("etmc.host.relay.hint", "Relay (joiners reach you through it)"), x, y, w, COLOR_WARN);
            var relayBox = add(relay.widget(font(), x, y + LINE_H, w - FIND_RELAY_W - GAP, ROW,
                    Txt.tr("etmc.settings.relays", "Relays")));
            findRelay(x + w - FIND_RELAY_W, y + LINE_H);
            y += LINE_H + ROW + GAP;
            //? if >=1.17 {
            setInitialFocus(relayBox);
            //?}
        } else {
            //? if >=1.17 {
            setInitialFocus(labelBox);
            //?}
        }

        String kept = Msg.tr("etmc.host.lan_kept", "Already opened to LAN? Game mode and cheats are kept.");
        Ui.Builder mode = cell(Ui.button(Txt.literal(modeLabel()), b -> {
            modeIndex = (modeIndex + 1) % MODES.length;
            Ui.setMessage(b, Txt.literal(modeLabel()));
        }), x, y, w, 0, 2);
        hint(mode, Msg.tr("etmc.host.mode.help", "The game mode friends join in.") + " " + kept);
        add(mode.build());

        Ui.Builder cheatsButton = cell(Ui.button(Txt.literal(cheatsLabel()), b -> {
            cheats = !cheats;
            Ui.setMessage(b, Txt.literal(cheatsLabel()));
        }), x, y, w, 1, 2);
        hint(cheatsButton, Msg.tr("etmc.host.cheats.help", "Lets everyone use cheats, you included.") + " " + kept);
        add(cheatsButton.build());
    }

    private void buildMoreOptions(int x, int y, int w) {
        label(Msg.tr("etmc.host.network.hint", "Network name (unique to you)"), x, y, w, COLOR_MUTED);
        add(network.widget(font(), x, y + LINE_H, w - NEW_W - GAP, ROW, Txt.tr("etmc.host.network", "Network name")));
        Ui.Builder fresh = Ui.button(Txt.tr("etmc.host.new", "New"), b -> {
            network.set("etmc-" + random(6));
            secret.set(random(12));
        }).dimensions(x + w - NEW_W, y + LINE_H, NEW_W, ROW);
        hint(fresh, Msg.tr("etmc.host.new.help", "Pick a new random network name and secret. Links you've already "
                + "shared stop working."));
        add(fresh.build());
        y += LINE_H + ROW + GAP;

        label(Msg.tr("etmc.host.secret.hint", "Secret (plain text, never a real password)"), x, y, w, COLOR_WARN);
        add(secret.widget(font(), x, y + LINE_H, w, ROW, Txt.tr("etmc.host.secret", "Secret")));
        y += LINE_H + ROW + GAP;

        Ui.Builder offlineButton = cell(Ui.button(Txt.literal(offlineLabel()), b -> {
            offline = !offline;
            Ui.setMessage(b, Txt.literal(offlineLabel()));
        }), x, y, w, 0, 2);
        hint(offlineButton, Msg.tr("etmc.host.offline.help", "Lets friends without a Minecraft account join."));
        add(offlineButton.build());

        Ui.Builder signBuilder = cell(Ui.button(Txt.literal(signLabel()), b -> {
            sign = !sign;
            Ui.setMessage(b, Txt.literal(signLabel()));
        }), x, y, w, 1, 2);
        hint(signBuilder, Msg.tr("etmc.host.sign.help", "Shows your name on the invite, and proves it to joiners."));
        var signButton = signBuilder.build();
        signButton.active = McIdentity.supported();
        add(signButton);
    }

    /** Why hosting can't start yet, or {@code null}. An empty relay box is {@code ""}: nothing's wrong, it just needs filling in. */
    private String blocker() {
        String world = EtmcManager.get().worldBlocker();
        if (world != null) return world;
        if (!needsRelay) return null;
        List<String> relays = Relays.parse(relay.text());
        if (relays.isEmpty()) return "";
        return Relays.firstProblem(relays);
    }

    /** The status line while nothing is happening: what's stopping the player, else what they've chosen. */
    private void showNotice() {
        if (error != null) {
            setMessage(error, COLOR_BAD);
            return;
        }
        String blocker = blocker();
        if ("".equals(blocker)) {
            setMessage(Msg.tr("etmc.host.relay.help", "Any EasyTier node works, such as tcp://relay.example.com:11010. "
                    + "For a public one, see %s.", Relays.PUBLIC_LIST_TEXT), COLOR_MUTED);
            return;
        }
        String warning = offline ? Msg.tr("etmc.host.offline.warning",
                "Anyone with your link can join under any name. Friends with real accounts start with a new inventory.") : "";
        String signing = sign ? Msg.tr("etmc.host.sign.notice", "Your link will name you, and joiners will see your "
                + "account. etmc signs their connections with your Minecraft chat key.") : "";
        setMessage(blocker != null ? blocker : (warning + " " + signing).trim(), COLOR_WARN);
    }

    @Override
    protected boolean submit() {
        if (starting || blocker() != null) return false;
        startHosting();
        return true;
    }

    private void startHosting() {
        EtmcManager m = EtmcManager.get();
        error = null;
        String networkName = network.text().trim();
        String networkSecret = secret.text();
        if (networkName.isEmpty() || networkSecret.isBlank()) {
            error = networkName.isEmpty() ? Msg.tr("etmc.host.need_network", "Enter a network name.")
                    : Msg.tr("etmc.host.need_secret", "Enter a secret: without one, anyone who guesses the network name can join.");
            if (!moreOptions) {
                moreOptions = true;
                rebuild();
            }
            return;
        }
        ModConfig cfg = m.config();
        if (needsRelay) cfg.relays = Relays.parse(relay.text());
        cfg.lanGameMode = MODES[modeIndex];
        cfg.lanCheats = cheats;
        cfg.lanAllowOffline = offline;
        cfg.signHostAccount = sign;
        starting = true;
        setMessage(Msg.tr("etmc.host.starting", "Starting…"), COLOR_BUSY);
        String mode = MODES[modeIndex].isEmpty() ? null : MODES[modeIndex];
        m.hostAsync(networkName, networkSecret, worldLabel.text().trim(), mode, cheats, offline).whenComplete((code, err) ->
                mc().execute(() -> {
                    starting = false;
                    if (err != null) {
                        error = Msg.tr("etmc.host.failed_start", "Couldn't start hosting: %s", Errors.message(err));
                    } else {
                        McScreens.setClipboard(code.encodeLink());
                        goTo(new StatusScreen(parent, Msg.tr("etmc.status.hosting_copied", "You're hosting. Invite link "
                                + "copied: paste it to your friends. It contains the secret, so send it only to them.")));
                    }
                }));
    }

    private String modeLabel() {
        String m = MODES[modeIndex];
        // Vanilla's own names for the game modes, so they read as they do everywhere else in the game.
        String name = m.isEmpty() ? Msg.tr("etmc.host.mode.same", "Same as you")
                : Txt.i18n("selectWorld.gameMode." + m.toLowerCase(java.util.Locale.ROOT));
        return Msg.tr("etmc.host.mode", "Joiners: %s", name);
    }

    private String cheatsLabel() {
        return Msg.tr("etmc.host.cheats", "Cheats: %s", onOff(cheats));
    }

    private String offlineLabel() {
        return Msg.tr("etmc.host.offline", "Offline accounts: %s", onOff(offline));
    }

    private String signLabel() {
        return Msg.tr("etmc.host.sign", "Sign as me: %s", onOff(sign));
    }

    private static String onOff(boolean on) {
        return Txt.i18n(on ? "options.on" : "options.off");
    }

    private static int indexOf(String mode) {
        for (int i = 0; i < MODES.length; i++) {
            if (MODES[i].equalsIgnoreCase(mode == null ? "" : mode)) return i;
        }
        return 0;
    }

    private static String random(int len) {
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789"; // no 0/o, 1/l: it may be read aloud
        StringBuilder b = new StringBuilder(len);
        for (int i = 0; i < len; i++) b.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return b.toString();
    }

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}

package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.Msg;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
//?} else {
/*import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.security.SecureRandom;

/**
 * Host the current singleplayer world: network name, secret and label, plus the LAN options vanilla's
 * own "Open to LAN" dialog offers (joiners' game mode, cheats), then start.
 *
 * <p>The network name and secret default to random values rather than a shared "my-world": on a
 * public relay everyone using the same name and secret lands in the same network, and every etmc host
 * takes the same virtual address, so joiners could end up in a stranger's world.
 */
public final class HostScreen extends EtmcBaseScreen {

    /** Joiner game-mode choices: blank = whatever mode the host is in now. */
    private static final String[] MODES = {"", "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR"};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int W = 240;

    //? if yarn {
    private TextFieldWidget networkField;
    private TextFieldWidget secretField;
    private TextFieldWidget labelField;
    private ButtonWidget modeButton;
    private ButtonWidget cheatsButton;
    private ButtonWidget hostButton;
    //?} else {
    /*private EditBox networkField;
    private EditBox secretField;
    private EditBox labelField;
    private Button modeButton;
    private Button cheatsButton;
    private Button hostButton;*/
    //?}

    private int modeIndex;
    private boolean cheats;

    public HostScreen(Screen parent) {
        super(Txt.tr("etmc.menu.host", "Host this world"), parent);
    }

    @Override
    protected void init() {
        EtmcManager m = EtmcManager.get();
        ModConfig cfg = m.config();
        int x = this.width / 2 - W / 2;
        int y = top();

        networkField = Ui.textField(font(), x, y + 10, W - 40, 18, Txt.tr("etmc.host.network", "Network name"));
        networkField.setMaxLength(64);
        Ui.setText(networkField, orDefault(cfg.lastNetworkName, "etmc-" + random(6)));
        add(networkField);
        add(Ui.button(Txt.tr("etmc.host.new", "New"), b -> {
                    Ui.setText(networkField, "etmc-" + random(6));
                    Ui.setText(secretField, random(12));
                })
                .dimensions(x + W - 36, y + 9, 36, 20).build());
        y += 32;

        secretField = Ui.textField(font(), x, y + 10, W, 18, Txt.tr("etmc.host.secret", "Secret"));
        secretField.setMaxLength(128);
        Ui.setText(secretField, orDefault(cfg.lastSecret, random(12)));
        add(secretField);
        y += 32;

        labelField = Ui.textField(font(), x, y + 10, W, 18, Txt.tr("etmc.host.label", "World label"));
        labelField.setMaxLength(64);
        Ui.setText(labelField, orDefault(cfg.lastLabel, Msg.tr("etmc.host.default_label", "%s's world", m.playerName())));
        add(labelField);
        y += 34;

        modeIndex = indexOf(cfg.lanGameMode);
        cheats = cfg.lanCheats;
        modeButton = Ui.button(modeLabel(), b -> {
            modeIndex = (modeIndex + 1) % MODES.length;
            Ui.setMessage(modeButton, modeLabel());
        }).dimensions(x, y, W / 2 - 2, 20).build();
        add(modeButton);
        cheatsButton = Ui.button(cheatsLabel(), b -> {
            cheats = !cheats;
            Ui.setMessage(cheatsButton, cheatsLabel());
        }).dimensions(x + W / 2 + 2, y, W / 2 - 2, 20).build();
        add(cheatsButton);
        y += 36;

        hostButton = Ui.button(Txt.tr("etmc.host.start", "Start hosting"), b -> startHosting())
                .dimensions(x, y, W, 20).build();
        String blocker = m.hostBlocker();
        hostButton.active = blocker == null;
        add(hostButton);
        y += 24;

        add(Ui.button(Txt.translatable("gui.back"), b -> this.close())
                .dimensions(x, y, W, 20).build());

        if (blocker != null) setMessage(blocker, COLOR_WARN);
    }

    private int top() {
        return Math.max(34, this.height / 2 - 112);
    }

    private void startHosting() {
        EtmcManager m = EtmcManager.get();
        String network = Ui.getText(networkField).trim();
        if (network.isEmpty()) {
            setMessage(Msg.tr("etmc.host.need_network", "Enter a network name."), COLOR_BAD);
            return;
        }
        String secret = Ui.getText(secretField);
        if (secret.isBlank()) {
            setMessage(Msg.tr("etmc.host.need_secret",
                    "Enter a secret: without one, anyone who guesses the network name can join."), COLOR_BAD);
            return;
        }
        ModConfig cfg = m.config();
        cfg.lanGameMode = MODES[modeIndex];
        cfg.lanCheats = cheats;
        hostButton.active = false;
        setMessage(Msg.tr("etmc.host.starting", "Starting…"), COLOR_BUSY);
        String mode = MODES[modeIndex].isEmpty() ? null : MODES[modeIndex];
        m.hostAsync(network, secret, Ui.getText(labelField).trim(), mode, cheats).whenComplete((code, err) ->
                mc().execute(() -> {
                    if (err != null) {
                        setMessage(Msg.tr("etmc.host.failed_start", "Couldn't start hosting: %s", Errors.message(err)),
                                COLOR_BAD);
                        hostButton.active = m.hostBlocker() == null;
                    } else {
                        goTo(new StatusScreen(parent));
                    }
                }));
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int x = cx - W / 2;
        int y = top();
        Gfx.centered(ctx, font(), this.title, cx, Math.max(8, y - 22), COLOR_TEXT);
        Gfx.text(ctx, font(), Txt.tr("etmc.host.network.hint", "Network name (unique to you)"), x, y, COLOR_MUTED);
        Gfx.text(ctx, font(), Txt.tr("etmc.host.secret.hint", "Secret: plain text, keep it random, never a real password"),
                x, y + 32, COLOR_WARN);
        Gfx.text(ctx, font(), Txt.tr("etmc.host.label.hint", "World label (what joiners see)"), x, y + 64, COLOR_MUTED);
        Gfx.centered(ctx, font(), Txt.tr("etmc.host.lan_kept", "Already opened to LAN? Those settings are kept."),
                cx, y + 120, 0xFF777777);
        if (!message.isEmpty()) {
            int my = this.height - 30;
            for (String l : Wrap.lines(font(), message, Math.min(320, this.width - 20))) {
                Gfx.centered(ctx, font(), Txt.literal(l), cx, my, messageColor);
                my += 11;
            }
        }
    }

    //? if yarn {
    private net.minecraft.text.Text modeLabel() {
    //?} else {
    /*private net.minecraft.network.chat.Component modeLabel() {*/
    //?}
        String m = MODES[modeIndex];
        // Vanilla's own names for the game modes, so they read as they do everywhere else in the game.
        String name = m.isEmpty() ? Msg.tr("etmc.host.mode.same", "Same as you")
                : Txt.i18n("selectWorld.gameMode." + m.toLowerCase(java.util.Locale.ROOT));
        return Txt.tr("etmc.host.mode", "Joiners: %s", name);
    }

    //? if yarn {
    private net.minecraft.text.Text cheatsLabel() {
    //?} else {
    /*private net.minecraft.network.chat.Component cheatsLabel() {*/
    //?}
        return Txt.tr("etmc.host.cheats", "Cheats (everyone): %s", Txt.i18n(cheats ? "options.on" : "options.off"));
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

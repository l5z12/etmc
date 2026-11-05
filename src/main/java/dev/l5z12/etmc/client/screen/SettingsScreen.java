package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcHud;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.Relays;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;*/
//?}

import java.util.List;

/**
 * Relays, the two ports, and the overlay toggle. Every change — text field or toggle — is applied
 * only by <b>Save</b>; <b>Cancel</b> (or Esc) discards all of them.
 */
public final class SettingsScreen extends EtmcBaseScreen {

    private static final int W = 300;

    //? if yarn {
    private TextFieldWidget relaysField;
    private TextFieldWidget portField;
    private TextFieldWidget joinPortField;
    private ButtonWidget hudButton;
    //?} else {
    /*private EditBox relaysField;
    private EditBox portField;
    private EditBox joinPortField;
    private Button hudButton;*/
    //?}

    /** The overlay choice being edited; written to the config only on Save. */
    private boolean hudShown;

    public SettingsScreen(Screen parent) {
        super(Txt.tr("etmc.settings.title", "etmc settings"), parent);
    }

    @Override
    protected void init() {
        ModConfig cfg = EtmcManager.get().config();
        int x = this.width / 2 - W / 2;
        int y = top();

        relaysField = Ui.textField(font(), x, y + 10, W, 20, Txt.tr("etmc.settings.relays", "Relays"));
        relaysField.setMaxLength(2048);
        Ui.setText(relaysField, cfg.relaysAsText());
        add(relaysField);
        y += 44;

        portField = Ui.textField(font(), x, y + 10, 70, 20, Txt.tr("etmc.settings.port", "Port"));
        portField.setMaxLength(5);
        Ui.setText(portField, String.valueOf(cfg.defaultVirtualPort));
        add(portField);
        joinPortField = Ui.textField(font(), x + W / 2 + 4, y + 10, 70, 20, Txt.tr("etmc.settings.join_port", "Join port"));
        joinPortField.setMaxLength(5);
        Ui.setText(joinPortField, String.valueOf(cfg.joinLocalPort));
        add(joinPortField);
        y += 44;

        hudShown = cfg.hudEnabled;
        hudButton = Ui.button(hudLabel(), b -> {
            hudShown = !hudShown;
            Ui.setMessage(hudButton, hudLabel());
        }).dimensions(x, y, W, 20).build();
        hudButton.active = EtmcHud.isAvailable();
        add(hudButton);
        y += 34;

        add(Ui.button(Txt.tr("etmc.settings.save", "Save"), b -> save())
                .dimensions(x, y, W / 2 - 2, 20).build());
        add(Ui.button(Txt.translatable("gui.cancel"), b -> this.close())
                .dimensions(x + W / 2 + 2, y, W / 2 - 2, 20).build());
    }

    private int top() {
        return Math.max(36, this.height / 2 - 96);
    }

    private void save() {
        List<String> relays = Relays.parse(Ui.getText(relaysField));
        String bad = Relays.firstProblem(relays);
        if (bad != null) {
            setMessage(bad, COLOR_BAD);
            return;
        }
        Integer port = port(Ui.getText(portField), 1, Msg.tr("etmc.settings.port.hint", "Port inside the network"));
        if (port == null) return;
        Integer joinPort = port(Ui.getText(joinPortField), 0, Msg.tr("etmc.settings.join_port", "Join port"));
        if (joinPort == null) return;

        ModConfig cfg = EtmcManager.get().config();
        cfg.relays = relays;
        cfg.defaultVirtualPort = port;
        cfg.joinLocalPort = joinPort;
        cfg.hudEnabled = hudShown;
        cfg.save();
        this.close();
    }

    /** Parses a port field, reporting a bad one in the status line; {@code null} = rejected. */
    private Integer port(String text, int min, String what) {
        try {
            int p = Integer.parseInt(text.trim());
            if (p >= min && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        setMessage(Msg.tr("etmc.settings.bad_port", "%s must be a number from %s to 65535.", what, min), COLOR_BAD);
        return null;
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int x = cx - W / 2;
        int y = top();
        Gfx.centered(ctx, font(), this.title, cx, Math.max(8, y - 24), COLOR_TEXT);
        Gfx.text(ctx, font(), Txt.tr("etmc.settings.relays.hint", "Relays, for hosting (commas or spaces between them)"), x, y, COLOR_MUTED);
        Gfx.text(ctx, font(), Txt.tr("etmc.settings.relays.example", "e.g. tcp://relay.example.com:11010"), x, y + 32, 0xFF777777);
        y += 44;
        Gfx.text(ctx, font(), Txt.tr("etmc.settings.port.hint", "Port inside the network"), x, y, COLOR_MUTED);
        Gfx.text(ctx, font(), Txt.tr("etmc.settings.join_port.hint", "Join port (0 = any)"), x + W / 2 + 4, y, COLOR_MUTED);
        Gfx.text(ctx, font(), Txt.tr("etmc.settings.port.note", "advanced; default %s", EtmcConfig.DEFAULT_VIRTUAL_PORT),
                x, y + 33, 0xFF777777);
        Gfx.text(ctx, font(), Txt.tr("etmc.settings.join_port.note", "fixed helps ViaFabricPlus"), x + W / 2 + 4, y + 33, 0xFF777777);
        if (!EtmcHud.isAvailable()) {
            Gfx.centered(ctx, font(), Txt.tr("etmc.settings.no_overlay",
                    "This Minecraft/Forge version has no overlay API, so there is no overlay."),
                    cx, y + 70, 0xFF777777);
        }
        if (!message.isEmpty()) {
            int my = this.height - 30;
            for (String l : Wrap.lines(font(), message, Math.min(340, this.width - 20))) {
                Gfx.centered(ctx, font(), Txt.literal(l), cx, my, messageColor);
                my += 11;
            }
        }
    }

    //? if yarn {
    private Text hudLabel() {
    //?} else {
    /*private Component hudLabel() {*/
    //?}
        if (!EtmcHud.isAvailable()) {
            return Txt.tr("etmc.settings.overlay.unavailable", "Top-left status overlay: not available here");
        }
        return Txt.tr("etmc.settings.overlay", "Top-left status overlay: %s", hudShown
                ? Msg.tr("etmc.settings.overlay.shown", "Shown") : Msg.tr("etmc.settings.overlay.hidden", "Hidden"));
    }
}

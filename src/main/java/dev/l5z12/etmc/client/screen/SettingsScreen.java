package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcConfig;
//? if fabric {
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
//? if fabric && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if fabric {
/*import net.minecraft.client.util.math.MatrixStack;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?}

/** Configure relays (required), default virtual port, and toggles. */
public final class SettingsScreen extends EtmcBaseScreen {

    private final Screen parent;
    //? if fabric {
    private TextFieldWidget relaysField;
    private TextFieldWidget portField;
    private ButtonWidget hudButton;
    private ButtonWidget reconnectButton;
    //?} else {
    /*private EditBox relaysField;
    private EditBox portField;
    private Button hudButton;
    private Button reconnectButton;*/
    //?}

    public SettingsScreen(Screen parent) {
        super(Txt.literal("etmc settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        ModConfig cfg = EtmcManager.get().config();
        int cx = this.width / 2;
        int w = 280;
        int y = this.height / 6 + 8;

        relaysField = Ui.textField(font(), cx - w / 2, y, w, 20, Txt.literal("Relays"));
        relaysField.setMaxLength(2048);
        Ui.setText(relaysField, String.join(", ", cfg.relays));
        add(relaysField);
        y += 40;

        portField = Ui.textField(font(), cx - w / 2, y, 80, 20, Txt.literal("Port"));
        portField.setMaxLength(5);
        Ui.setText(portField, String.valueOf(cfg.defaultVirtualPort));
        add(portField);
        y += 36;

        hudButton = Ui.button(hudLabel(cfg), b -> {
            cfg.hudEnabled = !cfg.hudEnabled;
            hudButton.setMessage(hudLabel(cfg));
        }).dimensions(cx - w / 2, y, w / 2 - 4, 20).build();
        add(hudButton);

        reconnectButton = Ui.button(reconnectLabel(cfg), b -> {
            cfg.autoReconnect = !cfg.autoReconnect;
            reconnectButton.setMessage(reconnectLabel(cfg));
        }).dimensions(cx + 4, y, w / 2 - 4, 20).build();
        add(reconnectButton);
        y += 30;

        add(Ui.button(Txt.literal("Save"), b -> save())
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        add(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void save() {
        ModConfig cfg = EtmcManager.get().config();
        cfg.setRelaysFromText(Ui.getText(relaysField));
        try {
            int p = Integer.parseInt(Ui.getText(portField).trim());
            if (p > 0 && p <= 65535) cfg.defaultVirtualPort = p;
        } catch (NumberFormatException ignored) {
        }
        cfg.save();
        this.close();
    }

    @Override
    //? if fabric && >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else if fabric {
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)*/
    //?} else {
    /*public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta)*/
    //?}
    {
        super.render(ctx, mouseX, mouseY, delta);
        Gfx.centered(ctx, font(), this.title, this.width / 2, 18, 0xFFFFFF);
        int cx = this.width / 2;
        int w = 280;
        Gfx.text(ctx, font(),
                Txt.literal("Relay URIs (comma-separated, e.g. tcp://my.relay:11010)"),
                cx - w / 2, this.height / 6 - 4, 0xFFAAAAAA);
        Gfx.text(ctx, font(), Txt.literal("Host virtual port (default "
                        + EtmcConfig.DEFAULT_VIRTUAL_PORT + ")"),
                cx - w / 2, this.height / 6 + 34, 0xFFAAAAAA);
    }

    //? if fabric {
    private static Text hudLabel(ModConfig cfg) {
    //?} else {
    /*private static Component hudLabel(ModConfig cfg) {*/
    //?}
        return Txt.literal("HUD: " + (cfg.hudEnabled ? "ON" : "OFF"));
    }

    //? if fabric {
    private static Text reconnectLabel(ModConfig cfg) {
    //?} else {
    /*private static Component reconnectLabel(ModConfig cfg) {*/
    //?}
        return Txt.literal("Auto-reconnect: " + (cfg.autoReconnect ? "ON" : "OFF"));
    }

    //? if fabric && >=1.18 {
    @Override
    //?}
    public void close() {
        mc().setScreen(parent);
    }

    //? if !fabric || <1.18 {
    /*@Override
    public void onClose() {
        this.close();
    }*/
    //?}
}

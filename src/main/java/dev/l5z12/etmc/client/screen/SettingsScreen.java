package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
//? if >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else
/*import net.minecraft.client.util.math.MatrixStack;*/
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Configure relays (required), default virtual port, and toggles. */
public final class SettingsScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget relaysField;
    private TextFieldWidget portField;
    private ButtonWidget hudButton;
    private ButtonWidget reconnectButton;

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

        relaysField = new TextFieldWidget(this.textRenderer, cx - w / 2, y, w, 20, Txt.literal("Relays"));
        relaysField.setMaxLength(2048);
        relaysField.setText(String.join(", ", cfg.relays));
        addDrawableChild(relaysField);
        y += 40;

        portField = new TextFieldWidget(this.textRenderer, cx - w / 2, y, 80, 20, Txt.literal("Port"));
        portField.setMaxLength(5);
        portField.setText(String.valueOf(cfg.defaultVirtualPort));
        addDrawableChild(portField);
        y += 36;

        hudButton = Ui.button(hudLabel(cfg), b -> {
            cfg.hudEnabled = !cfg.hudEnabled;
            hudButton.setMessage(hudLabel(cfg));
        }).dimensions(cx - w / 2, y, w / 2 - 4, 20).build();
        addDrawableChild(hudButton);

        reconnectButton = Ui.button(reconnectLabel(cfg), b -> {
            cfg.autoReconnect = !cfg.autoReconnect;
            reconnectButton.setMessage(reconnectLabel(cfg));
        }).dimensions(cx + 4, y, w / 2 - 4, 20).build();
        addDrawableChild(reconnectButton);
        y += 30;

        addDrawableChild(Ui.button(Txt.literal("Save"), b -> save())
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        addDrawableChild(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void save() {
        ModConfig cfg = EtmcManager.get().config();
        cfg.setRelaysFromText(relaysField.getText());
        try {
            int p = Integer.parseInt(portField.getText().trim());
            if (p > 0 && p <= 65535) cfg.defaultVirtualPort = p;
        } catch (NumberFormatException ignored) {
        }
        cfg.save();
        this.close();
    }

    @Override
    //? if >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)*/
    {
        super.render(ctx, mouseX, mouseY, delta);
        Gfx.centered(ctx, this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFF);
        int cx = this.width / 2;
        int w = 280;
        Gfx.text(ctx, this.textRenderer,
                Txt.literal("Relay URIs (comma-separated, e.g. tcp://my.relay:11010)"),
                cx - w / 2, this.height / 6 - 4, 0xFFAAAAAA);
        Gfx.text(ctx, this.textRenderer, Txt.literal("Host virtual port (default "
                        + EtmcConfig.DEFAULT_VIRTUAL_PORT + ")"),
                cx - w / 2, this.height / 6 + 34, 0xFFAAAAAA);
    }

    private static Text hudLabel(ModConfig cfg) {
        return Txt.literal("HUD: " + (cfg.hudEnabled ? "ON" : "OFF"));
    }

    private static Text reconnectLabel(ModConfig cfg) {
        return Txt.literal("Auto-reconnect: " + (cfg.autoReconnect ? "ON" : "OFF"));
    }

    //? if >=1.18 {
    @Override
    //?}
    public void close() {
        this.client.setScreen(parent);
    }

    //? if <1.18 {
    /*@Override
    public void onClose() {
        this.close();
    }*/
    //?}
}

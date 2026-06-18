package dev.l5z12.etmc.mc.screen;

import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.mc.EtmcClientCore;
import dev.l5z12.etmc.mc.McConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Relays + toggles (Mojmap). */
public final class SettingsScreen extends Screen {

    private final Screen parent;
    private EditBox relaysField;
    private EditBox portField;
    private Button hudButton;
    private Button reconnectButton;

    public SettingsScreen(Screen parent) {
        super(Component.literal("etmc settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        McConfig cfg = EtmcClientCore.get().config();
        int cx = this.width / 2;
        int w = 280;
        int y = this.height / 6 + 8;

        relaysField = new EditBox(this.font, cx - w / 2, y, w, 20, Component.literal("Relays"));
        relaysField.setMaxLength(2048);
        relaysField.setValue(String.join(", ", cfg.relays));
        addRenderableWidget(relaysField);
        y += 40;

        portField = new EditBox(this.font, cx - w / 2, y, 80, 20, Component.literal("Port"));
        portField.setMaxLength(5);
        portField.setValue(String.valueOf(cfg.defaultVirtualPort));
        addRenderableWidget(portField);
        y += 36;

        hudButton = Button.builder(hudLabel(cfg), b -> {
            cfg.hudEnabled = !cfg.hudEnabled;
            hudButton.setMessage(hudLabel(cfg));
        }).bounds(cx - w / 2, y, w / 2 - 4, 20).build();
        addRenderableWidget(hudButton);

        reconnectButton = Button.builder(reconnectLabel(cfg), b -> {
            cfg.autoReconnect = !cfg.autoReconnect;
            reconnectButton.setMessage(reconnectLabel(cfg));
        }).bounds(cx + 4, y, w / 2 - 4, 20).build();
        addRenderableWidget(reconnectButton);
        y += 30;

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(cx - w / 2, y, w, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(cx - w / 2, y, w, 20).build());
    }

    private void save() {
        McConfig cfg = EtmcClientCore.get().config();
        cfg.setRelaysFromText(relaysField.getValue());
        try {
            int p = Integer.parseInt(portField.getValue().trim());
            if (p > 0 && p <= 65535) cfg.defaultVirtualPort = p;
        } catch (NumberFormatException ignored) {
        }
        cfg.save();
        this.onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        int cx = this.width / 2;
        int w = 280;
        g.drawString(this.font, Component.literal("Relay URIs (comma-separated, e.g. tcp://my.relay:11010)"),
                cx - w / 2, this.height / 6 - 4, 0xAAAAAA);
        g.drawString(this.font, Component.literal("Host virtual port (default " + EtmcConfig.DEFAULT_VIRTUAL_PORT + ")"),
                cx - w / 2, this.height / 6 + 34, 0xAAAAAA);
    }

    private static Component hudLabel(McConfig cfg) {
        return Component.literal("HUD: " + (cfg.hudEnabled ? "ON" : "OFF"));
    }

    private static Component reconnectLabel(McConfig cfg) {
        return Component.literal("Auto-reconnect: " + (cfg.autoReconnect ? "ON" : "OFF"));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}

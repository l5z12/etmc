package dev.l5z12.etmc.mc.screen;

import dev.l5z12.etmc.mc.EtmcClientCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Connect to a server from a hosted EasyTier config URL (Mojmap). */
public final class ConnectUrlScreen extends Screen {

    private final Screen parent;
    private EditBox urlField;
    private EditBox serverField;
    private Button connectButton;
    private volatile String message = "";
    private volatile int messageColor = 0xAAAAAA;

    public ConnectUrlScreen(Screen parent) {
        super(Component.literal("Connect via config URL"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 280;
        int y = this.height / 4;

        urlField = new EditBox(this.font, cx - w / 2, y + 12, w, 20, Component.literal("Config URL"));
        urlField.setMaxLength(1024);
        addRenderableWidget(urlField);
        setInitialFocus(urlField);
        y += 44;

        serverField = new EditBox(this.font, cx - w / 2, y + 12, w, 20, Component.literal("Server"));
        serverField.setMaxLength(64);
        addRenderableWidget(serverField);
        y += 48;

        addRenderableWidget(Button.builder(Component.literal("Paste URL"),
                        b -> urlField.setValue(this.minecraft.keyboardHandler.getClipboard().trim()))
                .bounds(cx - w / 2, y, w, 20).build());
        y += 28;

        connectButton = Button.builder(Component.literal("Fetch & connect"), b -> connect())
                .bounds(cx - w / 2, y, w, 20).build();
        connectButton.active = EtmcClientCore.get().isReady();
        addRenderableWidget(connectButton);
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(cx - w / 2, y, w, 20).build());
    }

    private void connect() {
        String url = urlField.getValue().trim();
        if (url.isEmpty()) {
            set("Enter a config URL.", 0xFF5555);
            return;
        }
        String override = serverField.getValue().trim();
        connectButton.active = false;
        set("Fetching config…", 0xFFFF55);
        EtmcClientCore.get().connectUrlAsync(url, override.isEmpty() ? null : override)
                .whenComplete((port, err) -> this.minecraft.execute(() -> {
                    if (err != null) {
                        set("Failed: " + root(err), 0xFF5555);
                        connectButton.active = true;
                    }
                }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 22, 0xFFFFFF);
        int cx = this.width / 2;
        int w = 280;
        g.drawString(this.font, Component.literal("Config URL (http/https to an EasyTier config)"),
                cx - w / 2, this.height / 4, 0xAAAAAA);
        g.drawString(this.font, Component.literal("Server ip:port (optional if config has [etmc] server)"),
                cx - w / 2, this.height / 4 + 44, 0xAAAAAA);
        if (!message.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal(message), cx, this.height - 38, messageColor);
        }
    }

    private void set(String m, int c) {
        this.message = m;
        this.messageColor = c;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

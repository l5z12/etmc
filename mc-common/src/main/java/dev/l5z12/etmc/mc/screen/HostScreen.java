package dev.l5z12.etmc.mc.screen;

import dev.l5z12.etmc.mc.EtmcClientCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Host the current singleplayer world (Mojmap). */
public final class HostScreen extends Screen {

    private final Screen parent;
    private EditBox networkField;
    private EditBox secretField;
    private Button hostButton;
    private volatile String message = "";
    private volatile int messageColor = 0xAAAAAA;

    public HostScreen(Screen parent) {
        super(Component.literal("Host a world"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        EtmcClientCore core = EtmcClientCore.get();
        int cx = this.width / 2;
        int w = 220;
        int y = this.height / 4;

        networkField = new EditBox(this.font, cx - w / 2, y + 12, w, 20, Component.literal("Network name"));
        networkField.setMaxLength(64);
        networkField.setValue(orDefault(core.config().lastNetworkName, "my-world"));
        addRenderableWidget(networkField);
        y += 44;

        secretField = new EditBox(this.font, cx - w / 2, y + 12, w, 20, Component.literal("Secret"));
        secretField.setMaxLength(128);
        secretField.setValue(orDefault(core.config().lastSecret, ""));
        addRenderableWidget(secretField);
        y += 52;

        hostButton = Button.builder(Component.literal("Start hosting"), b -> startHosting())
                .bounds(cx - w / 2, y, w, 20).build();
        hostButton.active = core.isReady() && core.config().hasRelay();
        addRenderableWidget(hostButton);
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(cx - w / 2, y, w, 20).build());

        if (!core.config().hasRelay()) {
            message = "Add a relay in Settings before hosting.";
            messageColor = 0xFFAA00;
        }
        setInitialFocus(networkField);
    }

    private void startHosting() {
        EtmcClientCore core = EtmcClientCore.get();
        String network = networkField.getValue().trim();
        if (network.isEmpty()) {
            set("Enter a network name.", 0xFF5555);
            return;
        }
        if (!core.config().hasRelay()) {
            set("No relay configured (Settings).", 0xFF5555);
            return;
        }
        hostButton.active = false;
        set("Starting…", 0xFFFF55);
        core.hostAsync(network, secretField.getValue()).whenComplete((code, err) ->
                this.minecraft.execute(() -> {
                    if (err != null) {
                        set("Failed: " + root(err), 0xFF5555);
                        hostButton.active = true;
                    } else {
                        this.minecraft.setScreen(new StatusScreen(parent));
                    }
                }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        int cx = this.width / 2;
        int w = 220;
        g.drawString(this.font, Component.literal("Network name"), cx - w / 2, this.height / 4, 0xAAAAAA);
        g.drawString(this.font, Component.literal("Secret (optional, must match for peers)"),
                cx - w / 2, this.height / 4 + 32, 0xAAAAAA);
        if (!message.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal(message), cx, this.height - 40, messageColor);
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

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private static String root(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

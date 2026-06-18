package dev.l5z12.etmc.mc.screen;

import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.mc.EtmcClientCore;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Join via an ETMC1: code (Mojmap). */
public final class JoinScreen extends Screen {

    private final Screen parent;
    private EditBox codeField;
    private Button joinButton;
    private volatile String message = "";
    private volatile int messageColor = 0xAAAAAA;

    public JoinScreen(Screen parent) {
        super(Component.literal("Join a world"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 260;
        int y = this.height / 4 + 12;

        codeField = new EditBox(this.font, cx - w / 2, y, w, 20, Component.literal("Join code"));
        codeField.setMaxLength(4096);
        addRenderableWidget(codeField);
        setInitialFocus(codeField);
        y += 28;

        addRenderableWidget(Button.builder(Component.literal("Paste from clipboard"),
                        b -> codeField.setValue(this.minecraft.keyboardHandler.getClipboard().trim()))
                .bounds(cx - w / 2, y, w, 20).build());
        y += 28;

        joinButton = Button.builder(Component.literal("Join"), b -> startJoining())
                .bounds(cx - w / 2, y, w, 20).build();
        joinButton.active = EtmcClientCore.get().isReady();
        addRenderableWidget(joinButton);
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(cx - w / 2, y, w, 20).build());
    }

    private void startJoining() {
        JoinCode jc;
        try {
            jc = JoinCode.decode(codeField.getValue());
        } catch (IllegalArgumentException e) {
            set("Bad code: " + e.getMessage(), 0xFF5555);
            return;
        }
        joinButton.active = false;
        set("Joining '" + jc.networkName + "'…", 0xFFFF55);
        EtmcClientCore.get().joinAsync(jc).whenComplete((port, err) ->
                this.minecraft.execute(() -> {
                    if (err != null) {
                        set("Failed: " + root(err), 0xFF5555);
                        joinButton.active = true;
                    }
                }));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
        super.render(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 24, 0xFFFFFF);
        g.drawCenteredString(this.font, Component.literal("Paste the ETMC1:… code your host shared"),
                this.width / 2, this.height / 4 - 4, 0xAAAAAA);
        if (!message.isEmpty()) {
            g.drawCenteredString(this.font, Component.literal(message), this.width / 2, this.height - 40, messageColor);
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

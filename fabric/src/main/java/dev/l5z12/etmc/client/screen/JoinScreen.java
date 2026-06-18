package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.JoinCode;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Join a hosted world by pasting an {@code ETMC1:} join code. */
public final class JoinScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget codeField;
    private ButtonWidget joinButton;
    private volatile String message = "";
    private volatile int messageColor = 0xFFAAAAAA;

    public JoinScreen(Screen parent) {
        super(Text.literal("Join a world"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 260;
        int y = this.height / 4 + 12;

        codeField = new TextFieldWidget(this.textRenderer, cx - w / 2, y, w, 20, Text.literal("Join code"));
        codeField.setMaxLength(4096);
        addDrawableChild(codeField);
        setInitialFocus(codeField);
        y += 28;

        addDrawableChild(ButtonWidget.builder(Text.literal("Paste from clipboard"), b -> {
            codeField.setText(this.client.keyboard.getClipboard().trim());
        }).dimensions(cx - w / 2, y, w, 20).build());
        y += 28;

        joinButton = ButtonWidget.builder(Text.literal("Join"), b -> startJoining())
                .dimensions(cx - w / 2, y, w, 20).build();
        joinButton.active = EtmcManager.get().isReady();
        addDrawableChild(joinButton);
        y += 24;

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void startJoining() {
        EtmcManager m = EtmcManager.get();
        JoinCode jc;
        try {
            jc = JoinCode.decode(codeField.getText());
        } catch (IllegalArgumentException e) {
            setMessage("Bad code: " + e.getMessage(), 0xFFFF5555);
            return;
        }
        joinButton.active = false;
        setMessage("Joining '" + jc.networkName + "'…", 0xFFFFFF55);
        m.joinAsync(jc).whenComplete((port, err) ->
                this.client.execute(() -> {
                    if (err != null) {
                        setMessage("Failed: " + rootMessage(err), 0xFFFF5555);
                        joinButton.active = true;
                    }
                    // on success, McNet has already switched to the connect screen
                }));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 24, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Paste the ETMC1:… code your host shared"), this.width / 2, this.height / 4 - 4, 0xFFAAAAAA);
        if (!message.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(message), this.width / 2, this.height - 40, messageColor);
        }
    }

    private void setMessage(String msg, int color) {
        this.message = msg;
        this.messageColor = color;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

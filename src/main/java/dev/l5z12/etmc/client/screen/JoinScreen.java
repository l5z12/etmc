package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.JoinCode;
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

/** Join a hosted world by pasting an {@code ETMC1:} join code. */
public final class JoinScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget codeField;
    private ButtonWidget joinButton;
    private volatile String message = "";
    private volatile int messageColor = 0xFFAAAAAA;

    public JoinScreen(Screen parent) {
        super(Txt.literal("Join a world"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 260;
        int y = this.height / 4 + 12;

        codeField = new TextFieldWidget(this.textRenderer, cx - w / 2, y, w, 20, Txt.literal("Join code"));
        codeField.setMaxLength(4096);
        addDrawableChild(codeField);
        setInitialFocus(codeField);
        y += 28;

        addDrawableChild(Ui.button(Txt.literal("Paste from clipboard"), b -> {
            codeField.setText(this.client.keyboard.getClipboard().trim());
        }).dimensions(cx - w / 2, y, w, 20).build());
        y += 28;

        joinButton = Ui.button(Txt.literal("Join"), b -> startJoining())
                .dimensions(cx - w / 2, y, w, 20).build();
        joinButton.active = EtmcManager.get().isReady();
        addDrawableChild(joinButton);
        y += 24;

        addDrawableChild(Ui.button(Txt.literal("Back"), b -> this.close())
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
    //? if >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)*/
    {
        super.render(ctx, mouseX, mouseY, delta);
        Gfx.centered(ctx, this.textRenderer, this.title, this.width / 2, 24, 0xFFFFFF);
        Gfx.centered(ctx, this.textRenderer,
                Txt.literal("Paste the ETMC1:… code your host shared"), this.width / 2, this.height / 4 - 4, 0xFFAAAAAA);
        if (!message.isEmpty()) {
            Gfx.centered(ctx, this.textRenderer, Txt.literal(message), this.width / 2, this.height - 40, messageColor);
        }
    }

    private void setMessage(String msg, int color) {
        this.message = msg;
        this.messageColor = color;
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

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

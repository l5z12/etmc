package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.JoinCode;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
//?} else {
/*import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;*/
//?}
//? if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn && >=1.16 {
/*import net.minecraft.client.util.math.MatrixStack;
*///?} else if yarn {
//?} else if <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//?} else if <26 {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//?}

/** Join a hosted world by pasting an {@code ETMC1:} join code. */
public final class JoinScreen extends EtmcBaseScreen {

    //? if yarn {
    private TextFieldWidget codeField;
    private ButtonWidget joinButton;
    //?} else {
    /*private EditBox codeField;
    private Button joinButton;*/
    //?}

    public JoinScreen(Screen parent) {
        super(Txt.literal("Join a world"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 260;
        int y = this.height / 4 + 12;

        codeField = Ui.textField(font(), cx - w / 2, y, w, 20, Txt.literal("Join code"));
        codeField.setMaxLength(4096);
        add(codeField);
        //? if >=1.17 {
        setInitialFocus(codeField);
        //?}
        y += 28;

        add(Ui.button(Txt.literal("Paste from clipboard"), b -> {
            //? if yarn {
            Ui.setText(codeField, mc().keyboard.getClipboard().trim());
            //?} else {
            /*Ui.setText(codeField, mc().keyboardHandler.getClipboard().trim());*/
            //?}
        }).dimensions(cx - w / 2, y, w, 20).build());
        y += 28;

        joinButton = Ui.button(Txt.literal("Join"), b -> startJoining())
                .dimensions(cx - w / 2, y, w, 20).build();
        joinButton.active = EtmcManager.get().isReady();
        add(joinButton);
        y += 24;

        add(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void startJoining() {
        EtmcManager m = EtmcManager.get();
        JoinCode jc;
        try {
            jc = JoinCode.decode(Ui.getText(codeField));
        } catch (IllegalArgumentException e) {
            setMessage("Bad code: " + e.getMessage(), COLOR_BAD);
            return;
        }
        joinButton.active = false;
        setMessage("Joining '" + jc.networkName + "'…", COLOR_BUSY);
        m.joinAsync(jc).whenComplete((port, err) ->
                mc().execute(() -> {
                    if (err != null) {
                        setMessage("Failed: " + Errors.message(err), COLOR_BAD);
                        joinButton.active = true;
                    }
                    // on success, McNet has already switched to the connect screen
                }));
    }

    @Override
    //? if yarn && >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else if yarn && >=1.16 {
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)
    *///?} else if yarn {
    /*public void render(int mouseX, int mouseY, float delta)*/
    //?} else if <1.20 {
    /*public void render(PoseStack ctx, int mouseX, int mouseY, float delta)*/
    //?} else if <26 {
    /*public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta)*/
    //?} else {
    /*public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta)*/
    //?}
    {
        //? if yarn && <1.16 {
        /*int ctx = 0;*/
        //?}
        renderBackdrop(ctx, mouseX, mouseY, delta);
        Gfx.centered(ctx, font(), this.title, this.width / 2, 24, COLOR_TEXT);
        Gfx.centered(ctx, font(),
                Txt.literal("Paste the ETMC1:… code your host shared"), this.width / 2, this.height / 4 - 4, COLOR_MUTED);
        if (!message.isEmpty()) {
            Gfx.centered(ctx, font(), Txt.literal(message), this.width / 2, this.height - 40, messageColor);
        }
    }
}

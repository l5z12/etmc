package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.Errors;
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

/**
 * Connect to an EasyTier-networked Minecraft server from a hosted config: paste the HTTP(S) link to
 * the server's EasyTier config (network, relay, ip). If the config doesn't carry an
 * {@code [etmc] server = "ip:port"} line, fill the optional Server field.
 */
public final class ConnectUrlScreen extends EtmcBaseScreen {

    //? if yarn {
    private TextFieldWidget urlField;
    private TextFieldWidget serverField;
    private ButtonWidget connectButton;
    //?} else {
    /*private EditBox urlField;
    private EditBox serverField;
    private Button connectButton;*/
    //?}

    public ConnectUrlScreen(Screen parent) {
        super(Txt.literal("Connect via config URL"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 280;
        int y = this.height / 4;

        urlField = Ui.textField(font(), cx - w / 2, y + 12, w, 20, Txt.literal("Config URL"));
        urlField.setMaxLength(1024);
        add(urlField);
        //? if >=1.17 {
        setInitialFocus(urlField);
        //?}
        y += 44;

        serverField = Ui.textField(font(), cx - w / 2, y + 12, w, 20, Txt.literal("Server"));
        serverField.setMaxLength(64);
        add(serverField);
        y += 48;

        add(Ui.button(Txt.literal("Paste URL"), b -> {
                    //? if yarn {
                    Ui.setText(urlField, mc().keyboard.getClipboard().trim());
                    //?} else {
                    /*Ui.setText(urlField, mc().keyboardHandler.getClipboard().trim());*/
                    //?}
                })
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 28;

        connectButton = Ui.button(Txt.literal("Fetch & connect"), b -> connect())
                .dimensions(cx - w / 2, y, w, 20).build();
        connectButton.active = EtmcManager.get().isReady();
        add(connectButton);
        y += 24;

        add(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void connect() {
        String url = Ui.getText(urlField).trim();
        if (url.isEmpty()) {
            setMessage("Enter a config URL.", COLOR_BAD);
            return;
        }
        String override = Ui.getText(serverField).trim();
        connectButton.active = false;
        setMessage("Fetching config…", COLOR_BUSY);
        EtmcManager.get().connectUrlAsync(url, override.isEmpty() ? null : override)
                .whenComplete((port, err) -> mc().execute(() -> {
                    if (err != null) {
                        setMessage("Failed: " + Errors.message(err), COLOR_BAD);
                        connectButton.active = true;
                    }
                    // on success McNet has switched to the connect screen
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
        Gfx.centered(ctx, font(), this.title, this.width / 2, 22, COLOR_TEXT);
        int cx = this.width / 2;
        int w = 280;
        Gfx.text(ctx, font(), Txt.literal("Config URL (http/https to an EasyTier config)"),
                cx - w / 2, this.height / 4, COLOR_MUTED);
        Gfx.text(ctx, font(), Txt.literal("Server ip:port (optional if config has [etmc] server)"),
                cx - w / 2, this.height / 4 + 44, COLOR_MUTED);
        if (!message.isEmpty()) {
            Gfx.centered(ctx, font(), Txt.literal(message), cx, this.height - 38, messageColor);
        }
    }
}

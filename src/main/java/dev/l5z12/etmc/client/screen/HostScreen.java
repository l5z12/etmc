package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
//? if fabric {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
//?} else {
/*import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;*/
//?}
//? if fabric && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if fabric {
/*import net.minecraft.client.util.math.MatrixStack;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?}

/** Host the current singleplayer world: pick a network name + optional secret, then start. */
public final class HostScreen extends EtmcBaseScreen {

    private final Screen parent;
    //? if fabric {
    private TextFieldWidget networkField;
    private TextFieldWidget secretField;
    private ButtonWidget hostButton;
    //?} else {
    /*private EditBox networkField;
    private EditBox secretField;
    private Button hostButton;*/
    //?}
    private volatile String message = "";
    private volatile int messageColor = 0xFFAAAAAA;

    public HostScreen(Screen parent) {
        super(Txt.literal("Host a world"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        EtmcManager m = EtmcManager.get();
        int cx = this.width / 2;
        int w = 220;
        int y = this.height / 4;

        networkField = Ui.textField(font(), cx - w / 2, y + 12, w, 20, Txt.literal("Network name"));
        networkField.setMaxLength(64);
        Ui.setText(networkField, orDefault(m.config().lastNetworkName, "my-world"));
        add(networkField);
        y += 44;

        secretField = Ui.textField(font(), cx - w / 2, y + 12, w, 20, Txt.literal("Secret (optional)"));
        secretField.setMaxLength(128);
        Ui.setText(secretField, orDefault(m.config().lastSecret, ""));
        add(secretField);
        y += 52;

        hostButton = Ui.button(Txt.literal("Start hosting"), b -> startHosting())
                .dimensions(cx - w / 2, y, w, 20).build();
        hostButton.active = m.isReady() && m.config().hasRelay();
        add(hostButton);
        y += 24;

        add(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());

        if (!m.config().hasRelay()) {
            message = "Add a relay in Settings before hosting.";
            messageColor = 0xFFFFAA00;
        }
    }

    private void startHosting() {
        EtmcManager m = EtmcManager.get();
        String network = Ui.getText(networkField).trim();
        if (network.isEmpty()) {
            setMessage("Enter a network name.", 0xFFFF5555);
            return;
        }
        if (!m.config().hasRelay()) {
            setMessage("No relay configured (Settings).", 0xFFFF5555);
            return;
        }
        hostButton.active = false;
        setMessage("Starting…", 0xFFFFFF55);
        m.hostAsync(network, Ui.getText(secretField)).whenComplete((code, err) ->
                mc().execute(() -> {
                    if (err != null) {
                        setMessage("Failed: " + rootMessage(err), 0xFFFF5555);
                        hostButton.active = true;
                    } else {
                        mc().setScreen(new StatusScreen(parent));
                    }
                }));
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
        Gfx.centered(ctx, font(), this.title, this.width / 2, 24, 0xFFFFFF);
        int cx = this.width / 2;
        int w = 220;
        Gfx.text(ctx, font(), Txt.literal("Network name"), cx - w / 2, this.height / 4, 0xFFAAAAAA);
        Gfx.text(ctx, font(), Txt.literal("Secret (optional, must match for peers)"),
                cx - w / 2, this.height / 4 + 32, 0xFFAAAAAA);
        if (!message.isEmpty()) {
            Gfx.centered(ctx, font(), Txt.literal(message), cx, this.height - 40, messageColor);
        }
    }

    private void setMessage(String msg, int color) {
        this.message = msg;
        this.messageColor = color;
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

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

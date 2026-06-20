package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
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

/** Host the current singleplayer world: pick a network name + optional secret, then start. */
public final class HostScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget networkField;
    private TextFieldWidget secretField;
    private ButtonWidget hostButton;
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

        networkField = new TextFieldWidget(this.textRenderer, cx - w / 2, y + 12, w, 20, Txt.literal("Network name"));
        networkField.setMaxLength(64);
        networkField.setText(orDefault(m.config().lastNetworkName, "my-world"));
        addDrawableChild(networkField);
        y += 44;

        secretField = new TextFieldWidget(this.textRenderer, cx - w / 2, y + 12, w, 20, Txt.literal("Secret (optional)"));
        secretField.setMaxLength(128);
        secretField.setText(orDefault(m.config().lastSecret, ""));
        addDrawableChild(secretField);
        y += 52;

        hostButton = Ui.button(Txt.literal("Start hosting"), b -> startHosting())
                .dimensions(cx - w / 2, y, w, 20).build();
        hostButton.active = m.isReady() && m.config().hasRelay();
        addDrawableChild(hostButton);
        y += 24;

        addDrawableChild(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());

        if (!m.config().hasRelay()) {
            message = "Add a relay in Settings before hosting.";
            messageColor = 0xFFFFAA00;
        }
    }

    private void startHosting() {
        EtmcManager m = EtmcManager.get();
        String network = networkField.getText().trim();
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
        m.hostAsync(network, secretField.getText()).whenComplete((code, err) ->
                this.client.execute(() -> {
                    if (err != null) {
                        setMessage("Failed: " + rootMessage(err), 0xFFFF5555);
                        hostButton.active = true;
                    } else {
                        this.client.setScreen(new StatusScreen(parent));
                    }
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
        int cx = this.width / 2;
        int w = 220;
        Gfx.text(ctx, this.textRenderer, Txt.literal("Network name"), cx - w / 2, this.height / 4, 0xFFAAAAAA);
        Gfx.text(ctx, this.textRenderer, Txt.literal("Secret (optional, must match for peers)"),
                cx - w / 2, this.height / 4 + 32, 0xFFAAAAAA);
        if (!message.isEmpty()) {
            Gfx.centered(ctx, this.textRenderer, Txt.literal(message), cx, this.height - 40, messageColor);
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

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

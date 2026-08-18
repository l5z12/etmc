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

/** Host the current singleplayer world: pick a network name + optional secret, then start. */
public final class HostScreen extends EtmcBaseScreen {

    //? if yarn {
    private TextFieldWidget networkField;
    private TextFieldWidget secretField;
    private ButtonWidget hostButton;
    //?} else {
    /*private EditBox networkField;
    private EditBox secretField;
    private Button hostButton;*/
    //?}

    public HostScreen(Screen parent) {
        super(Txt.literal("Host a world"), parent);
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
            setMessage("Add a relay in Settings before hosting.", COLOR_WARN);
        }
    }

    private void startHosting() {
        EtmcManager m = EtmcManager.get();
        String network = Ui.getText(networkField).trim();
        if (network.isEmpty()) {
            setMessage("Enter a network name.", COLOR_BAD);
            return;
        }
        if (!m.config().hasRelay()) {
            setMessage("No relay configured (Settings).", COLOR_BAD);
            return;
        }
        hostButton.active = false;
        setMessage("Starting…", COLOR_BUSY);
        m.hostAsync(network, Ui.getText(secretField)).whenComplete((code, err) ->
                mc().execute(() -> {
                    if (err != null) {
                        setMessage("Failed: " + Errors.message(err), COLOR_BAD);
                        hostButton.active = true;
                    } else {
                        goTo(new StatusScreen(parent));
                    }
                }));
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        Gfx.centered(ctx, font(), this.title, this.width / 2, 24, COLOR_TEXT);
        int cx = this.width / 2;
        int w = 220;
        Gfx.text(ctx, font(), Txt.literal("Network name"), cx - w / 2, this.height / 4, COLOR_MUTED);
        Gfx.text(ctx, font(), Txt.literal("Secret (optional, must match for peers)"),
                cx - w / 2, this.height / 4 + 32, COLOR_MUTED);
        if (!message.isEmpty()) {
            Gfx.centered(ctx, font(), Txt.literal(message), cx, this.height - 40, messageColor);
        }
    }

    private static String orDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }
}

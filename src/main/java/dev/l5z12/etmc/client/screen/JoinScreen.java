package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.Msg;
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

/**
 * Join a hosted world from the {@code etmc://} link (or {@code ETMC1:} code — the same thing) the
 * host shared.
 *
 * <p>Outside a world this takes the same path as pasting the link into Direct Connect: it waits for
 * the host to be reachable and connects by itself. Inside a world Minecraft can't switch servers
 * directly, so it starts the network and adds an "etmc: ..." entry to the server list instead.
 */
public final class JoinScreen extends EtmcBaseScreen {

    //? if yarn {
    private TextFieldWidget codeField;
    private ButtonWidget joinButton;
    //?} else {
    /*private EditBox codeField;
    private Button joinButton;*/
    //?}

    public JoinScreen(Screen parent) {
        super(Txt.tr("etmc.join.title", "Join a world"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 260;
        int y = this.height / 4 + 12;

        codeField = Ui.textField(font(), cx - w / 2, y, w, 20, Txt.tr("etmc.join.field", "etmc:// link or code"));
        codeField.setMaxLength(4096);
        add(codeField);
        //? if >=1.17 {
        setInitialFocus(codeField);
        //?}
        y += 28;

        add(Ui.button(Txt.tr("etmc.join.paste", "Paste from clipboard"),
                        b -> Ui.setText(codeField, McScreens.getClipboard().trim()))
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 28;

        joinButton = Ui.button((McScreens.inWorld() ? Txt.tr("etmc.join.add_to_list", "Add to server list")
                : Txt.tr("etmc.join.join", "Join")), b -> startJoining())
                .dimensions(cx - w / 2, y, w, 20).build();
        joinButton.active = EtmcManager.get().isReady();
        add(joinButton);
        y += 24;

        add(Ui.button(Txt.translatable("gui.back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void startJoining() {
        EtmcManager m = EtmcManager.get();
        String text = Ui.getText(codeField).trim();
        JoinCode jc;
        try {
            jc = JoinCode.decode(text);
        } catch (IllegalArgumentException e) {
            setMessage(Msg.tr("etmc.join.not_a_link",
                    "That isn't an etmc link or code (%s). Copy it from the host again.", e.getMessage()), COLOR_BAD);
            return;
        }
        if (!McScreens.inWorld()) {
            // Same path as Direct Connect: waits for the host, then connects by itself.
            m.connectViaLink(this, text);
            return;
        }
        joinButton.active = false;
        setMessage(Msg.tr("etmc.join.starting", "Starting the network for '%s'…", jc.displayLabel()), COLOR_BUSY);
        m.joinAsync(jc).whenComplete((port, err) ->
                mc().execute(() -> {
                    if (err != null) {
                        setMessage(Msg.tr("etmc.join.failed", "Couldn't join: %s", Errors.message(err)), COLOR_BAD);
                        joinButton.active = true;
                    }
                    // on success, McNet has already closed this screen and posted where the entry went
                }));
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        Gfx.centered(ctx, font(), this.title, cx, 24, COLOR_TEXT);
        Gfx.centered(ctx, font(), Txt.tr("etmc.join.hint", "Paste the etmc:// link (or ETMC1: code) your host shared"),
                cx, this.height / 4 - 4, COLOR_MUTED);
        if (McScreens.inWorld()) {
            Gfx.centered(ctx, font(),
                    Txt.tr("etmc.join.in_world",
                            "You're in a world: this adds the host to your server list, to join after you leave."),
                    cx, this.height / 4 + 120, 0xFF777777);
        }
        if (!message.isEmpty()) {
            int y = this.height - 40;
            for (String l : Wrap.lines(font(), message, Math.min(320, this.width - 20))) {
                Gfx.centered(ctx, font(), Txt.literal(l), cx, y, messageColor);
                y += 11;
            }
        }
    }
}

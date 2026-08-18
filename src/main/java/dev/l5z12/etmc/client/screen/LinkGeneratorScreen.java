package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.JoinCode;

import java.util.ArrayList;
import java.util.List;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
//?} else {
/*import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;*/
//?}

/**
 * Build a shareable {@code etmc://} link (and {@code ETMC1:} code) from raw fields — network name,
 * secret, relay URI, host virtual ip:port, label. "Load config from clipboard" pastes an EasyTier
 * TOML and auto-fills the fields via {@link JoinCode#fromToml}.
 *
 * <p>This screen is offline: it just serializes the fields. The user does not have to be hosting.
 */
public final class LinkGeneratorScreen extends EtmcBaseScreen {

    //? if yarn {
    private TextFieldWidget networkField;
    private TextFieldWidget secretField;
    private TextFieldWidget relayField;
    private TextFieldWidget hostIpField;
    private TextFieldWidget hostPortField;
    private TextFieldWidget labelField;
    //?} else {
    /*private EditBox networkField;
    private EditBox secretField;
    private EditBox relayField;
    private EditBox hostIpField;
    private EditBox hostPortField;
    private EditBox labelField;*/
    //?}

    public LinkGeneratorScreen(Screen parent) {
        super(Txt.literal("Generate join link"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 280;
        int y = 42;

        networkField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.literal("Network name"));
        networkField.setMaxLength(64);
        add(networkField);
        //? if >=1.17 {
        setInitialFocus(networkField);
        //?}
        y += 34;

        secretField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.literal("Network secret"));
        secretField.setMaxLength(128);
        add(secretField);
        y += 34;

        relayField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.literal("Relay URI"));
        relayField.setMaxLength(512);
        add(relayField);
        y += 34;

        int ipW = w - 68;
        hostIpField = Ui.textField(font(), cx - w / 2, y + 12, ipW, 18, Txt.literal("Host virtual IP"));
        hostIpField.setMaxLength(45);
        Ui.setText(hostIpField, EtmcConfig.HOST_VIRTUAL_IP);
        add(hostIpField);
        hostPortField = Ui.textField(font(), cx - w / 2 + ipW + 4, y + 12, 64, 18, Txt.literal("Port"));
        hostPortField.setMaxLength(5);
        Ui.setText(hostPortField, Integer.toString(EtmcConfig.DEFAULT_VIRTUAL_PORT));
        add(hostPortField);
        y += 34;

        labelField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.literal("Label (optional)"));
        labelField.setMaxLength(64);
        add(labelField);
        y += 40;

        add(Ui.button(Txt.literal("Load config from clipboard"), b -> loadFromClipboard())
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        add(Ui.button(Txt.literal("Copy etmc:// link"), b -> copy(true))
                .dimensions(cx - w / 2, y, w / 2 - 4, 20).build());
        add(Ui.button(Txt.literal("Copy ETMC1 code"), b -> copy(false))
                .dimensions(cx + 4, y, w / 2 - 4, 20).build());
        y += 24;

        add(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void loadFromClipboard() {
        //? if yarn {
        String clip = mc().keyboard.getClipboard();
        //?} else {
        /*String clip = mc().keyboardHandler.getClipboard();*/
        //?}
        if (clip == null || clip.isBlank()) {
            setMessage("Clipboard is empty.", COLOR_BAD);
            return;
        }
        String s = clip.trim();
        // Accept an existing join code / etmc:// link too — no reason to force a fresh TOML paste.
        if (JoinCode.isLink(s) || s.startsWith(JoinCode.PREFIX)) {
            try {
                fillFrom(JoinCode.decode(s));
                setMessage("Loaded from join code.", COLOR_GOOD);
            } catch (IllegalArgumentException e) {
                setMessage("Bad code: " + e.getMessage(), COLOR_BAD);
            }
            return;
        }
        JoinCode extracted = JoinCode.fromToml(clip);
        if (!extracted.isValidCandidate()) {
            setMessage("Couldn't find network_name in the clipboard TOML.", COLOR_BAD);
            return;
        }
        fillFrom(extracted);
        setMessage("Loaded from config.", COLOR_GOOD);
    }

    private void fillFrom(JoinCode jc) {
        Ui.setText(networkField, safe(jc.networkName));
        Ui.setText(secretField, safe(jc.networkSecret));
        Ui.setText(relayField, jc.relays == null || jc.relays.isEmpty() ? "" : String.join(", ", jc.relays));
        if (jc.hostIp != null && !jc.hostIp.isBlank()) Ui.setText(hostIpField, jc.hostIp);
        if (jc.hostPort > 0 && jc.hostPort <= 65535) Ui.setText(hostPortField, Integer.toString(jc.hostPort));
        Ui.setText(labelField, safe(jc.label));
    }

    private JoinCode buildCode() {
        String network = Ui.getText(networkField).trim();
        if (network.isEmpty()) {
            setMessage("Enter a network name.", COLOR_BAD);
            return null;
        }
        String secret = Ui.getText(secretField);
        List<String> relays = splitRelays(Ui.getText(relayField));
        String ip = Ui.getText(hostIpField).trim();
        if (ip.isEmpty()) ip = EtmcConfig.HOST_VIRTUAL_IP;
        int port = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        String portText = Ui.getText(hostPortField).trim();
        if (!portText.isEmpty()) {
            try {
                int p = Integer.parseInt(portText);
                if (p <= 0 || p > 65535) {
                    setMessage("Port must be 1–65535.", COLOR_BAD);
                    return null;
                }
                port = p;
            } catch (NumberFormatException e) {
                setMessage("Port must be a number.", COLOR_BAD);
                return null;
            }
        }
        return new JoinCode(network, secret, relays, ip, port, Ui.getText(labelField).trim());
    }

    private void copy(boolean link) {
        JoinCode code = buildCode();
        if (code == null) return;
        String s = link ? code.encodeLink() : code.encode();
        //? if yarn {
        mc().keyboard.setClipboard(s);
        //?} else {
        /*mc().keyboardHandler.setClipboard(s);*/
        //?}
        setMessage(link ? "Copied etmc:// link" : "Copied ETMC1 code", COLOR_GOOD);
    }

    private static List<String> splitRelays(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String piece : text.split("[,\\s]+")) {
            String t = piece.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int w = 280;
        Gfx.centered(ctx, font(), this.title, cx, 18, COLOR_TEXT);

        int y = 42;
        int labelColor = COLOR_MUTED;
        Gfx.text(ctx, font(), Txt.literal("Network name"),   cx - w / 2, y, labelColor); y += 34;
        Gfx.text(ctx, font(), Txt.literal("Network secret"), cx - w / 2, y, labelColor); y += 34;
        Gfx.text(ctx, font(), Txt.literal("Relay URI (comma-separated for multiple)"),
                cx - w / 2, y, labelColor); y += 34;
        Gfx.text(ctx, font(), Txt.literal("Host virtual IP"), cx - w / 2, y, labelColor);
        Gfx.text(ctx, font(), Txt.literal("Port"),            cx - w / 2 + (w - 68) + 4, y, labelColor);
        y += 34;
        Gfx.text(ctx, font(), Txt.literal("Label (optional)"), cx - w / 2, y, labelColor);

        if (!message.isEmpty()) {
            Gfx.centered(ctx, font(), Txt.literal(message), cx, this.height - 14, messageColor);
        }
    }
}

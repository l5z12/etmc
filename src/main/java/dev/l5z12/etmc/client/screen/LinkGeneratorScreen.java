package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.Relays;

import java.util.List;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
//?} else {
/*import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;*/
//?}

/**
 * Build a shareable {@code etmc://} link from raw fields — network name,
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

    /**
     * The host key of the config this was loaded from. It has no field: nobody types one in, but
     * dropping it here would hand out a link that no longer checks who the host is.
     */
    private String hostKey;

    public LinkGeneratorScreen(Screen parent) {
        super(Txt.tr("etmc.menu.generate", "Generate join link"), parent);
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 280;
        int y = 42;

        networkField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.tr("etmc.host.network", "Network name"));
        networkField.setMaxLength(64);
        add(networkField);
        //? if >=1.17 {
        setInitialFocus(networkField);
        //?}
        y += 34;

        secretField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.tr("etmc.gen.secret", "Network secret"));
        secretField.setMaxLength(128);
        add(secretField);
        y += 34;

        relayField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.tr("etmc.gen.relay", "Relay URI"));
        relayField.setMaxLength(512);
        add(relayField);
        y += 34;

        int ipW = w - 68;
        hostIpField = Ui.textField(font(), cx - w / 2, y + 12, ipW, 18, Txt.tr("etmc.gen.host_ip", "Host virtual IP"));
        hostIpField.setMaxLength(45);
        Ui.setText(hostIpField, EtmcConfig.HOST_VIRTUAL_IP);
        add(hostIpField);
        hostPortField = Ui.textField(font(), cx - w / 2 + ipW + 4, y + 12, 64, 18, Txt.tr("etmc.settings.port", "Port"));
        hostPortField.setMaxLength(5);
        Ui.setText(hostPortField, Integer.toString(EtmcConfig.DEFAULT_VIRTUAL_PORT));
        add(hostPortField);
        y += 34;

        labelField = Ui.textField(font(), cx - w / 2, y + 12, w, 18, Txt.tr("etmc.gen.label", "Label (optional)"));
        labelField.setMaxLength(64);
        add(labelField);
        y += 40;

        add(Ui.button(Txt.tr("etmc.gen.load", "Load config from clipboard"), b -> loadFromClipboard())
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        add(Ui.button(Txt.tr("etmc.gen.copy_link", "Copy etmc:// link"), b -> copy())
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 24;

        add(Ui.button(Txt.translatable("gui.back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void loadFromClipboard() {
        String clip = McScreens.getClipboard();
        if (clip.isBlank()) {
            setMessage(Msg.tr("etmc.gen.clipboard_empty", "Clipboard is empty."), COLOR_BAD);
            return;
        }
        String s = clip.trim();
        // Accept an existing etmc:// link too — no reason to force a fresh TOML paste.
        if (JoinCode.isLink(s)) {
            try {
                fillFrom(JoinCode.decode(s));
                setMessage(Msg.tr("etmc.gen.loaded_link", "Loaded from the etmc:// link."), COLOR_GOOD);
            } catch (IllegalArgumentException e) {
                setMessage(Msg.tr("etmc.gen.bad_link", "Bad link: %s", e.getMessage()), COLOR_BAD);
            }
            return;
        }
        JoinCode extracted = JoinCode.fromToml(clip);
        if (!extracted.isValidCandidate()) {
            setMessage(Msg.tr("etmc.gen.no_network_name", "Couldn't find network_name in the clipboard TOML."), COLOR_BAD);
            return;
        }
        fillFrom(extracted);
        setMessage(Msg.tr("etmc.gen.loaded_config", "Loaded from config."), COLOR_GOOD);
    }

    private void fillFrom(JoinCode jc) {
        Ui.setText(networkField, safe(jc.networkName));
        Ui.setText(secretField, safe(jc.networkSecret));
        Ui.setText(relayField, jc.relays == null || jc.relays.isEmpty() ? "" : String.join(", ", jc.relays));
        if (jc.hostIp != null && !jc.hostIp.isBlank()) Ui.setText(hostIpField, jc.hostIp);
        if (jc.hostPort > 0 && jc.hostPort <= 65535) Ui.setText(hostPortField, Integer.toString(jc.hostPort));
        Ui.setText(labelField, safe(jc.label));
        hostKey = jc.hostKey;
    }

    private JoinCode buildCode() {
        String network = Ui.getText(networkField).trim();
        if (network.isEmpty()) {
            setMessage(Msg.tr("etmc.host.need_network", "Enter a network name."), COLOR_BAD);
            return null;
        }
        String secret = Ui.getText(secretField);
        List<String> relays = Relays.parse(Ui.getText(relayField));
        if (relays.isEmpty()) {
            setMessage(Msg.tr("etmc.gen.need_relay",
                    "Add the host's relay: without one, joiners have no way to reach the host."), COLOR_BAD);
            return null;
        }
        String bad = Relays.firstProblem(relays);
        if (bad != null) {
            setMessage(bad, COLOR_BAD);
            return null;
        }
        String ip = Ui.getText(hostIpField).trim();
        if (ip.isEmpty()) ip = EtmcConfig.HOST_VIRTUAL_IP;
        int port = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        String portText = Ui.getText(hostPortField).trim();
        if (!portText.isEmpty()) {
            try {
                int p = Integer.parseInt(portText);
                if (p <= 0 || p > 65535) {
                    setMessage(Msg.tr("etmc.gen.port_range", "Port must be 1–65535."), COLOR_BAD);
                    return null;
                }
                port = p;
            } catch (NumberFormatException e) {
                setMessage(Msg.tr("etmc.gen.port_number", "Port must be a number."), COLOR_BAD);
                return null;
            }
        }
        JoinCode code = new JoinCode(network, secret, relays, ip, port, Ui.getText(labelField).trim());
        code.hostKey = hostKey;
        return code;
    }

    private void copy() {
        JoinCode code = buildCode();
        if (code == null) return;
        McScreens.setClipboard(code.encodeLink());
        setMessage(Msg.tr("etmc.gen.link_copied",
                "Copied etmc:// link. It contains the secret: share it only with people you want in."), COLOR_GOOD);
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
        Gfx.text(ctx, font(), Txt.tr("etmc.host.network", "Network name"),   cx - w / 2, y, labelColor); y += 34;
        Gfx.text(ctx, font(), Txt.tr("etmc.gen.secret", "Network secret"), cx - w / 2, y, labelColor); y += 34;
        Gfx.text(ctx, font(), Txt.tr("etmc.gen.relay.hint", "Relay (several: commas or spaces between them)"),
                cx - w / 2, y, labelColor); y += 34;
        Gfx.text(ctx, font(), Txt.tr("etmc.gen.host_ip", "Host virtual IP"), cx - w / 2, y, labelColor);
        Gfx.text(ctx, font(), Txt.tr("etmc.settings.port", "Port"),            cx - w / 2 + (w - 68) + 4, y, labelColor);
        y += 34;
        Gfx.text(ctx, font(), Txt.tr("etmc.gen.label", "Label (optional)"), cx - w / 2, y, labelColor);

        if (!message.isEmpty()) {
            List<String> lines = Wrap.lines(font(), message, Math.min(340, this.width - 20));
            int my = this.height - 4 - lines.size() * 11;
            for (String l : lines) {
                Gfx.centered(ctx, font(), Txt.literal(l), cx, my, messageColor);
                my += 11;
            }
        }
    }
}

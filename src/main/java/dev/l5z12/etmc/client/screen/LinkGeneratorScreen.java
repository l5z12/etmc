package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.Relays;

import java.util.List;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

/**
 * Build a shareable {@code etmc://} link from raw fields — network name,
 * secret, relay URI, host virtual ip:port, label. "Load from clipboard" pastes an EasyTier
 * TOML and auto-fills the fields via {@link JoinCode#fromToml}.
 *
 * <p>This screen is offline: it just serializes the fields. The user does not have to be hosting.
 */
public final class LinkGeneratorScreen extends EtmcBaseScreen {

    private static final int W = 300;
    private static final int PORT_W = 64;
    private static final int ROW_H = LINE_H + ROW + 5;

    private final Ui.Field network = new Ui.Field(64, "");
    private final Ui.Field secret = new Ui.Field(128, "");
    private final Ui.Field relay = new Ui.Field(512, "");
    private final Ui.Field hostIp = new Ui.Field(45, EtmcConfig.HOST_VIRTUAL_IP);
    private final Ui.Field hostPort = new Ui.Field(5, Integer.toString(EtmcConfig.DEFAULT_VIRTUAL_PORT));
    private final Ui.Field worldLabel = new Ui.Field(64, "");

    /**
     * The host key of the config this was loaded from. It has no field: nobody types one in, but
     * dropping it here would hand out a link that no longer checks who the host is.
     */
    private String hostKey;

    public LinkGeneratorScreen(Screen parent) {
        super(Txt.tr("etmc.menu.generate", "Generate join link"), parent);
    }

    @Override
    protected void build() {
        int w = col(W);
        int x = left(w);
        int half = (w - GAP) / 2;
        int right = x + half + GAP;
        int y = formTop(4 * ROW_H, 3 * LINE_H);

        label(Msg.tr("etmc.host.network", "Network name"), x, y, half, COLOR_MUTED);
        var first = add(network.widget(font(), x, y + LINE_H, half, ROW, Txt.tr("etmc.host.network", "Network name")));
        //? if >=1.17 {
        setInitialFocus(first);
        //?}
        label(Msg.tr("etmc.gen.secret", "Network secret"), right, y, half, COLOR_MUTED);
        add(secret.widget(font(), right, y + LINE_H, half, ROW, Txt.tr("etmc.gen.secret", "Network secret")));
        y += ROW_H;

        label(Msg.tr("etmc.gen.relay.hint", "Relay (several: commas or spaces between them)"), x, y, w, COLOR_MUTED);
        add(relay.widget(font(), x, y + LINE_H, w, ROW, Txt.tr("etmc.gen.relay", "Relay URI")));
        y += ROW_H;

        int ipW = w - PORT_W - GAP;
        label(Msg.tr("etmc.gen.host_ip", "Host virtual IP"), x, y, ipW, COLOR_MUTED);
        add(hostIp.widget(font(), x, y + LINE_H, ipW, ROW, Txt.tr("etmc.gen.host_ip", "Host virtual IP")));
        label(Msg.tr("etmc.settings.port", "Port"), x + ipW + GAP, y, PORT_W, COLOR_MUTED);
        add(hostPort.widget(font(), x + ipW + GAP, y + LINE_H, PORT_W, ROW, Txt.tr("etmc.settings.port", "Port")));
        y += ROW_H;

        label(Msg.tr("etmc.gen.label", "Label (optional)"), x, y, half, COLOR_MUTED);
        add(worldLabel.widget(font(), x, y + LINE_H, half, ROW, Txt.tr("etmc.gen.label", "Label (optional)")));
        Ui.Builder load = Ui.button(Txt.tr("etmc.gen.load", "Load from clipboard"), b -> loadFromClipboard())
                .dimensions(right, y + LINE_H, half, ROW);
        hint(load, Msg.tr("etmc.gen.load.help", "Fills these in from an EasyTier config or an etmc:// link you've copied."));
        add(load.build());

        add(footer(Ui.button(Txt.tr("etmc.gen.copy_link", "Copy etmc:// link"), b -> copy()), 0, 2).build());
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), 1, 2).build());
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
        network.set(safe(jc.networkName));
        secret.set(safe(jc.networkSecret));
        relay.set(jc.relays == null || jc.relays.isEmpty() ? "" : String.join(", ", jc.relays));
        if (jc.hostIp != null && !jc.hostIp.isBlank()) hostIp.set(jc.hostIp);
        if (jc.hostPort > 0 && jc.hostPort <= 65535) hostPort.set(Integer.toString(jc.hostPort));
        worldLabel.set(safe(jc.label));
        hostKey = jc.hostKey;
    }

    private JoinCode buildCode() {
        String networkName = network.text().trim();
        if (networkName.isEmpty()) {
            setMessage(Msg.tr("etmc.host.need_network", "Enter a network name."), COLOR_BAD);
            return null;
        }
        List<String> relays = Relays.parse(relay.text());
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
        String ip = hostIp.text().trim();
        if (ip.isEmpty()) ip = EtmcConfig.HOST_VIRTUAL_IP;
        int port = EtmcConfig.DEFAULT_VIRTUAL_PORT;
        String portText = hostPort.text().trim();
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
        JoinCode code = new JoinCode(networkName, secret.text(), relays, ip, port, worldLabel.text().trim());
        code.hostKey = hostKey;
        return code;
    }

    private void copy() {
        JoinCode code = buildCode();
        if (code == null) return;
        McScreens.setClipboard(code.encodeLink());
        if (code.isOldHostLink()) {
            setMessage(Msg.tr("etmc.gen.link_copied_no_key", "Copied, but joiners will get a warning: only the host's own "
                    + "invite link can prove who the host is. This link contains the secret, so share it carefully."), COLOR_WARN);
        } else {
            setMessage(Msg.tr("etmc.gen.link_copied",
                    "Copied etmc:// link. It contains the secret: share it only with people you want in."), COLOR_GOOD);
        }
    }

    @Override
    protected boolean submit() {
        copy();
        return true;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}

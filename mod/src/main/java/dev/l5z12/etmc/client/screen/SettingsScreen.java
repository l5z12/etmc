package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcHud;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.ModConfig;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.EtmcConfig;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.Relays;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.util.List;

/**
 * Relays, the two ports, and the overlay toggle. Every change — text field or toggle — is applied
 * only by <b>Save</b>; <b>Cancel</b> (or Esc) discards all of them.
 */
public final class SettingsScreen extends EtmcBaseScreen {

    private static final int W = 300;
    private static final int PORT_W = 70;

    private Ui.Field relays;
    private Ui.Field port;
    private Ui.Field joinPort;
    /** The overlay choice being edited; written to the config only on Save. */
    private boolean hudShown;
    /** What's wrong with the relay box, or {@code null}. */
    private String relayProblem;
    private String checked;

    public SettingsScreen(Screen parent) {
        super(Txt.tr("etmc.settings.title", "etmc settings"), parent);
    }

    @Override
    protected void build() {
        if (relays == null) {
            ModConfig cfg = EtmcManager.get().config();
            relays = new Ui.Field(2048, cfg.relaysAsText());
            port = new Ui.Field(5, String.valueOf(cfg.defaultVirtualPort));
            joinPort = new Ui.Field(5, String.valueOf(cfg.joinLocalPort));
            hudShown = cfg.hudEnabled;
        }
        int w = col(W);
        int x = left(w);
        int half = (w - GAP) / 2;
        int right = x + half + GAP;
        int y = formTop(2 * LINE_H + ROW + 6 + ROW + 6 + LINE_H + ROW, 3 * LINE_H);

        label(Msg.tr("etmc.settings.relays.hint", "Relays, for hosting (commas or spaces between them)"), x, y, w, COLOR_MUTED);
        add(relays.widget(font(), x, y + LINE_H, w, ROW, Txt.tr("etmc.settings.relays", "Relays")));
        hint(x, y + LINE_H, w, ROW, Msg.tr("etmc.host.relay.help", "Any EasyTier node works, such as tcp://relay.example.com:11010. "
                + "See etmc.l5z12.dev/docs/getting-started."));
        y += LINE_H + ROW + 2;
        label(Msg.tr("etmc.settings.relays.example", "e.g. tcp://relay.example.com:11010"), x, y, w, COLOR_MUTED);
        y += LINE_H + 6;

        Ui.Builder hud = Ui.button(Txt.literal(hudLabel()), b -> {
            hudShown = !hudShown;
            Ui.setMessage(b, Txt.literal(hudLabel()));
        }).dimensions(x, y, w, ROW);
        if (!EtmcHud.isAvailable()) {
            hint(hud, Msg.tr("etmc.settings.no_overlay", "This Minecraft/Forge version has no overlay API, so there is no overlay."));
        }
        var hudButton = hud.build();
        hudButton.active = EtmcHud.isAvailable();
        add(hudButton);
        y += ROW + 6;

        label(Msg.tr("etmc.settings.port.hint", "Port inside the network"), x, y, half, COLOR_MUTED);
        add(port.widget(font(), x, y + LINE_H, PORT_W, ROW, Txt.tr("etmc.settings.port", "Port")));
        hint(x, y + LINE_H, PORT_W, ROW, Msg.tr("etmc.settings.port.note", "advanced; default %s", EtmcConfig.DEFAULT_VIRTUAL_PORT));
        label(Msg.tr("etmc.settings.join_port.hint", "Join port (0 = any)"), right, y, half, COLOR_MUTED);
        add(joinPort.widget(font(), right, y + LINE_H, PORT_W, ROW, Txt.tr("etmc.settings.join_port", "Join port")));
        hint(right, y + LINE_H, PORT_W, ROW, Msg.tr("etmc.settings.join_port.note", "fixed helps ViaFabricPlus"));

        var save = add(footer(Ui.button(Txt.tr("etmc.settings.save", "Save"), b -> save()), 0, 2).build());
        add(footer(Ui.button(Txt.translatable("gui.cancel"), b -> this.close()), 1, 2).build());
        live(() -> {
            checkRelays();
            save.active = relayProblem == null;
        });
    }

    /** Says what's wrong with the relay box as it's typed, rather than only on Save. */
    private void checkRelays() {
        String text = relays.text();
        if (text.equals(checked)) return;
        checked = text;
        boolean wasBad = relayProblem != null;
        relayProblem = Relays.firstProblem(Relays.parse(text));
        if (relayProblem != null) setMessage(relayProblem, COLOR_BAD);
        else if (wasBad) setMessage("", COLOR_MUTED);
    }

    @Override
    protected boolean submit() {
        save();
        return true;
    }

    private void save() {
        checkRelays();
        if (relayProblem != null) return;
        List<String> parsed = Relays.parse(relays.text());
        Integer virtualPort = port(port.text(), 1, Msg.tr("etmc.settings.port.hint", "Port inside the network"));
        if (virtualPort == null) return;
        Integer localPort = port(joinPort.text(), 0, Msg.tr("etmc.settings.join_port", "Join port"));
        if (localPort == null) return;

        ModConfig cfg = EtmcManager.get().config();
        cfg.relays = parsed;
        cfg.defaultVirtualPort = virtualPort;
        cfg.joinLocalPort = localPort;
        cfg.hudEnabled = hudShown;
        cfg.save();
        this.close();
    }

    /** Parses a port field, reporting a bad one in the status line; {@code null} = rejected. */
    private Integer port(String text, int min, String what) {
        try {
            int p = Integer.parseInt(text.trim());
            if (p >= min && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        setMessage(Msg.tr("etmc.settings.bad_port", "%s must be a number from %s to 65535.", what, min), COLOR_BAD);
        return null;
    }

    private String hudLabel() {
        if (!EtmcHud.isAvailable()) {
            return Msg.tr("etmc.settings.overlay.unavailable", "Top-left status overlay: not available here");
        }
        return Msg.tr("etmc.settings.overlay", "Top-left status overlay: %s", hudShown
                ? Msg.tr("etmc.settings.overlay.shown", "Shown") : Msg.tr("etmc.settings.overlay.hidden", "Hidden"));
    }
}

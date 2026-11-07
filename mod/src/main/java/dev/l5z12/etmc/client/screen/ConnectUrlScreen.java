package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.core.Errors;
import dev.l5z12.etmc.core.Msg;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

/**
 * Connect to an EasyTier-networked Minecraft server from a hosted config: paste the HTTP(S) link to
 * the server's EasyTier config (network, relay, ip). If the config doesn't carry an
 * {@code [etmc] server = "ip:port"} line, fill the optional Server field.
 */
public final class ConnectUrlScreen extends EtmcBaseScreen {

    private static final int W = 300;
    private static final int PASTE_W = 50;

    private final Ui.Field url = new Ui.Field(1024, "");
    private final Ui.Field server = new Ui.Field(64, "");
    private boolean connecting;

    public ConnectUrlScreen(Screen parent) {
        super(Txt.tr("etmc.menu.connect_url", "Connect via config URL"), parent);
    }

    @Override
    protected void build() {
        int w = col(W);
        int x = left(w);
        int y = formTop(2 * (LINE_H + ROW) + GAP, 3 * LINE_H);

        label(Msg.tr("etmc.url.field.hint", "Config URL (http/https to an EasyTier config)"), x, y, w, COLOR_MUTED);
        var box = add(url.widget(font(), x, y + LINE_H, w - PASTE_W - GAP, ROW, Txt.tr("etmc.url.field", "Config URL")));
        //? if >=1.17 {
        setInitialFocus(box);
        //?}
        add(Ui.button(Txt.tr("etmc.join.paste", "Paste"), b -> url.set(McScreens.getClipboard().trim()))
                .dimensions(x + w - PASTE_W, y + LINE_H, PASTE_W, ROW).build());
        y += LINE_H + ROW + GAP;

        label(Msg.tr("etmc.url.server.hint", "Server ip:port (optional if config has [etmc] server)"), x, y, w, COLOR_MUTED);
        add(server.widget(font(), x, y + LINE_H, w, ROW, Txt.tr("etmc.url.server", "Server")));

        var connect = add(footer(Ui.button(Txt.tr("etmc.url.connect", "Fetch & connect"), b -> connect()), 0, 2).build());
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), 1, 2).build());
        live(() -> connect.active = canConnect());
    }

    private boolean canConnect() {
        return !connecting && !url.text().isBlank() && EtmcManager.get().isReady();
    }

    @Override
    protected boolean submit() {
        if (!canConnect()) return false;
        connect();
        return true;
    }

    private void connect() {
        String override = server.text().trim();
        connecting = true;
        setMessage(Msg.tr("etmc.url.fetching", "Fetching config…"), COLOR_BUSY);
        EtmcManager.get().connectUrlAsync(url.text().trim(), override.isEmpty() ? null : override)
                .whenComplete((port, err) -> mc().execute(() -> {
                    if (err != null) {
                        setMessage(Msg.tr("etmc.url.failed", "Failed: %s", Errors.message(err)), COLOR_BAD);
                        connecting = false;
                    }
                    // on success McNet has switched to the connect screen
                }));
    }
}

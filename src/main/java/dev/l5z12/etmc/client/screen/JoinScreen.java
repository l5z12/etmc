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
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.util.List;

/**
 * Join a hosted world from the {@code etmc://} link the host shared. A link already on the clipboard
 * is filled in, and what's in the box is read as it's typed: the line under it says who's inviting, or
 * why that isn't a link, and <b>Join</b> stays off until there's something to join.
 *
 * <p>Outside a world this takes the same path as pasting the link into Direct Connect: it waits for
 * the host to be reachable and connects by itself. Inside a world Minecraft can't switch servers
 * directly, so it starts the network and adds an "etmc: ..." entry to the server list instead.
 */
public final class JoinScreen extends EtmcBaseScreen {

    private static final int W = 280;
    private static final int PASTE_W = 50;
    private static final int CONFIG_URL_W = 170;

    private Ui.Field code;
    private boolean joining;

    /** The box's text {@link #verdict} was read from: decoding a link every frame would be wasteful. */
    private String readFrom;
    private String verdict = "";
    private int verdictColor = COLOR_MUTED;
    private boolean joinable;
    private int verdictY;

    public JoinScreen(Screen parent) {
        super(Txt.tr("etmc.join.title", "Join a world"), parent);
    }

    @Override
    protected void build() {
        if (code == null) code = new Ui.Field(4096, linkOnClipboard());
        int w = col(W);
        int x = left(w);
        boolean inWorld = McScreens.inWorld();
        int y = formTop(ROW + 2 * LINE_H + GAP + ROW + (inWorld ? 3 * LINE_H : 0), 2 * LINE_H);

        var box = add(code.widget(font(), x, y, w - PASTE_W - GAP, ROW, Txt.tr("etmc.join.field", "etmc:// link")));
        //? if >=1.17 {
        setInitialFocus(box);
        //?}
        add(Ui.button(Txt.tr("etmc.join.paste", "Paste"), b -> code.set(McScreens.getClipboard().trim()))
                .dimensions(x + w - PASTE_W, y, PASTE_W, ROW).build());
        y += ROW + GAP;
        verdictY = y;
        y += 2 * LINE_H + GAP;

        // Narrower than the box above: this is the way in for a few, not the next step for everyone.
        int urlW = col(CONFIG_URL_W);
        var connectUrl = Ui.button(Txt.tr("etmc.menu.connect_url", "Connect via config URL"),
                b -> goTo(new ConnectUrlScreen(this))).dimensions(left(urlW), y, urlW, ROW).build();
        connectUrl.active = EtmcManager.get().isReady();
        add(connectUrl);
        y += ROW + GAP;

        if (inWorld) {
            paragraph(Msg.tr("etmc.join.in_world", "You're in a world: this adds the host to your server list, "
                    + "to join after you leave."), y, w, COLOR_MUTED);
        }

        var join = add(footer(Ui.button(inWorld ? Txt.tr("etmc.join.add_to_list", "Add to server list")
                : Txt.tr("etmc.join.join", "Join"), b -> submit()), 0, 2).build());
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), 1, 2).build());
        live(() -> {
            read();
            join.active = joinable && !joining && EtmcManager.get().isReady();
        });
    }

    /** The clipboard, if it's a link. Nothing is joined until the player says so. */
    private static String linkOnClipboard() {
        String clip = McScreens.getClipboard().trim();
        return JoinCode.isLink(clip) ? clip : "";
    }

    private void read() {
        String text = code.text().trim();
        if (text.equals(readFrom)) return;
        readFrom = text;
        joinable = false;
        verdictColor = COLOR_MUTED;
        if (text.isEmpty()) {
            verdict = Msg.tr("etmc.join.hint", "Paste the etmc:// link your host shared");
        } else if (JoinCode.configUrl(text) != null) {
            joinable = true;
            verdict = Msg.tr("etmc.join.config_link", "This link loads the server's details when you join.");
        } else {
            try {
                JoinCode link = JoinCode.decode(text);
                // Only a host who signs their links is named in one.
                verdict = link.invite() != null ? link.invite()
                        : Msg.tr("etmc.join.ready", "Ready to join '%s'", link.displayLabel());
                verdictColor = COLOR_GOOD;
                joinable = true;
            } catch (IllegalArgumentException e) {
                verdict = Msg.tr("etmc.join.not_a_link", "Can't use this: %s. Copy it from the host again.", e.getMessage());
                verdictColor = COLOR_BAD;
            }
        }
    }

    @Override
    protected boolean submit() {
        read();
        if (!joinable || joining) return false;
        startJoining();
        return true;
    }

    private void startJoining() {
        EtmcManager m = EtmcManager.get();
        String text = code.text().trim();
        String configUrl = JoinCode.configUrl(text);
        if (!McScreens.inWorld()) {
            // Same path as Direct Connect: waits for the host, then connects by itself.
            m.connectViaLink(this, text);
            return;
        }
        JoinCode jc = configUrl == null ? JoinCode.decode(text) : null;
        joining = true;
        setMessage(jc == null ? Msg.tr("etmc.url.fetching", "Fetching config…")
                : Msg.tr("etmc.join.starting", "Starting the network for '%s'…", jc.displayLabel()), COLOR_BUSY);
        (jc == null ? m.joinConfigAsync(configUrl) : m.joinAsync(jc)).whenComplete((port, err) ->
                mc().execute(() -> {
                    if (err != null) {
                        setMessage(Msg.tr("etmc.join.failed", "Couldn't join: %s", Errors.message(err)), COLOR_BAD);
                        joining = false;
                    }
                    // on success, McNet has already closed this screen and posted where the entry went
                }));
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        List<String> lines = Wrap.lines(font(), verdict, col(W));
        int y = verdictY;
        for (String line : lines.subList(0, Math.min(2, lines.size()))) {
            Gfx.centered(ctx, font(), Txt.literal(line), this.width / 2, y, verdictColor);
            y += LINE_H;
        }
    }
}

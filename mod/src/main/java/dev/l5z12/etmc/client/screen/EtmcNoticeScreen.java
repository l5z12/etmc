package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.Msg;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

/**
 * Error/notice screen shown when an etmc join or host can't proceed (bad link, unreachable host, ...).
 * The message is word-wrapped to the screen, and can be copied — to paste into a bug report or send
 * to the host — since it is often the only description of what went wrong. Where trying again can
 * help, such as a host who wasn't online yet, <b>Try again</b> does that without going back.
 */
public final class EtmcNoticeScreen extends EtmcBaseScreen {

    private static final int MAX_TEXT_WIDTH = 320;
    private final String text;
    private final Runnable retry;
    /** Whether Try again was pressed: it starts a join, and a second press would start another. */
    private boolean retried;

    public EtmcNoticeScreen(Screen parent, String title, String message) {
        this(parent, title, message, null);
    }

    /** @param retry what <b>Try again</b> does; {@code null} leaves the button out */
    public EtmcNoticeScreen(Screen parent, String title, String message, Runnable retry) {
        super(Txt.literal(title), parent);
        this.text = message == null || message.isBlank() ? Msg.tr("etmc.notice.unknown", "Unknown error") : message;
        this.retry = retry;
    }

    @Override
    protected int titleColor() {
        return COLOR_BAD;
    }

    @Override
    protected void build() {
        int w = col(MAX_TEXT_WIDTH);
        paragraph(text, formTop(Wrap.lines(font(), text, w).size() * LINE_H, LINE_H), w, COLOR_TEXT);

        int cells = retry == null ? 2 : 3;
        int cell = 0;
        if (retry != null) {
            var again = add(footer(Ui.button(Txt.tr("etmc.notice.retry", "Try again"), b -> retry()), cell++, cells).build());
            live(() -> again.active = !retried);
        }
        add(footer(Ui.button(Txt.tr("etmc.notice.copy", "Copy details"), b -> {
            McScreens.setClipboard(this.title.getString() + ": " + text);
            setMessage(Msg.tr("etmc.notice.copied", "Copied."), COLOR_GOOD);
        }), cell++, cells).build());
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), cell, cells).build());
    }

    private void retry() {
        if (retried) return;
        retried = true;
        retry.run();
    }
}

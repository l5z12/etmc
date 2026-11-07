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
 * to the host — since it is often the only description of what went wrong.
 */
public final class EtmcNoticeScreen extends EtmcBaseScreen {

    private static final int MAX_TEXT_WIDTH = 320;
    private final String text;

    public EtmcNoticeScreen(Screen parent, String title, String message) {
        super(Txt.literal(title), parent);
        this.text = message == null || message.isBlank() ? Msg.tr("etmc.notice.unknown", "Unknown error") : message;
    }

    @Override
    protected int titleColor() {
        return COLOR_BAD;
    }

    @Override
    protected void build() {
        int w = col(MAX_TEXT_WIDTH);
        paragraph(text, formTop(Wrap.lines(font(), text, w).size() * LINE_H, LINE_H), w, COLOR_TEXT);

        add(footer(Ui.button(Txt.tr("etmc.notice.copy", "Copy details"), b -> {
            McScreens.setClipboard(this.title.getString() + ": " + text);
            setMessage(Msg.tr("etmc.notice.copied", "Copied."), COLOR_GOOD);
        }), 0, 2).build());
        add(footer(Ui.button(Txt.translatable("gui.back"), b -> this.close()), 1, 2).build());
    }
}

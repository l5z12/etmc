package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
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

import java.util.List;

/**
 * Error/notice screen shown when an etmc join or host can't proceed (bad link, unreachable host, ...).
 * The message is word-wrapped to the screen, and can be copied — to paste into a bug report or send
 * to the host — since it is often the only description of what went wrong.
 */
public final class EtmcNoticeScreen extends EtmcBaseScreen {

    private static final int MAX_TEXT_WIDTH = 320;
    private static final int LINE_H = 11;
    private final String text;

    private List<String> lines() {
        return Wrap.lines(font(), text, Math.min(MAX_TEXT_WIDTH, this.width - 40));
    }

    private int textTop() {
        return Math.max(20, this.height / 2 - 30 - lines().size() * LINE_H / 2);
    }

    public EtmcNoticeScreen(Screen parent, String title, String message) {
        super(Txt.literal(title), parent);
        this.text = message == null || message.isBlank() ? Msg.tr("etmc.notice.unknown", "Unknown error") : message;
    }

    @Override
    protected void init() {
        int textEnd = textTop() + 18 + lines().size() * LINE_H;
        int y = Math.min(this.height - 52, Math.max(this.height / 2 + 40, textEnd + 8));
        add(Ui.button(Txt.tr("etmc.notice.copy", "Copy details"), b -> {
                    McScreens.setClipboard(this.title.getString() + ": " + text);
                    setMessage(Msg.tr("etmc.notice.copied", "Copied."), COLOR_GOOD);
                })
                .dimensions(this.width / 2 - 100, y, 98, 20).build());
        add(Ui.button(Txt.translatable("gui.back"), b -> this.close())
                .dimensions(this.width / 2 + 2, y, 98, 20).build());
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        int top = textTop();
        Gfx.centered(ctx, font(), this.title, this.width / 2, top, COLOR_BAD);
        int y = top + 18;
        for (String l : lines()) {
            Gfx.centered(ctx, font(), Txt.literal(l), this.width / 2, y, COLOR_TEXT);
            y += LINE_H;
        }
        if (!message.isEmpty()) {
            Gfx.centered(ctx, font(), Txt.literal(message), this.width / 2, this.height - 24, messageColor);
        }
    }
}

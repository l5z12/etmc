package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.util.List;

/**
 * Asks whether to go ahead with something etmc couldn't check. Backing out is the default: Esc,
 * Cancel and closing the screen all count as no, so only the button that says so can continue.
 */
public final class EtmcAskScreen extends EtmcBaseScreen {

    private static final int MAX_TEXT_WIDTH = 320;
    private static final int LINE_H = 11;

    private final String text;
    private final Runnable onContinue;
    private final Runnable onCancel;
    private boolean answered;

    public EtmcAskScreen(Screen parent, String title, String message, Runnable onContinue, Runnable onCancel) {
        super(Txt.literal(title), parent);
        this.text = message;
        this.onContinue = onContinue;
        this.onCancel = onCancel;
    }

    private List<String> lines() {
        return Wrap.lines(font(), text, Math.min(MAX_TEXT_WIDTH, this.width - 40));
    }

    private int textTop() {
        return Math.max(20, this.height / 2 - 30 - lines().size() * LINE_H / 2);
    }

    @Override
    protected void init() {
        int textEnd = textTop() + 18 + lines().size() * LINE_H;
        int y = Math.min(this.height - 52, Math.max(this.height / 2 + 40, textEnd + 8));
        add(Ui.button(Txt.tr("etmc.ask.continue", "Join anyway"), b -> {
                    answered = true;
                    super.close();
                    onContinue.run();
                })
                .dimensions(this.width / 2 - 100, y, 98, 20).build());
        add(Ui.button(Txt.translatable("gui.cancel"), b -> this.close())
                .dimensions(this.width / 2 + 2, y, 98, 20).build());
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        int top = textTop();
        Gfx.centered(ctx, font(), this.title, this.width / 2, top, COLOR_WARN);
        int y = top + 18;
        for (String l : lines()) {
            Gfx.centered(ctx, font(), Txt.literal(l), this.width / 2, y, COLOR_TEXT);
            y += LINE_H;
        }
    }

    @Override
    public void close() {
        if (!answered) {
            answered = true;
            onCancel.run();
        }
        super.close();
    }
}

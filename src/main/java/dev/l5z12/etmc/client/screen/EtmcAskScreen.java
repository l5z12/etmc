package dev.l5z12.etmc.client.screen;

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
 * Asks whether to go ahead with something that can't be taken back, or that etmc couldn't check.
 * Backing out is the default: Esc, Cancel and closing the screen all count as no, so only the button
 * that says so can continue.
 */
public final class EtmcAskScreen extends EtmcBaseScreen {

    private static final int MAX_TEXT_WIDTH = 320;

    private final String text;
    private final String continueLabel;
    private final Runnable onContinue;
    private final Runnable onCancel;
    private boolean answered;

    public EtmcAskScreen(Screen parent, String title, String message, Runnable onContinue, Runnable onCancel) {
        this(parent, title, message, Msg.tr("etmc.ask.continue", "Join anyway"), onContinue, onCancel);
    }

    public EtmcAskScreen(Screen parent, String title, String message, String continueLabel, Runnable onContinue,
                         Runnable onCancel) {
        super(Txt.literal(title), parent);
        this.text = message;
        this.continueLabel = continueLabel;
        this.onContinue = onContinue;
        this.onCancel = onCancel;
    }

    @Override
    protected int titleColor() {
        return COLOR_WARN;
    }

    @Override
    protected void build() {
        int w = col(MAX_TEXT_WIDTH);
        paragraph(text, formTop(Wrap.lines(font(), text, w).size() * LINE_H, 0), w, COLOR_TEXT);

        add(footer(Ui.button(Txt.literal(continueLabel), b -> {
            answered = true;
            super.close();
            onContinue.run();
        }), 0, 2).build());
        add(footer(Ui.button(Txt.translatable("gui.cancel"), b -> this.close()), 1, 2).build());
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

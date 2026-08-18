package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

/** Simple error/notice screen shown when an etmc join can't proceed (bad link, start failure, …). */
public final class EtmcNoticeScreen extends EtmcBaseScreen {

    public EtmcNoticeScreen(Screen parent, String title, String message) {
        super(Txt.literal(title), parent);
        setMessage(message == null || message.isBlank() ? "Unknown error" : message, COLOR_TEXT);
    }

    @Override
    protected void init() {
        add(Ui.button(Txt.literal("Back"), b -> this.close())
                .dimensions(this.width / 2 - 100, this.height / 2 + 30, 200, 20).build());
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        Gfx.centered(ctx, font(), this.title, this.width / 2, this.height / 2 - 34, COLOR_BAD);
        Gfx.centered(ctx, font(), Txt.literal(message), this.width / 2, this.height / 2 - 12, messageColor);
    }
}

package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
//? if >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else
/*import net.minecraft.client.util.math.MatrixStack;*/
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Simple error/notice screen shown when an etmc join can't proceed (bad link, start failure, …). */
public final class EtmcNoticeScreen extends Screen {

    private final Screen parent;
    private final String message;

    public EtmcNoticeScreen(Screen parent, String title, String message) {
        super(Text.literal(title));
        this.parent = parent;
        this.message = message == null ? "Unknown error" : message;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close())
                .dimensions(this.width / 2 - 100, this.height / 2 + 30, 200, 20).build());
    }

    @Override
    //? if >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)*/
    {
        super.render(ctx, mouseX, mouseY, delta);
        Gfx.centered(ctx, this.textRenderer, this.title, this.width / 2, this.height / 2 - 34, 0xFFFF5555);
        Gfx.centered(ctx, this.textRenderer, Text.literal(message), this.width / 2, this.height / 2 - 12, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}

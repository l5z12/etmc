package dev.l5z12.etmc.client.screen;

import net.minecraft.client.gui.DrawContext;
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
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 34, 0xFFFF5555);
        ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(message), this.width / 2, this.height / 2 - 12, 0xFFFFFFFF);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}

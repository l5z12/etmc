package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}
//? if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn && >=1.16 {
/*import net.minecraft.client.util.math.MatrixStack;
*///?} else if yarn {
//?} else if <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//?} else if <26 {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
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
    //? if yarn && >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else if yarn && >=1.16 {
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)
    *///?} else if yarn {
    /*public void render(int mouseX, int mouseY, float delta)*/
    //?} else if <1.20 {
    /*public void render(PoseStack ctx, int mouseX, int mouseY, float delta)*/
    //?} else if <26 {
    /*public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta)*/
    //?} else {
    /*public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta)*/
    //?}
    {
        //? if yarn && <1.16 {
        /*int ctx = 0;*/
        //?}
        renderBackdrop(ctx, mouseX, mouseY, delta);
        Gfx.centered(ctx, font(), this.title, this.width / 2, this.height / 2 - 34, COLOR_BAD);
        Gfx.centered(ctx, font(), Txt.literal(message), this.width / 2, this.height / 2 - 12, messageColor);
    }
}

package dev.l5z12.etmc.client;

//? if yarn {
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;*/
//?}
//? if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn {
/*import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;*/
//?} else if <26 {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//?}

/**
 * Tiny drawing facade so screens/HUD are version- AND loader-agnostic. Fabric yarn: 1.20+ via
 * {@code DrawContext}, 1.17–1.19 via {@code MatrixStack}+{@code DrawableHelper}. Mojmap (NeoForge/
 * Forge ≤1.21): {@code GuiGraphics}. Unobfuscated 26.x: {@code GuiGraphicsExtractor} (its new
 * retained-render API — {@code centeredText}/{@code text}/{@code fill}). Screens pass whatever their
 * render hook receives.
 */
public final class Gfx {

    private Gfx() {}

    //? if yarn && >=1.20 {
    public static void centered(DrawContext g, TextRenderer tr, Text t, int x, int y, int color) {
        g.drawCenteredTextWithShadow(tr, t, x, y, color);
    }

    public static void text(DrawContext g, TextRenderer tr, Text t, int x, int y, int color) {
        g.drawTextWithShadow(tr, t, x, y, color);
    }

    public static void fill(DrawContext g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y2, color);
    }
    //?} else if yarn {
    /*public static void centered(MatrixStack g, TextRenderer tr, Text t, int x, int y, int color) {
        // Pre-1.20 only exposes the OrderedText overload of drawCenteredTextWithShadow.
        DrawableHelper.drawCenteredTextWithShadow(g, tr, t.asOrderedText(), x, y, color);
    }

    public static void text(MatrixStack g, TextRenderer tr, Text t, int x, int y, int color) {
        DrawableHelper.drawTextWithShadow(g, tr, t, x, y, color);
    }

    public static void fill(MatrixStack g, int x1, int y1, int x2, int y2, int color) {
        DrawableHelper.fill(g, x1, y1, x2, y2, color);
    }
    *///?} else if <26 {
    /*public static void centered(GuiGraphics g, Font font, Component t, int x, int y, int color) {
        g.drawCenteredString(font, t, x, y, color);
    }

    public static void text(GuiGraphics g, Font font, Component t, int x, int y, int color) {
        g.drawString(font, t, x, y, color);
    }

    public static void fill(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y2, color);
    }
    *///?} else {
    /*public static void centered(GuiGraphicsExtractor g, Font font, Component t, int x, int y, int color) {
        g.centeredText(font, t, x, y, color);
    }

    public static void text(GuiGraphicsExtractor g, Font font, Component t, int x, int y, int color) {
        g.text(font, t, x, y, color);
    }

    public static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y2, color);
    }
    *///?}
}

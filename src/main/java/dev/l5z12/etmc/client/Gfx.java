package dev.l5z12.etmc.client;

import net.minecraft.client.font.TextRenderer;
//? if >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else {
/*import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;*/
//?}
import net.minecraft.text.Text;

/**
 * Tiny drawing facade so the Fabric screens/HUD are version-agnostic: 1.20+ draws through
 * {@code DrawContext}, while 1.17–1.19 draw through {@code MatrixStack} + {@code DrawableHelper}.
 * The screens pass whatever their {@code render} receives ({@code G}) and call these helpers; only
 * this file and each screen's {@code render} signature carry the version split.
 */
public final class Gfx {

    private Gfx() {}

    //? if >=1.20 {
    public static void centered(DrawContext g, TextRenderer tr, Text t, int x, int y, int color) {
        g.drawCenteredTextWithShadow(tr, t, x, y, color);
    }

    public static void text(DrawContext g, TextRenderer tr, Text t, int x, int y, int color) {
        g.drawTextWithShadow(tr, t, x, y, color);
    }

    public static void fill(DrawContext g, int x1, int y1, int x2, int y2, int color) {
        g.fill(x1, y1, x2, y2, color);
    }
    //?} else {
    /*public static void centered(MatrixStack g, TextRenderer tr, Text t, int x, int y, int color) {
        DrawableHelper.drawCenteredTextWithShadow(g, tr, t, x, y, color);
    }

    public static void text(MatrixStack g, TextRenderer tr, Text t, int x, int y, int color) {
        DrawableHelper.drawTextWithShadow(g, tr, t, x, y, color);
    }

    public static void fill(MatrixStack g, int x1, int y1, int x2, int y2, int color) {
        DrawableHelper.fill(g, x1, y1, x2, y2, color);
    }
    *///?}
}

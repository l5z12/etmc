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
//?} else if yarn && >=1.16 {
/*import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;*/
//?} else if yarn {
/*import net.minecraft.client.gui.DrawableHelper;*/
//?} else if <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;*/
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
 *
 * <p>That context is taken as {@code Object} deliberately. Its type is the one thing that changes on
 * nearly every Minecraft generation (and does not exist at all before 1.16), so naming it in a
 * caller's signature would spread the whole version ladder into every screen — which is exactly what
 * these methods exist to prevent. The value always comes straight from the render hook of the build
 * being compiled, so the cast below is the only one it can be, and this file stays the single place
 * that learns a new Minecraft version.
 */
public final class Gfx {

    private Gfx() {}

    //? if yarn && >=1.20 {
    public static void centered(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        ((DrawContext) g).drawCenteredTextWithShadow(tr, t, x, y, color);
    }

    public static void text(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        ((DrawContext) g).drawTextWithShadow(tr, t, x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        ((DrawContext) g).fill(x1, y1, x2, y2, color);
    }
    //?} else if yarn && >=1.17 {
    /*public static void centered(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        // 1.17-1.19 only exposes the OrderedText overload of drawCenteredTextWithShadow.
        DrawableHelper.drawCenteredTextWithShadow((MatrixStack) g, tr, t.asOrderedText(), x, y, color);
    }

    public static void text(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        DrawableHelper.drawTextWithShadow((MatrixStack) g, tr, t, x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        DrawableHelper.fill((MatrixStack) g, x1, y1, x2, y2, color);
    }
    *///?} else if yarn && >=1.16.2 {
    /*public static void centered(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        // 1.16.2-1.16.5: drawCenteredText is static and takes Text (no WithShadow/OrderedText overload yet).
        DrawableHelper.drawCenteredText((MatrixStack) g, tr, t, x, y, color);
    }

    public static void text(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        DrawableHelper.drawTextWithShadow((MatrixStack) g, tr, t, x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        DrawableHelper.fill((MatrixStack) g, x1, y1, x2, y2, color);
    }
    *///?} else if yarn && >=1.16 {
    /*public static void centered(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        // 1.16/1.16.1: DrawableHelper's text draws are instance methods; draw via the TextRenderer instead.
        tr.drawWithShadow((MatrixStack) g, t, x - tr.getWidth(t) / 2f, y, color);
    }

    public static void text(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        tr.drawWithShadow((MatrixStack) g, t, x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        DrawableHelper.fill((MatrixStack) g, x1, y1, x2, y2, color);
    }
    *///?} else if yarn {
    /*public static void centered(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        // Pre-1.16 has no draw context at all (g is null and unused), and text draws take String.
        tr.drawWithShadow(t.getString(), x - tr.getStringWidth(t.getString()) / 2f, y, color);
    }

    public static void text(Object g, TextRenderer tr, Text t, int x, int y, int color) {
        tr.drawWithShadow(t.getString(), x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        DrawableHelper.fill(x1, y1, x2, y2, color);
    }
    *///?} else if <1.20 {
    /*public static void centered(Object g, Font font, Component t, int x, int y, int color) {
        GuiComponent.drawCenteredString((PoseStack) g, font, t, x, y, color);
    }

    public static void text(Object g, Font font, Component t, int x, int y, int color) {
        GuiComponent.drawString((PoseStack) g, font, t, x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        GuiComponent.fill((PoseStack) g, x1, y1, x2, y2, color);
    }
    *///?} else if <26 {
    /*public static void centered(Object g, Font font, Component t, int x, int y, int color) {
        ((GuiGraphics) g).drawCenteredString(font, t, x, y, color);
    }

    public static void text(Object g, Font font, Component t, int x, int y, int color) {
        ((GuiGraphics) g).drawString(font, t, x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        ((GuiGraphics) g).fill(x1, y1, x2, y2, color);
    }
    *///?} else {
    /*public static void centered(Object g, Font font, Component t, int x, int y, int color) {
        ((GuiGraphicsExtractor) g).centeredText(font, t, x, y, color);
    }

    public static void text(Object g, Font font, Component t, int x, int y, int color) {
        ((GuiGraphicsExtractor) g).text(font, t, x, y, color);
    }

    public static void fill(Object g, int x1, int y1, int x2, int y2, int color) {
        ((GuiGraphicsExtractor) g).fill(x1, y1, x2, y2, color);
    }
    *///?}
}

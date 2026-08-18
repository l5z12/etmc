package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.McScreens;
//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;*/
//?}
// The clickable-widget base was renamed AbstractButtonWidget -> ClickableWidget at yarn 1.16.5.
//? if yarn && >=1.16.5 {
import net.minecraft.client.gui.widget.ClickableWidget;
//?} else if yarn {
/*import net.minecraft.client.gui.widget.AbstractButtonWidget;*/
//?}
//? if >=26 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//?} else if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn && >=1.16 {
/*import net.minecraft.client.util.math.MatrixStack;
*///?} else if yarn {
//?} else if <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?}

/**
 * Shared base for the etmc screens that absorbs the recurring mapping differences: the client handle
 * ({@code mc()}), the font ({@code font()}), adding widgets ({@code add()}), navigation
 * ({@code goTo()}), returning to the parent screen ({@code close()}) and the whole render hook —
 * whose name, context type and parameter list all move across the supported range. Subclasses
 * override the version-neutral {@link #draw} instead and read as plain Java.
 *
 * <p>It also owns the status line most etmc screens show ({@code setMessage}), so a screen only has
 * to decide where to draw it.
 */
abstract class EtmcBaseScreen extends Screen {

    /** Screen to return to on Esc / Back; may be null (returns to the game). */
    protected final Screen parent;

    /** Status line shown by screens that report progress or errors; empty means "nothing to say". */
    protected volatile String message = "";
    protected volatile int messageColor = COLOR_MUTED;

    protected static final int COLOR_TEXT = 0xFFFFFFFF;
    protected static final int COLOR_MUTED = 0xFFAAAAAA;
    protected static final int COLOR_GOOD = 0xFF55FF55;
    protected static final int COLOR_WARN = 0xFFFFAA00;
    protected static final int COLOR_BAD = 0xFFFF5555;
    protected static final int COLOR_BUSY = 0xFFFFFF55;

    //? if yarn {
    protected EtmcBaseScreen(Text title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected MinecraftClient mc() {
        //? if >=1.16 {
        return this.client;
        //?} else {
        /*return this.minecraft;*/
        //?}
    }

    protected TextRenderer font() {
        //? if >=1.16 {
        return this.textRenderer;
        //?} else {
        /*return this.font;*/
        //?}
    }

    //? if >=1.17 {
    protected <T extends ClickableWidget> T add(T widget) {
        return addDrawableChild(widget);
    }
    //?} else if >=1.16.5 {
    /*protected <T extends ClickableWidget> T add(T widget) {
        // 1.16.5 still named it addButton (it took any ClickableWidget); renamed addDrawableChild at 1.17.
        return addButton(widget);
    }
    *///?} else {
    /*protected <T extends AbstractButtonWidget> T add(T widget) {
        return addButton(widget);
    }
    *///?}
    //?} else {
    /*protected EtmcBaseScreen(Component title, Screen parent) {
        super(title);
        this.parent = parent;
    }

    protected Minecraft mc() {
        return this.minecraft;
    }

    protected Font font() {
        return this.font;
    }

    protected <T extends AbstractWidget> T add(T widget) {
        return addRenderableWidget(widget);
    }
    *///?}

    /** Navigate to another screen; the per-version call lives in {@link McScreens}. */
    protected void goTo(Screen screen) {
        McScreens.goTo(screen);
    }

    /** Sets the status line drawn by the subclass's render hook. */
    protected void setMessage(String msg, int color) {
        this.message = msg == null ? "" : msg;
        this.messageColor = color;
    }

    /**
     * The render hook, declared once for every etmc screen. Its name and parameters drift constantly
     * — {@code render} became {@code extractRenderState} at 26.x, the draw context is a different
     * type on nearly every generation and does not exist at all before 1.16 — so screens implement
     * {@link #draw} instead and never spell any of it out.
     */
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
        /*Object ctx = null; // pre-1.16 has no draw context; Gfx ignores it*/
        //?}
        renderBackdrop(ctx, mouseX, mouseY, delta);
        draw(ctx, mouseX, mouseY, delta);
    }

    /**
     * Draws this screen's own content, on top of the backdrop and widgets. {@code ctx} is whatever
     * draw context this Minecraft build uses — pass it straight to {@link dev.l5z12.etmc.client.Gfx},
     * which is where its real type is known.
     */
    protected abstract void draw(Object ctx, int mouseX, int mouseY, float delta);

    /**
     * Draws the menu backdrop and the widgets — the prologue the render hook above starts with.
     * Pre-1.20.2 the base {@code Screen.render()} does not draw the background itself, so we do;
     * 26.x renamed the hook to {@code extractRenderState}.
     */
    //? if >=26 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState((GuiGraphicsExtractor) ctx, mouseX, mouseY, delta);
    }
    *///?} else if yarn && >=1.20.2 {
    private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        super.render((DrawContext) ctx, mouseX, mouseY, delta);
    }
    //?} else if yarn && >=1.20 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((DrawContext) ctx);
        super.render((DrawContext) ctx, mouseX, mouseY, delta);
    }
    *///?} else if yarn && >=1.16 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((MatrixStack) ctx);
        super.render((MatrixStack) ctx, mouseX, mouseY, delta);
    }
    *///?} else if yarn {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        // Pre-1.16 there is no MatrixStack, and render() takes no context at all.
        this.renderBackground();
        super.render(mouseX, mouseY, delta);
    }
    *///?} else if <1.20 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((PoseStack) ctx);
        super.render((PoseStack) ctx, mouseX, mouseY, delta);
    }
    *///?} else if <1.20.2 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((GuiGraphics) ctx);
        super.render((GuiGraphics) ctx, mouseX, mouseY, delta);
    }
    *///?} else {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        super.render((GuiGraphics) ctx, mouseX, mouseY, delta);
    }
    *///?}

    /**
     * Returns to {@link #parent}. This is the Esc / Back path for every etmc screen: yarn 1.18.2+
     * calls it {@code close()}, everything else {@code onClose()} — so one of the two below is the
     * real override and the other delegates to it.
     */
    //? if yarn && >=1.18.2 {
    @Override
    //?}
    public void close() {
        goTo(parent);
    }

    //? if !yarn || <1.18.2 {
    /*@Override
    public void onClose() {
        this.close();
    }*/
    //?}
}

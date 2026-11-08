package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.LaunchProbe;
import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.Msg;
import dev.l5z12.etmc.core.Relays;
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
// Key presses became an event object at 1.21.9.
//? if yarn && >=1.21.9 {
import net.minecraft.client.input.KeyInput;
//?} else if >=1.21.9 {
/*import net.minecraft.client.input.KeyEvent;*/
//?}

import java.util.ArrayList;
import java.util.List;

/**
 * Shared base for the etmc screens that absorbs the recurring mapping differences: the client handle
 * ({@code mc()}), the font ({@code font()}), adding widgets ({@code add()}), navigation
 * ({@code goTo()}), returning to the parent screen ({@code close()}) and the whole render hook —
 * whose name, context type and parameter list all move across the supported range. Subclasses
 * implement the version-neutral {@link #build} and {@link #draw} instead and read as plain Java.
 *
 * <p>It also owns the frame every etmc screen shares: the title in a header, a dark band behind the
 * content (plain text is hard to read over a bright panorama or a blurred world), the buttons in a
 * footer, and the status line ({@code setMessage}) at the bottom of the band. {@link #build} declares
 * everything in one place, labels and hover hints included, so what's drawn can't drift away from the
 * widgets. Every layout has to fit 320x240, the smallest GUI the game gives a player at Auto scale.
 */
abstract class EtmcBaseScreen extends Screen {

    /** Screen to return to on Esc / Back; may be null (returns to the game). */
    protected final Screen parent;

    /** Status line shown at the bottom of the band; empty means "nothing to say". */
    protected volatile String message = "";
    protected volatile int messageColor = COLOR_MUTED;

    protected static final int COLOR_TEXT = 0xFFFFFFFF;
    protected static final int COLOR_MUTED = 0xFFBDBDBD;
    protected static final int COLOR_GOOD = 0xFF55FF55;
    protected static final int COLOR_WARN = 0xFFFFAA00;
    protected static final int COLOR_BAD = 0xFFFF5555;
    protected static final int COLOR_BUSY = 0xFFFFFF55;

    /** A button's height, and the space between two widgets. */
    protected static final int ROW = 20;
    protected static final int GAP = 4;
    /** Line height of text; a label above a text box takes one line. */
    protected static final int LINE_H = 11;
    /** The header and footer around the content band. */
    protected static final int HEADER = 28;
    protected static final int FOOTER = 30;
    /** A {@link #findRelay} button's width. */
    protected static final int FIND_RELAY_W = 76;

    /** Even the narrowest screen keeps this much clear on each side. */
    private static final int SIDE = 10;
    private static final int FOOTER_W = 300;
    private static final int MESSAGE_W = 320;
    private static final int HINT_W = 200;
    private static final int BAND = 0x80000000;
    private static final int BAND_EDGE = 0x30FFFFFF;
    private static final int HINT_BG = 0xF0100010;
    private static final int HINT_EDGE = 0xFF3C2A6A;
    private static final int KEY_ENTER = 257;
    private static final int KEY_KP_ENTER = 335;

    /** What {@link #build} declared; each list lasts until the next rebuild. */
    private final List<Label> labels = new ArrayList<>();
    private final List<Hint> hints = new ArrayList<>();
    private final List<Runnable> live = new ArrayList<>();

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

    /** Sets the status line. */
    protected void setMessage(String msg, int color) {
        this.message = msg == null ? "" : msg;
        this.messageColor = color;
    }

    /** Builds this screen again, when a button or the session changes what it should show. */
    protected void rebuild() {
        goTo(this);
    }

    @Override
    protected final void init() {
        labels.clear();
        hints.clear();
        live.clear();
        build();
    }

    /**
     * Declares the screen: its widgets, {@linkplain #label labels} and {@linkplain #hint hover hints}.
     * This runs again whenever the window is resized, so it mustn't reset what the player has chosen.
     */
    protected abstract void build();

    /** Runs {@code check} now and every tick, to keep a widget in step with what the player typed. */
    protected void live(Runnable check) {
        live.add(check);
        check.run();
    }

    @Override
    public void tick() {
        super.tick();
        // A check may rebuild the screen, which replaces this list.
        for (Runnable check : List.copyOf(live)) check.run();
    }

    /** What Enter does on this screen; {@code false} if nothing happened. */
    protected boolean submit() {
        return false;
    }

    //? if yarn && >=1.21.9 {
    @Override
    public boolean keyPressed(KeyInput input) {
        return super.keyPressed(input) || isEnter(input.key()) && submit();
    }
    //?} else if >=1.21.9 {
    /*@Override
    public boolean keyPressed(KeyEvent input) {
        return super.keyPressed(input) || isEnter(input.key()) && submit();
    }
    *///?} else {
    /*@Override
    public boolean keyPressed(int key, int scancode, int modifiers) {
        return super.keyPressed(key, scancode, modifiers) || isEnter(key) && submit();
    }
    *///?}

    private static boolean isEnter(int key) {
        return key == KEY_ENTER || key == KEY_KP_ENTER;
    }

    // ------------------------------------------------------------------ layout

    /** A column's width: {@code wanted}, or less on a narrow screen. */
    protected int col(int wanted) {
        return Math.min(wanted, this.width - 2 * SIDE);
    }

    /** The left edge of a centred column {@code w} wide. */
    protected int left(int w) {
        return (this.width - w) / 2;
    }

    protected int bandBottom() {
        return this.height - FOOTER;
    }

    /**
     * Where a form {@code height} tall starts: centred in the band, leaving {@code reserve} free below
     * it for the status line, and never up in the header.
     */
    protected int formTop(int height, int reserve) {
        return Math.max(HEADER + 6, HEADER + (bandBottom() - HEADER - height - reserve) / 2);
    }

    /** Places a button in cell {@code i} of a row of {@code n} equal cells, {@code w} wide from {@code x}. */
    protected Ui.Builder cell(Ui.Builder button, int x, int y, int w, int i, int n) {
        int cell = (w - GAP * (n - 1)) / n;
        return button.dimensions(x + i * (cell + GAP), y, cell, ROW);
    }

    /** Places a button in cell {@code i} of the footer's {@code n}. */
    protected Ui.Builder footer(Ui.Builder button, int i, int n) {
        int w = col(n == 1 ? 200 : FOOTER_W);
        return cell(button, left(w), this.height - FOOTER + (FOOTER - ROW) / 2, w, i, n);
    }

    /** Text that stays put, cut to {@code w} if it would run past it. */
    protected void label(String text, int x, int y, int w, int color) {
        labels.add(new Label(Wrap.fit(font(), text, w), x, y, color, false));
    }

    /** Centred text that stays put, wrapped to {@code w}; returns the y below it. */
    protected int paragraph(String text, int y, int w, int color) {
        for (String line : Wrap.lines(font(), text, w)) {
            labels.add(new Label(line, this.width / 2, y, color, true));
            y += LINE_H;
        }
        return y;
    }

    /** Text shown while the mouse is over this area. Unlike a tooltip, it works on a disabled button. */
    protected void hint(int x, int y, int w, int h, String text) {
        hints.add(new Hint(x, y, w, h, text));
    }

    protected void hint(Ui.Builder button, String text) {
        int[] b = button.bounds();
        hint(b[0], b[1], b[2], b[3], text);
    }

    /**
     * Adds a button, {@link #FIND_RELAY_W} wide, that offers the public relay list. It goes beside a
     * relay box: most players have no relay of their own, and nothing else in the game says where to
     * find one.
     */
    protected void findRelay(int x, int y) {
        Ui.Builder find = Ui.button(Txt.tr("etmc.relay.find", "Find a relay"),
                b -> McScreens.openLink(this, Relays.PUBLIC_LIST)).dimensions(x, y, FIND_RELAY_W, ROW);
        hint(find, Msg.tr("etmc.relay.find.help", "Opens %s, a list of public EasyTier nodes and how often each "
                + "is up. Copy one's address into the box.", Relays.PUBLIC_LIST_TEXT));
        add(find.build());
    }

    /** Where the status line starts, for a screen that fills the band and has to stop short of it. */
    protected int messageTop() {
        return bandBottom() - 4 - messageLines().size() * LINE_H;
    }

    private List<String> messageLines() {
        return Wrap.lines(font(), message, col(MESSAGE_W));
    }

    /** The title's colour, for a screen that is a warning or an error. */
    protected int titleColor() {
        return COLOR_TEXT;
    }

    /** The content band. It goes under the widgets, so the render hook can't draw it. */
    private void drawBand(Object ctx) {
        int bottom = bandBottom();
        Gfx.fill(ctx, 0, HEADER, this.width, bottom, BAND);
        Gfx.fill(ctx, 0, HEADER - 1, this.width, HEADER, BAND_EDGE);
        Gfx.fill(ctx, 0, bottom, this.width, bottom + 1, BAND_EDGE);
    }

    private void drawLabels(Object ctx) {
        // A title can hold a world's label, which is as long as the host made it.
        Gfx.centered(ctx, font(), Txt.literal(Wrap.fit(font(), this.title.getString(), col(this.width))),
                this.width / 2, (HEADER - 8) / 2, titleColor());
        for (Label l : labels) {
            if (l.centered) Gfx.centered(ctx, font(), Txt.literal(l.text), l.x, l.y, l.color);
            else Gfx.text(ctx, font(), Txt.literal(l.text), l.x, l.y, l.color);
        }
    }

    private void drawMessage(Object ctx) {
        int y = messageTop();
        for (String line : messageLines()) {
            Gfx.centered(ctx, font(), Txt.literal(line), this.width / 2, y, messageColor);
            y += LINE_H;
        }
    }

    private void drawHint(Object ctx, int mouseX, int mouseY) {
        for (Hint h : hints) {
            if (mouseX < h.x || mouseX >= h.x + h.w || mouseY < h.y || mouseY >= h.y + h.h) continue;
            List<String> lines = Wrap.lines(font(), h.text, col(HINT_W));
            int textW = 0;
            for (String line : lines) textW = Math.max(textW, Wrap.width(font(), line));
            int w = textW + 8;
            int height = lines.size() * LINE_H + 5;
            int x = Math.max(2, Math.min(mouseX + 8, this.width - w - 2));
            int y = Math.max(2, mouseY - height - 4);
            Gfx.fill(ctx, x, y, x + w, y + height, HINT_BG);
            Gfx.fill(ctx, x, y, x + w, y + 1, HINT_EDGE);
            Gfx.fill(ctx, x, y + height - 1, x + w, y + height, HINT_EDGE);
            Gfx.fill(ctx, x, y, x + 1, y + height, HINT_EDGE);
            Gfx.fill(ctx, x + w - 1, y, x + w, y + height, HINT_EDGE);
            int ty = y + 3;
            for (String line : lines) {
                Gfx.text(ctx, font(), Txt.literal(line), x + 4, ty, COLOR_TEXT);
                ty += LINE_H;
            }
            return;
        }
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
        drawLabels(ctx);
        draw(ctx, mouseX, mouseY, delta);
        drawMessage(ctx);
        drawHint(ctx, mouseX, mouseY);
        LaunchProbe.rendered(this);
    }

    /**
     * Draws whatever this screen shows that changes while it's open, on top of the frame and widgets;
     * many have nothing to add. {@code ctx} is whatever draw context this Minecraft build uses — pass
     * it straight to {@link dev.l5z12.etmc.client.Gfx}, which is where its real type is known.
     */
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {}

    /**
     * Draws the menu backdrop, the band and the widgets — the prologue the render hook above starts
     * with. Pre-1.20.2 the base {@code Screen.render()} does not draw the background itself, so we do,
     * and the band goes in between; from 1.20.2 the game's own background hook, below, carries the band.
     * 26.x renamed the hook to {@code extractRenderState}.
     */
    //? if >=26 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState((GuiGraphicsExtractor) ctx, mouseX, mouseY, delta);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractBackground(ctx, mouseX, mouseY, delta);
        drawBand(ctx);
    }
    *///?} else if yarn && >=1.20.2 {
    private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        super.render((DrawContext) ctx, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        drawBand(ctx);
    }
    //?} else if yarn && >=1.20 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((DrawContext) ctx);
        drawBand(ctx);
        super.render((DrawContext) ctx, mouseX, mouseY, delta);
    }
    *///?} else if yarn && >=1.16 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((MatrixStack) ctx);
        drawBand(ctx);
        super.render((MatrixStack) ctx, mouseX, mouseY, delta);
    }
    *///?} else if yarn {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        // Pre-1.16 there is no MatrixStack, and render() takes no context at all.
        this.renderBackground();
        drawBand(ctx);
        super.render(mouseX, mouseY, delta);
    }
    *///?} else if <1.20 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((PoseStack) ctx);
        drawBand(ctx);
        super.render((PoseStack) ctx, mouseX, mouseY, delta);
    }
    *///?} else if <1.20.2 {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        this.renderBackground((GuiGraphics) ctx);
        drawBand(ctx);
        super.render((GuiGraphics) ctx, mouseX, mouseY, delta);
    }
    *///?} else {
    /*private void renderBackdrop(Object ctx, int mouseX, int mouseY, float delta) {
        super.render((GuiGraphics) ctx, mouseX, mouseY, delta);
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        super.renderBackground(ctx, mouseX, mouseY, delta);
        drawBand(ctx);
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

    private record Label(String text, int x, int y, int color, boolean centered) {}

    private record Hint(int x, int y, int w, int h, String text) {}
}

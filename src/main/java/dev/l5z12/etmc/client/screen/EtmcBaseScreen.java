package dev.l5z12.etmc.client.screen;

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

/**
 * Shared base for the etmc screens that absorbs the recurring mapping differences: the client handle
 * ({@code mc()}), the font ({@code font()}), adding widgets ({@code add()}) and navigation
 * ({@code goTo()}). yarn vs mojmap names, plus 26.x's {@code setScreenAndShow} rename. Subclasses then
 * read almost identically; only their {@code render} hook, the {@code Screen} parent type and the
 * close hook stay per-screen.
 */
abstract class EtmcBaseScreen extends Screen {

    //? if yarn {
    protected EtmcBaseScreen(Text title) {
        super(title);
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
    /*protected EtmcBaseScreen(Component title) {
        super(title);
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

    /** Navigate to another screen. 26.x renamed {@code setScreen} -> {@code setScreenAndShow}; yarn
     * pre-1.17 it was {@code openScreen}. */
    protected void goTo(Screen screen) {
        //? if >=26 {
        /*mc().setScreenAndShow(screen);*/
        //?} else if yarn && <1.17 {
        /*mc().openScreen(screen);*/
        //?} else {
        mc().setScreen(screen);
        //?}
    }
}

package dev.l5z12.etmc.client.screen;

//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;*/
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
        return this.client;
    }

    protected TextRenderer font() {
        return this.textRenderer;
    }

    protected <T extends ClickableWidget> T add(T widget) {
        return addDrawableChild(widget);
    }
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

    /** Navigate to another screen. 26.x renamed {@code setScreen} -> {@code setScreenAndShow}. */
    protected void goTo(Screen screen) {
        //? if >=26 {
        /*mc().setScreenAndShow(screen);*/
        //?} else {
        mc().setScreen(screen);
        //?}
    }
}

package dev.l5z12.etmc.client.screen;

//? if fabric {
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
 * Shared base for the etmc screens that absorbs the recurring yarn-vs-mojmap mapping differences:
 * the client handle ({@code mc()}), the font ({@code font()}) and adding widgets ({@code add()}).
 * Subclasses then read almost identically across loaders; only their {@code render} signature, the
 * {@code Screen} parent type and the close hook stay per-screen.
 */
//? if fabric {
abstract class EtmcBaseScreen extends Screen {

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
}
//?} else {
/*abstract class EtmcBaseScreen extends Screen {

    protected EtmcBaseScreen(Component title) {
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
}*/
//?}

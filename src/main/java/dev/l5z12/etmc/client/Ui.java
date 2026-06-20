package dev.l5z12.etmc.client;

//? if fabric {
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;*/
//?}

/**
 * Version- AND loader-agnostic widget builder. Fabric (yarn): {@code ButtonWidget.builder(...)
 * .dimensions(...)} (1.19.4+) or the {@code new ButtonWidget(...)} ctor (1.16–1.19.3). NeoForge/Forge
 * (mojmap): {@code Button.builder(...).bounds(...)}. {@code Ui.button(...).dimensions(...).build()}
 * reads the same everywhere; only this file carries the split.
 */
public final class Ui {

    private Ui() {}

    //? if fabric {
    public static Builder button(Text message, ButtonWidget.PressAction onPress) {
        return new Builder(message, onPress);
    }
    //?} else {
    /*public static Builder button(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }
    *///?}

    public static final class Builder {
        //? if fabric {
        private final Text message;
        private final ButtonWidget.PressAction onPress;
        //?} else {
        /*private final Component message;
        private final Button.OnPress onPress;
        *///?}
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        //? if fabric {
        Builder(Text message, ButtonWidget.PressAction onPress) {
            this.message = message;
            this.onPress = onPress;
        }
        //?} else {
        /*Builder(Component message, Button.OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }
        *///?}

        public Builder dimensions(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        //? if fabric && >=1.19.4 {
        public ButtonWidget build() {
            return ButtonWidget.builder(message, onPress).dimensions(x, y, width, height).build();
        }
        //?} else if fabric {
        /*public ButtonWidget build() {
            return new ButtonWidget(x, y, width, height, message, onPress);
        }
        *///?} else {
        /*public Button build() {
            return Button.builder(message, onPress).bounds(x, y, width, height).build();
        }
        *///?}
    }
}

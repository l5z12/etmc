package dev.l5z12.etmc.client;

//? if yarn {
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;*/
//?}

/**
 * Version- AND loader-agnostic widget builder. Fabric (yarn): {@code ButtonWidget.builder(...)
 * .dimensions(...)} (1.19.3+) or the {@code new ButtonWidget(...)} ctor (1.16–1.19.2). NeoForge/Forge
 * (mojmap): {@code Button.builder(...).bounds(...)}. {@code Ui.button(...).dimensions(...).build()}
 * reads the same everywhere; only this file carries the split.
 */
public final class Ui {

    private Ui() {}

    //? if yarn {
    public static Builder button(Text message, ButtonWidget.PressAction onPress) {
        return new Builder(message, onPress);
    }

    public static TextFieldWidget textField(TextRenderer font, int x, int y, int w, int h, Text label) {
        return new TextFieldWidget(font, x, y, w, h, label);
    }

    public static void setText(TextFieldWidget f, String s) {
        f.setText(s);
    }

    public static String getText(TextFieldWidget f) {
        return f.getText();
    }
    //?} else {
    /*public static Builder button(Component message, Button.OnPress onPress) {
        return new Builder(message, onPress);
    }

    public static EditBox textField(Font font, int x, int y, int w, int h, Component label) {
        return new EditBox(font, x, y, w, h, label);
    }

    public static void setText(EditBox f, String s) {
        f.setValue(s);
    }

    public static String getText(EditBox f) {
        return f.getValue();
    }
    *///?}

    public static final class Builder {
        //? if yarn {
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

        //? if yarn {
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

        //? if yarn && >=1.19.3 {
        public ButtonWidget build() {
            return ButtonWidget.builder(message, onPress).dimensions(x, y, width, height).build();
        }
        //?} else if yarn {
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

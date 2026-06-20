package dev.l5z12.etmc.client;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Version-agnostic widget builders. {@code ButtonWidget.builder(...).dimensions(...).build()} only
 * exists from 1.19.4; on 1.16–1.19.3 buttons are built with the {@code new ButtonWidget(x, y, w, h,
 * message, onPress)} constructor. {@code Ui.button(...)} mirrors the 1.19.4 fluent shape so the
 * screens read identically across versions and only this file carries the split.
 */
public final class Ui {

    private Ui() {}

    public static Builder button(Text message, ButtonWidget.PressAction onPress) {
        return new Builder(message, onPress);
    }

    public static final class Builder {
        private final Text message;
        private final ButtonWidget.PressAction onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;

        Builder(Text message, ButtonWidget.PressAction onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder dimensions(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public ButtonWidget build() {
            //? if >=1.19.4 {
            return ButtonWidget.builder(message, onPress).dimensions(x, y, width, height).build();
            //?} else {
            /*return new ButtonWidget(x, y, width, height, message, onPress);*/
            //?}
        }
    }
}

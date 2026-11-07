package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.screen.EtmcScreen;
//? if yarn {
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "etmc" button to the pause menu, next to where a player looks for Open to LAN. The G key
 * is the only other way to the menu from inside a world, and nothing in the game says so.
 */
//? if yarn {
@Mixin(GameMenuScreen.class)
//?} else {
/*@Mixin(PauseScreen.class)*/
//?}
public abstract class PauseScreenMixin extends Screen {

    //? if yarn {
    protected PauseScreenMixin(Text title) {
        super(title);
    }
    //?} else {
    /*protected PauseScreenMixin(Component title) {
        super(title);
    }*/
    //?}

    @Inject(method = "init", at = @At("TAIL"))
    private void etmc$addMenuButton(CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        var button = Ui.button(Txt.literal("etmc"), b -> McScreens.goTo(new EtmcScreen(self)))
                .dimensions(5, 5, 44, 20).build();
        //? if yarn && >=1.17 {
        this.addDrawableChild(button);
        //?} else if yarn {
        /*this.addButton(button);*/
        //?} else {
        /*this.addRenderableWidget(button);*/
        //?}
    }
}

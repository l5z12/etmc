package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.client.McScreens;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.screen.EtmcScreen;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.text.Text;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds an "etmc" button to the Multiplayer screen. Without it the etmc menu (and so its settings and
 * Join screen) is only reachable from inside a world: the G key does nothing while a menu is open and
 * {@code /etmc} needs a world — yet the title screen is exactly where a joiner starts.
 */
//? if yarn {
@Mixin(MultiplayerScreen.class)
//?} else {
/*@Mixin(JoinMultiplayerScreen.class)*/
//?}
public abstract class MultiplayerScreenMixin extends Screen {

    //? if yarn {
    protected MultiplayerScreenMixin(Text title) {
        super(title);
    }
    //?} else {
    /*protected MultiplayerScreenMixin(Component title) {
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

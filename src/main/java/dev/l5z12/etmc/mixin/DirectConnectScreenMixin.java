package dev.l5z12.etmc.mixin;

//? if yarn && >=1.20.3 {
import net.minecraft.client.gui.screen.multiplayer.DirectConnectScreen;
//?} else if yarn {
/*import net.minecraft.client.gui.screen.DirectConnectScreen;*/
//?} else {
/*import net.minecraft.client.gui.screens.DirectJoinServerScreen;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Widens the address field's max length so a full {@code etmc://} link isn't truncated when pasted
 * into Direct Connect. yarn {@code DirectConnectScreen}; mojmap {@code DirectJoinServerScreen}; field
 * is {@code TextFieldWidget} vs {@code EditBox}.
 */
//? if yarn {
@Mixin(DirectConnectScreen.class)
//?} else {
/*@Mixin(DirectJoinServerScreen.class)*/
//?}
public class DirectConnectScreenMixin {

    //? if yarn {
    @ModifyArg(method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;setMaxLength(I)V"),
            index = 0)
    //?} else {
    /*@ModifyArg(method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;setMaxLength(I)V"),
            index = 0)*/
    //?}
    private int etmc$widenField(int original) {
        return Math.max(original, 2048);
    }
}

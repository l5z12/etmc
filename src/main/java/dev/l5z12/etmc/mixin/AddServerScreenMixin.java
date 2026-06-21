package dev.l5z12.etmc.mixin;

//? if yarn && >=1.20.2 {
import net.minecraft.client.gui.screen.multiplayer.AddServerScreen;
//?} else if yarn {
/*import net.minecraft.client.gui.screen.AddServerScreen;*/
//?} else if >=1.21.9 {
/*import net.minecraft.client.gui.screens.ManageServerScreen;*/
//?} else {
/*import net.minecraft.client.gui.screens.EditServerScreen;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Widens the address field's max length so a full {@code etmc://} link (which is long) isn't
 * truncated when pasted into Add Server. yarn {@code AddServerScreen}; mojmap {@code ManageServerScreen}
 * (1.21.9+) / {@code EditServerScreen} (older); field is {@code TextFieldWidget} vs {@code EditBox}.
 */
//? if yarn {
@Mixin(AddServerScreen.class)
//?} else if >=1.21.9 {
/*@Mixin(ManageServerScreen.class)*/
//?} else {
/*@Mixin(EditServerScreen.class)*/
//?}
public class AddServerScreenMixin {

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

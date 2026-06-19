package dev.l5z12.etmc.mixin;

//? if >=1.20.2 {
import net.minecraft.client.gui.screen.multiplayer.DirectConnectScreen;
//?} else
/*import net.minecraft.client.gui.screen.DirectConnectScreen;*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Widens the address field's max length so a full {@code etmc://} link isn't truncated when pasted
 * into Direct Connect.
 */
@Mixin(DirectConnectScreen.class)
public class DirectConnectScreenMixin {

    @ModifyArg(method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;setMaxLength(I)V"),
            index = 0)
    private int etmc$widenField(int original) {
        return Math.max(original, 2048);
    }
}

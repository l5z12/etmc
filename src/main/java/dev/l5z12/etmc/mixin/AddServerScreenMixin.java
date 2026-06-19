package dev.l5z12.etmc.mixin;

import net.minecraft.client.gui.screen.multiplayer.AddServerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Widens the address field's max length so a full {@code etmc://} link (which is long) isn't
 * truncated when pasted into Add Server.
 */
@Mixin(AddServerScreen.class)
public class AddServerScreenMixin {

    @ModifyArg(method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/widget/TextFieldWidget;setMaxLength(I)V"),
            index = 0)
    private int etmc$widenField(int original) {
        return Math.max(original, 2048);
    }
}

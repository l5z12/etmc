package dev.l5z12.etmc.mixin;

import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Mojmap (NeoForge/Forge) twin: widens the Direct Connect address field's max length so a full
 * {@code etmc://} link isn't truncated when pasted.
 */
@Mixin(DirectJoinServerScreen.class)
public class DirectJoinServerScreenMixin {

    @ModifyArg(method = "init",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/EditBox;setMaxLength(I)V"),
            index = 0)
    private int etmc$widenField(int original) {
        return Math.max(original, 2048);
    }
}

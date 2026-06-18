package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.mc.EtmcClientCore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mojmap (NeoForge/Forge) twin of the Fabric ConnectScreen mixin: detects an {@code etmc://} address
 * typed into Add Server / Direct Connect and reroutes it through etmc instead of letting vanilla try
 * to resolve the bogus host. Transport layer only — ViaFabricPlus-safe.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    @Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("HEAD"), cancellable = true)
    private static void etmc$interceptLink(Screen screen, Minecraft mc, ServerAddress address, ServerData data,
                                           boolean transfer, TransferState transferState, CallbackInfo ci) {
        if (data != null && JoinCode.isLink(data.ip)) {
            ci.cancel();
            EtmcClientCore.get().connectViaLink(screen, data.ip);
        }
    }
}

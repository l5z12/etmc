package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.JoinCode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
//? if >=1.20.2 {
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
//?} else
/*import net.minecraft.client.gui.screen.ConnectScreen;*/
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects {@code etmc://} addresses typed into Add Server / Direct Connect (or stored in the server
 * list) and reroutes them through etmc instead of letting vanilla try to resolve the bogus host.
 * This is the only interception point — at the "which server" layer, not the protocol pipeline, so
 * it stays compatible with protocol mods. The handler captures only the leading params (Mixin allows
 * omitting trailing ones), so it works across versions whose {@code connect} adds a {@code CookieStorage}.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    //? if >=1.20.5 {
    @Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
            at = @At("HEAD"), cancellable = true)
    //?} else if >=1.20 {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;Z)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;)V",
            at = @At("HEAD"), cancellable = true)*/
    //?}
    private static void etmc$interceptLink(Screen screen, MinecraftClient client, ServerAddress address,
                                           ServerInfo info, CallbackInfo ci) {
        if (info != null && JoinCode.isLink(info.address)) {
            ci.cancel();
            EtmcManager.get().connectViaLink(screen, info.address);
        }
    }
}

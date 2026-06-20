package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.JoinCode;
//? if fabric {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;*/
//?}
//? if fabric && >=1.20.2 {
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
//?} else if fabric {
/*import net.minecraft.client.gui.screen.ConnectScreen;*/
//?} else {
/*import net.minecraft.client.gui.screens.ConnectScreen;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects {@code etmc://} addresses typed into Add Server / Direct Connect (or stored in the server
 * list) and reroutes them through etmc instead of letting vanilla try to resolve the bogus host.
 * The handler captures only the leading params (Mixin allows omitting trailing ones), so it works
 * across versions whose {@code connect}/{@code startConnecting} adds a {@code CookieStorage}/{@code
 * TransferState}. Fabric {@code connect} (yarn) vs NeoForge/Forge {@code startConnecting} (mojmap).
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    //? if fabric && >=1.20.5 {
    @Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
            at = @At("HEAD"), cancellable = true)
    //?} else if fabric && >=1.20 {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;Z)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else if fabric {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else {
    /*@Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("HEAD"), cancellable = true)*/
    //?}
    //? if fabric {
    private static void etmc$interceptLink(Screen screen, MinecraftClient client, ServerAddress address,
                                           ServerInfo info, CallbackInfo ci) {
    //?} else {
    /*private static void etmc$interceptLink(Screen screen, Minecraft client, ServerAddress address,
                                           ServerData info, CallbackInfo ci) {*/
    //?}
        if (info != null && JoinCode.isLink(info.address)) {
            ci.cancel();
            EtmcManager.get().connectViaLink(screen, info.address);
        }
    }
}

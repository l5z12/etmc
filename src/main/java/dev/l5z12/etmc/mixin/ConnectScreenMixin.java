package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.JoinCode;
//? if yarn {
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
//? if yarn && >=1.20.3 {
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
//?} else if yarn {
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
 * Fabric {@code connect} (yarn) vs NeoForge/Forge {@code startConnecting} (mojmap). The mojmap/26.x
 * handler declares the full param list ({@code boolean} + {@code TransferState}) because the Mixin
 * shipped with 26.x rejects partial trailing-arg capture; older yarn Mixin still tolerates it.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    //? if yarn && >=1.20.5 {
    @Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
            at = @At("HEAD"), cancellable = true)
    //?} else if yarn && >=1.20 {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;Z)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else if yarn {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else if <1.20 {
    /*@Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else if <1.20.5 {
    /*@Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;Z)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else {
    /*@Inject(method = "startConnecting(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/multiplayer/resolver/ServerAddress;Lnet/minecraft/client/multiplayer/ServerData;ZLnet/minecraft/client/multiplayer/TransferState;)V",
            at = @At("HEAD"), cancellable = true)*/
    //?}
    //? if yarn {
    private static void etmc$interceptLink(Screen screen, MinecraftClient client, ServerAddress address,
                                           ServerInfo info, CallbackInfo ci) {
    //?} else if <1.20 {
    /*private static void etmc$interceptLink(Screen screen, Minecraft client, ServerAddress address,
                                           ServerData info, CallbackInfo ci) {*/
    //?} else if <1.20.5 {
    /*private static void etmc$interceptLink(Screen screen, Minecraft client, ServerAddress address,
                                           ServerData info, boolean hidden, CallbackInfo ci) {*/
    //?} else {
    /*private static void etmc$interceptLink(Screen screen, Minecraft client, ServerAddress address,
                                           ServerData info, boolean hidden,
                                           net.minecraft.client.multiplayer.TransferState transferState,
                                           CallbackInfo ci) {*/
    //?}
        //? if yarn {
        String serverAddr = info == null ? null : info.address;
        //?} else {
        /*String serverAddr = info == null ? null : info.ip;*/
        //?}
        if (serverAddr != null && JoinCode.isLink(serverAddr)) {
            ci.cancel();
            EtmcManager.get().connectViaLink(screen, serverAddr);
        }
    }
}

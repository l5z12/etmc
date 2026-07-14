package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.core.JoinCode;
//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ServerInfo;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;*/
//?}
// ServerAddress moved net.minecraft.network -> net.minecraft.client.network at yarn 1.16.5 (used by
// the 1.17+ connect signatures; the 1.16 ctor target below doesn't reference it).
//? if yarn && >=1.16.5 {
import net.minecraft.client.network.ServerAddress;
//?} else if yarn {
/*import net.minecraft.network.ServerAddress;*/
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
 * Fabric {@code connect} (yarn) vs NeoForge/Forge {@code startConnecting} (mojmap). Every handler
 * declares the target's FULL param list ({@code boolean hidden} + {@code CookieStorage}/yarn or
 * {@code TransferState}/mojmap) because modern Mixin (Fabric's 0.8.x and 26.x) rejects partial
 * trailing-arg capture — a short form throws InvalidInjectionException at apply time.
 */
@Mixin(ConnectScreen.class)
public class ConnectScreenMixin {

    // Pre-1.17 yarn starts the vanilla connect from inside the ConnectScreen constructor, so the
    // constructor-TAIL inject below can't cancel it via CallbackInfo. Shadow the screen's own "cancelled"
    // flag so we can flip it and make that doomed connector thread bail quietly. 1.17+ / mojmap cancel the
    // connect outright and never touch this field.
    //? if yarn && <1.17 {
    /*@org.spongepowered.asm.mixin.Shadow
    private boolean connectingCancelled;*/
    //?}

    //? if yarn && >=1.20.5 {
    @Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;ZLnet/minecraft/client/network/CookieStorage;)V",
            at = @At("HEAD"), cancellable = true)
    //?} else if yarn && >=1.20 {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;Z)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else if yarn && >=1.17 {
    /*@Inject(method = "connect(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerAddress;Lnet/minecraft/client/network/ServerInfo;)V",
            at = @At("HEAD"), cancellable = true)*/
    //?} else if yarn {
    /*@Inject(method = "<init>(Lnet/minecraft/client/gui/screen/Screen;Lnet/minecraft/client/MinecraftClient;Lnet/minecraft/client/network/ServerInfo;)V",
            at = @At("TAIL"))*/
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
    //? if yarn && >=1.20.5 {
    private static void etmc$interceptLink(Screen screen, MinecraftClient client, ServerAddress address,
                                           ServerInfo info, boolean hidden,
                                           net.minecraft.client.network.CookieStorage cookieStorage,
                                           CallbackInfo ci) {
    //?} else if yarn && >=1.20 {
    /*private static void etmc$interceptLink(Screen screen, MinecraftClient client, ServerAddress address,
                                           ServerInfo info, boolean hidden, CallbackInfo ci) {*/
    //?} else if yarn && >=1.17 {
    /*private static void etmc$interceptLink(Screen screen, MinecraftClient client, ServerAddress address,
                                           ServerInfo info, CallbackInfo ci) {*/
    //?} else if yarn {
    /*private void etmc$interceptLink(Screen screen, MinecraftClient client, ServerInfo info, CallbackInfo ci) {*/
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
            //? if >=1.17 || !yarn {
            // Cancel the vanilla connect before it reaches the netty channel.
            ci.cancel();
            //?} else {
            /*// 1.16 and older start the vanilla connect from inside this very constructor, so CallbackInfo
            // can't cancel it. Flag the screen cancelled instead: the connector thread re-checks this before
            // its socket attempt and again in its catch blocks, so it bails silently for the placeholder
            // loopback address instead of logging "Couldn't connect to server" mid-P2P-wait.
            this.connectingCancelled = true;*/
            //?}
            EtmcManager.get().connectViaLink(screen, serverAddr);
        }
    }
}

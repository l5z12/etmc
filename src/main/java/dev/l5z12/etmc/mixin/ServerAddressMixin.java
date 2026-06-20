package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.core.JoinCode;
//? if yarn {
import net.minecraft.client.network.ServerAddress;
//?} else {
/*import net.minecraft.client.multiplayer.resolver.ServerAddress;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Minecraft tolerate {@code etmc://} addresses: {@code etmc$allowLink} keeps the Add Server /
 * Direct Connect button enabled, and {@code etmc$parseLink} stops the parse from throwing
 * "Unparseable port number" (it returns a harmless placeholder; the real reroute happens in
 * {@code ConnectScreenMixin}). yarn {@code isValid}/{@code parse} vs mojmap {@code isValidAddress}/
 * {@code parseString}.
 */
@Mixin(ServerAddress.class)
public class ServerAddressMixin {

    //? if yarn {
    @Inject(method = "isValid(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "isValidAddress(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)*/
    //?}
    private static void etmc$allowLink(String address, CallbackInfoReturnable<Boolean> cir) {
        if (JoinCode.isLink(address)) {
            cir.setReturnValue(true);
        }
    }

    //? if yarn {
    @Inject(method = "parse(Ljava/lang/String;)Lnet/minecraft/client/network/ServerAddress;",
            at = @At("HEAD"), cancellable = true)
    //?} else {
    /*@Inject(method = "parseString(Ljava/lang/String;)Lnet/minecraft/client/multiplayer/resolver/ServerAddress;",
            at = @At("HEAD"), cancellable = true)*/
    //?}
    private static void etmc$parseLink(String address, CallbackInfoReturnable<ServerAddress> cir) {
        if (JoinCode.isLink(address)) {
            //? if yarn {
            cir.setReturnValue(ServerAddress.parse("127.0.0.1:25565"));
            //?} else {
            /*cir.setReturnValue(ServerAddress.parseString("127.0.0.1:25565"));*/
            //?}
        }
    }
}

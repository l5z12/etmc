package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.core.JoinCode;
import net.minecraft.client.network.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Minecraft tolerate {@code etmc://} addresses: {@link #etmc$allowLink} keeps the Add Server /
 * Direct Connect button enabled, and {@link #etmc$parseLink} stops {@code parse} from throwing
 * "Unparseable port number" (it returns a harmless placeholder; the real reroute happens in
 * {@link ConnectScreenMixin} based on the raw {@code ServerInfo} address). The placeholder is only
 * ever used for the cosmetic server-list ping, never for the actual connection.
 */
@Mixin(ServerAddress.class)
public class ServerAddressMixin {

    @Inject(method = "isValid(Ljava/lang/String;)Z", at = @At("HEAD"), cancellable = true)
    private static void etmc$allowLink(String address, CallbackInfoReturnable<Boolean> cir) {
        if (JoinCode.isLink(address)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "parse(Ljava/lang/String;)Lnet/minecraft/client/network/ServerAddress;",
            at = @At("HEAD"), cancellable = true)
    private static void etmc$parseLink(String address, CallbackInfoReturnable<ServerAddress> cir) {
        if (JoinCode.isLink(address)) {
            cir.setReturnValue(ServerAddress.parse("127.0.0.1:25565"));
        }
    }
}

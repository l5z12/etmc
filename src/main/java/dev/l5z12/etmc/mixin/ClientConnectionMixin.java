package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.core.EtmcChannel;
import dev.l5z12.etmc.core.EtmcConnect;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import net.minecraft.network.ClientConnection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When an {@code etmc://} connection is pending, swaps the netty channel used by the client
 * connection for an {@link EtmcChannel} (which rides the EasyTier data plane) instead of a TCP
 * socket channel — no loopback socket, no port. For all other connections this is a no-op.
 */
@Mixin(ClientConnection.class)
public class ClientConnectionMixin {

    //? if >=1.20 {
    @Redirect(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))
    //?} else {
    /*@Redirect(method = "connect(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/ClientConnection;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))*/
    //?}
    private static AbstractBootstrap<?, ?> etmc$swapChannel(Bootstrap bootstrap, Class<? extends Channel> channelClass) {
        EtmcConnect.Target target = EtmcConnect.takePending();
        if (target != null) {
            ChannelFactory<EtmcChannel> factory = () -> new EtmcChannel(target);
            return bootstrap.channelFactory(factory);
        }
        return bootstrap.channel(channelClass);
    }
}

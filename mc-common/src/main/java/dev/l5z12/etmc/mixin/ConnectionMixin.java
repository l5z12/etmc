package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.core.EtmcChannel;
import dev.l5z12.etmc.core.EtmcConnect;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mojmap (NeoForge/Forge) twin of the Fabric ClientConnection mixin: when an {@code etmc://}
 * connection is pending, swaps the netty channel for an {@link EtmcChannel} (riding the EasyTier
 * data plane) instead of a TCP socket — no loopback socket, no port. No-op otherwise.
 */
@Mixin(Connection.class)
public class ConnectionMixin {

    @Redirect(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))
    private static AbstractBootstrap<?, ?> etmc$swapChannel(Bootstrap bootstrap, Class<? extends Channel> channelClass) {
        EtmcConnect.Target target = EtmcConnect.takePending();
        if (target != null) {
            ChannelFactory<EtmcChannel> factory = () -> new EtmcChannel(target);
            return bootstrap.channelFactory(factory);
        }
        return bootstrap.channel(channelClass);
    }
}

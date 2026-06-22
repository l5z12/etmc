package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.core.EtmcChannel;
import dev.l5z12.etmc.core.EtmcConnect;
import io.netty.bootstrap.AbstractBootstrap;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFactory;
//? if yarn {
import net.minecraft.network.ClientConnection;
//?} else {
/*import net.minecraft.network.Connection;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * When an {@code etmc://} connection is pending, swaps the netty channel used by the client
 * connection for an {@link EtmcChannel} (which rides the EasyTier data plane) instead of a TCP
 * socket channel — no loopback socket, no port. For all other connections this is a no-op.
 * Targets yarn {@code ClientConnection} on Fabric, mojmap {@code Connection} on NeoForge/Forge.
 */
//? if yarn {
@Mixin(ClientConnection.class)
//?} else {
/*@Mixin(Connection.class)*/
//?}
public class ClientConnectionMixin {

    // The netty Bootstrap.channel call moved from the 2-arg factory (connect/connectToServer, returns the
    // connection) into a split-out 3-arg low-level connect(...)ChannelFuture at 1.20.1; arg2 went boolean →
    // EventLoopGroupHolder (mojmap) / NetworkingBackend (yarn) at 1.21.11.
    //? if yarn && >=1.21.11 {
    /*@Redirect(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/network/NetworkingBackend;Lnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))*/
    //?} else if yarn && >=1.20.1 {
    @Redirect(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/ClientConnection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))
    //?} else if yarn {
    /*@Redirect(method = "connect(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/ClientConnection;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))*/
    //?} else if >=1.21.11 {
    /*@Redirect(method = "connect(Ljava/net/InetSocketAddress;Lnet/minecraft/server/network/EventLoopGroupHolder;Lnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))*/
    //?} else if >=1.20.1 {
    /*@Redirect(method = "connect(Ljava/net/InetSocketAddress;ZLnet/minecraft/network/Connection;)Lio/netty/channel/ChannelFuture;",
            at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;channel(Ljava/lang/Class;)Lio/netty/bootstrap/AbstractBootstrap;"))*/
    //?} else {
    /*@Redirect(method = "connectToServer(Ljava/net/InetSocketAddress;Z)Lnet/minecraft/network/Connection;",
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

package dev.l5z12.etmc.mixin;

import dev.l5z12.etmc.core.JoinCode;
import dev.l5z12.etmc.core.Msg;
//? if yarn {
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
//?} else {
/*import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerStatusPinger;*/
//?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-list entries saved with an {@code etmc://} link: instead of pinging the placeholder address
 * (which fails, so the entry read "Can't connect to server" in red although joining it works), the
 * entry says what it is. There is nothing to ping without joining the host's network first, so it
 * keeps the "no connection" icon. yarn {@code add} vs mojmap {@code pingServer}; every handler takes
 * the target's full parameter list (see ConnectScreenMixin).
 */
//? if yarn {
@Mixin(MultiplayerServerListPinger.class)
//?} else {
/*@Mixin(ServerStatusPinger.class)*/
//?}
public class ServerListPingerMixin {

    //? if yarn && >=1.21.11 {
    /*@Inject(method = "add(Lnet/minecraft/client/network/ServerInfo;Ljava/lang/Runnable;Ljava/lang/Runnable;Lnet/minecraft/network/NetworkingBackend;)V",
            at = @At("HEAD"), cancellable = true)
    private void etmc$describeLink(ServerInfo info, Runnable saver, Runnable pinged,
                                   net.minecraft.network.NetworkingBackend backend, CallbackInfo ci) {*/
    //?} else if yarn && >=1.20.5 {
    @Inject(method = "add(Lnet/minecraft/client/network/ServerInfo;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true)
    private void etmc$describeLink(ServerInfo info, Runnable saver, Runnable pinged, CallbackInfo ci) {
    //?} else if yarn && >=1.16 {
    /*@Inject(method = "add(Lnet/minecraft/client/network/ServerInfo;Ljava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true)
    private void etmc$describeLink(ServerInfo info, Runnable saver, CallbackInfo ci) {*/
    //?} else if yarn {
    /*@Inject(method = "add(Lnet/minecraft/client/network/ServerInfo;)V", at = @At("HEAD"), cancellable = true)
    private void etmc$describeLink(ServerInfo info, CallbackInfo ci) {*/
    //?} else if >=1.21.11 {
    /*@Inject(method = "pingServer(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;Ljava/lang/Runnable;Lnet/minecraft/server/network/EventLoopGroupHolder;)V",
            at = @At("HEAD"), cancellable = true)
    private void etmc$describeLink(ServerData info, Runnable saver, Runnable pinged,
                                   net.minecraft.server.network.EventLoopGroupHolder loops, CallbackInfo ci) {*/
    //?} else if >=1.20.5 {
    /*@Inject(method = "pingServer(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;Ljava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true)
    private void etmc$describeLink(ServerData info, Runnable saver, Runnable pinged, CallbackInfo ci) {*/
    //?} else {
    /*@Inject(method = "pingServer(Lnet/minecraft/client/multiplayer/ServerData;Ljava/lang/Runnable;)V",
            at = @At("HEAD"), cancellable = true)
    private void etmc$describeLink(ServerData info, Runnable saver, CallbackInfo ci) {*/
    //?}
        //? if yarn {
        String address = info.address;
        //?} else {
        /*String address = info.ip;*/
        //?}
        if (address == null || !JoinCode.isLink(address)) return;
        ci.cancel();
        String invite = JoinCode.inviteIn(address);
        String label = invite != null ? invite
                : Msg.tr("etmc.serverlist.link", "etmc invite link: click Join Server to connect");
        // The entry reads these fields as it draws; a negative ping is "pinged, no connection".
        //? if yarn && <1.16 {
        /*info.label = label;
        info.playerCountLabel = "";*/
        //?} else if yarn {
        info.label = dev.l5z12.etmc.client.Txt.literal(label);
        info.playerCountLabel = dev.l5z12.etmc.client.Txt.literal("");
        //?} else {
        /*info.motd = dev.l5z12.etmc.client.Txt.literal(label);
        info.status = dev.l5z12.etmc.client.Txt.literal("");*/
        //?}
        info.ping = -1L;
        // 1.20.5+ draws from a status, which the ping callback sets (to "answered") before queueing the
        // entry's icon refresh on the client thread; overriding it right after means the refresh
        // reads "no connection".
        //? if yarn && >=1.20.5 {
        pinged.run();
        info.setStatus(ServerInfo.Status.UNREACHABLE);
        //?} else if !yarn && >=1.20.5 {
        /*pinged.run();
        info.setState(ServerData.State.UNREACHABLE);*/
        //?}
    }
}

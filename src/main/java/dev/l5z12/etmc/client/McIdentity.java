package dev.l5z12.etmc.client;

import dev.l5z12.etmc.core.PlayerCert;

import java.util.concurrent.TimeUnit;

/**
 * The game's player certificate, for a host who chose to prove their account. It comes from the
 * game's own key manager, so etmc never touches the account's access token. Versions before 1.19.3
 * have no public way to ask for it, so {@link #supported} is false there.
 */
public final class McIdentity {

    private static final long TIMEOUT_SECONDS = 10;

    private McIdentity() {}

    /** Whether this version of the game can sign at all. */
    public static boolean supported() {
        //? if >=1.19.3 {
        return true;
        //?} else {
        /*return false;*/
        //?}
    }

    /**
     * The certificate as it stands now, or {@code null} without one: an offline account, a version
     * that can't, or Mojang not answering. The game renews it, so ask again for each use.
     * Blocking; call off the render thread.
     */
    public static PlayerCert currentCert() {
        //? if >=1.19.3 {
        try {
            var client = McScreens.mc();
            //? if yarn {
            var pair = client.getProfileKeys().fetchKeyPair().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse(null);
            var account = client.getSession().getUuidOrNull();
            //?} else {
            /*var pair = client.getProfileKeyPairManager().prepareKeyPair().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse(null);
            var account = client.getUser().getProfileId();*/
            //?}
            if (pair == null || account == null) return null;
            var data = pair.publicKey().data();
            return new PlayerCert(account, data.expiresAt().toEpochMilli(), data.key().getEncoded(),
                    data.keySignature(), pair.privateKey());
        } catch (Exception e) {
            EtmcManager.LOGGER.warn("[etmc] couldn't get the player certificate", e);
            return null;
        }
        //?} else {
        /*return null;*/
        //?}
    }
}

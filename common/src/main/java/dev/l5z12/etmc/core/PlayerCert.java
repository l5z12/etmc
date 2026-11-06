package dev.l5z12.etmc.core;

import java.security.PrivateKey;
import java.util.UUID;

/**
 * A Minecraft account's player certificate, the same one that signs chat, as the game holds it.
 *
 * @param uuid            the account it was issued to. It names no player: Mojang signs the account ID
 * @param expiresAtMs     when it stops being valid, in epoch milliseconds
 * @param publicKey       the certified public key, X.509 encoded
 * @param mojangSignature Mojang's signature over the three fields above
 * @param privateKey      the matching private key, which never leaves this process
 */
public record PlayerCert(UUID uuid, long expiresAtMs, byte[] publicKey, byte[] mojangSignature, PrivateKey privateKey) {}

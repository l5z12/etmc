package dev.l5z12.etmc.core;

import java.io.IOException;
import java.security.PublicKey;
import java.util.Collection;
import java.util.UUID;

/** What a joiner has to ask Mojang to check a host's account. A failure means "can't tell", not "fake". */
interface AccountDirectory {

    /** The keys Mojang signs player certificates with. */
    Collection<PublicKey> certificateKeys() throws IOException, InterruptedException;

    /** The account's current name. */
    String nameOf(UUID account) throws IOException, InterruptedException;
}

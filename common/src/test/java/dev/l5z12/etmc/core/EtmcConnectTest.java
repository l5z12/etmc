package dev.l5z12.etmc.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hand-off slot between the screen mixin (which knows the etmc:// target) and the connection
 * mixin (which needs it a moment later, inside vanilla's connect). It is a single slot on purpose:
 * one user action at a time.
 */
class EtmcConnectTest {

    private final FakeEasyTier et = new FakeEasyTier();

    @AfterEach
    void reset() {
        EtmcConnect.takePending();
        EtmcConnect.setOnDisconnect(null);
    }

    private EtmcConnect.Target target(String instance) {
        return new EtmcConnect.Target(et, instance, EtmcConfig.HOST_VIRTUAL_IP, 25565);
    }

    @Test
    void theTargetIsHandedOverExactlyOnce() {
        EtmcConnect.Target t = target("etmc-host-ann");
        EtmcConnect.setPending(t);

        assertSame(t, EtmcConnect.takePending());
        assertNull(EtmcConnect.takePending(), "a second connect must not be hijacked");
    }

    @Test
    void thereIsNothingPendingByDefault() {
        assertNull(EtmcConnect.takePending());
    }

    @Test
    void aNewerTargetSupersedesAnAbandonedOne() {
        EtmcConnect.setPending(target("first"));
        EtmcConnect.setPending(target("second"));

        assertEquals("second", EtmcConnect.takePending().instanceName());
    }

    @Test
    void disconnectReportsTheInstanceThatEnded() {
        AtomicReference<String> seen = new AtomicReference<>();
        EtmcConnect.setOnDisconnect(seen::set);

        EtmcConnect.fireDisconnect("etmc-host-ann");

        assertEquals("etmc-host-ann", seen.get());
    }

    @Test
    void disconnectWithoutAListenerIsHarmless() {
        EtmcConnect.setOnDisconnect(null);
        EtmcConnect.fireDisconnect("etmc-host-ann"); // must not throw
    }

    @Test
    void thePlaceholderAddressIsAUsableHostPortPair() {
        MeshAddress addr = MeshAddress.parse(EtmcConnect.PLACEHOLDER_ADDRESS, 0);
        assertTrue(addr.port() > 0, "vanilla has to be able to parse it");
        assertEquals("127.0.0.1", addr.ip(), "it must never leave the machine");
    }
}

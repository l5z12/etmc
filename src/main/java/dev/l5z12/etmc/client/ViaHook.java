package dev.l5z12.etmc.client;

import java.lang.reflect.Method;

/**
 * Soft (reflection-only, zero compile dependency) integration with ViaFabricPlus.
 *
 * <p>VFP's protocol auto-detect opens its OWN raw {@code java.net.Socket} to the server address to read
 * the version. For an {@code etmc://} join that address is a loopback placeholder (the real transport
 * is an {@link dev.l5z12.etmc.core.EtmcChannel}), so VFP's probe reaches nothing and detection fails.
 *
 * <p>Instead we detect the host's protocol ourselves over the mesh ({@link
 * dev.l5z12.etmc.core.StatusPing}) and pin it onto the server entry via VFP's per-server
 * {@code IServerData#viaFabricPlus$forceVersion}. With a forced (non-auto-detect) version set, VFP
 * uses it directly and never runs its own probe. All reflective, so etmc carries no VFP dependency and
 * this is a no-op when VFP (or the protocol) is absent — including on NeoForge/Forge.
 */
public final class ViaHook {

    private static final String PROTOCOL_VERSION_CLASS =
            "com.viaversion.viaversion.api.protocol.version.ProtocolVersion";
    private static final boolean PRESENT = detect();

    private ViaHook() {}

    /** True if ViaFabricPlus is on the classpath (so a protocol probe is worth doing). */
    public static boolean isPresent() {
        return PRESENT;
    }

    /**
     * Forces {@code serverEntry} (a {@code ServerInfo}/{@code ServerData}) to the given protocol so
     * VFP skips auto-detect. Silently does nothing if VFP is absent, the protocol is unknown to VFP,
     * or the API has shifted — in which case VFP just falls back to its own (failing) detect, no worse
     * than before.
     */
    public static void forceVersion(Object serverEntry, int protocol) {
        if (serverEntry == null || protocol <= 0 || !PRESENT) return;
        try {
            Class<?> pvClass = Class.forName(PROTOCOL_VERSION_CLASS);
            Object pv = pvClass.getMethod("getProtocol", int.class).invoke(null, protocol);
            if (pv == null) return;
            Object known = pvClass.getMethod("isKnown").invoke(pv);
            if (known instanceof Boolean b && !b) return; // VFP can't translate it — leave it to VFP
            Method force = serverEntry.getClass().getMethod("viaFabricPlus$forceVersion", pvClass);
            force.invoke(serverEntry, pv);
        } catch (Throwable ignored) {
            // VFP not present / API changed — fall through, VFP keeps its own behavior.
        }
    }

    private static boolean detect() {
        try {
            Class.forName("com.viaversion.viafabricplus.protocoltranslator.ProtocolTranslator");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}

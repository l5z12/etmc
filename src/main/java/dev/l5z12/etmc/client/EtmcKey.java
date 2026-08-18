package dev.l5z12.etmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import net.minecraft.client.KeyMapping;
//? if >=1.19 {
import org.lwjgl.glfw.GLFW;
//?}

/**
 * Shared client glue for the Mojmap loaders (NeoForge/Forge): the open-menu keybind and the
 * per-tick work their entry points drive. Excluded from the Fabric build, which uses Fabric's
 * KeyBinding API and lifecycle events in {@code EtmcClient} instead. Loaders register
 * {@link #OPEN_MENU} on their mod bus and call {@link #clientTick()} each client tick.
 */
public final class EtmcKey {

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.etmc.open_menu",
            InputConstants.Type.KEYSYM,
            //? if >=1.19 {
            GLFW.GLFW_KEY_G,
            //?} else {
            /*InputConstants.getKey("key.keyboard.g").getValue(),*/
            //?}
            //? if >=1.21.9 {
            KeyMapping.Category.MISC);
            //?} else {
            /*"key.categories.misc");*/
            //?}

    private static volatile boolean inited;

    private EtmcKey() {}

    /**
     * One client tick for the Mojmap loaders. The native library is loaded on the first tick rather
     * than at construction: NeoForge and Forge build the mod object well before the game is ready,
     * and {@code EtmcManager.init()} needs the game directory.
     */
    public static void clientTick() {
        if (!inited) {
            inited = true;
            EtmcManager.get().init();
        }
        handleTick();
        EtmcManager.get().tick();
    }

    /** Opens the etmc menu when the keybind fires and no other screen is up. */
    private static void handleTick() {
        while (OPEN_MENU.consumeClick()) {
            if (McScreens.current() == null) {
                McScreens.goTo(new EtmcScreen(null));
            }
        }
    }
}

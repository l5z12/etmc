package dev.l5z12.etmc.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Shared open-menu keybind for the Mojmap loaders (NeoForge/Forge). Excluded from the Fabric build
 * (which uses Fabric's KeyBinding API in EtmcClient instead). Loaders register {@link #OPEN_MENU}
 * and call {@link #handleTick()}.
 */
public final class EtmcKey {

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.etmc.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            //? if >=1.21.9 {
            KeyMapping.Category.MISC);
            //?} else {
            /*"key.categories.misc");*/
            //?}

    private EtmcKey() {}

    public static void handleTick() {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_MENU.consumeClick()) {
            //? if >=26 {
            /*if (mc.gui.screen() == null) {
                mc.setScreenAndShow(new EtmcScreen(null));
            }*/
            //?} else {
            if (mc.screen == null) {
                mc.setScreen(new EtmcScreen(null));
            }
            //?}
        }
    }
}

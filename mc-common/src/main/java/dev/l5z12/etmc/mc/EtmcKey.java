package dev.l5z12.etmc.mc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.l5z12.etmc.mc.screen.EtmcScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Shared open-menu keybind. Loaders register {@link #OPEN_MENU} and call {@link #handleTick()}. */
public final class EtmcKey {

    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.etmc.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KeyMapping.Category.MISC);

    private EtmcKey() {}

    public static void handleTick() {
        Minecraft mc = Minecraft.getInstance();
        while (OPEN_MENU.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new EtmcScreen(null));
            }
        }
    }
}

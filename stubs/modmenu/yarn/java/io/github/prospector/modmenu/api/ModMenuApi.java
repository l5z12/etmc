package io.github.prospector.modmenu.api;

import net.minecraft.client.gui.screen.Screen;

import java.util.function.Function;

/**
 * Stub of Mod Menu's original entrypoint interface (io.github.prospector, Mod Menu 1.x–2.0.x, MC
 * 1.14–1.17). The oldest builds call getModId and getConfigScreenFactory, later ones
 * getModConfigScreenFactory. See stubs/README.md.
 */
public interface ModMenuApi {
    default String getModId() {
        return null;
    }

    default Function<Screen, ? extends Screen> getConfigScreenFactory() {
        return screen -> null;
    }

    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}

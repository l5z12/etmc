package io.github.prospector.modmenu.api;

import net.minecraft.client.gui.screen.Screen;

/** Stub of Mod Menu's original config screen factory (io.github.prospector). See stubs/README.md. */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}

package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

/** Stub of Mod Menu's config screen factory (com.terraformersmc). See stubs/README.md. */
@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}

package com.terraformersmc.modmenu.api;

/** Stub of Mod Menu's entrypoint interface (com.terraformersmc, Mod Menu 1.16.x+). See stubs/README.md. */
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> null;
    }
}

package dev.l5z12.etmc.client;

import dev.l5z12.etmc.client.screen.SettingsScreen;
//? if >=1.17 {
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
//?} else {
/*import io.github.prospector.modmenu.api.ConfigScreenFactory;
import io.github.prospector.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;*/
//?}

/**
 * Mod Menu integration (Fabric): etmc's entry in Mod Menu's mod list gets a config button that opens
 * etmc's settings. Registered as the {@code modmenu} entrypoint in fabric.mod.json, so it is only
 * loaded when Mod Menu is installed; it compiles against the stubs in {@code stubs/modmenu}, and the
 * real Mod Menu supplies the interfaces at runtime.
 *
 * <p>Mod Menu for MC 1.14–1.16 has the original {@code io.github.prospector} API (still supported by
 * the 1.16.x and 2.0.x builds); from 1.17 on, {@code com.terraformersmc}.
 */
public final class EtmcModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }

    //? if <1.17 {
    /*// The oldest Mod Menu builds (MC 1.14/1.15) identify the mod and ask for a plain function.
    @Override
    public String getModId() {
        return "etmc";
    }

    @Override
    public java.util.function.Function<Screen, ? extends Screen> getConfigScreenFactory() {
        return SettingsScreen::new;
    }
    *///?}
}

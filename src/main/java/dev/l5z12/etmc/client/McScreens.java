package dev.l5z12.etmc.client;

//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;*/
//?}

/**
 * The one place that knows how <em>this</em> Minecraft build hands you the client and its screen.
 *
 * <p>Three unrelated things drift across the supported range, and every caller that opens a screen
 * used to carry all three: the client class ({@code MinecraftClient} on yarn, {@code Minecraft} on
 * mojmap and on unobfuscated 26.x), the navigation call ({@code openScreen} before yarn 1.17,
 * {@code setScreen} through 1.21.x, {@code setScreenAndShow} from 26.x), and where the current screen
 * lives ({@code currentScreen} on yarn, {@code screen} on mojmap, {@code gui.screen()} from 26.x).
 *
 * <p>Keeping them here means a new Minecraft version is one edit, not five — the menu keybind, the
 * {@code /etmc menu} command, the manager's error/progress screens and the screen base class all go
 * through these.
 */
public final class McScreens {

    private McScreens() {}

    //? if yarn {
    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }
    //?} else {
    /*public static Minecraft mc() {
        return Minecraft.getInstance();
    }*/
    //?}

    /** Navigates to {@code screen}; {@code null} returns to the game. Client thread. */
    public static void goTo(Screen screen) {
        //? if >=26 {
        /*mc().setScreenAndShow(screen);*/
        //?} else if yarn && <1.17 {
        /*mc().openScreen(screen);*/
        //?} else {
        mc().setScreen(screen);
        //?}
    }

    /** The screen currently open, or {@code null} when the player is in the world. */
    public static Screen current() {
        //? if yarn {
        return mc().currentScreen;
        //?} else if <26 {
        /*return mc().screen;*/
        //?} else {
        /*return mc().gui.screen();*/
        //?}
    }
}

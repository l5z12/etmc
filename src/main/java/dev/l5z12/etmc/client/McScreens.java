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
 * <p>Four unrelated things drift across the supported range, and every caller used to carry the ones
 * it needed: the client class ({@code MinecraftClient} on yarn, {@code Minecraft} on mojmap and on
 * unobfuscated 26.x), the navigation call ({@code openScreen} before yarn 1.17, {@code setScreen}
 * through 1.21.x, {@code setScreenAndShow} from 26.x), where the current screen lives
 * ({@code currentScreen} on yarn, {@code screen} on mojmap, {@code gui.screen()} from 26.x), and the
 * clipboard handle ({@code keyboard} on yarn, {@code keyboardHandler} on mojmap).
 *
 * <p>Keeping them here means a new Minecraft version is one edit rather than one per caller — the
 * menu keybind, the {@code /etmc menu} and {@code /etmc invite} commands, the manager's
 * error/progress screens, the screen base class and every paste/copy button all go through these.
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

    /** The system clipboard's contents, or {@code ""} when it holds no text — never null. */
    public static String getClipboard() {
        //? if yarn {
        String text = mc().keyboard.getClipboard();
        //?} else {
        /*String text = mc().keyboardHandler.getClipboard();*/
        //?}
        return text == null ? "" : text;
    }

    /** Replaces the system clipboard's contents. */
    public static void setClipboard(String text) {
        //? if yarn {
        mc().keyboard.setClipboard(text);
        //?} else {
        /*mc().keyboardHandler.setClipboard(text);*/
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

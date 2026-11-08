package dev.l5z12.etmc.client;

//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;*/
//?}
// yarn named the link prompt ConfirmChatLinkScreen until 1.19.1; mojmap always called it ConfirmLinkScreen.
//? if yarn && >=1.19.1 {
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
//?} else if yarn {
/*import net.minecraft.client.gui.screen.ConfirmChatLinkScreen;*/
//?} else {
/*import net.minecraft.client.gui.screens.ConfirmLinkScreen;*/
//?}
// mojmap moved Util from net.minecraft into net.minecraft.util at 1.21.11. 26.3 takes links as URIs and
// opens them through Blaze3D (it replaced GLFW with SDL).
//? if >=26.3 {
/*import com.mojang.blaze3d.Blaze3D;
import java.net.URI;
*///?} else if yarn || >=1.21.11 {
import net.minecraft.util.Util;
//?} else {
/*import net.minecraft.Util;*/
//?}

/**
 * The one place that knows how <em>this</em> Minecraft build hands you the client and its screen.
 *
 * <p>Four unrelated things drift across the supported range, and every caller used to carry the ones
 * it needed: the client class ({@code MinecraftClient} on yarn, {@code Minecraft} on mojmap and on
 * unobfuscated 26.x), the navigation call ({@code openScreen} before yarn 1.17.1, {@code setScreen}
 * through 1.21.x, {@code setScreenAndShow} from 26.x), where the current screen lives
 * ({@code currentScreen} on yarn, {@code screen} on mojmap and 26.1, {@code gui.screen()} from 26.2),
 * and the clipboard handle ({@code keyboard} on yarn, {@code keyboardHandler} on mojmap).
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
        //?} else if yarn && <1.17.1 {
        /*mc().openScreen(screen);*/
        //?} else {
        mc().setScreen(screen);
        //?}
    }

    /**
     * Offers to open {@code url} in the browser, on the game's own prompt (which can also copy it),
     * then returns to {@code back}. Client thread.
     */
    public static void openLink(Screen back, String url) {
        //? if yarn && >=1.19.1 {
        goTo(new ConfirmLinkScreen(open -> linkAnswered(back, url, open), url, true));
        //?} else if yarn {
        /*goTo(new ConfirmChatLinkScreen(open -> linkAnswered(back, url, open), url, true));*/
        //?} else if >=26.3 {
        /*goTo(new ConfirmLinkScreen(open -> linkAnswered(back, url, open), URI.create(url), true));*/
        //?} else {
        /*goTo(new ConfirmLinkScreen(open -> linkAnswered(back, url, open), url, true));*/
        //?}
    }

    private static void linkAnswered(Screen back, String url, boolean open) {
        //? if yarn {
        if (open) Util.getOperatingSystem().open(url);
        //?} else if >=26.3 {
        /*if (open) Blaze3D.openUri(URI.create(url));*/
        //?} else {
        /*if (open) Util.getPlatform().openUri(url);*/
        //?}
        goTo(back);
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

    /** Whether a world is loaded (singleplayer or a server). */
    public static boolean inWorld() {
        //? if yarn {
        return mc().world != null;
        //?} else {
        /*return mc().level != null;*/
        //?}
    }

    /** Whether this client is running its own (singleplayer) world, i.e. has something to host. */
    public static boolean singleplayer() {
        //? if yarn {
        return mc().getServer() != null;
        //?} else {
        /*return mc().getSingleplayerServer() != null;*/
        //?}
    }

    /** Whether the F3 debug screen is up: it fills the corner the etmc overlay uses. */
    public static boolean debugScreenOpen() {
        //? if yarn && >=1.20.2 {
        return mc().getDebugHud().shouldShowDebugHud();
        //?} else if yarn {
        /*return mc().options.debugEnabled;*/
        //?} else if >=1.20.2 {
        /*return mc().getDebugOverlay().showDebugScreen();*/
        //?} else {
        /*return mc().options.renderDebug;*/
        //?}
    }

    /** Posts an {@code [etmc]} line to the player's chat; dropped when no world is loaded. */
    public static void chat(String msg) {
        var client = mc();
        client.execute(() -> {
            if (client.player == null) return;
            //? if yarn && >=1.16 {
            client.player.sendMessage(Txt.literal("[etmc] " + msg), false);
            //?} else if yarn {
            /*client.player.sendMessage(Txt.literal("[etmc] " + msg));*/
            //?} else if <26 {
            /*client.player.displayClientMessage(Txt.literal("[etmc] " + msg), false);*/
            //?} else {
            /*client.player.sendSystemMessage(Txt.literal("[etmc] " + msg));*/
            //?}
        });
    }

    /** The screen currently open, or {@code null} when the player is in the world. */
    public static Screen current() {
        //? if yarn {
        return mc().currentScreen;
        //?} else if <26.2 {
        /*return mc().screen;*/
        //?} else {
        /*return mc().gui.screen();*/
        //?}
    }
}

package dev.l5z12.etmc.client;

import dev.l5z12.etmc.client.command.EtmcCommands;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import net.fabricmc.api.ClientModInitializer;
//? if >=1.19 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//?}
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//? if yarn {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;*/
//?}
//? if >=1.21 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;*/
//?}
//? if yarn {
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
//?} else {
/*import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;*/
//?}
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entry point: loads the EasyTier native library, registers the open-menu keybind, the HUD
 * overlay, and the {@code /etmc} client commands.
 */
public final class EtmcClient implements ClientModInitializer {

    public static final String MOD_ID = "etmc";
    public static final Logger LOGGER = LoggerFactory.getLogger("etmc");

    //? if yarn {
    private static KeyBinding openMenuKey;
    //?} else {
    /*private static KeyMapping openMenuKey;*/
    //?}

    @Override
    public void onInitializeClient() {
        EtmcManager.get().init();
        if (EtmcManager.get().isReady()) {
            LOGGER.info("[etmc] EasyTier native library loaded.");
        } else {
            LOGGER.warn("[etmc] EasyTier native library NOT loaded: {}", EtmcManager.get().nativeError());
        }

        //? if yarn && >=1.21 {
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.etmc.open_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyBinding.Category.MISC));
        //?} else if yarn {
        /*openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.etmc.open_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.misc"));*/
        //?} else {
        /*openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.etmc.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));*/
        //?}

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            EtmcManager.get().tick();
            if (openMenuKey != null) {
                //? if yarn {
                while (openMenuKey.wasPressed()) {
                    if (client.currentScreen == null) {
                        client.setScreen(new EtmcScreen(null));
                    }
                }
                //?} else {
                /*while (openMenuKey.consumeClick()) {
                    if (client.gui.screen() == null) {
                        client.setScreenAndShow(new EtmcScreen(null));
                    }
                }*/
                //?}
            }
        });

        // 1.21+: HudRenderCallback is deprecated/no-op, use the HUD element API. Older: HudRenderCallback.
        //? if yarn && >=1.21 {
        HudElementRegistry.addLast(Identifier.of("etmc", "status"), (ctx, counter) -> EtmcHud.render(ctx));
        //?} else if >=1.21 {
        /*HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("etmc", "status"), (ctx, counter) -> EtmcHud.render(ctx));*/
        //?} else {
        /*HudRenderCallback.EVENT.register((ctx, tickDelta) -> EtmcHud.render(ctx));*/
        //?}

        // 1.19+: register via the command-registration event (v2 API). 1.16-1.18: the v1 client
        // command API has no event — register directly on the global ClientCommandManager.DISPATCHER.
        //? if >=1.19 {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                EtmcCommands.register(dispatcher));
        //?} else {
        /*EtmcCommands.register(net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.DISPATCHER);*/
        //?}
    }

    //? if yarn {
    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }
    //?} else {
    /*public static Minecraft mc() {
        return Minecraft.getInstance();
    }*/
    //?}
}

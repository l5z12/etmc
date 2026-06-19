package dev.l5z12.etmc.client;

import dev.l5z12.etmc.client.command.EtmcCommands;
import dev.l5z12.etmc.client.screen.EtmcScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//? if >=1.21 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;*/
//?}
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
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

    private static KeyBinding openMenuKey;

    @Override
    public void onInitializeClient() {
        EtmcManager.get().init();
        if (EtmcManager.get().isReady()) {
            LOGGER.info("[etmc] EasyTier native library loaded.");
        } else {
            LOGGER.warn("[etmc] EasyTier native library NOT loaded: {}", EtmcManager.get().nativeError());
        }

        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.etmc.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                //? if >=1.21 {
                KeyBinding.Category.MISC));
                //?} else {
                /*"key.categories.misc"));*/
                //?}

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            EtmcManager.get().tick();
            if (openMenuKey != null) {
                while (openMenuKey.wasPressed()) {
                    if (client.currentScreen == null) {
                        client.setScreen(new EtmcScreen(null));
                    }
                }
            }
        });

        // 1.21+: HudRenderCallback is deprecated/no-op, use the HUD element API. Older: HudRenderCallback.
        //? if >=1.21 {
        HudElementRegistry.addLast(Identifier.of("etmc", "status"), (ctx, counter) -> EtmcHud.render(ctx));
        //?} else {
        /*HudRenderCallback.EVENT.register((ctx, tickDelta) -> EtmcHud.render(ctx));*/
        //?}

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                EtmcCommands.register(dispatcher));
    }

    public static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }
}

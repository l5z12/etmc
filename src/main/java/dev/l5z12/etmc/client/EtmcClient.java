package dev.l5z12.etmc.client;

//? if >=1.16.2 {
import dev.l5z12.etmc.client.command.EtmcCommands;
//?}
import dev.l5z12.etmc.client.screen.EtmcScreen;
import net.fabricmc.api.ClientModInitializer;
//? if >=1.19 {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//?}
// fabric-api 0.13.1+build.370-1.16 (the only build published for MC 1.16/1.16.1) doesn't
// include the fabric-lifecycle-events-v1 client module; fall back to the old v0 callback there.
//? if >=1.16.2 {
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//?} else {
/*import net.fabricmc.fabric.api.event.client.ClientTickCallback;*/
//?}
//? if yarn {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;*/
//?}
//? if >=1.21.6 {
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//?} else if >=1.15 {
/*import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
*///?}
//? if yarn {
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
//?} else {
/*import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;*/
//?}
// KeyBinding moved net.minecraft.client.options -> net.minecraft.client.option at yarn 1.16.5.
//? if yarn && >=1.16.5 {
import net.minecraft.client.option.KeyBinding;
//?} else if yarn {
/*import net.minecraft.client.options.KeyBinding;*/
//?}
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.Logger;

/**
 * Client entry point: loads the EasyTier native library, registers the open-menu keybind, the HUD
 * overlay, and the {@code /etmc} client commands.
 */
public final class EtmcClient implements ClientModInitializer {

    /** The one etmc logger, shared with {@link EtmcManager} (log4j2 exists on every version/loader). */
    private static final Logger LOGGER = EtmcManager.LOGGER;

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

        //? if yarn && >=1.21.9 {
        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.etmc.open_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyBinding.Category.MISC));
        //?} else if yarn {
        /*openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.etmc.open_menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.misc"));
        *///?} else {
        /*openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.etmc.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KeyMapping.Category.MISC));*/
        //?}

        //? if >=1.16.2 {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
        //?} else {
        /*ClientTickCallback.EVENT.register(client -> {*/
        //?}
            EtmcManager.get().tick();
            if (openMenuKey != null) {
                // Only the "was it pressed" call differs by loader; where the screen goes is McScreens'.
                //? if yarn {
                while (openMenuKey.wasPressed()) {
                //?} else {
                /*while (openMenuKey.consumeClick()) {*/
                //?}
                    if (McScreens.current() == null) {
                        McScreens.goTo(new EtmcScreen(null));
                    }
                }
            }
        });

        // HUD element API (fabric-api hud package) lands at 1.21.6; 1.16-1.21.5 use HudRenderCallback with
        // a graphics ctx; 1.15 uses the pre-MatrixStack 1-arg callback; 1.14.4's fabric-api has no
        // rendering.v1 at all → the HUD is skipped there (keybind + GUI still cover it).
        //? if yarn && >=1.21.6 {
        HudElementRegistry.addLast(Identifier.of("etmc", "status"), (ctx, counter) -> EtmcHud.render(ctx));
        //?} else if yarn && >=1.16 {
        /*HudRenderCallback.EVENT.register((ctx, tickDelta) -> EtmcHud.render(ctx));
        *///?} else if yarn && >=1.15 {
        /*HudRenderCallback.EVENT.register(tickDelta -> EtmcHud.render(0));*/
        //?} else if !yarn {
        /*HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("etmc", "status"), (ctx, counter) -> EtmcHud.render(ctx));*/
        //?}

        // 1.19+: register via the command-registration event (v2 API). 1.16.2-1.18: the v1 client
        // command API has no event — register directly on ClientCommandManager.DISPATCHER. Below 1.16.2
        // no fabric-api ships a client command API (it postdates 1.16.1) → the /etmc command is skipped.
        //? if >=1.19 {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                EtmcCommands.register(dispatcher));
        //?} else if >=1.16.2 {
        /*EtmcCommands.register(net.fabricmc.fabric.api.client.command.v1.ClientCommandManager.DISPATCHER);*/
        //?}
    }

}

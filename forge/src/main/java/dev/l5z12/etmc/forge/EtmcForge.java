package dev.l5z12.etmc.forge;

import dev.l5z12.etmc.mc.EtmcClientCore;
import dev.l5z12.etmc.mc.EtmcCommands;
import dev.l5z12.etmc.mc.EtmcHud;
import dev.l5z12.etmc.mc.EtmcKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * MinecraftForge client entry (Forge 1.21.x / EventBus 7). Registers the shared Mojmap client
 * (keybind, HUD, commands, tick) onto the Forge buses; all behaviour lives in the shared
 * {@code dev.l5z12.etmc.mc} package, also used by NeoForge.
 */
@Mod("etmc")
public final class EtmcForge {

    private boolean inited;

    public EtmcForge() {
        BusGroup modBus = FMLJavaModLoadingContext.get().getModBusGroup();

        // Mod bus: registration events.
        RegisterKeyMappingsEvent.getBus(modBus).addListener(e -> e.register(EtmcKey.OPEN_MENU));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(e ->
                e.getLayeredDraw().add(ResourceLocation.fromNamespaceAndPath("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));

        // Game bus: client commands + ticking.
        RegisterClientCommandsEvent.BUS.addListener(e -> EtmcCommands.register(e.getDispatcher()));
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> onClientTick());
    }

    private void onClientTick() {
        if (!inited) {
            inited = true;
            EtmcClientCore.get().init();
        }
        EtmcKey.handleTick();
        EtmcClientCore.get().tick();
    }
}

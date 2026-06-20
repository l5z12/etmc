package dev.l5z12.etmc.neoforge;

import dev.l5z12.etmc.client.EtmcHud;
import dev.l5z12.etmc.client.EtmcKey;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.command.EtmcCommands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client entry. Registers the shared client (keybind, HUD, commands, tick) onto the NeoForge
 * event buses; all behaviour lives in the shared {@code dev.l5z12.etmc.client} package (the same tree
 * Fabric builds, selected by the {@code //? if !fabric} mojmap guards).
 */
@Mod("etmc")
public final class EtmcNeoForge {

    private boolean inited;

    public EtmcNeoForge(IEventBus modBus) {
        // Mod bus: registration events.
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(EtmcKey.OPEN_MENU));
        modBus.addListener((RegisterGuiLayersEvent e) ->
                e.registerAboveAll(ResourceLocation.fromNamespaceAndPath("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));

        // Game bus: client commands + ticking.
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> EtmcCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> onClientTick());
    }

    private void onClientTick() {
        if (!inited) {
            inited = true;
            EtmcManager.get().init();
        }
        EtmcKey.handleTick();
        EtmcManager.get().tick();
    }
}

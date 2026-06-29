package dev.l5z12.etmc.neoforge;

import dev.l5z12.etmc.client.EtmcHud;
import dev.l5z12.etmc.client.EtmcKey;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.command.EtmcCommands;
//? if >=1.21.11 {
/*import net.minecraft.resources.Identifier;*/
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
//? if >=1.20.5 {
import net.neoforged.neoforge.client.event.ClientTickEvent;
//?} else {
/*import net.neoforged.neoforge.event.TickEvent;*/
//?}
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
//? if >=1.20.5 {
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.RegisterGuiOverlaysEvent;*/
//?}
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge client entry. Registers the shared client (keybind, HUD, commands, tick) onto the NeoForge
 * event buses; all behaviour lives in the shared {@code dev.l5z12.etmc.client} package (the same tree
 * Fabric builds, selected by the {@code //? if !fabric} mojmap guards).
 *
 * <p>The HUD + tick events changed at 1.20.5: {@code >=1.20.5} uses the layer API
 * ({@code RegisterGuiLayersEvent}) and split {@code ClientTickEvent.Post}; the first NeoForge releases
 * ({@code 1.20.2}/{@code 1.20.4}) use the older overlay API ({@code RegisterGuiOverlaysEvent} +
 * {@code IGuiOverlay}) and the Forge-style {@code TickEvent.ClientTickEvent} with a {@code phase}.
 */
@Mod("etmc")
public final class EtmcNeoForge {

    private boolean inited;

    public EtmcNeoForge(IEventBus modBus) {
        // Mod bus: keybind + HUD overlay.
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(EtmcKey.OPEN_MENU));
        //? if >=1.21.11 {
        /*modBus.addListener((RegisterGuiLayersEvent e) ->
                e.registerAboveAll(Identifier.fromNamespaceAndPath("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));*/
        //?} else if >=1.21 {
        modBus.addListener((RegisterGuiLayersEvent e) ->
                e.registerAboveAll(ResourceLocation.fromNamespaceAndPath("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));
        //?} else if >=1.20.5 {
        /*modBus.addListener((RegisterGuiLayersEvent e) ->
                e.registerAboveAll(new ResourceLocation("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));*/
        //?} else {
        /*modBus.addListener((RegisterGuiOverlaysEvent e) ->
                e.registerAboveAll("etmc_status",
                        (gui, guiGraphics, partialTick, width, height) -> EtmcHud.render(guiGraphics)));*/
        //?}

        // Game bus: client commands + ticking.
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> EtmcCommands.register(e.getDispatcher()));
        //? if >=1.20.5 {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> onClientTick());
        //?} else {
        /*NeoForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) onClientTick();
        });*/
        //?}
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

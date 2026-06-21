package dev.l5z12.etmc.forge;

import dev.l5z12.etmc.client.EtmcHud;
import dev.l5z12.etmc.client.EtmcKey;
import dev.l5z12.etmc.client.EtmcManager;
import dev.l5z12.etmc.client.command.EtmcCommands;
//? if >=1.21.11 {
/*import net.minecraft.resources.Identifier;*/
//?} else {
import net.minecraft.resources.ResourceLocation;
//?}
//? if >=1.21.8 {
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
//?} else if >=1.21.6 {
//?} else if >=1.21.1 {
/*import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;*/
//?} else {
//?}
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
//? if >=1.21.6 {
import net.minecraftforge.eventbus.api.bus.BusGroup;
//?} else {
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;*/
//?}
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * MinecraftForge client entry. Registers the shared client (keybind, HUD, commands, tick) onto the
 * Forge buses; all behaviour lives in the shared {@code dev.l5z12.etmc.client} package — the same tree
 * Fabric/NeoForge build, with the {@code //? if !fabric} mojmap branches.
 *
 * <p>Forge's bus API changed mid-1.21.x: <b>1.21.6+</b> uses EventBus 7 ({@code BusGroup}, per-event
 * {@code .getBus()}/{@code .BUS}, {@code ClientTickEvent.Post}); <b>1.21–1.21.5</b> uses EventBus 6
 * ({@code IEventBus} from {@code getModEventBus()}, {@code MinecraftForge.EVENT_BUS}, {@code TickEvent}
 * with a {@code phase}). The HUD-layer event ({@code AddGuiOverlayLayersEvent}) exists EventBus-6-style
 * pre-1.21.6 and EventBus-7-style from 1.21.8; the short 1.21.6/1.21.7 window has no HUD-layer
 * registration API, so the overlay is skipped there (everything else still works).
 */
@Mod("etmc")
public final class EtmcForge {

    private boolean inited;

    public EtmcForge() {
        // Mod bus + open-menu keybind.
        //? if >=1.21.6 {
        BusGroup modBus = FMLJavaModLoadingContext.get().getModBusGroup();
        RegisterKeyMappingsEvent.getBus(modBus).addListener(e -> e.register(EtmcKey.OPEN_MENU));
        //?} else {
        /*IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(EtmcKey.OPEN_MENU));*/
        //?}

        // HUD overlay. Skipped on 1.21 and 1.21.6/1.21.7 (no HUD-layer registration API in those windows).
        //? if >=1.21.11 {
        /*AddGuiOverlayLayersEvent.getBus(modBus).addListener(e ->
                e.getLayeredDraw().add(Identifier.fromNamespaceAndPath("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));*/
        //?} else if >=1.21.8 {
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(e ->
                e.getLayeredDraw().add(ResourceLocation.fromNamespaceAndPath("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));
        //?} else if >=1.21.6 {
        //?} else if >=1.21.1 {
        /*modBus.addListener((AddGuiOverlayLayersEvent e) ->
                e.getLayeredDraw().add(ResourceLocation.fromNamespaceAndPath("etmc", "status"),
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));*/
        //?} else {
        //?}

        // Game bus: client commands + ticking.
        //? if >=1.21.6 {
        RegisterClientCommandsEvent.BUS.addListener(e -> EtmcCommands.register(e.getDispatcher()));
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> onClientTick());
        //?} else {
        /*MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> EtmcCommands.register(e.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
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

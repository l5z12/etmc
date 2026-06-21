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
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * MinecraftForge client entry (Forge 1.21.x / EventBus 7). Registers the shared client (keybind, HUD,
 * commands, tick) onto the Forge buses; all behaviour lives in the shared {@code dev.l5z12.etmc.client}
 * package — the same tree Fabric/NeoForge build, with the {@code //? if !fabric} mojmap branches.
 */
@Mod("etmc")
public final class EtmcForge {

    private boolean inited;

    public EtmcForge() {
        BusGroup modBus = FMLJavaModLoadingContext.get().getModBusGroup();

        // Mod bus: registration events.
        RegisterKeyMappingsEvent.getBus(modBus).addListener(e -> e.register(EtmcKey.OPEN_MENU));
        AddGuiOverlayLayersEvent.getBus(modBus).addListener(e ->
                //? if >=1.21.11 {
                /*e.getLayeredDraw().add(Identifier.fromNamespaceAndPath("etmc", "status"),*/
                //?} else {
                e.getLayeredDraw().add(ResourceLocation.fromNamespaceAndPath("etmc", "status"),
                //?}
                        (guiGraphics, deltaTracker) -> EtmcHud.render(guiGraphics)));

        // Game bus: client commands + ticking.
        RegisterClientCommandsEvent.BUS.addListener(e -> EtmcCommands.register(e.getDispatcher()));
        TickEvent.ClientTickEvent.Post.BUS.addListener(e -> onClientTick());
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

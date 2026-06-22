package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}
//? if yarn && >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else if yarn {
/*import net.minecraft.client.util.math.MatrixStack;*/
//?} else if <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;*/
//?} else if <26 {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?} else {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;*/
//?}

/**
 * Shown while etmc waits for a <b>direct (P2P)</b> link to the host (instance start + hole punching).
 * The manager auto-proceeds as soon as a direct route is available (or a short timeout elapses);
 * <b>Join now anyway</b> proceeds immediately over whatever path exists (including a relay).
 * Cancel/Esc aborts via {@code onCancel}.
 */
public final class EtmcConnectingScreen extends EtmcBaseScreen {

    private final Screen parent;
    private final String label;
    private final Runnable onProceed;
    private final Runnable onCancel;
    private int ticks;

    public EtmcConnectingScreen(Screen parent, String label, Runnable onProceed, Runnable onCancel) {
        super(Txt.literal("Establishing P2P connection"));
        this.parent = parent;
        this.label = label;
        this.onProceed = onProceed;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        add(Ui.button(Txt.literal("Join now anyway"), b -> {
                    if (onProceed != null) onProceed.run();
                })
                .dimensions(this.width / 2 - 100, this.height / 2 + 18, 200, 20).build());
        add(Ui.button(Txt.translatable("gui.cancel"), b -> this.close())
                .dimensions(this.width / 2 - 100, this.height / 2 + 42, 200, 20).build());
    }

    @Override
    public void tick() {
        this.ticks++;
    }

    @Override
    //? if yarn && >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else if yarn {
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)*/
    //?} else if <1.20 {
    /*public void render(PoseStack ctx, int mouseX, int mouseY, float delta)*/
    //?} else if <26 {
    /*public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta)*/
    //?} else {
    /*public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta)*/
    //?}
    {
        //? if >=26 {
        /*super.extractRenderState(ctx, mouseX, mouseY, delta);*/
        //?} else {
        super.render(ctx, mouseX, mouseY, delta);
        //?}
        String dots = ".".repeat((this.ticks / 5) % 4);
        String sub = (label == null || label.isBlank() ? "Connecting" : "Connecting to " + label) + dots;
        Gfx.centered(ctx, font(), this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
        Gfx.centered(ctx, font(), Txt.literal(sub), this.width / 2, this.height / 2 - 30, 0xFFAAAAAA);
        Gfx.centered(ctx, font(),
                Txt.literal("Waiting for a direct link — or join now over a relay."),
                this.width / 2, this.height / 2 - 14, 0xFF777777);
    }

    //? if yarn && >=1.18.2 {
    @Override
    //?}
    public void close() {
        if (onCancel != null) onCancel.run();
        goTo(parent);
    }

    //? if !yarn || <1.18.2 {
    /*@Override
    public void onClose() {
        this.close();
    }*/
    //?}
}

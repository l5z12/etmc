package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
//? if >=1.20 {
import net.minecraft.client.gui.DrawContext;
//?} else
/*import net.minecraft.client.util.math.MatrixStack;*/
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Shown while etmc waits for a <b>direct (P2P)</b> link to the host (instance start + hole punching).
 * The manager auto-proceeds as soon as a direct route is available (or a short timeout elapses);
 * <b>Join now anyway</b> proceeds immediately over whatever path exists (including a relay).
 * Cancel/Esc aborts via {@code onCancel}.
 */
public final class EtmcConnectingScreen extends Screen {

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
        addDrawableChild(Ui.button(Txt.literal("Join now anyway"), b -> {
                    if (onProceed != null) onProceed.run();
                })
                .dimensions(this.width / 2 - 100, this.height / 2 + 18, 200, 20).build());
        addDrawableChild(Ui.button(Txt.translatable("gui.cancel"), b -> this.close())
                .dimensions(this.width / 2 - 100, this.height / 2 + 42, 200, 20).build());
    }

    @Override
    public void tick() {
        this.ticks++;
    }

    @Override
    //? if >=1.20 {
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta)
    //?} else
    /*public void render(MatrixStack ctx, int mouseX, int mouseY, float delta)*/
    {
        super.render(ctx, mouseX, mouseY, delta);
        String dots = ".".repeat((this.ticks / 5) % 4);
        String sub = (label == null || label.isBlank() ? "Connecting" : "Connecting to " + label) + dots;
        Gfx.centered(ctx, this.textRenderer, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
        Gfx.centered(ctx, this.textRenderer, Txt.literal(sub), this.width / 2, this.height / 2 - 30, 0xFFAAAAAA);
        Gfx.centered(ctx, this.textRenderer,
                Txt.literal("Waiting for a direct link — or join now over a relay."),
                this.width / 2, this.height / 2 - 14, 0xFF777777);
    }

    //? if >=1.18 {
    @Override
    //?}
    public void close() {
        if (onCancel != null) onCancel.run();
        this.client.setScreen(parent);
    }

    //? if <1.18 {
    /*@Override
    public void onClose() {
        this.close();
    }*/
    //?}
}

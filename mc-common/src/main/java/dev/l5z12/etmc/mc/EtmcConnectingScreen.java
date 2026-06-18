package dev.l5z12.etmc.mc;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Mojmap (NeoForge/Forge) twin: waits for a <b>direct (P2P)</b> link to the host. The manager
 * auto-proceeds once a direct route is available (or a short timeout elapses); <b>Join now anyway</b>
 * proceeds immediately over whatever path exists (including a relay). Cancel/Esc aborts via {@code onCancel}.
 */
public final class EtmcConnectingScreen extends Screen {

    private final Screen parent;
    private final String label;
    private final Runnable onProceed;
    private final Runnable onCancel;
    private int ticks;

    public EtmcConnectingScreen(Screen parent, String label, Runnable onProceed, Runnable onCancel) {
        super(Component.literal("Establishing P2P connection"));
        this.parent = parent;
        this.label = label;
        this.onProceed = onProceed;
        this.onCancel = onCancel;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Join now anyway"), b -> {
                    if (onProceed != null) onProceed.run();
                })
                .bounds(this.width / 2 - 100, this.height / 2 + 18, 200, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height / 2 + 42, 200, 20).build());
    }

    @Override
    public void tick() {
        this.ticks++;
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        String dots = ".".repeat((this.ticks / 5) % 4);
        String sub = (label == null || label.isBlank() ? "Connecting" : "Connecting to " + label) + dots;
        ctx.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 50, 0xFFFFFFFF);
        ctx.drawCenteredString(this.font, Component.literal(sub), this.width / 2, this.height / 2 - 30, 0xFFAAAAAA);
        ctx.drawCenteredString(this.font, Component.literal("Waiting for a direct link — or join now over a relay."),
                this.width / 2, this.height / 2 - 14, 0xFF777777);
    }

    @Override
    public void onClose() {
        if (onCancel != null) onCancel.run();
        this.minecraft.setScreen(parent);
    }
}

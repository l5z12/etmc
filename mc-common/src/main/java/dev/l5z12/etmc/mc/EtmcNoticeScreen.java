package dev.l5z12.etmc.mc;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Mojmap (NeoForge/Forge) twin of the Fabric notice screen: shown when an etmc join can't proceed
 * (bad link, start failure, …).
 */
public final class EtmcNoticeScreen extends Screen {

    private final Screen parent;
    private final Component message;

    public EtmcNoticeScreen(Screen parent, String title, String message) {
        super(Component.literal(title));
        this.parent = parent;
        this.message = Component.literal(message == null ? "Unknown error" : message);
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> this.onClose())
                .bounds(this.width / 2 - 100, this.height / 2 + 30, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 34, 0xFFFF5555);
        ctx.drawCenteredString(this.font, this.message, this.width / 2, this.height / 2 - 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}

package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.Gfx;
import dev.l5z12.etmc.client.Txt;
import dev.l5z12.etmc.client.Ui;
import dev.l5z12.etmc.client.Wrap;
import dev.l5z12.etmc.core.Msg;
//? if yarn {
import net.minecraft.client.gui.screen.Screen;
//?} else {
/*import net.minecraft.client.gui.screens.Screen;*/
//?}

import java.util.function.Supplier;

/**
 * Shown while an {@code etmc://} join gets the connection ready: the EasyTier network starting, the
 * relay answering, the host turning up, then a few seconds' chance at a direct (P2P) link. The line
 * under the title says which of those it is actually waiting on, so "it's stuck" has a reason.
 * The manager joins on its own when ready (or gives up with an explanation); <b>Join now anyway</b>
 * skips the wait, and Cancel/Esc aborts via {@code onCancel}.
 */
public final class EtmcConnectingScreen extends EtmcBaseScreen {

    private final Runnable onProceed;
    private final Runnable onCancel;
    private final Supplier<String> progress;
    private int ticks;

    public EtmcConnectingScreen(Screen parent, String label, Runnable onProceed, Runnable onCancel,
                                Supplier<String> progress) {
        super(label == null || label.isBlank() ? Txt.tr("etmc.connecting.title", "Joining")
                : Txt.tr("etmc.connecting.title_label", "Joining %s", label), parent);
        this.onProceed = onProceed;
        this.onCancel = onCancel;
        this.progress = progress;
    }

    @Override
    protected void init() {
        add(Ui.button(Txt.tr("etmc.connecting.now", "Join now anyway"), b -> {
                    if (onProceed != null) onProceed.run();
                })
                .dimensions(this.width / 2 - 100, this.height / 2 + 24, 200, 20).build());
        add(Ui.button(Txt.translatable("gui.cancel"), b -> this.close())
                .dimensions(this.width / 2 - 100, this.height / 2 + 48, 200, 20).build());
    }

    @Override
    public void tick() {
        this.ticks++;
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        String dots = ".".repeat((this.ticks / 5) % 4);
        String now = progress == null ? null : progress.get();
        Gfx.centered(ctx, font(), this.title, this.width / 2, this.height / 2 - 50, COLOR_TEXT);
        int y = this.height / 2 - 32;
        for (String l : Wrap.lines(font(), (now == null ? Msg.tr("etmc.connecting.connecting", "Connecting") : now) + dots, Math.min(320, this.width - 40))) {
            Gfx.centered(ctx, font(), Txt.literal(l), this.width / 2, y, COLOR_MUTED);
            y += 11;
        }
        Gfx.centered(ctx, font(),
                Txt.tr("etmc.connecting.now.hint",
                        "\"Join now anyway\" skips the wait (it may fail if the host isn't reachable yet)."),
                this.width / 2, this.height / 2 + 8, 0xFF777777);
    }

    /** Esc / Cancel aborts the pending join before returning to the parent screen. */
    @Override
    public void close() {
        if (onCancel != null) onCancel.run();
        super.close();
    }
}

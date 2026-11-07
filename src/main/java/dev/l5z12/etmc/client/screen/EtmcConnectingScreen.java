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
 * under the bar says which of those it is actually waiting on, so "it's stuck" has a reason.
 * The manager joins on its own when ready (or gives up with an explanation); <b>Join now anyway</b>
 * skips the wait, and Cancel/Esc aborts via {@code onCancel}. With no {@code onProceed} there is
 * nothing to skip yet, so that button stays hidden.
 */
public final class EtmcConnectingScreen extends EtmcBaseScreen {

    private static final int BAR_W = 120;
    private static final int BAR_H = 2;
    private static final int BLOCK_W = 32;
    private static final int BAR_TRACK = 0x40FFFFFF;
    /** Pixels the bar's block moves per tick. */
    private static final float BAR_SPEED = 4;

    private final Runnable onProceed;
    private final Runnable onCancel;
    private final Supplier<String> progress;
    private int ticks;

    public EtmcConnectingScreen(Screen parent, String label, Runnable onProceed, Runnable onCancel,
                                Supplier<String> progress) {
        this(parent, label, null, onProceed, onCancel, progress);
    }

    /** @param invite the link's invite line, shown as the title in place of {@code label}; may be {@code null} */
    public EtmcConnectingScreen(Screen parent, String label, String invite, Runnable onProceed, Runnable onCancel,
                                Supplier<String> progress) {
        super(invite != null ? Txt.literal(invite)
                : label == null || label.isBlank() ? Txt.tr("etmc.connecting.title", "Joining")
                : Txt.tr("etmc.connecting.title_label", "Joining %s", label), parent);
        this.onProceed = onProceed;
        this.onCancel = onCancel;
        this.progress = progress;
    }

    @Override
    protected void build() {
        int cells = onProceed == null ? 1 : 2;
        if (onProceed != null) {
            Ui.Builder now = footer(Ui.button(Txt.tr("etmc.connecting.now", "Join now anyway"), b -> onProceed.run()), 0, 2);
            hint(now, Msg.tr("etmc.connecting.now.hint",
                    "\"Join now anyway\" skips the wait (it may fail if the host isn't reachable yet)."));
            add(now.build());
        }
        add(footer(Ui.button(Txt.translatable("gui.cancel"), b -> this.close()), cells - 1, cells).build());
    }

    @Override
    public void tick() {
        super.tick();
        this.ticks++;
    }

    @Override
    protected void draw(Object ctx, int mouseX, int mouseY, float delta) {
        int cx = this.width / 2;
        int y = HEADER + (bandBottom() - HEADER) / 2 - 16;

        // A block sliding along a track: it says "still working", and text can't jitter as dots would.
        int x = cx - BAR_W / 2;
        Gfx.fill(ctx, x, y, x + BAR_W, y + BAR_H, BAR_TRACK);
        int travel = BAR_W + BLOCK_W;
        int head = (int) ((this.ticks + delta) * BAR_SPEED % travel);
        Gfx.fill(ctx, x + Math.max(0, head - BLOCK_W), y, x + Math.min(BAR_W, head), y + BAR_H, COLOR_GOOD);
        y += BAR_H + 10;

        String now = progress == null ? null : progress.get();
        for (String l : Wrap.lines(font(), now == null ? Msg.tr("etmc.connecting.connecting", "Connecting…") : now, col(320))) {
            Gfx.centered(ctx, font(), Txt.literal(l), cx, y, COLOR_TEXT);
            y += LINE_H;
        }
    }

    /** Esc / Cancel aborts the pending join before returning to the parent screen. */
    @Override
    public void close() {
        if (onCancel != null) onCancel.run();
        super.close();
    }
}

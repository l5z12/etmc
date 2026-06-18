package dev.l5z12.etmc.client.screen;

import dev.l5z12.etmc.client.EtmcManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Connect to an EasyTier-networked Minecraft server from a hosted config: paste the HTTP(S) link to
 * the server's EasyTier config (network, relay, ip). If the config doesn't carry an
 * {@code [etmc] server = "ip:port"} line, fill the optional Server field.
 */
public final class ConnectUrlScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget urlField;
    private TextFieldWidget serverField;
    private ButtonWidget connectButton;
    private volatile String message = "";
    private volatile int messageColor = 0xFFAAAAAA;

    public ConnectUrlScreen(Screen parent) {
        super(Text.literal("Connect via config URL"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 280;
        int y = this.height / 4;

        urlField = new TextFieldWidget(this.textRenderer, cx - w / 2, y + 12, w, 20, Text.literal("Config URL"));
        urlField.setMaxLength(1024);
        addDrawableChild(urlField);
        setInitialFocus(urlField);
        y += 44;

        serverField = new TextFieldWidget(this.textRenderer, cx - w / 2, y + 12, w, 20, Text.literal("Server"));
        serverField.setMaxLength(64);
        addDrawableChild(serverField);
        y += 48;

        addDrawableChild(ButtonWidget.builder(Text.literal("Paste URL"), b ->
                urlField.setText(this.client.keyboard.getClipboard().trim()))
                .dimensions(cx - w / 2, y, w, 20).build());
        y += 28;

        connectButton = ButtonWidget.builder(Text.literal("Fetch & connect"), b -> connect())
                .dimensions(cx - w / 2, y, w, 20).build();
        connectButton.active = EtmcManager.get().isReady();
        addDrawableChild(connectButton);
        y += 24;

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), b -> this.close())
                .dimensions(cx - w / 2, y, w, 20).build());
    }

    private void connect() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            setMessage("Enter a config URL.", 0xFFFF5555);
            return;
        }
        String override = serverField.getText().trim();
        connectButton.active = false;
        setMessage("Fetching config…", 0xFFFFFF55);
        EtmcManager.get().connectUrlAsync(url, override.isEmpty() ? null : override)
                .whenComplete((port, err) -> this.client.execute(() -> {
                    if (err != null) {
                        setMessage("Failed: " + rootMessage(err), 0xFFFF5555);
                        connectButton.active = true;
                    }
                    // on success McNet has switched to the connect screen
                }));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 22, 0xFFFFFF);
        int cx = this.width / 2;
        int w = 280;
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Config URL (http/https to an EasyTier config)"),
                cx - w / 2, this.height / 4, 0xFFAAAAAA);
        ctx.drawTextWithShadow(this.textRenderer, Text.literal("Server ip:port (optional if config has [etmc] server)"),
                cx - w / 2, this.height / 4 + 44, 0xFFAAAAAA);
        if (!message.isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(message), cx, this.height - 38, messageColor);
        }
    }

    private void setMessage(String msg, int color) {
        this.message = msg;
        this.messageColor = color;
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}

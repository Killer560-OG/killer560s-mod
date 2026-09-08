package com.killer560.hub.proxy.gui;

import com.killer560.hub.proxy.config.ProxyConfig;
import com.killer560.hub.proxy.config.ProxyType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Configuration screen opened from the multiplayer server list. Lets the user
 * pick the SOCKS version, enter the proxy address, optionally supply
 * credentials, and Apply / Go Back / Reset.
 */
public class ProxyConfigScreen extends Screen {

    private final Screen parent;

    private EditBox addressField;
    private EditBox usernameField;
    private EditBox passwordField;
    private Button socks4Button;
    private Button socks5Button;

    private ProxyType selectedType;
    private int warningY;

    // Layout constants
    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;

    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_WARNING = 0xFFFF5555;
    private static final int COLOR_LABEL = 0xFFA0A0A0;

    public ProxyConfigScreen(Screen parent) {
        super(Component.literal("Proxy Configuration"));
        this.parent = parent;
        this.selectedType = ProxyConfig.getInstance().getType();
    }

    @Override
    protected void init() {
        ProxyConfig config = ProxyConfig.getInstance();
        this.selectedType = config.getType();

        int centerX = this.width / 2;
        int fieldX = centerX - FIELD_WIDTH / 2;

        int typeButtonsTop = this.height / 4;
        int addressFieldTop = typeButtonsTop + 34;
        int usernameFieldTop = addressFieldTop + 44;
        int passwordFieldTop = usernameFieldTop + 34;
        int actionButtonsTop = passwordFieldTop + 40;
        this.warningY = actionButtonsTop + 28;

        // --- Type toggle row (two half-width radio-style buttons) ---
        int halfWidth = (FIELD_WIDTH - 4) / 2;
        this.socks4Button = Button.builder(typeLabel(ProxyType.SOCKS4), b -> selectType(ProxyType.SOCKS4))
                .bounds(fieldX, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.socks5Button = Button.builder(typeLabel(ProxyType.SOCKS5), b -> selectType(ProxyType.SOCKS5))
                .bounds(fieldX + halfWidth + 4, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.addRenderableWidget(this.socks4Button);
        this.addRenderableWidget(this.socks5Button);

        // --- Address field ---
        this.addressField = new EditBox(this.font, fieldX, addressFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Address"));
        this.addressField.setMaxLength(256);
        this.addressField.setHint(Component.literal("127.0.0.1:1080"));
        this.addressField.setValue(config.getAddressString());
        this.addRenderableWidget(this.addressField);

        // --- Login (optional) section ---
        this.usernameField = new EditBox(this.font, fieldX, usernameFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Username"));
        this.usernameField.setMaxLength(256);
        this.usernameField.setHint(Component.literal("Username"));
        this.usernameField.setValue(config.getUsername());
        this.addRenderableWidget(this.usernameField);

        this.passwordField = new EditBox(this.font, fieldX, passwordFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Password"));
        this.passwordField.setMaxLength(256);
        this.passwordField.setHint(Component.literal("Password"));
        this.passwordField.setValue(config.getPassword());
        this.addRenderableWidget(this.passwordField);

        // --- Action buttons (Apply / Go Back / Reset) ---
        int btnWidth = (FIELD_WIDTH - 8) / 3;
        this.addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
                .bounds(fieldX, actionButtonsTop, btnWidth, FIELD_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Go Back"), b -> onClose())
                .bounds(fieldX + btnWidth + 4, actionButtonsTop, btnWidth, FIELD_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("Reset"), b -> reset())
                .bounds(fieldX + (btnWidth + 4) * 2, actionButtonsTop, btnWidth, FIELD_HEIGHT)
                .build());
    }

    private Component typeLabel(ProxyType type) {
        String marker = (this.selectedType == type) ? "[x] " : "[ ] ";
        String name = (type == ProxyType.SOCKS4) ? "Socks4" : "Socks5";
        return Component.literal(marker + name);
    }

    private void selectType(ProxyType type) {
        this.selectedType = type;
        this.socks4Button.setMessage(typeLabel(ProxyType.SOCKS4));
        this.socks5Button.setMessage(typeLabel(ProxyType.SOCKS5));
    }

    private void apply() {
        ProxyConfig config = ProxyConfig.getInstance();
        config.setType(this.selectedType);
        config.parseAndSetAddress(this.addressField.getValue());
        config.setUsername(this.usernameField.getValue());
        config.setPassword(this.passwordField.getValue());
        // Enable only when there is actually somewhere to connect.
        config.setEnabled(config.hasValidAddress());
        config.save();
        onClose();
    }

    private void reset() {
        ProxyConfig config = ProxyConfig.getInstance();
        this.addressField.setValue("");
        this.usernameField.setValue("");
        this.passwordField.setValue("");
        config.parseAndSetAddress("");
        config.setUsername("");
        config.setPassword("");
        config.setEnabled(false);
        config.save();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // Title
        guiGraphics.centeredText(this.font, this.title, centerX, this.height / 4 - 34, COLOR_WHITE);

        // "Type:" label above the toggle row
        guiGraphics.text(this.font, "Type:",
                this.socks4Button.getX(), this.socks4Button.getY() - 11, COLOR_LABEL);

        // "IPAddress:Port" label above the address field
        guiGraphics.text(this.font, "IPAddress:Port",
                this.addressField.getX(), this.addressField.getY() - 11, COLOR_LABEL);

        // "Login (optional)" heading centered above the username field
        guiGraphics.centeredText(this.font, Component.literal("Login (optional)"),
                centerX, this.usernameField.getY() - 14, COLOR_WHITE);

        // Warning footer
        guiGraphics.centeredText(this.font, Component.literal("Do NOT use free proxies."),
                centerX, this.warningY, COLOR_WARNING);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

package com.killer560.hub.proxy.gui;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.proxy.config.ProxyConfig;
import com.killer560.hub.proxy.config.ProxyType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private SettingsButtonWidget socks4Button;
    private SettingsButtonWidget socks5Button;

    private ProxyType selectedType;
    private int warningY;

    // Layout constants
    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;

    private static final int COLOR_TITLE = 0xFFCC6600;
    private static final int COLOR_WARNING = 0xFFFF5555;
    private static final int COLOR_LABEL = 0xFFA0A0A0;
    // Per killer560's "make this look more like the mod. The typing areas do not really look like it"
    // (2026-09-09) - real bug found and fixed in the first attempt at this: calling setBordered(false)
    // to draw a custom themed box DISABLED vanilla EditBox's own text-centering math too (confirmed by
    // decompiling EditBox - both textX/textY are computed only in the `bordered` branch of
    // updateTextPosition(), so text/hints snapped to the raw top-left corner instead of staying
    // vertically centered once bordered was false). Fields now stay bordered=true (vanilla keeps
    // handling centering correctly) and this outline is drawn OVER vanilla's own border sprite
    // afterward instead, purely recoloring it to match the theme without touching text layout at all.
    private static final int FIELD_BORDER = 0xFF663D1A;
    private static final int FIELD_BORDER_FOCUSED = 0xFFCC6600;

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
        this.socks4Button = SettingsButtonWidget.builder(typeLabel(ProxyType.SOCKS4), b -> selectType(ProxyType.SOCKS4))
                .bounds(fieldX, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.socks5Button = SettingsButtonWidget.builder(typeLabel(ProxyType.SOCKS5), b -> selectType(ProxyType.SOCKS5))
                .bounds(fieldX + halfWidth + 4, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.addRenderableWidget(this.socks4Button);
        this.addRenderableWidget(this.socks5Button);

        // --- Address field ---
        this.addressField = new EditBox(this.font, fieldX, addressFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Address"));
        this.addressField.setMaxLength(256);
        // Per killer560's "make it all white" - an unstyled hint Component gets EditBox's own default
        // dim-grey hint style; giving it an explicit style here (any style, not specifically white)
        // makes EditBox use exactly this one instead - see EditBox#setHint's hasNoStyle check.
        this.addressField.setHint(Component.literal("127.0.0.1:1080").withStyle(ChatFormatting.WHITE));
        this.addressField.setValue(config.getAddressString());
        this.addRenderableWidget(this.addressField);

        // --- Login (optional) section ---
        this.usernameField = new EditBox(this.font, fieldX, usernameFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Username"));
        this.usernameField.setMaxLength(256);
        this.usernameField.setHint(Component.literal("Username").withStyle(ChatFormatting.WHITE));
        this.usernameField.setValue(config.getUsername());
        this.addRenderableWidget(this.usernameField);

        this.passwordField = new EditBox(this.font, fieldX, passwordFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Password"));
        this.passwordField.setMaxLength(256);
        this.passwordField.setHint(Component.literal("Password").withStyle(ChatFormatting.WHITE));
        this.passwordField.setValue(config.getPassword());
        this.addRenderableWidget(this.passwordField);

        // --- Action buttons (Apply / Go Back / Reset) ---
        int btnWidth = (FIELD_WIDTH - 8) / 3;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Apply"), b -> apply())
                .bounds(fieldX, actionButtonsTop, btnWidth, FIELD_HEIGHT)
                .build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Go Back"), b -> onClose())
                .bounds(fieldX + btnWidth + 4, actionButtonsTop, btnWidth, FIELD_HEIGHT)
                .build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Reset"), b -> reset())
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
        // Black + amber theme (2026-09-09) - see AccountSwitcherScreen#extractRenderState.
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        // Recolor each field's border AFTER vanilla draws its own (see the doc comment on
        // FIELD_BORDER) - drawn on top rather than replacing vanilla's rendering entirely, so
        // text/hint positioning is untouched.
        outlineField(guiGraphics, this.addressField);
        outlineField(guiGraphics, this.usernameField);
        outlineField(guiGraphics, this.passwordField);

        int centerX = this.width / 2;

        // Title
        guiGraphics.centeredText(this.font, this.title, centerX, this.height / 4 - 34, COLOR_TITLE);

        // "Type:" label above the toggle row
        guiGraphics.text(this.font, "Type:",
                this.socks4Button.getX(), this.socks4Button.getY() - 11, COLOR_LABEL);

        // "IPAddress:Port" label above the address field
        guiGraphics.text(this.font, "IPAddress:Port",
                this.addressField.getX(), this.addressField.getY() - 11, COLOR_LABEL);

        // "Login (optional)" heading centered above the username field
        guiGraphics.centeredText(this.font, Component.literal("Login (optional)"),
                centerX, this.usernameField.getY() - 14, COLOR_TITLE);

        // Warning footer
        guiGraphics.centeredText(this.font, Component.literal("Do NOT use free proxies."),
                centerX, this.warningY, COLOR_WARNING);
    }

    /** Recolors one field's border to match the theme - see the doc comment on {@link #FIELD_BORDER}. */
    private static void outlineField(GuiGraphicsExtractor graphics, EditBox field) {
        graphics.outline(field.getX(), field.getY(), field.getWidth(), field.getHeight(),
                field.isFocused() ? FIELD_BORDER_FOCUSED : FIELD_BORDER);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

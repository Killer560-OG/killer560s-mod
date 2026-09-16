package com.killer560.hub.proxy.gui;

import com.killer560.hub.accounts.gui.AccountScreenBackground;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.proxy.config.ProxyConfig;
import com.killer560.hub.proxy.config.ProxyType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Instance proxy editor, opened from the Swap Accounts screen's "Set Instance Proxy" button. Lets the user
 * pick the SOCKS version, enter the proxy address, optionally supply
 * credentials, and Apply / Go Back / Reset. While the universal proxy is ON, saving here also republishes
 * the settings to the universal file (see {@link ProxyConfig#syncUniversalFromInstance()}).
 */
public class ProxyConfigScreen extends Screen {

    private final Screen parent;
    private final ProxyConfig target;

    private EditBox addressField;
    private EditBox usernameField;
    private EditBox passwordField;
    private SettingsButtonWidget socks4Button;
    private SettingsButtonWidget socks5Button;

    private ProxyType selectedType;
    private int warningY;
    /** Editing the instance proxy while the universal proxy is ON (Apply will republish it) - read in init(). */
    private boolean universalOn;

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
        this(parent, ProxyConfig.getInstance());
    }

    /** @param target the per-instance proxy ({@link ProxyConfig#getInstance()}) or the shared universal one. */
    public ProxyConfigScreen(Screen parent, ProxyConfig target) {
        super(Component.literal(target.isUniversal() ? "Universal Proxy" : "Instance Proxy"));
        this.parent = parent;
        this.target = target;
        this.selectedType = target.getType();
    }

    @Override
    protected void init() {
        ProxyConfig config = this.target;
        this.selectedType = config.getType();
        this.universalOn = !config.isUniversal() && ProxyConfig.isUniversalActive();

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
        // Masked: the saved SOCKS password must not be re-rendered in cleartext every time the screen opens.
        this.passwordField.addFormatter((text, offset) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
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
        ProxyConfig config = this.target;
        config.setType(this.selectedType);
        config.parseAndSetAddress(this.addressField.getValue());
        config.setUsername(this.usernameField.getValue());
        config.setPassword(this.passwordField.getValue());
        // Instance proxy: enable only when there is actually somewhere to connect. The universal proxy has its own
        // on/off toggle in the account switcher, so Apply only turns it off if the address was cleared.
        if (config.isUniversal()) {
            if (!config.hasValidAddress()) {
                config.setEnabled(false);
            }
        } else {
            config.setEnabled(config.hasValidAddress());
        }
        config.save();
        syncUniversal();
        onClose();
    }

    private void reset() {
        ProxyConfig config = this.target;
        this.addressField.setValue("");
        this.usernameField.setValue("");
        this.passwordField.setValue("");
        config.parseAndSetAddress("");
        config.setUsername("");
        config.setPassword("");
        config.setEnabled(false);
        config.save();
        syncUniversal();
        this.universalOn = !config.isUniversal() && ProxyConfig.isUniversalActive();
    }

    /** 2026-09-15: the separate universal editor is gone ("Remove the edit universal button"), so while Universal is
     *  ON, saving the instance proxy here also republishes it to the shared universal file (clearing it turns
     *  universal off). No-op while Universal is OFF or when editing the universal config directly. */
    private void syncUniversal() {
        if (!this.target.isUniversal()) {
            ProxyConfig.syncUniversalFromInstance();
        }
    }

    /** Same animated title-screen background as the account switcher it's opened from - see AccountScreenBackground. */
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (!AccountScreenBackground.draw(graphics, this)) {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Black + amber theme (2026-09-09) - see AccountSwitcherScreen#extractRenderState. The dim overlay is only
        // needed when the themed title background isn't drawn.
        if (!AccountScreenBackground.themed()) {
            guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);
        }
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

        if (this.universalOn) {
            guiGraphics.centeredText(this.font, Component.literal("Universal is ON - Apply also updates every instance."),
                    centerX, this.warningY + 12, COLOR_TITLE);
        }
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

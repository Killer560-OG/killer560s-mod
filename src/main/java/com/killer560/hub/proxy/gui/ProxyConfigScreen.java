package com.killer560.hub.proxy.gui;

import com.killer560.hub.accounts.gui.AccountScreenBackground;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.proxy.config.ProxyConfig;
import com.killer560.hub.proxy.config.ProxyType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.killer560.hub.compat.McCompat;

/**
 * Universal proxy editor, opened from the Swap Accounts screen's "Set Universal Proxy" button (called Set Instance Proxy
 * until 2026-10-15, killer560: "change set instance proxy to be called set universal proxy"). It edits this instance's
 * proxy, which the Universal switch publishes to every instance (the saved file and its keys are unchanged). Lets the user
 * pick the SOCKS version, enter the proxy address, optionally supply
 * credentials, and Apply / Go Back / Reset. While the universal proxy is ON, saving here also republishes
 * the settings to the universal file (see {@link ProxyConfig#syncUniversalFromInstance()}).
 */
public class ProxyConfigScreen extends Screen implements MenuThemedScreen {

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

    /** The framed panel around the form, laid out in init(). */
    private int panelX, panelY, panelW, panelH;

    public ProxyConfigScreen(Screen parent) {
        this(parent, ProxyConfig.getInstance());
    }

    /** @param target the per-instance proxy ({@link ProxyConfig#getInstance()}) or the shared universal one. */
    public ProxyConfigScreen(Screen parent, ProxyConfig target) {
        super(Component.literal("Universal Proxy"));
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

        // Never so high that the panel's header (44 above the type row) leaves the screen.
        int typeButtonsTop = Math.max(this.height / 4, 48);
        int addressFieldTop = typeButtonsTop + 34;
        int usernameFieldTop = addressFieldTop + 44;
        int passwordFieldTop = usernameFieldTop + 34;
        int actionButtonsTop = passwordFieldTop + 40;
        this.warningY = actionButtonsTop + 28;
        this.panelW = Math.max(FIELD_WIDTH + 40, Math.max(this.font.width(footerLine()),
                this.font.width(universalLine())) + 24);
        this.panelX = centerX - this.panelW / 2;
        this.panelY = typeButtonsTop - 44;
        this.panelH = this.warningY + 12 * footerLines() + 6 - this.panelY;

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
        this.addressField = new ThemedEditBox(this.font, fieldX, addressFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Address")).hint("127.0.0.1:1080").brightHint();
        this.addressField.setMaxLength(256);
        this.addressField.setValue(config.getAddressString());
        this.addRenderableWidget(this.addressField);

        // --- Login (optional) section ---
        this.usernameField = new ThemedEditBox(this.font, fieldX, usernameFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Username")).hint("Username").brightHint();
        this.usernameField.setMaxLength(256);
        this.usernameField.setValue(config.getUsername());
        this.addRenderableWidget(this.usernameField);

        this.passwordField = new ThemedEditBox(this.font, fieldX, passwordFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Password")).hint("Password").brightHint();
        this.passwordField.setMaxLength(256);
        // Masked: the saved SOCKS password must not be re-rendered in cleartext every time the screen opens.
        this.passwordField.addFormatter((text, offset) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
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

    /** This screen's own Theme setting (Theme Default follows the menu). */
    public static final String THEME_ID = "accounts";

    @Override
    public String themeId() {
        return THEME_ID;
    }

    @Override
    public int[] themedPanelBounds() {
        return new int[]{panelX, panelY, panelW, panelH};
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        // The menu theme's panel over the animated title background (or the dim, in a world).
        if (AccountScreenBackground.themed()) {
            MenuStyle.use();
        } else {
            ScreenChrome.backdrop(guiGraphics, this.width, this.height);
        }
        ScreenChrome.frame(guiGraphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(guiGraphics, panelX, panelY, panelW, this.title.getString());
        int centerX = this.width / 2;
        int[] address = ((ThemedEditBox) this.addressField).outer();
        int[] user = ((ThemedEditBox) this.usernameField).outer();
        ScreenChrome.text(guiGraphics, "Type:", this.socks4Button.getX(), this.socks4Button.getY() - 11, MenuStyle.DIM);
        ScreenChrome.text(guiGraphics, "IPAddress:Port", address[0], address[1] - 11, MenuStyle.DIM);
        ScreenChrome.centered(guiGraphics, "Login (optional)", centerX, user[1] - 14, MenuStyle.OR);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        ScreenChrome.centered(guiGraphics, footerLine(), centerX, this.warningY, MenuStyle.RED);
        if (!this.target.isUniversal()) {
            ScreenChrome.centered(guiGraphics, universalLine(), centerX, this.warningY + 12,
                    this.universalOn ? MenuStyle.OR : MenuStyle.DIM);
        }
    }

    private static String footerLine() {
        return "Do NOT use free proxies.";
    }

    /** What Apply reaches: every instance while the Universal switch is on, this one while it is off. */
    private String universalLine() {
        return this.universalOn ? "Universal is ON - Apply also updates every instance."
                : "Universal is OFF - only this instance uses this proxy.";
    }

    /** Lines under the buttons: the warning and the Universal note (the note is left out only when editing the shared
     *  file directly). */
    private int footerLines() {
        return this.target.isUniversal() ? 1 : 2;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, this.parent);
    }
}

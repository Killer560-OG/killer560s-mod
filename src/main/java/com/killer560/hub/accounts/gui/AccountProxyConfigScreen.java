package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.core.PrismAccount;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.proxy.config.AccountProxyProfile;
import com.killer560.hub.proxy.config.AccountProxyStore;
import com.killer560.hub.proxy.config.ProxyType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Per-account proxy assignment, opened from a "Proxy" button on {@link AccountSwitcherScreen}'s own
 * account rows. Visually mirrors {@code ProxyConfigScreen} (same theme/layout), but saves into
 * {@link AccountProxyStore} keyed by this one account's uuid instead of the single global
 * {@code ProxyConfig} - per killer560's "add a button to set a proxy by account... whenever i swap to
 * that account it should auto swap to that proxy" request (2026-09-10). The actual auto-swap itself
 * happens in {@link AccountSwitcherScreen#onAccountSelected}, which reads whatever this screen saves
 * here right after every account swap.
 */
public class AccountProxyConfigScreen extends Screen {

    private final Screen parent;
    private final PrismAccount account;

    private EditBox addressField;
    private EditBox usernameField;
    private EditBox passwordField;
    private SettingsButtonWidget socks4Button;
    private SettingsButtonWidget socks5Button;

    private ProxyType selectedType;
    private int warningY;

    private static final int FIELD_WIDTH = 200;
    private static final int FIELD_HEIGHT = 20;

    private static final int COLOR_TITLE = 0xFFCC6600;
    private static final int COLOR_WARNING = 0xFFFF5555;
    private static final int COLOR_LABEL = 0xFFA0A0A0;
    private static final int FIELD_BORDER = 0xFF663D1A;
    private static final int FIELD_BORDER_FOCUSED = 0xFFCC6600;

    public AccountProxyConfigScreen(Screen parent, PrismAccount account) {
        super(Component.literal("Proxy for " + account.displayName()));
        this.parent = parent;
        this.account = account;
        AccountProxyProfile existing = AccountProxyStore.get(account.uuid());
        this.selectedType = existing != null ? existing.getType() : ProxyType.SOCKS5;
    }

    @Override
    protected void init() {
        AccountProxyProfile existing = AccountProxyStore.get(this.account.uuid());

        int centerX = this.width / 2;
        int fieldX = centerX - FIELD_WIDTH / 2;

        int typeButtonsTop = this.height / 4;
        int addressFieldTop = typeButtonsTop + 34;
        int usernameFieldTop = addressFieldTop + 44;
        int passwordFieldTop = usernameFieldTop + 34;
        int actionButtonsTop = passwordFieldTop + 40;
        this.warningY = actionButtonsTop + 28;

        int halfWidth = (FIELD_WIDTH - 4) / 2;
        this.socks4Button = SettingsButtonWidget.builder(typeLabel(ProxyType.SOCKS4), b -> selectType(ProxyType.SOCKS4))
                .bounds(fieldX, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.socks5Button = SettingsButtonWidget.builder(typeLabel(ProxyType.SOCKS5), b -> selectType(ProxyType.SOCKS5))
                .bounds(fieldX + halfWidth + 4, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.addRenderableWidget(this.socks4Button);
        this.addRenderableWidget(this.socks5Button);

        this.addressField = new EditBox(this.font, fieldX, addressFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Address"));
        this.addressField.setMaxLength(256);
        this.addressField.setHint(Component.literal("127.0.0.1:1080").withStyle(ChatFormatting.WHITE));
        this.addressField.setValue(existing != null ? existing.getAddressString() : "");
        this.addRenderableWidget(this.addressField);

        this.usernameField = new EditBox(this.font, fieldX, usernameFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Username"));
        this.usernameField.setMaxLength(256);
        this.usernameField.setHint(Component.literal("Username").withStyle(ChatFormatting.WHITE));
        this.usernameField.setValue(existing != null ? existing.getUsername() : "");
        this.addRenderableWidget(this.usernameField);

        this.passwordField = new EditBox(this.font, fieldX, passwordFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Password"));
        this.passwordField.setMaxLength(256);
        this.passwordField.setHint(Component.literal("Password").withStyle(ChatFormatting.WHITE));
        this.passwordField.setValue(existing != null ? existing.getPassword() : "");
        this.addRenderableWidget(this.passwordField);

        int btnWidth = (FIELD_WIDTH - 8) / 3;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Apply"), b -> apply())
                .bounds(fieldX, actionButtonsTop, btnWidth, FIELD_HEIGHT)
                .build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Go Back"), b -> onClose())
                .bounds(fieldX + btnWidth + 4, actionButtonsTop, btnWidth, FIELD_HEIGHT)
                .build());
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Remove"), b -> remove())
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
        AccountProxyProfile profile = new AccountProxyProfile();
        profile.setType(this.selectedType);
        profile.parseAndSetAddress(this.addressField.getValue());
        profile.setUsername(this.usernameField.getValue());
        profile.setPassword(this.passwordField.getValue());
        if (!profile.hasValidAddress()) {
            // Nothing meaningful to save - same as clicking Remove, so this account just goes back to
            // "no proxy assigned" (turns the active proxy off on its next swap) rather than saving a
            // profile with a blank address that could never actually connect.
            AccountProxyStore.remove(this.account.uuid());
            onClose();
            return;
        }
        AccountProxyStore.set(this.account.uuid(), profile);
        onClose();
    }

    private void remove() {
        AccountProxyStore.remove(this.account.uuid());
        this.addressField.setValue("");
        this.usernameField.setValue("");
        this.passwordField.setValue("");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        guiGraphics.fill(0, 0, this.width, this.height, 0xCC000000);
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        outlineField(guiGraphics, this.addressField);
        outlineField(guiGraphics, this.usernameField);
        outlineField(guiGraphics, this.passwordField);

        int centerX = this.width / 2;

        guiGraphics.centeredText(this.font, this.title, centerX, this.height / 4 - 34, COLOR_TITLE);

        guiGraphics.text(this.font, "Type:",
                this.socks4Button.getX(), this.socks4Button.getY() - 11, COLOR_LABEL);

        guiGraphics.text(this.font, "IPAddress:Port",
                this.addressField.getX(), this.addressField.getY() - 11, COLOR_LABEL);

        guiGraphics.centeredText(this.font, Component.literal("Login (optional)"),
                centerX, this.usernameField.getY() - 14, COLOR_TITLE);

        guiGraphics.centeredText(this.font, Component.literal("Applied automatically every time you swap to this account."),
                centerX, this.warningY, COLOR_WARNING);
    }

    private static void outlineField(GuiGraphicsExtractor graphics, EditBox field) {
        graphics.outline(field.getX(), field.getY(), field.getWidth(), field.getHeight(),
                field.isFocused() ? FIELD_BORDER_FOCUSED : FIELD_BORDER);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.parent);
    }
}

package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.core.PrismAccount;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.proxy.config.AccountProxyProfile;
import com.killer560.hub.proxy.config.AccountProxyStore;
import com.killer560.hub.proxy.config.ProxyType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.killer560.hub.compat.McCompat;

/**
 * Per-account proxy assignment, opened from a "Proxy" button on {@link AccountSwitcherScreen}'s own
 * account rows. Visually mirrors {@code ProxyConfigScreen} (same theme/layout), but saves into
 * {@link AccountProxyStore} keyed by this one account's uuid instead of the single global
 * {@code ProxyConfig} - per killer560's "add a button to set a proxy by account... whenever i swap to
 * that account it should auto swap to that proxy" request (2026-09-10). The actual auto-swap itself
 * happens in {@link AccountSwitcherScreen#onAccountSelected}, which reads whatever this screen saves
 * here right after every account swap.
 */
public class AccountProxyConfigScreen extends Screen implements MenuThemedScreen {

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

    /** The framed panel around the form, laid out in init(). */
    private int panelX, panelY, panelW, panelH;

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

        // Never so high that the panel's header (44 above the type row) leaves the screen.
        int typeButtonsTop = Math.max(this.height / 4, 48);
        int addressFieldTop = typeButtonsTop + 34;
        int usernameFieldTop = addressFieldTop + 44;
        int passwordFieldTop = usernameFieldTop + 34;
        int actionButtonsTop = passwordFieldTop + 40;
        this.warningY = actionButtonsTop + 28;
        this.panelW = Math.max(FIELD_WIDTH + 40, this.font.width(footerLine()) + 24);
        this.panelX = centerX - this.panelW / 2;
        this.panelY = typeButtonsTop - 44;
        this.panelH = this.warningY + 12 * footerLines() + 6 - this.panelY;

        int halfWidth = (FIELD_WIDTH - 4) / 2;
        this.socks4Button = SettingsButtonWidget.builder(typeLabel(ProxyType.SOCKS4), b -> selectType(ProxyType.SOCKS4))
                .bounds(fieldX, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.socks5Button = SettingsButtonWidget.builder(typeLabel(ProxyType.SOCKS5), b -> selectType(ProxyType.SOCKS5))
                .bounds(fieldX + halfWidth + 4, typeButtonsTop, halfWidth, FIELD_HEIGHT)
                .build();
        this.addRenderableWidget(this.socks4Button);
        this.addRenderableWidget(this.socks5Button);

        this.addressField = new ThemedEditBox(this.font, fieldX, addressFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Address")).hint("127.0.0.1:1080").brightHint();
        this.addressField.setMaxLength(256);
        this.addressField.setValue(existing != null ? existing.getAddressString() : "");
        this.addRenderableWidget(this.addressField);

        this.usernameField = new ThemedEditBox(this.font, fieldX, usernameFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Username")).hint("Username").brightHint();
        this.usernameField.setMaxLength(256);
        this.usernameField.setValue(existing != null ? existing.getUsername() : "");
        this.addRenderableWidget(this.usernameField);

        this.passwordField = new ThemedEditBox(this.font, fieldX, passwordFieldTop, FIELD_WIDTH, FIELD_HEIGHT,
                Component.literal("Password")).hint("Password").brightHint();
        this.passwordField.setMaxLength(256);
        // Masked: the saved SOCKS password must not be re-rendered in cleartext every time the screen opens.
        this.passwordField.addFormatter((text, offset) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
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

    /** Same animated title-screen background as the account switcher - see {@link AccountScreenBackground}. */
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
    }

    private static String footerLine() {
        return "Applied automatically every time you swap to this account.";
    }

    private int footerLines() {
        return 1;
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, this.parent);
    }
}

package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.AccountApplier;
import com.killer560.hub.accounts.core.AuthException;
import com.killer560.hub.accounts.core.MicrosoftAuthFlow;
import com.killer560.hub.accounts.core.PrismAccount;
import com.killer560.hub.accounts.core.PrismAccountStore;
import com.killer560.hub.accounts.core.SharedBanStatusStore;
import com.killer560.hub.accounts.core.StoredBanStatus;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.proxy.config.AccountProxyProfile;
import com.killer560.hub.proxy.config.AccountProxyStore;
import com.killer560.hub.proxy.config.ProxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.killer560.hub.compat.McCompat;

public class AccountSwitcherScreen extends Screen implements MenuThemedScreen {

    private static final MicrosoftAuthFlow AUTH_FLOW = new MicrosoftAuthFlow();
    private static final ZoneId CENTRAL = ZoneId.of("America/Chicago");
    private static final DateTimeFormatter BAN_TIME_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.US);

    /** Only used for the swap action itself now - ban status is read from disk, not fetched here. */
    private static final ExecutorService AUTH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "killer560smod-account-switcher-auth");
        thread.setDaemon(true);
        return thread;
    });

    private final Screen parent;
    private List<PrismAccount> accounts = List.of();
    private Map<String, StoredBanStatus> banStatuses = Map.of();
    private String statusMessage;
    private boolean busy;

    private static final long UNIVERSAL_HINT_MS = 2500L;
    private SettingsButtonWidget universalButton;
    /** Util.getMillis() until which the Universal button shows "Set Instance Proxy first"; 0 = no hint. */
    private long universalHintUntil;
    /** Pixels the account list is scrolled down (killer560, 2026-09-21: "make the swap accounts page scrollable"). */
    private int scroll;
    private static final int ROW_H = 22;
    private int maxScroll;

    /** The framed panel, the account list's well and the rows shown in it, laid out in init (2026-10-13 redo in the
     *  menu theme: one panel with a header, the accounts as rows in a recessed list, the four actions in a footer). */
    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int statusY;
    /** One shown row: its top, and the ban-status lines drawn beside its buttons (null line = none). */
    private record Row(int y, int textX, int textW, String line1, String line2, boolean banned) {
    }
    private final List<Row> rows = new ArrayList<>();

    public AccountSwitcherScreen(Screen parent) {
        super(Component.literal("Swap Accounts"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (this.accounts.isEmpty() && this.statusMessage == null) {
            try {
                this.accounts = PrismAccountStore.load();
                if (this.accounts.isEmpty()) {
                    this.statusMessage = "No Microsoft accounts found in Prism Launcher.";
                }
            } catch (IOException e) {
                this.statusMessage = "Couldn't read Prism's accounts.json: " + e.getMessage();
            }
        }
        this.banStatuses = SharedBanStatusStore.load();

        int rowHeight = ROW_H;
        int copyWidth = 60;
        int proxyWidth = 70;
        int gap = 6;
        int footerButtonW = 200;

        this.panelW = Math.min(this.width - 12, 620);
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = 6;
        this.panelH = this.height - 12;
        // Status message (sign-in progress, errors) on its own line under the header.
        this.statusY = this.panelY + 32;
        this.listX = this.panelX + 6;
        this.listY = this.panelY + 30 + (this.statusMessage != null ? 14 : 0);
        this.listW = this.panelW - 12;
        // Footer: Back / Direct Session Login over Set Instance Proxy / Universal, two rows of two.
        int footerTop = this.panelY + this.panelH - 6 - 20 - 6 - 20;
        this.listH = Math.max(rowHeight + 6, footerTop - 6 - this.listY);

        // Each row: account button, Copy ID, Proxy, then the ban status (only for an account that was checked -
        // killer560 2026-09-09: "remove the not checked yet portion") in the room left on the right.
        int avail = this.listW - 12;
        int fixed = copyWidth + proxyWidth + 2 * gap + 8;
        int buttonWidth = Math.max(100, Math.min(200, avail - fixed - 200));
        int startX = this.listX + 6;
        int startY = this.listY + 3;

        rows.clear();
        int listBottom = this.listY + this.listH - 3;
        int visibleRows = Math.max(1, (listBottom - startY) / rowHeight);
        this.maxScroll = Math.max(0, (this.accounts.size() - visibleRows) * rowHeight);
        this.scroll = Math.max(0, Math.min(this.maxScroll, this.scroll));
        int i = 0;
        for (PrismAccount account : this.accounts) {
            int y = startY + i * rowHeight - this.scroll;
            if (y < startY || y + rowHeight > listBottom + 2) {
                i++;
                continue;
            }

            SettingsButtonWidget button = SettingsButtonWidget.builder(Component.literal(account.displayName()), btn -> onAccountSelected(account))
                    .bounds(startX, y, buttonWidth, 20)
                    .build();
            button.active = !this.busy;
            this.addRenderableWidget(button);

            SettingsButtonWidget copyButton = SettingsButtonWidget.builder(Component.literal("Copy ID"), btn -> onCopySessionId(account))
                    .bounds(startX + buttonWidth + gap, y, copyWidth, 20)
                    .build();
            copyButton.active = !this.busy;
            this.addRenderableWidget(copyButton);

            SettingsButtonWidget proxyButton = SettingsButtonWidget.builder(proxyButtonLabel(account),
                            btn -> McCompat.setScreen(Minecraft.getInstance(), new AccountProxyConfigScreen(this, account)))
                    .bounds(startX + buttonWidth + gap + copyWidth + gap, y, proxyWidth, 20)
                    .build();
            proxyButton.active = !this.busy;
            this.addRenderableWidget(proxyButton);

            int textX = startX + buttonWidth + gap + copyWidth + gap + proxyWidth + 8;
            addBanStatusRow(account, textX, y, this.listX + this.listW - 6 - textX);

            i++;
        }

        int fx = this.width / 2 - footerButtonW - 3;
        int fx2 = this.width / 2 + 3;
        if (this.panelW < 2 * footerButtonW + 18) {
            footerButtonW = (this.panelW - 18) / 2;
            fx = this.panelX + 6;
            fx2 = fx + footerButtonW + 6;
        }
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), btn -> onBack())
                .bounds(fx, footerTop, footerButtonW, 20)
                .build());

        // "Direct Connect"-style entry point: a temporary login from a raw session token, not one of Prism's saved
        // accounts (see DirectSessionLoginScreen).
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Direct Session Login"), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new DirectSessionLoginScreen(this, this.parent)))
                .bounds(fx2, footerTop, footerButtonW, 20)
                .build());

        // Proxy controls (2026-09-15: moved here from the multiplayer screen). "Set Instance Proxy" opens this
        // instance's own proxy editor (the instance proxy is also set automatically by an account's saved proxy on
        // swap). "Universal" is a plain ON/OFF toggle (killer560: "should just be a toggle on or off not open any
        // menu"): ON publishes this instance's proxy to every instance, OFF disables it.
        int proxyY = footerTop + 26;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Set Instance Proxy"), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new com.killer560.hub.proxy.gui.ProxyConfigScreen(this)))
                .bounds(fx, proxyY, footerButtonW, 20)
                .build());
        this.universalButton = SettingsButtonWidget.builder(universalToggleLabel(), btn -> onUniversalToggled())
                .bounds(fx2, proxyY, footerButtonW, 20)
                .build();
        this.addRenderableWidget(this.universalButton);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScroll > 0 && scrollY != 0) {
            int next = Math.max(0, Math.min(this.maxScroll, this.scroll - (int) Math.signum(scrollY) * ROW_H));
            if (next != this.scroll) {
                this.scroll = next;
                this.rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** ON -> OFF disables the shared universal file; OFF -> ON publishes this instance's proxy into it. With no
     *  instance proxy address there's nothing to publish, so it stays OFF and the button briefly says so (no menu is
     *  opened; ModChat isn't usable on the title screen). */
    private void onUniversalToggled() {
        if (ProxyConfig.isUniversalActive()) {
            ProxyConfig.disableUniversal();
            this.universalHintUntil = 0L;
        } else if (!ProxyConfig.enableUniversalFromInstance()) {
            this.universalHintUntil = Util.getMillis() + UNIVERSAL_HINT_MS;
        } else {
            this.universalHintUntil = 0L;
        }
        refreshUniversalLabel();
    }

    private void refreshUniversalLabel() {
        if (this.universalButton != null) {
            this.universalButton.setMessage(universalToggleLabel());
        }
    }

    /** Keeps the Universal label current: expires the "Set Instance Proxy first" hint and picks up another
     *  instance toggling the shared file (ProxyConfig.universal() re-reads it when its mtime changes). */
    @Override
    public void tick() {
        super.tick();
        refreshUniversalLabel();
    }

    /** Title-screen animated background (2026-09-15, killer560: "make this menu have the same moving background")
     *  when the main-menu theme is on and no world is loaded - see {@link AccountScreenBackground}. Otherwise
     *  vanilla's background path runs as before. */
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

    /** The menu theme's panel over the animated title background (or the dim, in a world): header, status line, the
     *  account rows in a recessed well, their ban status beside the buttons, and the footer buttons. */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (AccountScreenBackground.themed()) {
            MenuStyle.use();
        } else {
            ScreenChrome.backdrop(graphics, this.width, this.height);
        }
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, this.title.getString());
        ScreenChrome.headerRight(graphics, panelX, panelY, panelW, this.accounts.size() + " account(s)", MenuStyle.DIM);
        if (this.statusMessage != null) {
            ScreenChrome.centered(graphics, MenuStyle.ellipsize(this.statusMessage, panelW - 12), this.width / 2, statusY,
                    MenuStyle.TXT);
        }
        ScreenChrome.well(graphics, listX, listY, listW, listH);
        GuiRects r = GuiRects.begin(graphics);
        int k = 0;
        for (Row row : rows) {
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= row.y() - 1 && mouseY < row.y() + ROW_H - 1;
            ScreenChrome.row(r, listX + 2, row.y() - 1, listW - 4, ROW_H, hover, false, k++ % 2 == 0);
        }
        r.submit();
        if (maxScroll > 0) {
            int view = listH - 6;
            int thumbH = Math.max(14, view * view / (view + maxScroll));
            int thumbY = listY + 3 + (view - thumbH) * scroll / maxScroll;
            ScreenChrome.scrollbar(graphics, listX + listW - 6, listY + 3, 4, view, thumbY, thumbH, false);
        }
        for (Row row : rows) {
            if (row.line1() != null && row.textW() > 10) {
                int c = row.banned() ? MenuStyle.RED : ScreenChrome.readable(0xFF55FF55);
                ScreenChrome.text(graphics, MenuStyle.ellipsize(row.line1(), row.textW()), row.textX(), row.y() + 1, c);
                if (row.line2() != null) {
                    ScreenChrome.text(graphics, MenuStyle.ellipsize(row.line2(), row.textW()), row.textX(), row.y() + 11,
                            MenuStyle.DIM);
                }
            }
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void addBanStatusRow(PrismAccount account, int x, int y, int width) {
        StoredBanStatus info = this.banStatuses.get(account.uuid());
        // Per killer560's "remove the not checked yet portion" (2026-09-09): an account with no
        // recorded status at all now shows nothing here, instead of a "Not checked yet"/"Join Hypixel
        // to check" placeholder.
        if (info == null) {
            rows.add(new Row(y, x, width, null, null, false));
            return;
        }
        String line1;
        String line2 = null;

        if (info.status() == com.killer560.hub.accounts.core.HypixelBanStatus.Status.BANNED) {
            if (info.estimatedExpiryEpochMillis() != null) {
                Instant expiry = Instant.ofEpochMilli(info.estimatedExpiryEpochMillis());
                double daysFromNow = Duration.between(Instant.now(), expiry).toMillis() / 86400000.0;
                if (daysFromNow <= 0) {
                    line1 = "Banned (length likely elapsed)";
                    line2 = "Last checked " + relativeTime(info.recordedAtEpochMillis());
                } else {
                    ZonedDateTime central = expiry.atZone(CENTRAL);
                    line1 = "Banned until " + BAN_TIME_FORMAT.format(central) + " CST";
                    line2 = String.format(Locale.US, "%.2f days from now", daysFromNow);
                }
            } else {
                line1 = "Banned";
                line2 = truncate(info.rawMessage(), 60);
            }
        } else {
            line1 = "Unbanned";
            line2 = "Checked " + relativeTime(info.recordedAtEpochMillis());
        }

        rows.add(new Row(y, x, width, line1, line2,
                info.status() == com.killer560.hub.accounts.core.HypixelBanStatus.Status.BANNED));
    }

    private static String relativeTime(long epochMillis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - epochMillis) / 1000);
        if (seconds < 60) {
            return "just now";
        } else if (seconds < 3600) {
            return (seconds / 60) + "m ago";
        } else if (seconds < 86400) {
            return (seconds / 3600) + "h ago";
        } else {
            return (seconds / 86400) + "d ago";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        String cleaned = s.replaceAll("\\s+", " ").trim();
        return cleaned.length() > max ? cleaned.substring(0, max - 1) + "…" : cleaned;
    }

    private void onBack() {
        McCompat.setScreen(Minecraft.getInstance(), this.parent);
    }

    private Component universalToggleLabel() {
        if (this.universalHintUntil != 0L && Util.getMillis() < this.universalHintUntil) {
            return Component.literal("§cSet Instance Proxy first");
        }
        this.universalHintUntil = 0L;
        return Component.literal("Universal: " + (ProxyConfig.isUniversalActive() ? "§aON" : "§cOFF"));
    }

    private static Component proxyButtonLabel(PrismAccount account) {
        AccountProxyProfile profile = AccountProxyStore.get(account.uuid());
        return Component.literal(profile != null ? "Proxy ✓" : "Set Proxy");
    }

    private void onAccountSelected(PrismAccount account) {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.statusMessage = "Signing in as " + account.displayName() + "...";
        rebuild();

        CompletableFuture.supplyAsync(() -> {
            try {
                return AUTH_FLOW.refresh(account);
            } catch (AuthException e) {
                throw new RuntimeException(e);
            }
        }, AUTH_EXECUTOR).whenCompleteAsync((result, throwable) -> {
            this.busy = false;
            if (throwable != null) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                this.statusMessage = "Failed to sign in as " + account.displayName() + ": " + cause.getMessage();
                rebuild();
                return;
            }
            AccountApplier.apply(result);
            // Per killer560's "add a button to set a proxy by account... whenever i swap to that
            // account it should auto swap to that proxy" request (2026-09-10) - applies (or, per his
            // explicit choice, explicitly clears) the active proxy to match this account's own saved
            // assignment every time, right after the swap actually completes.
            ProxyConfig.getInstance().applyAccountProfile(AccountProxyStore.get(account.uuid()));
            McCompat.setScreen(Minecraft.getInstance(), this.parent);
        }, this.screenExecutor);
    }

    /**
     * Fetches a fresh Minecraft access token for {@code account} (the same refresh chain signing
     * in as it uses) and copies it to the clipboard, WITHOUT swapping the running client's own
     * identity - so this account's session can be handed to someone else (or pasted into Direct
     * Session Login later) without actually switching accounts right now.
     */
    private void onCopySessionId(PrismAccount account) {
        if (this.busy) {
            return;
        }
        this.busy = true;
        this.statusMessage = "Copying session ID for " + account.displayName() + "...";
        rebuild();

        CompletableFuture.supplyAsync(() -> {
            try {
                return AUTH_FLOW.refresh(account);
            } catch (AuthException e) {
                throw new RuntimeException(e);
            }
        }, AUTH_EXECUTOR).whenCompleteAsync((result, throwable) -> {
            this.busy = false;
            if (throwable != null) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                this.statusMessage = "Failed to copy session ID for " + account.displayName() + ": " + cause.getMessage();
                rebuild();
                return;
            }
            Minecraft.getInstance().keyboardHandler.setClipboard(result.minecraftAccessToken());
            this.statusMessage = "Copied session ID for " + account.displayName() + " to clipboard!";
            rebuild();
        }, this.screenExecutor);
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }
}

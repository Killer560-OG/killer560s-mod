package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.AccountApplier;
import com.killer560.hub.accounts.core.AuthException;
import com.killer560.hub.accounts.core.MicrosoftAuthFlow;
import com.killer560.hub.accounts.core.PrismAccount;
import com.killer560.hub.accounts.core.PrismAccountStore;
import com.killer560.hub.accounts.core.SharedBanStatusStore;
import com.killer560.hub.accounts.core.StoredBanStatus;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuAnim;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.proxy.config.AccountProxyProfile;
import com.killer560.hub.proxy.config.AccountProxyStore;
import com.killer560.hub.proxy.config.ProxyConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The title screen's Swap Accounts page.
 *
 * <p>Redone 2026-10-15 (killer560: "please redo how this looks. Also change set instance proxy to be called set universal
 * proxy"): one menu-theme panel filling the window, a toolbar (a search box once there are {@value #SEARCH_MIN} or more
 * accounts, and a "Playing as" chip), the accounts as full-width cards ({@link AccountRowWidget}: skin head, name, an
 * Active pill on the signed-in account, its proxy - none, its own, or the Universal one - and its ban status, with Copy ID
 * and the proxy button at the right end) in a smoothly scrolling list, a status line, and a footer with Back, Direct
 * Session Login, Set Universal Proxy and the Universal switch. What each control does is unchanged.
 */
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

    /** The search box shows from this many accounts on. */
    static final int SEARCH_MIN = 6;
    private static final int ROW_H = 28;
    private static final int ROW_GAP = 3;
    private static final int STRIDE = ROW_H + ROW_GAP;
    private static final int SCROLLBAR_W = 6;
    private static final int BTN_H = 16;
    private static final int FOOTER_H = 20;
    private static final long UNIVERSAL_HINT_MS = 2500L;
    /** Each row's entrance starts this much after the one above it. */
    private static final long STAGGER_NANOS = 25_000_000L;

    private final Screen parent;
    private List<PrismAccount> accounts = List.of();
    private Map<String, StoredBanStatus> banStatuses = Map.of();
    private String statusMessage;
    private Kind statusKind = Kind.INFO;
    private boolean busy;

    private enum Kind { INFO, BUSY, OK, ERROR }

    private UniversalSwitch universalButton;
    /** Util.getMillis() until which the Universal switch says "Set Universal Proxy first"; 0 = no hint. */
    private long universalHintUntil;
    /** Read once a tick (it stats the shared file): whether the Universal proxy is in force. */
    private boolean universalActive;

    private ThemedEditBox searchBox;
    private String searchQuery = "";
    private final List<AccountRowWidget> rows = new ArrayList<>();
    private final List<AbstractWidget> rowButtons = new ArrayList<>();
    /** Hover tweens by account, kept across a row rebuild (a search, a status change). */
    private final Map<String, MenuAnim.Tween> hoverState = new HashMap<>();

    /** Pixels the list is scrolled to (killer560, 2026-09-21: "make the swap accounts page scrollable"), and the
     *  shown value easing towards it. */
    private int scroll;
    private final MenuAnim.Tween scrollAnim = new MenuAnim.Tween(MenuAnim.SCROLL_MS, true);
    private boolean draggingScroll;
    private double dragGrabY;
    /** When the list first drew (the rows' staggered entrance); {@link Long#MIN_VALUE} until then. */
    private long openNanos = Long.MIN_VALUE;

    private int panelX, panelY, panelW, panelH;
    private int listX, listY, listW, listH;
    private int toolbarY;
    private boolean toolbar;
    private int chipX, chipW;
    private int statusY;
    private int footerTop;
    private int copyW, proxyW;

    public AccountSwitcherScreen(Screen parent) {
        super(Component.literal("Swap Accounts"));
        this.parent = parent;
    }

    // ---- layout -------------------------------------------------------------------------------------------------------

    @Override
    protected void init() {
        if (this.accounts.isEmpty() && this.statusMessage == null) {
            try {
                this.accounts = PrismAccountStore.load();
                if (this.accounts.isEmpty()) {
                    setStatus("No Microsoft accounts found in Prism Launcher.", Kind.ERROR);
                }
            } catch (IOException e) {
                setStatus("Couldn't read Prism's accounts.json: " + e.getMessage(), Kind.ERROR);
            }
        }
        this.banStatuses = SharedBanStatusStore.load();
        this.universalActive = ProxyConfig.isUniversalActive();

        this.panelW = Math.min(this.width - 16, 680);
        this.panelH = this.height - 16;
        this.panelX = (this.width - this.panelW) / 2;
        this.panelY = 8;
        int contentX = this.panelX + 8;
        int contentW = this.panelW - 16;

        // Footer: one row of four when it fits, else two rows of two.
        boolean oneRow = contentW >= 4 * 92 + 3 * 4;
        int footerRows = oneRow ? 1 : 2;
        this.footerTop = this.panelY + this.panelH - 8 - (footerRows * FOOTER_H + (footerRows - 1) * 4);
        this.statusY = this.footerTop - 6 - 9;

        // Toolbar: search box (with enough accounts) and the "Playing as" chip.
        this.toolbarY = this.panelY + ScreenChrome.HEADER_H + 9;
        boolean search = this.accounts.size() >= SEARCH_MIN;
        this.toolbar = search || this.panelH >= 200;
        String playing = playingAs();
        this.chipW = this.font.width(playing) + 18;
        int searchW = contentW - (this.chipW + 6);
        if (search && searchW < 120) {
            this.chipW = 0;
            searchW = contentW;
        }
        this.chipX = search ? contentX + searchW + 6 : contentX;
        this.searchBox = null;
        if (search) {
            this.searchBox = new ThemedEditBox(this.font, contentX, this.toolbarY, searchW, 18, Component.literal("Search"))
                    .hint("Search " + this.accounts.size() + " accounts...");
            this.searchBox.setMaxLength(16);
            this.searchBox.setValue(this.searchQuery);
            this.searchBox.setResponder(text -> {
                if (!text.equals(this.searchQuery)) {
                    this.searchQuery = text;
                    this.scroll = 0;
                    this.scrollAnim.snap(0f);
                    rebuildRows();
                }
            });
            this.searchBox.setTooltip(Tooltip.create(Component.literal(
                    "Type to narrow the list by name. Typing anywhere on this page starts a search.")));
            this.addRenderableWidget(this.searchBox);
        }

        this.listX = contentX;
        this.listY = this.toolbar ? this.toolbarY + 18 + 6 : this.panelY + ScreenChrome.HEADER_H + 9;
        this.listW = contentW;
        this.listH = Math.max(STRIDE + 6, this.statusY - 5 - this.listY);

        // Footer buttons go in BEFORE the rows, so a click on the footer is never taken by a row scrolled under it.
        int gap = 4;
        int fw = oneRow ? (contentW - 3 * gap) / 4 : (contentW - gap) / 2;
        int[][] at = new int[4][];
        for (int i = 0; i < 4; i++) {
            int col = oneRow ? i : i % 2;
            int rowIdx = oneRow ? 0 : i / 2;
            at[i] = new int[]{contentX + col * (fw + gap), this.footerTop + rowIdx * (FOOTER_H + 4)};
        }
        // the last column takes the rounding remainder, so the footer ends flush with the list
        int lastW = contentX + contentW - (oneRow ? at[3][0] : at[1][0]);
        SettingsButtonWidget back = SettingsButtonWidget.builder(Component.literal("Back"), btn -> onBack())
                .bounds(at[0][0], at[0][1], fw, FOOTER_H).build();
        this.addRenderableWidget(back);
        // "Direct Connect"-style entry point: a temporary login from a raw session token, not one of Prism's saved
        // accounts (see DirectSessionLoginScreen).
        SettingsButtonWidget direct = SettingsButtonWidget.builder(Component.literal("Direct Session Login"), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new DirectSessionLoginScreen(this, this.parent)))
                .bounds(at[1][0], at[1][1], oneRow ? fw : lastW, FOOTER_H).build();
        direct.setTooltip(Tooltip.create(Component.literal(
                "Sign in for this session only from a Minecraft session token (Copy ID gives one). Nothing is saved.")));
        this.addRenderableWidget(direct);
        // Proxy controls (2026-09-15: moved here from the multiplayer screen). "Set Universal Proxy" (called Set Instance
        // Proxy until 2026-10-15) opens this instance's proxy editor - the proxy the Universal switch publishes to every
        // instance; an account's saved proxy also lands here on a swap. "Universal" is a plain ON/OFF switch (killer560:
        // "should just be a toggle on or off not open any menu").
        SettingsButtonWidget setUniversal = SettingsButtonWidget.builder(Component.literal("Set Universal Proxy"), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new com.killer560.hub.proxy.gui.ProxyConfigScreen(this)))
                .bounds(at[2][0], at[2][1], fw, FOOTER_H).build();
        setUniversal.setTooltip(Tooltip.create(Component.literal(
                "Set the proxy the Universal switch shares with every instance. While Universal is off, only this "
                        + "instance uses it (until an account with its own proxy is swapped to).")));
        this.addRenderableWidget(setUniversal);
        this.universalButton = new UniversalSwitch(at[3][0], at[3][1], oneRow ? fw + (contentX + contentW - (at[3][0] + fw))
                : lastW, FOOTER_H);
        this.universalButton.setTooltip(Tooltip.create(Component.literal(
                "On: every Prism instance connects through the Universal proxy, whatever its own or its account's proxy "
                        + "is. Off: each instance uses its own.")));
        this.addRenderableWidget(this.universalButton);
        refreshUniversalLabel();

        this.copyW = this.font.width("Copy ID") + 14;
        this.proxyW = Math.max(this.font.width("Set Proxy"), this.font.width("Edit Proxy")) + 14;
        rebuildRows();
    }

    private String playingAs() {
        return "Playing as " + Minecraft.getInstance().getUser().getName();
    }

    /** The accounts whose name contains the search (case-insensitive); all of them for a blank one. */
    private List<PrismAccount> visibleAccounts() {
        String q = this.searchBox == null ? "" : this.searchQuery.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return this.accounts;
        }
        List<PrismAccount> out = new ArrayList<>();
        for (PrismAccount a : this.accounts) {
            if (a.displayName() != null && a.displayName().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(a);
            }
        }
        return out;
    }

    /** Drops the row widgets and builds them again for the accounts the search shows. */
    private void rebuildRows() {
        for (AbstractWidget w : this.rows) {
            this.removeWidget(w);
        }
        for (AbstractWidget w : this.rowButtons) {
            this.removeWidget(w);
        }
        this.rows.clear();
        this.rowButtons.clear();
        UUID signedIn = Minecraft.getInstance().getUser().getProfileId();
        for (PrismAccount account : visibleAccounts()) {
            AccountRowWidget row = new AccountRowWidget(account,
                    signedIn != null && signedIn.equals(AccountRowWidget.parseUuid(account.uuid())), this::onAccountSelected);
            MenuAnim.Tween old = this.hoverState.get(account.uuid());
            if (old != null) {
                row.hover.copyFrom(old);
            }
            this.hoverState.put(account.uuid(), row.hover);
            row.active = !this.busy;
            row.setTooltip(Tooltip.create(Component.literal(row.signedIn
                    ? "Signed in as " + account.displayName() + " now. Click to sign in again."
                    : "Click to sign in as " + account.displayName() + ".")));
            fillBanStatus(row);

            SettingsButtonWidget copy = SettingsButtonWidget.builder(Component.literal("Copy ID"), btn -> onCopySessionId(account))
                    .bounds(0, 0, this.copyW, BTN_H).build();
            copy.active = !this.busy;
            copy.setTooltip(Tooltip.create(Component.literal("Copy " + account.displayName()
                    + "'s session ID to the clipboard without switching to it.")));
            SettingsButtonWidget proxy = SettingsButtonWidget.builder(proxyButtonLabel(account),
                            btn -> McCompat.setScreen(Minecraft.getInstance(), new AccountProxyConfigScreen(this, account)))
                    .bounds(0, 0, this.proxyW, BTN_H).build();
            proxy.active = !this.busy;
            proxy.setTooltip(Tooltip.create(Component.literal("Give " + account.displayName()
                    + " its own proxy, switched to every time you swap to it (the Universal proxy still wins while it is on).")));
            // Buttons before their row: a click on a button is the button's, not the row's.
            this.addWidget(copy);
            this.addWidget(proxy);
            this.addWidget(row);
            this.rows.add(row);
            this.rowButtons.add(copy);
            this.rowButtons.add(proxy);
        }
        refreshProxyStatus();
        clampScroll();
        layoutRows();
    }

    private int contentHeight() {
        return this.rows.isEmpty() ? 0 : this.rows.size() * STRIDE - ROW_GAP + 8;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - this.listH);
    }

    private void clampScroll() {
        int max = maxScroll();
        if (this.scroll > max || this.scroll < 0) {
            this.scroll = Math.max(0, Math.min(max, this.scroll));
            this.scrollAnim.set(this.scroll);
        }
        if (!this.scrollAnim.placed()) {
            this.scrollAnim.snap(this.scroll);
        }
    }

    private int rowRight() {
        return this.listX + this.listW - 4 - (maxScroll() > 0 ? SCROLLBAR_W + 3 : 0);
    }

    /** Moves every row (and its buttons) to where the shown scroll and its entrance put it; rows outside the list are
     *  hidden, so they take no clicks. */
    private void layoutRows() {
        int shown = Math.round(this.scrollAnim.get());
        int x = this.listX + 4;
        int right = rowRight();
        int w = right - x;
        int top = this.listY + 4;
        for (int i = 0; i < this.rows.size(); i++) {
            AccountRowWidget row = this.rows.get(i);
            int y = top + i * STRIDE - shown + entranceOffset(i);
            row.setX(x);
            row.setY(y);
            row.setWidth(w);
            row.setHeight(ROW_H);
            int by = y + (ROW_H - BTN_H) / 2;
            AbstractWidget copy = this.rowButtons.get(2 * i);
            AbstractWidget proxy = this.rowButtons.get(2 * i + 1);
            int px = right - 6 - this.proxyW;
            int cx = px - 4 - this.copyW;
            proxy.setX(px);
            proxy.setY(by);
            copy.setX(cx);
            copy.setY(by);
            row.textRight = cx;
            boolean in = y + ROW_H > this.listY && y < this.listY + this.listH;
            row.visible = in;
            copy.visible = in && by + BTN_H > this.listY && by < this.listY + this.listH;
            proxy.visible = copy.visible;
        }
    }

    // ---- the rows' entrance ------------------------------------------------------------------------------------------

    /** The row's fade-in (0..1) on the screen's first frames, staggered down the list. */
    private float entrance(int i) {
        if (this.openNanos == Long.MIN_VALUE) {
            return 1f;
        }
        int slot = Math.max(0, i - (int) (this.scroll / STRIDE));
        return MenuAnim.progress(this.openNanos + Math.min(slot, 12) * STAGGER_NANOS, MenuAnim.OPEN_MS, false);
    }

    /** The row's slide (pixels below its place) during its entrance; none under Reduced or Off. */
    private int entranceOffset(int i) {
        if (this.openNanos == Long.MIN_VALUE) {
            return 0;
        }
        int slot = Math.max(0, i - (int) (this.scroll / STRIDE));
        float p = MenuAnim.progress(this.openNanos + Math.min(slot, 12) * STAGGER_NANOS, MenuAnim.OPEN_MS, true);
        return Math.round((1f - p) * 8f);
    }

    // ---- state read each tick ----------------------------------------------------------------------------------------

    /** Keeps the Universal label and each row's proxy line current: expires the "Set Universal Proxy first" hint and
     *  picks up another instance toggling the shared file (ProxyConfig.universal() re-reads it when its mtime changes). */
    @Override
    public void tick() {
        super.tick();
        this.universalActive = ProxyConfig.isUniversalActive();
        refreshUniversalLabel();
        refreshProxyStatus();
    }

    private void refreshProxyStatus() {
        for (int i = 0; i < this.rows.size(); i++) {
            AccountRowWidget row = this.rows.get(i);
            AccountProxyProfile own = AccountProxyStore.get(row.account.uuid());
            boolean hasOwn = own != null && own.hasValidAddress();
            row.ownType = hasOwn ? own.getType().name() : null;
            row.proxyKind = this.universalActive ? 2 : hasOwn ? 1 : 0;
            AbstractWidget button = this.rowButtons.get(2 * i + 1);
            Component label = proxyButtonLabel(row.account);
            if (!label.getString().equals(button.getMessage().getString())) {
                button.setMessage(label);
            }
        }
    }

    private void fillBanStatus(AccountRowWidget row) {
        StoredBanStatus info = this.banStatuses.get(row.account.uuid());
        // Per killer560's "remove the not checked yet portion" (2026-09-09): an account with no recorded status at all
        // shows nothing, instead of a "Not checked yet" placeholder.
        if (info == null) {
            return;
        }
        if (info.status() == com.killer560.hub.accounts.core.HypixelBanStatus.Status.BANNED) {
            row.banned = true;
            if (info.estimatedExpiryEpochMillis() != null) {
                Instant expiry = Instant.ofEpochMilli(info.estimatedExpiryEpochMillis());
                double daysFromNow = Duration.between(Instant.now(), expiry).toMillis() / 86400000.0;
                if (daysFromNow <= 0) {
                    row.ban1 = "Banned (length likely elapsed)";
                    row.ban2 = "Last checked " + relativeTime(info.recordedAtEpochMillis());
                } else {
                    ZonedDateTime central = expiry.atZone(CENTRAL);
                    row.ban1 = "Banned until " + BAN_TIME_FORMAT.format(central) + " CST";
                    row.ban2 = String.format(Locale.US, "%.2f days from now", daysFromNow);
                }
            } else {
                row.ban1 = "Banned";
                row.ban2 = truncate(info.rawMessage(), 60);
            }
        } else {
            row.ban1 = "Unbanned";
            row.ban2 = "Checked " + relativeTime(info.recordedAtEpochMillis());
        }
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

    // ---- input --------------------------------------------------------------------------------------------------------

    private boolean inList(double x, double y) {
        return x >= this.listX && x < this.listX + this.listW && y >= this.listY && y < this.listY + this.listH;
    }

    private int trackX() {
        return this.listX + this.listW - 4 - SCROLLBAR_W;
    }

    private int trackY() {
        return this.listY + 4;
    }

    private int trackH() {
        return this.listH - 8;
    }

    private int thumbH() {
        int content = Math.max(1, contentHeight());
        return Math.min(trackH(), Math.max(16, trackH() * this.listH / content));
    }

    private int thumbY(int shown) {
        int max = maxScroll();
        return max <= 0 ? trackY() : trackY() + (trackH() - thumbH()) * Math.max(0, Math.min(max, shown)) / max;
    }

    private boolean overTrack(double x, double y) {
        return maxScroll() > 0 && x >= trackX() - 1 && x < trackX() + SCROLLBAR_W + 1 && y >= trackY()
                && y < trackY() + trackH();
    }

    private void dragTo(double y) {
        int travel = trackH() - thumbH();
        if (travel <= 0) {
            return;
        }
        double t = (y - this.dragGrabY - trackY()) / travel;
        this.scroll = (int) Math.round(Math.max(0, Math.min(1, t)) * maxScroll());
        this.scrollAnim.snap(this.scroll);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        MenuAnim.input();
        if (event.button() == 0 && overTrack(event.x(), event.y())) {
            int ty = thumbY(this.scroll);
            this.dragGrabY = event.y() >= ty && event.y() < ty + thumbH() ? event.y() - ty : thumbH() / 2.0;
            this.draggingScroll = true;
            dragTo(event.y());
            return true;
        }
        if (inList(event.x(), event.y())) {
            return super.mouseClicked(event, doubleClick);
        }
        // Outside the list a row scrolled under the toolbar or the footer is not there to be clicked.
        List<Boolean> was = new ArrayList<>(this.rows.size() + this.rowButtons.size());
        for (AbstractWidget w : this.rows) {
            was.add(w.visible);
            w.visible = false;
        }
        for (AbstractWidget w : this.rowButtons) {
            was.add(w.visible);
            w.visible = false;
        }
        try {
            return super.mouseClicked(event, doubleClick);
        } finally {
            int k = 0;
            for (AbstractWidget w : this.rows) {
                w.visible = was.get(k++);
            }
            for (AbstractWidget w : this.rowButtons) {
                w.visible = was.get(k++);
            }
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (this.draggingScroll) {
            dragTo(event.y());
            layoutRows();
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (this.draggingScroll) {
            this.draggingScroll = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll() > 0 && scrollY != 0 && inList(mouseX, mouseY)) {
            MenuAnim.input();
            int next = Math.max(0, Math.min(maxScroll(), this.scroll - (int) Math.round(scrollY * STRIDE)));
            if (next != this.scroll) {
                this.scroll = next;
                this.scrollAnim.set(next);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Typing with nothing focused goes into the search box, so just typing a name narrows the list. */
    @Override
    public boolean charTyped(CharacterEvent event) {
        MenuAnim.input();
        if (this.searchBox != null && !(getFocused() instanceof EditBox)) {
            setFocused(this.searchBox);
            this.searchBox.setFocused(true);
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        MenuAnim.input();
        return super.keyPressed(event);
    }

    // ---- universal switch ---------------------------------------------------------------------------------------------

    /** ON -> OFF disables the shared universal file; OFF -> ON publishes this instance's proxy into it. With no proxy
     *  address set there's nothing to publish, so it stays OFF and the switch briefly says so (no menu is opened;
     *  ModChat isn't usable on the title screen). */
    private void onUniversalToggled() {
        if (ProxyConfig.isUniversalActive()) {
            ProxyConfig.disableUniversal();
            this.universalHintUntil = 0L;
        } else if (!ProxyConfig.enableUniversalFromInstance()) {
            this.universalHintUntil = Util.getMillis() + UNIVERSAL_HINT_MS;
        } else {
            this.universalHintUntil = 0L;
        }
        this.universalActive = ProxyConfig.isUniversalActive();
        refreshUniversalLabel();
        refreshProxyStatus();
    }

    private void refreshUniversalLabel() {
        if (this.universalButton != null) {
            Component label = universalToggleLabel();
            if (!label.getString().equals(this.universalButton.getMessage().getString())) {
                this.universalButton.setMessage(label);
            }
        }
    }

    private boolean universalHint() {
        if (this.universalHintUntil != 0L && Util.getMillis() < this.universalHintUntil) {
            return true;
        }
        this.universalHintUntil = 0L;
        return false;
    }

    private Component universalToggleLabel() {
        if (universalHint()) {
            return Component.literal("§cSet Universal Proxy first");
        }
        return Component.literal("Universal: " + (this.universalActive ? "§aON" : "§cOFF"));
    }

    private static Component proxyButtonLabel(PrismAccount account) {
        AccountProxyProfile profile = AccountProxyStore.get(account.uuid());
        return Component.literal(profile != null && profile.hasValidAddress() ? "Edit Proxy" : "Set Proxy");
    }

    /** The Universal switch: a footer button holding a label and the menu's on/off switch, the knob easing across. Its
     *  message ("Universal: ON" / "Universal: OFF", or the hint) is what narration and the testkit read. */
    private final class UniversalSwitch extends AbstractWidget {
        private final MenuAnim.Tween knob = new MenuAnim.Tween(MenuAnim.TOGGLE_MS, true);
        private final MenuAnim.Tween colour = new MenuAnim.Tween(MenuAnim.TOGGLE_MS, false);
        private final MenuAnim.Tween hover = new MenuAnim.Tween(MenuAnim.HOVER_MS, false);

        UniversalSwitch(int x, int y, int w, int h) {
            super(x, y, w, h, Component.literal("Universal: OFF"));
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onUniversalToggled();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            MenuStyle.use();
            boolean on = AccountSwitcherScreen.this.universalActive;
            this.knob.set(on ? 1f : 0f);
            this.colour.set(on ? 1f : 0f);
            this.hover.set(isHovered() && this.active ? 1f : 0f);
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            GuiRects r = GuiRects.begin(g);
            MenuStyle.raisedButton(r, x + 1, y + 1, w - 2, h - 2, this.hover.get(), 0f);
            boolean hint = universalHint();
            int sx = x + w - 8 - MenuStyle.SWITCH_W;
            int sy = y + (h - MenuStyle.SWITCH_H) / 2;
            if (!hint) {
                MenuStyle.toggle(r, sx, sy, this.knob.get(), this.colour.get(), this.hover.get());
            }
            r.submit();
            var font = MenuStyle.font();
            if (hint) {
                String s = MenuStyle.ellipsize("Set Universal Proxy first", w - 8);
                ScreenChrome.text(g, s, x + (w - font.width(s)) / 2, y + (h - 8) / 2, MenuStyle.RED);
                return;
            }
            String label = font.width("Universal Proxy") <= sx - x - 14 ? "Universal Proxy" : "Universal";
            label = MenuStyle.ellipsize(label, sx - x - 12);
            int tc = this.hover.get() > 0.5f ? MenuStyle.OR_HOVER : MenuStyle.OR;
            ScreenChrome.text(g, label, x + 8, y + (h - 8) / 2, tc);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    // ---- drawing ------------------------------------------------------------------------------------------------------

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

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        MenuAnim.frame();
        if (this.openNanos == Long.MIN_VALUE) {
            this.openNanos = MenuAnim.now();
        }
        if (AccountScreenBackground.themed()) {
            MenuStyle.use();
        } else {
            ScreenChrome.backdrop(graphics, this.width, this.height);
        }
        layoutRows();

        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, this.title.getString());
        String count = this.searchBox != null && !this.searchQuery.isBlank()
                ? this.rows.size() + " of " + this.accounts.size() + " accounts"
                : this.accounts.size() + (this.accounts.size() == 1 ? " account" : " accounts");
        ScreenChrome.headerRight(graphics, panelX, panelY, panelW, count, MenuStyle.DIM);

        if (this.toolbar && this.chipW > 0) {
            drawChip(graphics);
        }

        // the list
        ScreenChrome.well(graphics, listX, listY, listW, listH);
        boolean mouseInList = inList(mouseX, mouseY) && !this.draggingScroll;
        int mx = mouseInList ? mouseX : -1;
        int my = mouseInList ? mouseY : -1;
        graphics.enableScissor(listX + 1, listY + 1, listX + listW - 1, listY + listH - 1);
        try {
            if (this.rows.isEmpty()) {
                String msg = !this.accounts.isEmpty() ? "No accounts match \"" + this.searchQuery.trim() + "\"."
                        : this.statusMessage != null ? this.statusMessage : "No accounts.";
                ScreenChrome.centered(graphics, MenuStyle.ellipsize(msg, listW - 16), listX + listW / 2,
                        listY + listH / 2 - 4, MenuStyle.DIM);
            }
            for (int i = 0; i < this.rows.size(); i++) {
                AccountRowWidget row = this.rows.get(i);
                if (!row.visible) {
                    continue;
                }
                float prev = MenuAnim.pushFade(entrance(i));
                try {
                    row.extractRenderState(graphics, mx, my, partialTick);
                    AbstractWidget copy = this.rowButtons.get(2 * i);
                    AbstractWidget proxy = this.rowButtons.get(2 * i + 1);
                    if (copy.visible) {
                        copy.extractRenderState(graphics, mx, my, partialTick);
                        proxy.extractRenderState(graphics, mx, my, partialTick);
                    }
                } finally {
                    MenuAnim.popFade(prev);
                }
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            int shown = Math.round(this.scrollAnim.get());
            int ty = thumbY(shown);
            boolean hot = this.draggingScroll || overTrack(mouseX, mouseY);
            ScreenChrome.scrollbar(graphics, trackX(), trackY(), SCROLLBAR_W, trackH(), ty, thumbH(), hot);
        }

        // status line and the footer's rule
        String status = this.statusMessage != null ? this.statusMessage
                : this.accounts.isEmpty() ? "" : "Click an account to switch to it.";
        int sc = switch (this.statusMessage == null ? Kind.INFO : this.statusKind) {
            case BUSY -> MenuStyle.OR;
            case OK -> ScreenChrome.good();
            case ERROR -> MenuStyle.RED;
            default -> MenuStyle.DIM;
        };
        if (!status.isEmpty()) {
            String s = MenuStyle.ellipsize(status, listW - 4);
            ScreenChrome.text(graphics, s, listX + 2, statusY, sc);
        }
        ScreenChrome.separator(graphics, listX, listX + listW, footerTop - 5);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** The "Playing as" chip: a small recessed pill with a green dot. */
    private void drawChip(GuiGraphicsExtractor g) {
        int x = this.chipX;
        int y = this.toolbarY;
        int w = this.chipW;
        int h = 18;
        GuiRects r = GuiRects.begin(g);
        MenuStyle.well(r, x, y, w, h, MenuStyle.WELL);
        int good = ScreenChrome.good();
        r.fill(x + 6, y + 7, x + 10, y + 11, good);
        r.fill(x + 5, y + 6, x + 11, y + 12, MenuStyle.alpha(good, 0x30));
        r.submit();
        String s = MenuStyle.ellipsize(playingAs(), w - 18);
        ScreenChrome.text(g, s, x + 14, y + 5, MenuStyle.TXT);
    }

    // ---- actions ------------------------------------------------------------------------------------------------------

    private void setStatus(String message, Kind kind) {
        this.statusMessage = message;
        this.statusKind = kind;
    }

    private void onBack() {
        McCompat.setScreen(Minecraft.getInstance(), this.parent);
    }

    @Override
    public void onClose() {
        onBack();
    }

    private void onAccountSelected(PrismAccount account) {
        if (this.busy) {
            return;
        }
        this.busy = true;
        setStatus("Signing in as " + account.displayName() + "...", Kind.BUSY);
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
                setStatus("Failed to sign in as " + account.displayName() + ": " + cause.getMessage(), Kind.ERROR);
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
        setStatus("Copying session ID for " + account.displayName() + "...", Kind.BUSY);
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
                setStatus("Failed to copy session ID for " + account.displayName() + ": " + cause.getMessage(), Kind.ERROR);
                rebuild();
                return;
            }
            Minecraft.getInstance().keyboardHandler.setClipboard(result.minecraftAccessToken());
            setStatus("Copied session ID for " + account.displayName() + " to clipboard!", Kind.OK);
            rebuild();
        }, this.screenExecutor);
    }

    /** Lays the screen out again (a status change, busy on or off), keeping the search and the scroll. */
    private void rebuild() {
        boolean searchFocused = this.searchBox != null && this.searchBox.isFocused();
        this.clearWidgets();
        this.rows.clear();
        this.rowButtons.clear();
        this.init();
        if (searchFocused && this.searchBox != null) {
            setFocused(this.searchBox);
            this.searchBox.setFocused(true);
        }
    }

    @Override
    protected void rebuildWidgets() {
        rebuild();
    }
}

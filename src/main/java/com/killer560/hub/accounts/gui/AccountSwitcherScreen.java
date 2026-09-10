package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.AccountApplier;
import com.killer560.hub.accounts.core.AuthException;
import com.killer560.hub.accounts.core.MicrosoftAuthFlow;
import com.killer560.hub.accounts.core.PrismAccount;
import com.killer560.hub.accounts.core.PrismAccountStore;
import com.killer560.hub.accounts.core.SharedBanStatusStore;
import com.killer560.hub.accounts.core.StoredBanStatus;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AccountSwitcherScreen extends Screen {

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

        int rowHeight = 22;
        int buttonWidth = 200;
        int copyWidth = 60;
        int statusWidth = 240;
        int gap = 10;
        // Per killer560's "remove the not checked yet portion and recenter it" (2026-09-09): the ban
        // status column is now blank for any account that's never been checked (see
        // addBanStatusWidgets), so it's no longer counted in the centering math - the account/copy
        // button pair is centered as its own group, with status text (when there IS any) sitting as an
        // annex to the right of that centered pair rather than pulling the whole row off-center.
        int totalWidth = buttonWidth + gap + copyWidth;
        int startX = this.width / 2 - totalWidth / 2;
        int startY = 40;

        if (this.statusMessage != null) {
            this.addRenderableOnly(new StringWidget(0, 20, this.width, 16,
                    Component.literal(this.statusMessage), this.font));
        }

        int i = 0;
        for (PrismAccount account : this.accounts) {
            int y = startY + i * rowHeight;

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

            addBanStatusWidgets(account, startX + buttonWidth + gap + copyWidth + gap, y, statusWidth);

            i++;
        }

        int belowListY = startY + Math.max(i, 1) * rowHeight + 12;
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), btn -> onBack())
                .bounds(this.width / 2 - buttonWidth / 2, belowListY, buttonWidth, 20)
                .build());

        // Bottom-middle "Direct Connect"-style entry point: a temporary login from a raw session
        // token, not one of Prism's saved accounts (see DirectSessionLoginScreen).
        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Direct Session Login"), btn ->
                        Minecraft.getInstance().setScreen(new DirectSessionLoginScreen(this, this.parent)))
                .bounds(this.width / 2 - buttonWidth / 2, belowListY + 26, buttonWidth, 20)
                .build());
    }

    /** Black + amber theme (2026-09-09), matching {@link com.killer560.hub.gui.ModScreen} - per
     *  killer560's "make the account switcher and proxy mod more fit the new theme." Same dim overlay
     *  instead of the vanilla blurred background, title drawn in the same amber accent as the main
     *  menu's own header. */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFCC6600);
    }

    private void addBanStatusWidgets(PrismAccount account, int x, int y, int width) {
        StoredBanStatus info = this.banStatuses.get(account.uuid());
        // Per killer560's "remove the not checked yet portion" (2026-09-09): an account with no
        // recorded status at all now shows nothing here, instead of a "Not checked yet"/"Join Hypixel
        // to check" placeholder.
        if (info == null) {
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

        this.addRenderableOnly(new StringWidget(x, y, width, 10, Component.literal(line1), this.font));
        if (line2 != null) {
            this.addRenderableOnly(new StringWidget(x, y + 10, width, 10, Component.literal(line2), this.font));
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

    private void onBack() {
        Minecraft.getInstance().setScreen(this.parent);
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
            Minecraft.getInstance().setScreen(this.parent);
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

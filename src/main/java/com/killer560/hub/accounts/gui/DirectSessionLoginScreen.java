package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.AccountApplier;
import com.killer560.hub.accounts.core.AuthException;
import com.killer560.hub.accounts.core.MicrosoftAuthFlow;
import com.killer560.hub.accounts.core.SessionLoginStore;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A "Direct Connect"-style quick login: paste a Minecraft session/access token straight from
 * another tool and swap the running client to whatever account it belongs to, without adding it
 * to Prism's saved account list. The token itself IS persisted (via {@link SessionLoginStore}, its
 * own small file) so it survives closing and reopening the game - per killer560, he wants it to still
 * be there next launch, he just doesn't want it showing up in the main account list like a
 * permanent saved account.
 */
public class DirectSessionLoginScreen extends Screen {

    private static final MicrosoftAuthFlow AUTH_FLOW = new MicrosoftAuthFlow();

    private static final ExecutorService AUTH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "killer560smod-session-login-auth");
        thread.setDaemon(true);
        return thread;
    });

    /** Where "Back" (cancel) returns to - normally the account switcher list itself. */
    private final Screen backTarget;
    /** Where a successful connect closes all the way out to - matches how picking a saved account
     *  from the list also closes the whole account-switcher flow, not just this sub-screen. */
    private final Screen successTarget;
    private EditBox tokenField;
    private String statusMessage;
    private boolean busy;

    /** IGN last resolved for the currently-saved token, so killer560 can tell which account it is
     *  without decoding the token himself - null/blank until a resolve has actually run. */
    private String resolvedName;
    private boolean resolving;
    private boolean resolveAttempted;

    public DirectSessionLoginScreen(Screen backTarget, Screen successTarget) {
        super(Component.literal("Direct Session Login"));
        this.backTarget = backTarget;
        this.successTarget = successTarget;
    }

    @Override
    protected void init() {
        int fieldWidth = 320;
        int centerX = this.width / 2;
        int y = this.height / 2 - 30;

        // First time this screen is built (this play session), pre-fill from whatever was saved
        // last time - not on later rebuilds, so the field's own live edits (and cursor position)
        // aren't clobbered every time onConnect()/onBack() triggers a rebuild. Resolve state must
        // be seeded here too, BEFORE resolvedNameLine() is read below, or the very first frame
        // would show "unresolved" for a split second even when a cached IGN is already known.
        boolean firstBuild = this.tokenField == null;
        if (firstBuild) {
            this.resolvedName = SessionLoginStore.getInstance().getLastKnownName();
        }
        String initialValue = firstBuild ? SessionLoginStore.getInstance().getToken() : this.tokenField.getValue();

        this.addRenderableOnly(new StringWidget(centerX - fieldWidth / 2, y - 34, fieldWidth, 12,
                Component.literal(resolvedNameLine()), this.font));
        this.addRenderableOnly(new StringWidget(centerX - fieldWidth / 2, y - 20, fieldWidth, 12,
                Component.literal("Paste a Minecraft session/access token - kept, but not shown as a saved account"),
                this.font));

        this.tokenField = new EditBox(this.font, centerX - fieldWidth / 2, y, fieldWidth, 20,
                Component.literal("Session Token"));
        this.tokenField.setMaxLength(4096);
        this.tokenField.setHint(Component.literal("Session/access token..."));
        this.tokenField.setValue(initialValue);
        this.tokenField.setResponder(text -> {
            SessionLoginStore store = SessionLoginStore.getInstance();
            if (!text.equals(store.getToken())) {
                // A genuinely different token - the cached IGN no longer applies to it.
                store.setLastKnownName("");
                this.resolvedName = null;
                this.resolveAttempted = false;
            }
            store.setToken(text);
            store.save();
        });
        this.addRenderableWidget(this.tokenField);
        this.setInitialFocus(this.tokenField);

        if (firstBuild && !initialValue.isBlank() && !this.resolveAttempted) {
            resolveIgn(initialValue);
        }

        SettingsButtonWidget connectButton = SettingsButtonWidget.builder(Component.literal("Connect"), btn -> onConnect())
                .bounds(centerX - fieldWidth / 2, y + 26, fieldWidth, 20)
                .build();
        connectButton.active = !this.busy;
        this.addRenderableWidget(connectButton);

        this.addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Back"), btn -> onBack())
                .bounds(centerX - fieldWidth / 2, y + 52, fieldWidth, 20)
                .build());

        if (this.statusMessage != null) {
            this.addRenderableOnly(new StringWidget(centerX - fieldWidth / 2, y + 78, fieldWidth, 12,
                    Component.literal(this.statusMessage), this.font));
        }
    }

    /** Black + amber theme (2026-09-09) - see {@link AccountSwitcherScreen#extractRenderState}. */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFCC6600);
    }

    private void onBack() {
        Minecraft.getInstance().setScreen(this.backTarget);
    }

    private void onConnect() {
        if (this.busy) {
            return;
        }
        String token = this.tokenField.getValue().trim();
        if (token.isEmpty()) {
            this.statusMessage = "Enter a session token first.";
            rebuild();
            return;
        }

        this.busy = true;
        this.statusMessage = "Signing in...";
        rebuild();

        CompletableFuture.supplyAsync(() -> {
            try {
                return AUTH_FLOW.loginWithSessionToken(token);
            } catch (AuthException e) {
                throw new RuntimeException(e);
            }
        }, AUTH_EXECUTOR).whenCompleteAsync((result, throwable) -> {
            this.busy = false;
            if (throwable != null) {
                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                this.statusMessage = "Failed to sign in: " + cause.getMessage();
                rebuild();
                return;
            }
            SessionLoginStore.getInstance().setLastKnownName(result.name());
            SessionLoginStore.getInstance().save();
            AccountApplier.apply(result);
            Minecraft.getInstance().setScreen(this.successTarget);
        }, this.screenExecutor);
    }

    /** Resolves and caches the IGN for {@code token} without applying it as the active account -
     *  this is purely to answer "whose session is this," matching killer560's request. */
    private void resolveIgn(String token) {
        this.resolveAttempted = true;
        this.resolving = true;

        CompletableFuture.supplyAsync(() -> {
            try {
                return AUTH_FLOW.loginWithSessionToken(token);
            } catch (AuthException e) {
                throw new RuntimeException(e);
            }
        }, AUTH_EXECUTOR).whenCompleteAsync((result, throwable) -> {
            this.resolving = false;
            if (throwable == null) {
                this.resolvedName = result.name();
                SessionLoginStore.getInstance().setLastKnownName(result.name());
                SessionLoginStore.getInstance().save();
            }
            rebuild();
        }, this.screenExecutor);
    }

    private String resolvedNameLine() {
        if (this.tokenField != null ? this.tokenField.getValue().isBlank() : SessionLoginStore.getInstance().getToken().isBlank()) {
            return "§7No session token saved.";
        }
        if (this.resolving) {
            return "§7Checking whose session this is...";
        }
        if (this.resolvedName != null && !this.resolvedName.isBlank()) {
            return "Saved session IGN: §e" + this.resolvedName;
        }
        return "§7Couldn't resolve an IGN for this token (may be invalid/expired) - press Connect to retry.";
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }
}

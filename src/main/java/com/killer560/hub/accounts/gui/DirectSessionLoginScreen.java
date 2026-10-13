package com.killer560.hub.accounts.gui;

import com.killer560.hub.accounts.AccountApplier;
import com.killer560.hub.accounts.core.AuthException;
import com.killer560.hub.accounts.core.MicrosoftAuthFlow;
import com.killer560.hub.accounts.core.SessionLoginStore;
import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedEditBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.killer560.hub.compat.McCompat;

/**
 * A "Direct Connect"-style quick login: paste a Minecraft session/access token straight from
 * another tool and swap the running client to whatever account it belongs to, without adding it
 * to Prism's saved account list. The token itself IS persisted (via {@link SessionLoginStore}, its
 * own small file) so it survives closing and reopening the game - per killer560, he wants it to still
 * be there next launch, he just doesn't want it showing up in the main account list like a
 * permanent saved account.
 */
public class DirectSessionLoginScreen extends Screen implements MenuThemedScreen {

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

    /** The framed panel and the two text lines above the box (drawn in the theme, not as white StringWidgets). */
    private int panelX, panelY, panelW, panelH;
    private int infoY;
    private int statusY;
    private static final String EXPLAIN = "Paste a Minecraft session/access token - kept, but not shown as a saved account";

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

        this.infoY = y - 34;
        this.statusY = y + 80;
        this.panelW = Math.max(fieldWidth, this.font.width(EXPLAIN)) + 24;
        this.panelX = centerX - this.panelW / 2;
        this.panelY = y - 34 - 32;
        this.panelH = this.statusY + 10 + 8 - this.panelY;

        this.tokenField = new ThemedEditBox(this.font, centerX - fieldWidth / 2, y, fieldWidth, 20,
                Component.literal("Session Token")).hint("Session/access token...");
        this.tokenField.setMaxLength(4096);
        // Credential field: never draw the token itself (screenshots / streams / shoulder-surfing). Paste,
        // clear and the IGN resolve below still work - only the glyphs are replaced.
        this.tokenField.addFormatter((text, offset) -> net.minecraft.util.FormattedCharSequence.forward(
                "*".repeat(text.length()), net.minecraft.network.chat.Style.EMPTY));
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

    /** The menu theme's panel over the animated title background (or the dim, in a world). */
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (AccountScreenBackground.themed()) {
            MenuStyle.use();
        } else {
            ScreenChrome.backdrop(graphics, this.width, this.height);
        }
        ScreenChrome.frame(graphics, panelX, panelY, panelW, panelH);
        ScreenChrome.header(graphics, panelX, panelY, panelW, this.title.getString());
        int cx = this.width / 2;
        ScreenChrome.centered(graphics, resolvedNameLine(), cx, infoY + 2, MenuStyle.TXT);
        ScreenChrome.centered(graphics, EXPLAIN, cx, infoY + 16, MenuStyle.DIM);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        if (this.statusMessage != null) {
            ScreenChrome.centered(graphics, MenuStyle.ellipsize(this.statusMessage, panelW - 12), cx, statusY, MenuStyle.TXT);
        }
    }

    private void onBack() {
        McCompat.setScreen(Minecraft.getInstance(), this.backTarget);
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
            McCompat.setScreen(Minecraft.getInstance(), this.successTarget);
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

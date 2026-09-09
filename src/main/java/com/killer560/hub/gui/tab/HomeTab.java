package com.killer560.hub.gui.tab;

import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.updatecheck.UpdateCheckFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** Landing tab - deliberately kept small (2026-09-08, per killer560's explicit request): just the mod
 *  meta actions (Check for Updates, Join Discord) and the HUD position editor. Everything else that
 *  used to live here (Borderless Fullscreen, Fullbright) moved to {@link DisplayTab}. */
public class HomeTab extends BaseTab implements KeyCaptureTab {

    // Same permanent invite already published in README.md and set as the repo's "Website" link.
    private static final String DISCORD_INVITE_URL = "https://discord.gg/hkQMF5fE84";

    private boolean listening = false;

    private volatile boolean checkingForUpdate = false;
    private volatile String availableUpdateVersion = null;
    private volatile String availableUpdateUrl = null;
    // Sticks on the button itself (rather than a timed toast) until the next check, since the
    // center-screen ModOverlayMessage this used to rely on alone renders BEHIND this very menu's own
    // panel while it's open - killer560 reported having no way to tell if a check had actually run.
    private volatile String lastCheckResultText = null;

    public HomeTab() {
        super("Home");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(updateButtonText(), btn -> {
                    // Only ever assigned from a real click on a real rendered widget - never from
                    // BaseTab#widgetsMatchSearch's speculative, discarded buildWidgets() call (see its
                    // own doc), which would otherwise silently clobber this with a throwaway widget
                    // every time killer560 types in the search box while a check is in flight.
                    if (availableUpdateUrl != null) {
                        Util.getPlatform().openUri(availableUpdateUrl);
                        return;
                    }
                    if (checkingForUpdate) {
                        return;
                    }
                    checkingForUpdate = true;
                    lastCheckResultText = null;
                    btn.setMessage(updateButtonText());
                    UpdateCheckFeature.checkForUpdateAsync(result -> onUpdateCheckResult(result, btn));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Join Discord"), btn ->
                    Util.getPlatform().openUri(DISCORD_INVITE_URL)
                ).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Edit HUD Positions"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new HudEditorScreen(client.screen));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        Component keybindLabel = listening
                ? Component.literal("Press any key...")
                : keybindText();
        widgets.add(SettingsButtonWidget.builder(keybindLabel, btn -> {
                    listening = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private void onUpdateCheckResult(UpdateCheckFeature.Result result, SettingsButtonWidget btn) {
        checkingForUpdate = false;
        if (result.error() != null) {
            lastCheckResultText = "§cCheck Failed - Click to Retry";
            ModOverlayMessage.show("§c[Killer560's Mod] Update check failed", 3000);
        } else if (result.updateAvailable()) {
            availableUpdateVersion = result.remoteVersion();
            availableUpdateUrl = result.releaseUrl();
            ModOverlayMessage.show("§6[Killer560's Mod] Update available: v" + result.remoteVersion(), 5000);
        } else {
            lastCheckResultText = "§aUp to Date (v" + result.currentVersion() + ")";
            ModOverlayMessage.show("§a[Killer560's Mod] You're up to date (v" + result.currentVersion() + ")", 3000);
        }
        btn.setMessage(updateButtonText());
    }

    public boolean isListeningForKey() {
        return listening;
    }

    public void onKeyCaptured(int keyCode) {
        listening = false;
        HudConfig.getInstance().setEditKeyCode(keyCode);
        HudConfig.getInstance().save();
    }

    private Component updateButtonText() {
        if (checkingForUpdate) {
            return Component.literal("Checking for Updates...");
        }
        if (availableUpdateUrl != null) {
            return Component.literal("§aUpdate Available: v" + availableUpdateVersion + " (Click to Open)");
        }
        if (lastCheckResultText != null) {
            return Component.literal(lastCheckResultText);
        }
        return Component.literal("Check for Updates");
    }

    private static Component keybindText() {
        int code = HudConfig.getInstance().getEditKeyCode();
        String name = code < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal("Edit HUD Keybind: §b" + name);
    }
}

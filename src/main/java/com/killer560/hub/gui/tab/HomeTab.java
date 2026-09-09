package com.killer560.hub.gui.tab;

import com.killer560.hub.fullbright.FullbrightConfig;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.screenshotcopy.ScreenshotCopyConfig;
import com.killer560.hub.updatecheck.UpdateCheckFeature;
import com.killer560.hub.window.WindowModeConfig;
import com.killer560.hub.window.WindowModeFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/** Landing tab: feature overview and mod-wide settings (HUD editor keybind and shortcut) - merged
 *  in from the old separate "Main" tab. The RNG Meter's Hypixel API key is no longer user-entered
 *  here - see {@link com.killer560.hub.rngmeter.HypixelApiKeyProvider}. */
public class HomeTab extends BaseTab implements KeyCaptureTab {

    // Same permanent invite already published in README.md and set as the repo's "Website" link.
    private static final String DISCORD_INVITE_URL = "https://discord.gg/hkQMF5fE84";

    private boolean listening = false;

    private volatile boolean checkingForUpdate = false;
    private volatile String availableUpdateVersion = null;
    private volatile String availableUpdateUrl = null;
    private SettingsButtonWidget updateButtonRef;

    public HomeTab() {
        super("Home");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        Component keybindLabel = listening
                ? Component.literal("Press any key...")
                : keybindText();
        widgets.add(SettingsButtonWidget.builder(keybindLabel, btn -> {
                    listening = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Edit HUD Positions"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new HudEditorScreen(client.screen));
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(borderlessText(), btn -> {
                    WindowModeFeature.toggle();
                    btn.setMessage(borderlessText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(fullbrightText(), btn -> {
                    FullbrightConfig cfg = FullbrightConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(fullbrightText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(screenshotCopyText(), btn -> {
                    ScreenshotCopyConfig cfg = ScreenshotCopyConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(screenshotCopyText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Join Discord"), btn ->
                    Util.getPlatform().openUri(DISCORD_INVITE_URL)
                ).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(updateButtonText(), btn -> {
                    // Only ever assigned from a real click on a real rendered widget - never from
                    // BaseTab#widgetsMatchSearch's speculative, discarded buildWidgets() call (see its
                    // own doc), which would otherwise silently clobber this with a throwaway widget
                    // every time killer560 types in the search box while a check is in flight.
                    updateButtonRef = btn;
                    if (availableUpdateUrl != null) {
                        Util.getPlatform().openUri(availableUpdateUrl);
                        return;
                    }
                    if (checkingForUpdate) {
                        return;
                    }
                    checkingForUpdate = true;
                    btn.setMessage(updateButtonText());
                    UpdateCheckFeature.checkForUpdateAsync(this::onUpdateCheckResult);
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private void onUpdateCheckResult(UpdateCheckFeature.Result result) {
        checkingForUpdate = false;
        if (result.error() != null) {
            ModOverlayMessage.show("§c[Killer560's Mod] Update check failed", 3000);
        } else if (result.updateAvailable()) {
            availableUpdateVersion = result.remoteVersion();
            availableUpdateUrl = result.releaseUrl();
            ModOverlayMessage.show("§6[Killer560's Mod] Update available: v" + result.remoteVersion(), 5000);
        } else {
            ModOverlayMessage.show("§a[Killer560's Mod] You're up to date (v" + result.currentVersion() + ")", 3000);
        }
        if (updateButtonRef != null) {
            updateButtonRef.setMessage(updateButtonText());
        }
    }

    public boolean isListeningForKey() {
        return listening;
    }

    public void onKeyCaptured(int keyCode) {
        listening = false;
        HudConfig.getInstance().setEditKeyCode(keyCode);
        HudConfig.getInstance().save();
    }

    private static Component borderlessText() {
        return Component.literal("Borderless Fullscreen: "
                + (WindowModeConfig.getInstance().isBorderlessFullscreenEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component fullbrightText() {
        return Component.literal("Fullbright: "
                + (FullbrightConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component screenshotCopyText() {
        return Component.literal("Copy Screenshots to Clipboard: "
                + (ScreenshotCopyConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private Component updateButtonText() {
        if (checkingForUpdate) {
            return Component.literal("Checking for Updates...");
        }
        if (availableUpdateUrl != null) {
            return Component.literal("§aUpdate Available: v" + availableUpdateVersion + " (Click)");
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

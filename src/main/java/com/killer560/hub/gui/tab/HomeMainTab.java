package com.killer560.hub.gui.tab;

import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.updatecheck.UpdateCheckFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

/** Landing tab - deliberately kept small (2026-09-08, per killer560's explicit request): just the mod
 *  meta actions (Check for Updates, Join Discord) and the HUD position editor. Everything else that
 *  used to live here (Borderless Fullscreen, Fullbright) moved to {@link DisplayTab}. */
public class HomeMainTab extends BaseTab implements KeyCaptureTab {

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

    public HomeMainTab() {
        super("Mod & HUD");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        int gap = 8;
        int half = (contentWidth - gap) / 2;
        int rightW = Math.max(1, contentWidth - half - gap);
        int y = contentY;

        // Reformatted 2026-09-16: this used to be five 220px-wide buttons stacked in one column down the
        // left, leaving most of the panel empty. Two columns under real headings instead, so the update /
        // Discord actions read as separate from the things that change how the mod behaves.
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Mod", false), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(updateButtonText(), btn -> {
                    // Only ever assigned from a real click on a real rendered widget - never from
                    // BaseTab#widgetsMatchSearch's speculative, discarded buildWidgets() call (see its
                    // own doc), which would otherwise silently clobber this with a throwaway widget
                    // every time killer560 types in the search box while a check is in flight.
                    if (availableUpdateUrl != null) {
                        ExternalOpen.uri(availableUpdateUrl);
                        return;
                    }
                    if (checkingForUpdate) {
                        return;
                    }
                    checkingForUpdate = true;
                    lastCheckResultText = null;
                    btn.setMessage(updateButtonText());
                    UpdateCheckFeature.checkForUpdateAsync(result -> onUpdateCheckResult(result, btn));
                }).bounds(contentX, y, half, 20).build());

        widgets.add(SettingsButtonWidget.builder(Component.literal("Join Discord"), btn ->
                    ExternalOpen.uri(DISCORD_INVITE_URL)
                ).bounds(contentX + half + gap, y, rightW, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(updateNotifyText(), btn -> {
                    var ucfg = com.killer560.hub.updatecheck.UpdateCheckConfig.getInstance();
                    ucfg.setNotifyOnStart(!ucfg.isNotifyOnStart());
                    ucfg.save();
                    btn.setMessage(updateNotifyText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(skyblockOnlyText(), btn -> {
                    com.killer560.hub.util.SkyblockGate.setEnabled(!com.killer560.hub.util.SkyblockGate.isEnabled());
                    btn.setMessage(skyblockOnlyText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 30;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("HUD", false), font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Edit HUD Positions"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new HudEditorScreen(McCompat.screen(client)));
                }).bounds(contentX, y, half, 20).build());

        Component keybindLabel = listening
                ? Component.literal("Press any key...")
                : keybindText();
        widgets.add(SettingsButtonWidget.builder(keybindLabel, btn -> {
                    listening = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX + half + gap, y, rightW, 20).build());
        y += 26;

        // Global HUD scale (killer560, 2026-10-04, "just like SkyHanni"): 5%..300% in 5% steps, multiplied onto every
        // element's own scale - see HudConfig#globalScale. Saved in killer560smod-hud.json, so profiles carry it.
        float span = HudConfig.MAX_GLOBAL_SCALE - HudConfig.MIN_GLOBAL_SCALE;
        double normalized = (HudConfig.getInstance().getGlobalScale() - HudConfig.MIN_GLOBAL_SCALE) / span;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 20, globalScaleText(), normalized) {
            @Override
            protected void updateMessage() {
                setMessage(globalScaleText());
            }

            @Override
            protected void applyValue() {
                HudConfig cfg = HudConfig.getInstance();
                cfg.setGlobalScale(HudConfig.MIN_GLOBAL_SCALE + (float) this.value * span);
                cfg.save();
            }
        });
        y += 26;

        // Auto Scale (monitor), killer560 2026-10-05: ON by default. Sizes the HUD AND the mod's menus to the window
        // relative to his 2560x1440 / GUI 3 monitor (see hud/AutoScale); the HUD Scale slider above multiplies on
        // top. The factor in use is shown so a "why is it smaller here" has its answer on the button. Toggling it
        // re-lays this very menu out on its next frame (AutoScaleScreenMixin), which rebuilds this widget too.
        widgets.add(SettingsButtonWidget.builder(autoScaleText(), btn -> {
                    HudConfig cfg = HudConfig.getInstance();
                    cfg.setAutoScale(!cfg.isAutoScale());
                    cfg.save();
                    btn.setMessage(autoScaleText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        return widgets;
    }

    private static Component autoScaleText() {
        if (!HudConfig.getInstance().isAutoScale()) {
            return Component.literal("Auto Scale (monitor): §cOFF");
        }
        float f = com.killer560.hub.hud.AutoScale.current();
        return Component.literal("Auto Scale (monitor): §aON §7(" + Math.round(f * 100) + "% here)");
    }

    private static Component globalScaleText() {
        return Component.literal("HUD Scale: §b" + Math.round(HudConfig.getInstance().getGlobalScale() * 100) + "%");
    }

    private static Component updateNotifyText() {
        return Component.literal("Notify Me Of Updates: "
                + (com.killer560.hub.updatecheck.UpdateCheckConfig.getInstance().isNotifyOnStart() ? "§aON" : "§cOFF"));
    }

    private static Component skyblockOnlyText() {
        return Component.literal("Skyblock Only: " + (com.killer560.hub.util.SkyblockGate.isEnabled() ? "§aON" : "§cOFF"));
    }

    private void onUpdateCheckResult(UpdateCheckFeature.Result result, SettingsButtonWidget btn) {
        checkingForUpdate = false;
        if (result.error() != null) {
            lastCheckResultText = "§cCheck Failed - Click to Retry";
            ModOverlayMessage.show("§c[Killer560's Mod] Update check failed", 3000);
        } else if (result.updateAvailable()) {
            availableUpdateVersion = result.remoteVersion();
            availableUpdateUrl = result.releaseUrl();
            ModOverlayMessage.show("[Killer560's Mod] Update available: v" + result.remoteVersion(), 5000);
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
        // Escape unbinds (-1), same convention as every other KeyCaptureTab - it used to bind Escape itself.
        HudConfig.getInstance().setEditKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
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

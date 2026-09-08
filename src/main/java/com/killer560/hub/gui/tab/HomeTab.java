package com.killer560.hub.gui.tab;

import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.hud.HudEditorScreen;
import com.killer560.hub.nofire.NoFireConfig;
import com.killer560.hub.window.WindowModeConfig;
import com.killer560.hub.window.WindowModeFeature;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Landing tab: feature overview and mod-wide settings (HUD editor keybind and shortcut) - merged
 *  in from the old separate "Main" tab. The RNG Meter's Hypixel API key is no longer user-entered
 *  here - see {@link com.killer560.hub.rngmeter.HypixelApiKeyProvider}. */
public class HomeTab extends BaseTab implements KeyCaptureTab {

    private boolean listening = false;

    public HomeTab() {
        super("Home");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("- Account Switcher: \"Swap Accounts\" button on the main menu"),
                Minecraft.getInstance().font));
        y += 16;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("- Proxy Client: \"Proxy: Enabled/Disabled\" button on the multiplayer screen"),
                Minecraft.getInstance().font));
        y += 32;

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

        widgets.add(SettingsButtonWidget.builder(noFireText(), btn -> {
                    NoFireConfig cfg = NoFireConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(noFireText());
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
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

    private static Component noFireText() {
        return Component.literal("No Fire Overlay: "
                + (NoFireConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component keybindText() {
        int code = HudConfig.getInstance().getEditKeyCode();
        String name = code < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal("Edit HUD Keybind: §b" + name);
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.profileviewer.ProfileViewerConfig;
import com.killer560.hub.profileviewer.ProfileViewerFeature;
import com.killer560.hub.profileviewer.screen.ProfileViewerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Profile Viewer settings - see {@link ProfileViewerFeature}. Not a toggleable feature (the /pv command
 *  is always available), so this tab is just the data source, API key, keybind and display options. */
public class ProfileViewerTab extends BaseTab implements KeyCaptureTab {

    private boolean capturingKey = false;

    public ProfileViewerTab() {
        super("Profile Viewer");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ProfileViewerConfig cfg = ProfileViewerConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open My Profile"), btn -> {
                    mc.setScreen(new ProfileViewerScreen(mc.screen, ProfileViewerFeature.self()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        widgets.add(SettingsButtonWidget.builder(sourceText(cfg), btn -> {
                    cfg.setSource(cfg.getSource().next());
                    cfg.save();
                    btn.setMessage(sourceText(cfg));
                }).bounds(contentX, y, colW, 18).build());

        Component keyLabel = capturingKey ? Component.literal("Press any key...") : keyText(cfg);
        widgets.add(SettingsButtonWidget.builder(keyLabel, btn -> {
                    capturingKey = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(colBX, y, colW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Remember Last Page", cfg.isRememberLastPage()), btn -> {
                    cfg.setRememberLastPage(!cfg.isRememberLastPage());
                    cfg.save();
                    btn.setMessage(onOff("Remember Last Page", cfg.isRememberLastPage()));
                }).bounds(contentX, y, colW, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Show Skin", cfg.isShowSkin()), btn -> {
                    cfg.setShowSkin(!cfg.isShowSkin());
                    cfg.save();
                    btn.setMessage(onOff("Show Skin", cfg.isShowSkin()));
                }).bounds(colBX, y, colW, 18).build());
        y += 28;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("Hypixel API Key"), mc.font));
        y += 13;

        int clearW = 50;
        EditBox keyBox = new EditBox(mc.font, contentX, y, contentWidth - clearW - 4, 18, Component.literal("Hypixel API Key"));
        keyBox.setMaxLength(64);
        keyBox.setHint(Component.literal("Paste key..."));
        // Masked: the key is drawn as asterisks and never shown in clear text.
        keyBox.addFormatter((text, offset) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        keyBox.setValue(cfg.getApiKey());
        keyBox.setResponder(text -> {
            String clean = ProfileViewerConfig.sanitizeKey(text);
            if (!clean.equals(cfg.getApiKey())) {
                cfg.setApiKey(clean);
                cfg.save();
            }
        });
        widgets.add(keyBox);

        widgets.add(SettingsButtonWidget.builder(Component.literal("Clear"), btn -> {
                    cfg.setApiKey("");
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + contentWidth - clearW, y, clearW, 18).build());

        return widgets;
    }

    private static Component sourceText(ProfileViewerConfig cfg) {
        return Component.literal("Source: §b" + cfg.getSource().label);
    }

    private static Component keyText(ProfileViewerConfig cfg) {
        String name = cfg.getOpenKeyCode() < 0 ? "Not Set"
                : InputConstants.Type.KEYSYM.getOrCreate(cfg.getOpenKeyCode()).getDisplayName().getString();
        return Component.literal("Open Key: §b" + name);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public boolean isListeningForKey() {
        return capturingKey;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        ProfileViewerConfig cfg = ProfileViewerConfig.getInstance();
        cfg.setOpenKeyCode(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
        capturingKey = false;
        cfg.save();
    }
}

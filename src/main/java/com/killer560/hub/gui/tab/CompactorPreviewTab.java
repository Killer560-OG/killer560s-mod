package com.killer560.hub.gui.tab;

import com.killer560.hub.compactorpreview.CompactorPreviewConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Compactor Preview settings - see {@link com.killer560.hub.compactorpreview.CompactorPreviewFeature}: the master
 *  switch, show on hover or only while a key is held, and that key. */
public class CompactorPreviewTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing = false;

    public CompactorPreviewTab() {
        super("Compactor Preview");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        applyCapture(com.killer560.hub.util.KeyUtil.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        CompactorPreviewConfig cfg = CompactorPreviewConfig.getInstance();
        cfg.setKeyCode(code);
        capturing = false;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        CompactorPreviewConfig cfg = CompactorPreviewConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Compactor Preview", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.cycling(Component.literal("Show: " + cfg.getMode().label), (btn, dir) -> {
                    cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), dir));
                    cfg.save();
                    requestRebuild.run(); // the key button only shows in the key mode
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (cfg.getMode() == CompactorPreviewConfig.Mode.WHILE_HOLDING_KEY) {
            widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                        capturing = true;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Skyblock Only", cfg.isSkyblockOnly()), btn -> {
                    cfg.setSkyblockOnly(!cfg.isSkyblockOnly());
                    cfg.save();
                    btn.setMessage(onOff("Skyblock Only", cfg.isSkyblockOnly()));
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private Component keyText(CompactorPreviewConfig cfg) {
        if (capturing) {
            return Component.literal("Press any key...");
        }
        return Component.literal("Key: " + CommandKeybindsTab.bindName(cfg.getKeyCode()));
    }
}

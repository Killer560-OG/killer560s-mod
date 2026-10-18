package com.killer560.hub.gui.tab;

import com.killer560.hub.freelook.FreelookConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Freelook settings: the switch, Hold/Toggle, and the pan key. See {@link com.killer560.hub.freelook.FreelookFeature}. */
public class FreelookTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing;

    public FreelookTab() {
        super("Freelook");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        apply(keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    @Override
    public void onMouseCaptured(int button) {
        apply(KeyUtil.codeForMouseButton(button));
    }

    private void apply(int code) {
        FreelookConfig cfg = FreelookConfig.getInstance();
        cfg.setPanKeyCode(code);
        cfg.save();
        capturing = false;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        FreelookConfig cfg = FreelookConfig.getInstance();
        int y = contentY;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Freelook: " + (cfg.isEnabled() ? "§aON" : "§cOFF")), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        if (!cfg.isEnabled()) {
            return widgets;
        }
        widgets.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), dir));
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                    capturing = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 20).build());
        return widgets;
    }

    private static Component modeText(FreelookConfig cfg) {
        return Component.literal("Mode: " + cfg.getMode().label);
    }

    private Component keyText(FreelookConfig cfg) {
        if (capturing) {
            return Component.literal("Press any key...");
        }
        int code = cfg.getPanKeyCode();
        return Component.literal("Pan Key: " + (code == KeyUtil.NONE ? "Not Set" : KeyUtil.bindDisplayName(code)));
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.peekchat.PeekChatConfig;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Peek Chat settings: the switch and its key. See {@link com.killer560.hub.peekchat.PeekChatFeature}. */
public class PeekChatTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing;

    public PeekChatTab() {
        super("Peek Chat");
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
        PeekChatConfig cfg = PeekChatConfig.getInstance();
        cfg.setKeyCode(code);
        cfg.save();
        capturing = false;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PeekChatConfig cfg = PeekChatConfig.getInstance();
        int y = contentY;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Peek Chat: " + (cfg.isEnabled() ? "§aON" : "§cOFF")), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                    capturing = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;
        // Toggle Peek (press to open, press again to close) or Hold to Peek (open while the key is held).
        widgets.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.setHoldToPeek(!cfg.isHoldToPeek());
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        return widgets;
    }

    private static Component modeText(PeekChatConfig cfg) {
        return Component.literal("Peek Mode: §b" + (cfg.isHoldToPeek() ? "Hold To Peek" : "Toggle Peek"));
    }

    private Component keyText(PeekChatConfig cfg) {
        if (capturing) {
            return Component.literal("Press any key...");
        }
        int code = cfg.getKeyCode();
        return Component.literal("Peek Chat Key: " + (code == KeyUtil.NONE ? "Not Set" : KeyUtil.bindDisplayName(code)));
    }
}

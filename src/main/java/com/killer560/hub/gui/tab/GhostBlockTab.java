package com.killer560.hub.gui.tab;

import com.killer560.hub.ghostblock.GhostBlockConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.KeyUtil;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Ghost Block (cheat only): the switch and the hold key. See {@link com.killer560.hub.ghostblock.GhostBlockFeature}. */
public class GhostBlockTab extends BaseTab implements KeyCaptureTab {

    private boolean capturing;

    public GhostBlockTab() {
        super("Ghost Block");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public boolean isListeningForKey() {
        return capturing;
    }

    @Override
    public void cancelKeyCapture() {
        capturing = false;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        apply(keyCode == com.killer560.hub.util.Keys.ESCAPE ? KeyUtil.NONE : keyCode);
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
        GhostBlockConfig cfg = GhostBlockConfig.getInstance();
        cfg.setKeyCode(code);
        cfg.save();
        capturing = false;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        GhostBlockConfig cfg = GhostBlockConfig.getInstance();
        widgets.add(onOffButton("Ghost Block", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, contentY, contentWidth, 20));
        if (!cfg.isEnabled() && !isSearchScan()) {
            return widgets;
        }
        widgets.add(SettingsButtonWidget.builder(keyText(cfg), btn -> {
                    capturing = true;
                    btn.setMessage(Component.literal("Press any key..."));
                }).bounds(contentX, contentY + 26, contentWidth, 20).build());
        return widgets;
    }

    private Component keyText(GhostBlockConfig cfg) {
        int code = cfg.getKeyCode();
        return Component.literal(capturing ? "Press any key..."
                : "Hold Key: " + (code == KeyUtil.NONE ? "Not Set" : KeyUtil.bindDisplayName(code)));
    }
}

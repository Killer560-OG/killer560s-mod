package com.killer560.hub.gui.tab;

import com.killer560.hub.architect.ArchitectDraftConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Architect's First Draft - see {@link com.killer560.hub.architect.ArchitectDraftFeature}. The Auto Get row only
 *  exists on the cheat build (red label). */
public class ArchitectDraftTab extends BaseTab {

    public ArchitectDraftTab() {
        super("Architect's First Draft");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ArchitectDraftConfig cfg = ArchitectDraftConfig.getInstance();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(onOff("Click Message On Puzzle Fail", cfg.isClickMessageRaw()), btn -> {
                    cfg.setClickMessage(!cfg.isClickMessageRaw());
                    cfg.save();
                    btn.setMessage(onOff("Click Message On Puzzle Fail", cfg.isClickMessageRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            w.add(SettingsButtonWidget.builder(onOff("§cAuto Get From Sack", cfg.isAutoGetRaw()), btn -> {
                        cfg.setAutoGet(!cfg.isAutoGetRaw());
                        cfg.save();
                        btn.setMessage(onOff("§cAuto Get From Sack", cfg.isAutoGetRaw()));
                    }).bounds(contentX, y, contentWidth, 20).build());
        }
        return w;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

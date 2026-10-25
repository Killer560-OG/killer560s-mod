package com.killer560.hub.gui.tab;

import com.killer560.hub.architect.ArchitectDraftConfig;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;

import java.util.ArrayList;
import java.util.List;

/** Architect's First Draft - see {@link com.killer560.hub.architect.ArchitectDraftFeature}. The Auto Get row only
 *  exists on the cheat build, at the bottom under the red Cheat header (killer560, 2026-10-14). */
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
            w.add(new StringWidget(contentX, y, contentWidth, 12,
                    SectionHeaders.header("Cheat Build - Automation", true), Minecraft.getInstance().font));
            y += 16;
            w.add(SettingsButtonWidget.builder(onOff("Auto Get From Sack", cfg.isAutoGetRaw()), btn -> {
                        cfg.setAutoGet(!cfg.isAutoGetRaw());
                        cfg.save();
                        btn.setMessage(onOff("Auto Get From Sack", cfg.isAutoGetRaw()));
                    }).bounds(contentX, y, contentWidth, 20).build());
        }
        return w;
    }
}

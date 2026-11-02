package com.killer560.hub.gui.tab;

import com.killer560.hub.architect.ArchitectDraftConfig;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Architect's First Draft - see {@link com.killer560.hub.architect.ArchitectDraftFeature}. The Auto Get row only
 *  exists on the cheat build, at the bottom under the red Cheat header (killer560, 2026-10-14). */
public class ArchitectDraftTab extends BaseTab {

    public ArchitectDraftTab() {
        super("Architect's First Draft");
    }

    /** The click message and Auto Get each run alone (ArchitectDraftFeature checks either). Several independent features side by side, so the card is ON while any is on
     *  (CLAUDE.md masterToggles rule; menufix-1102 sweep). */
    @Override
    public java.util.List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(
            java.util.List<net.minecraft.client.gui.components.AbstractWidget> built) {
        return switchRows(built, "Click Message On Puzzle Fail", "Auto Get From Sack");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ArchitectDraftConfig cfg = ArchitectDraftConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Click Message On Puzzle Fail", cfg::isClickMessageRaw, cfg::setClickMessage, cfg::save,
                null, contentX, y, contentWidth, 20));
        y += 24;
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Automation", true));
            y += 16;
            w.add(onOffButton("Auto Get From Sack", cfg::isAutoGetRaw, cfg::setAutoGet, cfg::save,
                    null, contentX, y, contentWidth, 20));
        }
        return w;
    }
}

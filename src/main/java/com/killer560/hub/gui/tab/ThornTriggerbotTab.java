package com.killer560.hub.gui.tab;

import com.killer560.hub.thorn.ThornConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Thorn Triggerbot (cheat build only) - holds right click with the Tribal Spear in the F4/M4 Thorn fight; see
 *  {@link com.killer560.hub.thorn.ThornTriggerbot}. Its own card in the Dungeon cheat block (killer560, 2026-11-01). */
public class ThornTriggerbotTab extends BaseTab {

    public ThornTriggerbotTab() {
        super("Thorn Triggerbot");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        ThornConfig cfg = ThornConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Thorn Triggerbot", cfg::isTriggerbotRaw, cfg::setTriggerbot, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        if (!cfg.isTriggerbotRaw()) {
            return w;
        }
        w.add(onOffButton("Only When Aimed At Thorn", cfg::isTriggerbotAimed, cfg::setTriggerbotAimed, cfg::save, null,
                contentX, y, contentWidth, 20));
        return w;
    }
}

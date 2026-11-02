package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.splittimers.BloodRushSplitsConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Split Timers' "Blood Rush" section ({@link com.killer560.hub.splittimers.BloodRushSplits}), kept in its own file. */
final class BloodRushSplitsRows {

    private BloodRushSplitsRows() {
    }

    /** Adds the section at {@code y}. @return the y under it */
    static int add(List<AbstractWidget> widgets, int contentX, int y, int contentWidth, Runnable requestRebuild) {
        BloodRushSplitsConfig cfg = BloodRushSplitsConfig.getInstance();
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Blood Rush", false));
        y += 14;
        widgets.add(BaseTab.onOffButton("Blood Rush Splits", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, 220, 18));
        y += 22;
        if (cfg.isEnabled()) {
            widgets.add(SettingsButtonWidget.cycling(styleLabel(cfg), (btn, dir) -> {
                        BloodRushSplitsConfig.Style[] all = BloodRushSplitsConfig.Style.values();
                        int i = Math.floorMod(cfg.getStyle().ordinal() + (dir < 0 ? -1 : 1), all.length);
                        cfg.setStyle(all[i]);
                        cfg.save();
                        btn.setMessage(styleLabel(cfg));
                    }).bounds(contentX, y, 160, 18).build());
            widgets.add(BaseTab.onOffButton("Rush To Chat", cfg::isAnnounceInChat, cfg::setAnnounceInChat, cfg::save,
                    null, contentX + 168, y, 160, 18));
            y += 22;
        }
        return y + 4;
    }

    private static Component styleLabel(BloodRushSplitsConfig cfg) {
        return Component.literal("Rush Style: §6" + cfg.getStyle().label());
    }
}

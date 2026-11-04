package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Dark Stone (Mining): see {@code com.killer560.hub.miningqol.DarkStone}. */
public class MiningDarkStoneTab extends BaseTab {

    public MiningDarkStoneTab() {
        super("Dark Stone");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Dark Stone", () -> cfg.isDarkStoneRaw(), cfg::setDarkStone, cfg::save, requestRebuild, contentX, y,
                contentWidth, 20));
        y += 24;
        if (cfg.isDarkStoneRaw() || isSearchScan()) {
            w.add(steppedSlider(contentX, y, contentWidth, 20, levelText(cfg), 10, 90, cfg.getDarkStoneLevel(), 5,
                    v -> cfg.setDarkStoneLevel((int) Math.round(v)), () -> levelText(cfg), cfg::save));
            y += 24;
            w.add(onOffButton("Darken Mithril Too", cfg::isDarkStoneMithril, cfg::setDarkStoneMithril, cfg::save,
                    requestRebuild, contentX, y, contentWidth, 20));
        }
        return w;
    }

    private static Component levelText(MiningQolConfig cfg) {
        return Component.literal("Darkness: " + cfg.getDarkStoneLevel() + "%");
    }
}

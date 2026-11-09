package com.killer560.hub.gui.tab;

import com.killer560.hub.itemhighlight.DungeonItemHighlightConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Salvage Helper and the sell highlight ({@link com.killer560.hub.itemhighlight.DungeonItemHighlighter}). */
public class DungeonItemHighlighterTab extends BaseTab {

    public DungeonItemHighlighterTab() {
        super("Salvage Helper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        DungeonItemHighlightConfig cfg = DungeonItemHighlightConfig.getInstance();
        int y = contentY;
        if (masterClosed(w, "Salvage Helper", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return w;
        }
        y += 24;
        w.add(onOffButton("Highlight In Salvage Menu", cfg::isSalvage, cfg::setSalvage, cfg::save, null,
                contentX, y, contentWidth, 20));
        y += 24;
        w.add(onOffButton("Highlight In Trades Menu", cfg::isSell, cfg::setSell, cfg::save, null,
                contentX, y, contentWidth, 20));
        y += 24;
        w.add(SettingsButtonWidget.cycleEnum(() -> Component.literal("Max Item Value: " + cfg.getLimit().label()),
                cfg::getLimit, cfg::setLimit, cfg::save).bounds(contentX, y, contentWidth, 20).build());
        return w;
    }
}

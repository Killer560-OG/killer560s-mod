package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonbreaker.DungeonBreakerConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Dungeon Breaker settings: the secret guard. */
public class DungeonBreakerTab extends BaseTab {

    public DungeonBreakerTab() {
        super("Dungeon Breaker");
    }

    /** Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        DungeonBreakerConfig cfg = DungeonBreakerConfig.getInstance();

        widgets.add(onOffButton("Don't break secrets", cfg::isNoBreakSecrets, cfg::setNoBreakSecrets, cfg::save,
                null, contentX, contentY, contentWidth, 20));
        return widgets;
    }
}

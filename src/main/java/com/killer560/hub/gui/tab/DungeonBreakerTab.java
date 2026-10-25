package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonbreaker.DungeonBreakerConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** "0 Ping Dungeon Breaker" settings - see
 *  {@link com.killer560.hub.dungeonbreaker.DungeonBreakerFeature}'s class doc for the real QUOI-ported
 *  mechanic this is built on, and its own scope note on what's deliberately not included yet. */
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
        int y = contentY;
        DungeonBreakerConfig cfg = DungeonBreakerConfig.getInstance();

        widgets.add(onOffButton("Zero Ping", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        // Offered with Zero Ping off too: the guard also covers Breaker Aura and breaker nodes.
        widgets.add(onOffButton("Don't break secrets", cfg::isNoBreakSecrets, cfg::setNoBreakSecrets, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 24;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Fatigue Only", cfg::isFatigueOnly, cfg::setFatigueOnly, cfg::save,
                null, contentX, y, contentWidth, 18));

        return widgets;
    }
}

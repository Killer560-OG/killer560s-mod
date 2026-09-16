package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonbreaker.DungeonBreakerConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** "0 Ping Dungeon Breaker" settings - see
 *  {@link com.killer560.hub.dungeonbreaker.DungeonBreakerFeature}'s class doc for the real QUOI-ported
 *  mechanic this is built on, and its own scope note on what's deliberately not included yet. */
public class DungeonBreakerTab extends BaseTab {

    public DungeonBreakerTab() {
        super("Dungeon Breaker");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DungeonBreakerConfig cfg = DungeonBreakerConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Zero Ping", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Fatigue Only", cfg.isFatigueOnly()), btn -> {
                    cfg.setFatigueOnly(!cfg.isFatigueOnly());
                    cfg.save();
                    btn.setMessage(onOff("Fatigue Only", cfg.isFatigueOnly()));
                }).bounds(contentX, y, contentWidth, 18).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

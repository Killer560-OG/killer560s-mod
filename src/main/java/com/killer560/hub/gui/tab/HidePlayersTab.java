package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Hide Players - stops other players rendering, optionally only past a distance, only in a dungeon, or
 *  only in a boss room. Its own section of the Hud Elements folder since 2026-09-30 ("Make the hide
 *  players its own section"); it was the last header inside Object Hider before that.
 *  <p>
 *  Presentation only - the toggles and the distance slider still read and write the SAME
 *  {@link ObjectHiderConfig} fields under the same JSON keys, so an existing config keeps whatever was set. */
public class HidePlayersTab extends BaseTab {

    private static final int MIN_DISTANCE = 0;
    private static final int MAX_DISTANCE = 128;

    public HidePlayersTab() {
        super("Hide Players");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        int[] y = {contentY};

        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Players", cfg::getHidePlayersRaw, cfg::setHidePlayers);
        if (cfg.getHidePlayersRaw()) {
            widgets.add(new ThemedSliderButton(contentX + 12, y[0], contentWidth - 12, 18,
                    distanceText(cfg),
                    (cfg.getHidePlayersDistance() - MIN_DISTANCE) / (double) (MAX_DISTANCE - MIN_DISTANCE)) {
                @Override
                protected void updateMessage() {
                    setMessage(distanceText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setHidePlayersDistance(
                            (int) Math.round(MIN_DISTANCE + this.value * (MAX_DISTANCE - MIN_DISTANCE)));
                    cfg.save();
                }
            });
            y[0] += 22;
            ObjectHiderTab.toggle(widgets, contentX + 12, contentWidth - 12, y, cfg, requestRebuild,
                    "Dungeon Only", cfg::isHidePlayersDungeonOnly, cfg::setHidePlayersDungeonOnly);
            ObjectHiderTab.toggle(widgets, contentX + 12, contentWidth - 12, y, cfg, requestRebuild,
                    "Boss Only", cfg::isHidePlayersBossOnly, cfg::setHidePlayersBossOnly);
        }

        return widgets;
    }

    private static Component distanceText(ObjectHiderConfig cfg) {
        int distance = cfg.getHidePlayersDistance();
        return Component.literal("Distance: " + (distance <= 0 ? "No Limit" : distance + " blocks"));
    }
}

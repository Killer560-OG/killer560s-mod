package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ModuleKeybinds;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mapcycler.MapCyclerConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Map Cycler (roomcycle-1118): re-rolls the dungeon until Blood is at most N rooms from the Entrance. Cheat build only. */
public class MapCyclerTab extends BaseTab {

    public static final String NAME = "Map Cycler";

    public MapCyclerTab() {
        super(NAME);
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public String cardDescription() {
        return "Re-rolls long maps.";
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, NAME);
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        MapCyclerConfig cfg = MapCyclerConfig.getInstance();
        if (masterClosed(w, NAME, cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY, contentWidth)) {
            return w;
        }
        int y = contentY + 26;
        w.add(valueSlider(contentX, y, contentWidth, "Max Rooms Before Blood", " rooms", MapCyclerConfig.MIN_ROOMS,
                MapCyclerConfig.MAX_ROOMS, 1, 0, cfg::getMaxRooms, v -> cfg.setMaxRooms((int) Math.round(v)), cfg::save));
        y += 22;
        w.add(valueSlider(contentX, y, contentWidth, "Keep Chance (F7)", "%", MapCyclerConfig.MIN_KEEP_CHANCE,
                MapCyclerConfig.MAX_KEEP_CHANCE, 1, 0, cfg::getKeepChance, v -> cfg.setKeepChance((int) Math.round(v)), cfg::save));
        y += 22;
        w.add(valueSlider(contentX, y, contentWidth, "Max Re-rolls (0 = no limit)", "", 0, MapCyclerConfig.MAX_REROLLS_CAP,
                1, 0, cfg::getMaxRerolls, v -> cfg.setMaxRerolls((int) Math.round(v)), cfg::save));
        y += 22;
        w.add(ModuleKeybinds.row(ModuleKeybinds.MAP_CYCLER_RUN_ID, contentX, y, contentWidth));
        return w;
    }
}

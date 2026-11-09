package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonqol.DungeonQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Current Room Name HUD switch - see {@link com.killer560.hub.dungeonqol.RoomNameHud}. */
public class RoomNameTab extends BaseTab {

    public RoomNameTab() {
        super("Current Room Name");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        DungeonQolConfig cfg = DungeonQolConfig.getInstance();
        List<AbstractWidget> widgets = new ArrayList<>();
        widgets.add(onOffButton("Current Room Name", cfg::isRoomHud, cfg::setRoomHud, cfg::save,
                null, contentX, contentY, contentWidth, 20));
        return widgets;
    }
}

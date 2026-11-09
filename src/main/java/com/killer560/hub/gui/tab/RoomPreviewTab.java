package com.killer560.hub.gui.tab;

import com.killer560.hub.livemap.RoomPreviewConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Room Preview (see {@link com.killer560.hub.livemap.RoomPreview}): just the switch. */
public class RoomPreviewTab extends BaseTab {

    public RoomPreviewTab() {
        super("Room Preview");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        RoomPreviewConfig cfg = RoomPreviewConfig.getInstance();
        masterClosed(widgets, "Room Preview", cfg::getEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild, contentX, contentY, contentWidth);
        return widgets;
    }
}

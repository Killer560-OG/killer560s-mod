package com.killer560.hub.gui.tab;

import com.killer560.hub.revertmasterstars.RevertMasterStarsConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Revert Master Stars settings - see
 *  {@link com.killer560.hub.revertmasterstars.RevertMasterStarsFeature}'s class doc for the real
 *  QUOI-ported star-display revert this is built on. */
public class RevertMasterStarsTab extends BaseTab {

    public RevertMasterStarsTab() {
        super("Revert Master Stars");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        RevertMasterStarsConfig cfg = RevertMasterStarsConfig.getInstance();

        widgets.add(onOffButton("Revert Master Stars", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        return widgets;
    }
}

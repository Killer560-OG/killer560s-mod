package com.killer560.hub.gui.tab;

import com.killer560.hub.fastleap.FastLeapConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** No Rotate (killer560, 2026-11-09: "call leap look no rotate and make it its own cheat setting"): the former Fast/Auto
 *  Leap row "Leap Takes Target's Look". Same config key ({@code leapTakesTargetLook} in FastLeapConfig), so a saved ON stays. */
public class NoRotateTab extends BaseTab {

    public static final String NAME = "No Rotate";
    private static final int ROW = 18;

    public NoRotateTab() {
        super(NAME);
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public String cardDescription() {
        return "Leaps keep your look.";
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
        FastLeapConfig cfg = FastLeapConfig.getInstance();
        w.add(onOffButton(NAME, cfg::isLeapTakesTargetLook, cfg::setLeapTakesTargetLook, cfg::save, null,
                contentX, contentY, contentWidth, ROW));
        return w;
    }
}

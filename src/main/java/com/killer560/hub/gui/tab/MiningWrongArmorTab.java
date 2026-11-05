package com.killer560.hub.gui.tab;

import com.killer560.hub.miningqol.MiningQolConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Wrong Armor Alert (Mining): see {@link com.killer560.hub.miningqol.WrongArmorAlert}. */
public class MiningWrongArmorTab extends BaseTab {

    public MiningWrongArmorTab() {
        super("Wrong Armor Alert");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("Wrong Armor Alert", cfg::isWrongArmorRaw, cfg::setWrongArmor, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Alert Sound", cfg::isWrongArmorSound, cfg::setWrongArmorSound,
                cfg::save);
        y += MiningQolRows.STEP;
        // Independent of the armor alert: its own switch, so the card is ON while either is (masterToggles below).
        w.add(onOffButton("Jungle Pickaxe Alert", cfg::isJunglePickRaw, cfg::setJunglePick, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += MiningQolRows.STEP;
        MiningQolRows.toggle(w, contentX, y, contentWidth, "Jungle Alert Sound", cfg::isJunglePickSound,
                cfg::setJunglePickSound, cfg::save);
        return w;
    }
}

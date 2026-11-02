package com.killer560.hub.gui.tab;

import com.killer560.hub.creationcooldown.CreationCooldownConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Creation Cooldown ({@link com.killer560.hub.creationcooldown.CreationCooldownFeature}): the HUD and Block Instance
 *  Creation are two features with a switch each (SkyHanni's two feature toggles), so the card is ON while either is. */
public class CreationCooldownTab extends BaseTab {

    public CreationCooldownTab() {
        super("Creation Cooldown");
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Creation Cooldown", "Block Instance Creation");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CreationCooldownConfig cfg = CreationCooldownConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(onOffButton("Creation Cooldown", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (cfg.isEnabledRaw()) {
            widgets.add(onOffButton("Show On All Islands", cfg::isShowOnAllIslands, cfg::setShowOnAllIslands, cfg::save,
                    null, contentX, y, colW, 18));
            widgets.add(onOffButton("Only In Green Room", cfg::isOnlyInGreenRoom, cfg::setOnlyInGreenRoom, cfg::save,
                    null, colBX, y, colW, 18));
            y += 20;
            widgets.add(onOffButton("Chat When Over", cfg::isChatWhenOver, cfg::setChatWhenOver, cfg::save,
                    null, contentX, y, contentWidth, 18));
            y += 24;
        }
        widgets.add(onOffButton("Block Instance Creation", cfg::isBlockCreation, cfg::setBlockCreation, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        return widgets;
    }
}

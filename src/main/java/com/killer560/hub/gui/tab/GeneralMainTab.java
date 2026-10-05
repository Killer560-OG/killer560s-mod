package com.killer560.hub.gui.tab;

import com.killer560.hub.autojoinskyblock.AutoJoinSkyblockConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** General server/session behavior - a home for features that don't fit the more specific
 *  categories, starting with Auto Join Skyblock (2026-09-08). Since 2026-10-04 this is the pinned top of the
 *  {@link GeneralTab} folder (it was the whole General tab before Etherwarp Overlay moved in); it keeps the
 *  name "General" so its tooltip scope is unchanged. */
public class GeneralMainTab extends BaseTab {

    public GeneralMainTab() {
        super("General");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(autoJoinSkyblockText(), btn -> {
                    AutoJoinSkyblockConfig cfg = AutoJoinSkyblockConfig.getInstance();
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(autoJoinSkyblockText());
                }).bounds(contentX, y, 220, 20).build());
        y += 30;

        return widgets;
    }

    private static Component autoJoinSkyblockText() {
        return Component.literal("Auto Join Skyblock: "
                + (AutoJoinSkyblockConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }
}

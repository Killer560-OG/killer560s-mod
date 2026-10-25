package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.p3status.P3StatusConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** P3 Status (2026-10-25): the F7/M7 Phase 3 status HUD and title rewrite. See
 *  {@link com.killer560.hub.p3status.P3StatusFeature}. The switch is the first row; every part under it defaults ON. */
public class P3StatusTab extends BaseTab {

    public P3StatusTab() {
        super("P3 Status");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        P3StatusConfig cfg = P3StatusConfig.getInstance();
        int[] y = {contentY};
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild, "P3 Status", cfg::getEnabledRaw, cfg::setEnabled);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild, "Status HUD", cfg::getShowHudRaw, cfg::setShowHud);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Show Who Is At Each", cfg::getShowWhoIsAtRaw, cfg::setShowWhoIsAt);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Short Progress Titles", cfg::getRewriteTitlesRaw, cfg::setRewriteTitles);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Device Done Title", cfg::getDeviceDoneTitleRaw, cfg::setDeviceDoneTitle);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Section Complete Title", cfg::getSectionCompleteTitleRaw, cfg::setSectionCompleteTitle);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Energy Laser Subtitle", cfg::getHideLaserSubtitleRaw, cfg::setHideLaserSubtitle);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild, "Title Sound", cfg::getTitleSoundRaw, cfg::setTitleSound);
        return widgets;
    }

    private static void toggle(List<AbstractWidget> widgets, int x, int width, int[] y, P3StatusConfig cfg,
                               Runnable requestRebuild, String label, BooleanSupplier get, Consumer<Boolean> set) {
        widgets.add(SettingsButtonWidget.builder(Component.literal(label + ": " + (get.getAsBoolean() ? "§aON" : "§cOFF")), btn -> {
                    set.accept(!get.getAsBoolean());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
    }
}

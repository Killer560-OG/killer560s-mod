package com.killer560.hub.gui.tab;

import com.killer560.hub.autoreclaim.AutoReclaimConfig;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Reclaim: one switch; see {@link com.killer560.hub.autoreclaim.AutoReclaimFeature}. */
public class AutoReclaimTab extends BaseTab {

    public AutoReclaimTab() {
        super("Auto Reclaim");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        AutoReclaimConfig cfg = AutoReclaimConfig.getInstance();
        masterClosed(widgets, "Auto Reclaim", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, contentY, contentWidth);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED && (cfg.isEnabled() || isSearchScan())) {
            int y = contentY + 28;
            widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Automation", true));
            widgets.add(onOffButton("Pull And Reclaim", cfg::isPullAndReclaim, cfg::setPullAndReclaim, cfg::save,
                    null, contentX, y + 16, contentWidth, 20));
        }
        return widgets;
    }
}

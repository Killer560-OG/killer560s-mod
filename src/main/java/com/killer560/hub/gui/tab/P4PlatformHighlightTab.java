package com.killer560.hub.gui.tab;

import com.killer560.hub.p4platform.P4PlatformHighlightConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** P4 Platform Highlight settings - see
 *  {@link com.killer560.hub.p4platform.P4PlatformHighlightFeature}'s class doc for the real QUOI-ported
 *  "highlight the 3x3 to mine after Goldor dies" this is built on. */
public class P4PlatformHighlightTab extends BaseTab {

    public P4PlatformHighlightTab() {
        super("P4 Platform Highlight");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        P4PlatformHighlightConfig cfg = P4PlatformHighlightConfig.getInstance();

        widgets.add(onOffButton("P4 Platform Highlight", cfg::isEnabled, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(onOffButton("Filled Box", cfg::isFilled, cfg::setFilled, cfg::save,
                null, contentX, y, contentWidth, 18));

        // No shared Solver Highlight section here (killer560, 2026-10-18): the 3x3 highlight is the only thing this tab draws,
        // and its own switch and Filled Box are the rows above.

        return widgets;
    }
}

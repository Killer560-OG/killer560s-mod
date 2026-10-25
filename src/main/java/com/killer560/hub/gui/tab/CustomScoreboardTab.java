package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.scoreboard.CustomScoreboardConfig;
import com.killer560.hub.scoreboard.ScoreboardEditorScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom Scoreboard settings - see {@link com.killer560.hub.scoreboard.CustomScoreboardFeature}.
 * <p>
 * Since 2026-10-11 the tab holds only the on/off switch and the button into {@link ScoreboardEditorScreen}, where the
 * board is edited by dragging its own lines (killer560: "make it so the main way of editing it is through that open
 * visual editor. The other things on that page should be removed."). The Lines/Events/Chunked Stats lists and the
 * General and Background pages that lived here are all in the editor now.
 */
public class CustomScoreboardTab extends BaseTab {

    public CustomScoreboardTab() {
        super("Custom Scoreboard");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Scoreboard", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Visual Editor"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ScoreboardEditorScreen(McCompat.screen(client)));
                }).primary().bounds(contentX, y, contentWidth, 20).build());
        // The themes (the board's and the editor's) moved into the Visual Editor's Settings page (killer560, 2026-10-18).
        return widgets;
    }
}

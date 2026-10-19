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
        com.killer560.hub.gui.ScreenThemes.append(widgets, ScoreboardEditorScreen.THEME_ID, contentX, contentWidth, y);
        // The board itself in game (killer560, 2026-10-18): its background, title band and border follow this theme. Default keeps
        // the colours on this tab. The Visual Editor's own theme above is separate and only changes that editor screen.
        int boardY = y;
        for (AbstractWidget w : widgets) {
            boardY = Math.max(boardY, w.getY() + w.getHeight() + 6);
        }
        widgets.add(com.killer560.hub.gui.OverlayThemes.button(com.killer560.hub.gui.OverlayThemes.CUSTOM_SCOREBOARD,
                contentX, boardY, 220, 20));
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

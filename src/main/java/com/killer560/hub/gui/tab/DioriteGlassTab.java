package com.killer560.hub.gui.tab;

import com.killer560.hub.diorite.DioriteGlassConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** "I Hate Diorite" settings - see {@link com.killer560.hub.diorite.DioriteGlassFeature}'s class doc for
 *  the real Noamm-ported Storm-pillar glass swap this is built on, and why no legit variant exists. */
public class DioriteGlassTab extends BaseTab {

    public DioriteGlassTab() {
        super("I Hate Diorite");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        DioriteGlassConfig cfg = DioriteGlassConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("I Hate Diorite", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());

        // In-panel description moved to the toggle's hover tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.DescriptionConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Descriptions (killer560, 2026-10-15): whether the feature cards show their one-line description, and whether the
 * HUD editor shows its hint lines. Both on by default; the long hover descriptions stay either way.
 */
public class DescriptionsTab extends BaseTab {

    public DescriptionsTab() {
        super("Descriptions");
    }

    /** Two plain settings, neither of them "the feature": no switch on the card. */
    @Override
    public SettingsButtonWidget masterToggle(List<AbstractWidget> built) {
        return null;
    }

    @Override
    public String cardDescription() {
        return "Show or hide the card descriptions and the HUD editor hints.";
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        widgets.add(SettingsButtonWidget.builder(menuText(), btn -> {
                    DescriptionConfig cfg = DescriptionConfig.getInstance();
                    cfg.setShowMenu(!cfg.isShowMenu());
                    cfg.save();
                    btn.setMessage(menuText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(editorText(), btn -> {
                    DescriptionConfig cfg = DescriptionConfig.getInstance();
                    cfg.setShowEditor(!cfg.isShowEditor());
                    cfg.save();
                    btn.setMessage(editorText());
                }).bounds(contentX, y, contentWidth, 20).build());
        return widgets;
    }

    private static Component menuText() {
        return Component.literal("Show Descriptions: " + (DescriptionConfig.getInstance().isShowMenu() ? "§aON" : "§cOFF"));
    }

    private static Component editorText() {
        return Component.literal("Show Editor Descriptions: "
                + (DescriptionConfig.getInstance().isShowEditor() ? "§aON" : "§cOFF"));
    }
}

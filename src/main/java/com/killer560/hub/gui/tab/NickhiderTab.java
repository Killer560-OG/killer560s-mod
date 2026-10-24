package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.namechanger.NameChangerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Nickhider (killer560, 2026-10-04: "Make a Nickhider section its own tab and that should be where the randomize
 *  names lives"). Randomize Others gives every other player a stable fake name for the session; it runs on its own,
 *  without the Cosmetics tab's Name Changer toggle (see {@link NameChangerConfig#isActive}). Randomized Name swaps the
 *  made-up names for a typed base name plus a per-player number (2026-10-08). No explanatory text on the tab itself
 *  (killer560, 2026-10-08: "remove the text"); the hover tooltips say it. Purely visual. */
public class NickhiderTab extends BaseTab {

    public NickhiderTab() {
        super("Nickhider");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        NameChangerConfig cfg = NameChangerConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Randomize Others", cfg.isRandomizeOthers()), btn -> {
                    cfg.setRandomizeOthers(!cfg.isRandomizeOthers());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isRandomizeOthers()) {
            return widgets;
        }
        // A style of Randomize Others, so it only shows while that is on (and pressing Randomize Others twice
        // leaves every other setting as it was).
        widgets.add(SettingsButtonWidget.builder(onOff("Randomized Name", cfg.isRandomizedName()), btn -> {
                    cfg.setRandomizedName(!cfg.isRandomizedName());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (cfg.isRandomizedName()) {
            EditBox baseField = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                    Component.literal("Base name"));
            baseField.setMaxLength(32);
            baseField.setValue(cfg.getRandomBaseName());
            baseField.setHint(Component.literal("§8Base name"));
            baseField.setResponder(text -> {
                cfg.setRandomBaseName(text);
                cfg.save();
            });
            widgets.add(baseField);
        }
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.cape.CapeConfig;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.CapeDesignerScreen;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Custom Cape: pick one of the built-in designs to wear (your own player only), or Make Your Own: a pattern and two colours.
 *  Click to step; the first click on None starts at the first design, and Make Your Own comes after the last design. */
public class CapeTab extends BaseTab {

    public CapeTab() {
        super("Cape");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CapeConfig cfg = CapeConfig.getInstance();
        widgets.add(SettingsButtonWidget.cycling(capeText(cfg), (btn, dir) -> {
                    cfg.cycle(dir);
                    cfg.save();
                    btn.setMessage(capeText(cfg));
                    requestRebuild.run();
                }).bounds(contentX, contentY, contentWidth, 18).build());
        // Make Your Own's own designer: each pixel of the back panel is placed by hand (killer560, 2026-10-19).
        widgets.add(SettingsButtonWidget.builder(Component.literal("Design Make Your Own..."), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new CapeDesignerScreen(McCompat.screen(Minecraft.getInstance()))))
                .bounds(contentX, contentY + 22, contentWidth, 18).build());
        // The pattern lives inside Make Your Own (the designer), with the grid it fills.
        return widgets;
    }

    private static Component capeText(CapeConfig cfg) {
        String name = cfg.isCustom() ? "Make Your Own" : cfg.hasCape() ? titleCase(cfg.getSelected()) : "None";
        return Component.literal("Cape: " + name);
    }

    private static String titleCase(String id) {
        return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}

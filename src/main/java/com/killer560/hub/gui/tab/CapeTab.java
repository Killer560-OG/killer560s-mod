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

/** Custom Cape: the Cape switch first (killer560, 2026-11-02: a feature that can be off has a real switch), then while it
 *  is ON one of the built-in designs to wear (your own player only), or Make Your Own: a pattern and two colors. The
 *  design steps through the built-in ones, then Make Your Own; it is kept while the switch is off. */
public class CapeTab extends BaseTab {

    public CapeTab() {
        super("Cape");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CapeConfig cfg = CapeConfig.getInstance();
        widgets.add(onOffButton("Cape", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, contentY, contentWidth, 18));
        if (!cfg.isEnabled()) {
            return widgets;
        }
        widgets.add(SettingsButtonWidget.cycling(capeText(cfg), (btn, dir) -> {
                    cfg.cycle(dir);
                    cfg.save();
                    btn.setMessage(capeText(cfg));
                    requestRebuild.run();
                }).bounds(contentX, contentY + 22, contentWidth, 18).build());
        // Make Your Own's own designer: each pixel of the back panel is placed by hand (killer560, 2026-10-19).
        widgets.add(SettingsButtonWidget.builder(Component.literal("Design Make Your Own..."), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new CapeDesignerScreen(McCompat.screen(Minecraft.getInstance()))))
                .bounds(contentX, contentY + 44, contentWidth, 18).build());
        // The pattern lives inside Make Your Own (the designer), with the grid it fills.
        return widgets;
    }

    private static Component capeText(CapeConfig cfg) {
        String name = cfg.isCustom() ? "Make Your Own" : titleCase(cfg.getDesign());
        return Component.literal("Design: " + name);
    }

    private static String titleCase(String id) {
        return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}

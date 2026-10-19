package com.killer560.hub.gui.tab;

import com.killer560.hub.cape.CapeConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Custom Cape: pick one of the built-in designs to wear (your own player only). Click to step; the first click on None
 *  starts at the first design. */
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
                }).bounds(contentX, contentY, contentWidth, 18).build());
        return widgets;
    }

    private static Component capeText(CapeConfig cfg) {
        String name = cfg.hasCape() ? titleCase(cfg.getSelected()) : "None";
        return Component.literal("Cape: " + name);
    }

    private static String titleCase(String id) {
        return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }
}

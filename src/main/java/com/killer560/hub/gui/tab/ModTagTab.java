package com.killer560.hub.gui.tab;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.modusers.ModUserConfig;
import com.killer560.hub.modusers.ModUsers;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Mod User Tag: the symbol that marks other mod users, and whether you show yours. Everything is off by default. */
public class ModTagTab extends BaseTab {

    public ModTagTab() {
        super("Mod User Tag");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        ModUserConfig cfg = ModUserConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("See Other Mod Users", cfg.isSeeOthers()), btn -> {
                    cfg.setSeeOthers(!cfg.isSeeOthers());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Show Me As A Mod User", cfg.isShare()), btn -> {
                    cfg.setShare(!cfg.isShare());
                    cfg.save();
                    ModUsers.announce();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        if (BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Arrows like the other cycling settings: left click steps forward, right click back (killer560, 2026-10-19).
            widgets.add(SettingsButtonWidget.cycling(showAsText(cfg), (btn, dir) -> {
                        cfg.setShareAs("cheat".equals(cfg.getShareAs()) ? "legit" : "cheat");
                        cfg.save();
                        ModUsers.announce();
                        btn.setMessage(showAsText(cfg));
                    }).bounds(contentX, y, contentWidth, 18).build());
        }
        return widgets;
    }

    private static Component showAsText(ModUserConfig cfg) {
        return Component.literal("Show As: " + ("cheat".equals(cfg.getShareAs()) ? "Cheat (red)" : "Legit (green)"));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

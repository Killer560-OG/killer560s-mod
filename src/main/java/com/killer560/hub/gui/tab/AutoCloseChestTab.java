package com.killer560.hub.gui.tab;

import com.killer560.hub.autoclosechest.AutoCloseChestConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Close Chest settings - see {@link com.killer560.hub.autoclosechest.AutoCloseChestFeature}'s class
 *  doc for the real QUOI-ported secret-chest detection this is built on. Cheat build only since 2026-10-07
 *  (killer560: "auto close chest should be in a cheat only version"): added only in its category's cheat
 *  block, red title, and it builds nothing in a legit jar. */
public class AutoCloseChestTab extends BaseTab {

    public AutoCloseChestTab() {
        super("Auto Close Chest");
    }

    /** Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        int y = contentY;
        AutoCloseChestConfig cfg = AutoCloseChestConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Close Chest", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Auto Close Chest", cfg.isEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

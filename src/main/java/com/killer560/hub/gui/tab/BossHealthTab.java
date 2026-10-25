package com.killer560.hub.gui.tab;

import com.killer560.hub.bosshealth.BossHealthConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Boss Health settings: the switch first, then what each line shows (see {@code bosshealth/BossHealthFeature}). */
public class BossHealthTab extends BaseTab {

    public BossHealthTab() {
        super("Boss Health");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        BossHealthConfig cfg = BossHealthConfig.getInstance();
        int y = contentY;
        w.add(toggle(contentX, y, contentWidth, "Boss Health", cfg::isEnabledRaw, cfg::setEnabled));
        y += 24;
        w.add(toggle(contentX, y, contentWidth, "Show Max Health", cfg::isShowMax, cfg::setShowMax));
        y += 22;
        w.add(toggle(contentX, y, contentWidth, "Show Percent", cfg::isShowPercent, cfg::setShowPercent));
        y += 22;
        w.add(toggle(contentX, y, contentWidth, "Show Minions", cfg::isShowMinions, cfg::setShowMinions));
        return w;
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return SettingsButtonWidget.builder(onOff(label, getter.getAsBoolean()), btn -> {
            setter.accept(!getter.getAsBoolean());
            BossHealthConfig.getInstance().save();
            btn.setMessage(onOff(label, getter.getAsBoolean()));
        }).bounds(x, y, width, 20).build();
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

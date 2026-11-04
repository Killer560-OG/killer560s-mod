package com.killer560.hub.gui.tab;

import com.killer560.hub.bosshealth.BossHealthConfig;
import net.minecraft.client.gui.components.AbstractWidget;

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
        return onOffButton(label, () -> getter.getAsBoolean(), v -> setter.accept(v), () -> BossHealthConfig.getInstance().save(), null, x, y, width, 20);
    }
}

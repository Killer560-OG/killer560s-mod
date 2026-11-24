package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.kitcheck.KitCheckConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Kit Check settings: what the countdown check wants in your inventory. Information only. */
public class KitCheckTab extends BaseTab {

    public KitCheckTab() {
        super("Kit Check");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        KitCheckConfig cfg = KitCheckConfig.getInstance();
        if (masterClosed(widgets, "Kit Check", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth)) {
            return widgets;
        }
        y += 26;
        widgets.add(SettingsButtonWidget.cycling(floorLabel(cfg), (btn, dir) -> {
            cfg.setMinFloor(cfg.getMinFloor() + dir);
            cfg.save();
            btn.setMessage(floorLabel(cfg));
        }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        y = count(widgets, "Spirit Leaps At Least", cfg::getLeaps, cfg::setLeaps, cfg, contentX, y, contentWidth);
        y = count(widgets, "Superboom TNT At Least", cfg::getBoom, cfg::setBoom, cfg, contentX, y, contentWidth);
        y = count(widgets, "Decoys At Least", cfg::getDecoys, cfg::setDecoys, cfg, contentX, y, contentWidth);
        y = count(widgets, "Arrows At Least", cfg::getArrows, cfg::setArrows, cfg, contentX, y, contentWidth);
        widgets.add(onOffButton("Check God Potion", cfg::isGodPotion, cfg::setGodPotion, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 22;
        widgets.add(onOffButton("Check Booster Cookie", cfg::isCookie, cfg::setCookie, cfg::save,
                null, contentX, y, contentWidth, 18));
        return widgets;
    }

    private static Component floorLabel(KitCheckConfig cfg) {
        int f = cfg.getMinFloor();
        return Component.literal("Check From Floor: " + (f == 0 ? "Entrance" : "F" + f));
    }

    private static int count(List<AbstractWidget> widgets, String label, IntSupplier get, IntConsumer set,
            KitCheckConfig cfg, int x, int y, int w) {
        widgets.add(SettingsButtonWidget.cycling(countLabel(label, get.getAsInt()), (btn, dir) -> {
            set.accept(KitCheckConfig.step(get.getAsInt(), dir));
            cfg.save();
            btn.setMessage(countLabel(label, get.getAsInt()));
        }).bounds(x, y, w, 18).build());
        return y + 22;
    }

    private static Component countLabel(String label, int n) {
        return Component.literal(label + ": " + (n == 0 ? "Off" : String.valueOf(n)));
    }
}

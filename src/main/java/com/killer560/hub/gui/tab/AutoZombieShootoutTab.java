package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.ShootoutShotConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Zombie Shootout settings (cheat build only) - see {@code carnival.AutoZombieShootout}. */
public class AutoZombieShootoutTab extends BaseTab {

    public AutoZombieShootoutTab() {
        super("Auto Zombie Shootout");
    }

    private static net.minecraft.network.chat.Component aimMode(ShootoutShotConfig cfg) {
        return net.minecraft.network.chat.Component.literal("Aim Mode: " + (cfg.isFreeCamera() ? "No Rotate" : "Legit"));
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ShootoutShotConfig cfg = ShootoutShotConfig.auto();
        Runnable save = cfg::save;
        int y = IslandRows.master(w, contentX, contentY, contentWidth, "Auto Zombie Shootout", cfg::isEnabledRaw,
                cfg::setEnabled, save, requestRebuild);
        y = IslandRows.automationNote(w, contentX, y, contentWidth);
        if (!cfg.isEnabledRaw() && !isSearchScan()) {
            return w;
        }
        y = ShootoutRows.rows(w, contentX, y, contentWidth, cfg);
        int half = (contentWidth - 4) / 2;
        IslandRows.toggle(w, contentX, y, half, "Lamps First", cfg::isLampsFirst, cfg::setLampsFirst, save);
        // Legit turns the view you see; No Rotate turns only the look the server hears (ViewFreeze holds your screen).
        w.add(com.killer560.hub.gui.SettingsButtonWidget.cycling(aimMode(cfg), (btn, dir) -> {
            cfg.setFreeCamera(!cfg.isFreeCamera());
            save.run();
            btn.setMessage(aimMode(cfg));
        }).bounds(contentX + half + 4, y, half, 18).build());
        return w;
    }
}

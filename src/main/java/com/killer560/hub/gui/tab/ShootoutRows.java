package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.ShootoutShotConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/** The shot rows Zombie Shootout Triggerbot's and Auto Zombie Shootout's tabs share. Returns the next row's y. */
final class ShootoutRows {

    private ShootoutRows() {
    }

    private static Component shootWith(ShootoutShotConfig cfg) {
        return Component.literal("Shoot With: " + ShootoutShotConfig.SHOOT_WITH[cfg.getShootWith()]);
    }

    static int rows(List<AbstractWidget> w, int x, int y, int width, ShootoutShotConfig cfg) {
        Runnable save = cfg::save;
        int half = (width - 4) / 2;
        int colB = x + half + 4;
        w.add(SettingsButtonWidget.cycling(shootWith(cfg), (btn, dir) -> {
            cfg.setShootWith(SettingsButtonWidget.step(cfg.getShootWith(), dir, ShootoutShotConfig.SHOOT_WITH.length));
            save.run();
            btn.setMessage(shootWith(cfg));
        }).bounds(x, y, half, 18).build());
        y = IslandRows.toggle(w, colB, y, half, "Dart Tube Only", cfg::isTubeOnly, cfg::setTubeOnly, save);
        IslandRows.slider(w, x, y, half, ShootoutShotConfig.MIN_SHOT_MS, ShootoutShotConfig.MAX_SHOT_MS, 10,
                cfg::getShotDelayMs, cfg::setShotDelayMs, v -> "Shot Delay: " + v + "ms", save);
        y = IslandRows.slider(w, colB, y, half, 0, ShootoutShotConfig.MAX_AIM_DELAY_MS, 10, cfg::getAimDelayMs,
                cfg::setAimDelayMs, v -> "Aim Delay: " + v + "ms", save);
        return y;
    }
}

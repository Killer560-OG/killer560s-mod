package com.killer560.hub.gui.tab;

import com.killer560.hub.goldor.GoldorTriggerbotConfig;
import com.killer560.hub.gui.RangeSliderWidget;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Wither Boss Triggerbot (Goldor Triggerbot until 2026-11-01) settings - cheat build only, so the whole tab only exists on the cheat jar and its header is
 *  drawn red. The rate is a two-handled CPS range ({@link RangeSliderWidget}, the same control i4 Sensors uses),
 *  with the millisecond band it works out to shown alongside it so it lines up with Arrow Align's delays. See
 *  {@code GoldorTriggerbotFeature}. */
public class GoldorTriggerbotTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;

    public GoldorTriggerbotTab() {
        super("Wither Boss Triggerbot");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Wither Boss Triggerbot", "Wither Aimbot");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        GoldorTriggerbotConfig cfg = GoldorTriggerbotConfig.getInstance();
        int y = contentY;
        int halfW = (contentWidth - GAP) / 2;
        int rightW = Math.max(1, contentWidth - halfW - GAP);

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Wither Boss Triggerbot", true));
        y += 16;

        // Two features side by side, each with its own switch (masterToggles): the triggerbot and the Wither Aimbot.
        // The rate, click type and classes below serve both, so they show while either is on.
        widgets.add(onOffButton("Wither Boss Triggerbot", cfg::isEnabledRaw, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        boolean scan = isSearchScan();
        if (cfg.isEnabledRaw() || scan) {
            widgets.add(onOffButton("All Wither Bosses", cfg::isAllWithers, cfg::setAllWithers, cfg::save,
                    null, contentX, y, contentWidth, 20));
            y += 24;
        }

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Wither Aimbot", true));
        y += 16;
        widgets.add(onOffButton("Wither Aimbot", cfg::isAimbot, cfg::setAimbot, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        if (cfg.isAimbot() || scan) {
            widgets.add(onOffButton("Auto Hit", cfg::isAimAutoHit, cfg::setAimAutoHit, cfg::save, null,
                    contentX, y, halfW, 20));
            widgets.add(onOffButton("Hold Left Click", cfg::isAimHoldLeftClick, cfg::setAimHoldLeftClick, cfg::save, null,
                    contentX + halfW + GAP, y, rightW, 20));
            y += 24;
            int bossW = (contentWidth - GAP * 3) / 4;
            for (int i = 0; i < GoldorTriggerbotConfig.BOSSES.length; i++) {
                int boss = i;
                widgets.add(onOffButton(GoldorTriggerbotConfig.BOSSES[i], () -> cfg.isAimAt(boss), v -> cfg.setAimAt(boss, v),
                        cfg::save, null, contentX + i * (bossW + GAP), y, bossW, 20));
            }
            y += 24;
        }
        if (!cfg.isEnabledRaw() && !cfg.isAimbot() && !scan) {
            return widgets;
        }
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Clicks", true));
        y += 16;

        widgets.add(msRange(contentX, y, contentWidth, "Delay", cfg.getDelayMin(), cfg.getDelayMax(),
                cfg::setDelayRange, cfg::save));
        y += ROW + GAP;

        widgets.add(SettingsButtonWidget.cycling(clickTypeText(cfg), (btn, dir) -> {
                    cfg.cycleClickType(dir);
                    cfg.save();
                    btn.setMessage(clickTypeText(cfg));
                }).bounds(contentX, y, halfW, ROW).build());
        widgets.add(valueSlider(contentX + halfW + GAP, y, rightW, "Aim Delay", " ms", 0, GoldorTriggerbotConfig.MAX_AIM_DELAY_MS,
                50, 0, cfg::getAimDelayMs, v -> cfg.setAimDelayMs((int) v), cfg::save));
        y += ROW + GAP;

        // Classes it works on (your class from the dungeon tab list); all on = every class, and an unknown class too.
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Classes", true));
        y += 16;
        com.killer560.hub.dungeonclass.DungeonClass[] all = com.killer560.hub.dungeonclass.DungeonClass.values();
        int colW = (contentWidth - GAP * 2) / 3;
        for (int i = 0; i < all.length; i++) {
            com.killer560.hub.dungeonclass.DungeonClass c = all[i];
            int col = i % 3;
            if (i > 0 && col == 0) {
                y += 24;
            }
            widgets.add(onOffButton(c.displayName(), () -> cfg.isClassEnabled(c), v -> cfg.setClassEnabled(c, v),
                    cfg::save, null, contentX + col * (colW + GAP), y, colW, 20));
        }
        y += 24;
        return widgets;
    }

    private static Component clickTypeText(GoldorTriggerbotConfig cfg) {
        return Component.literal("Click Type: §b" + cfg.getClickType().label);
    }


}

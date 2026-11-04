package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.luck.LuckConfig;
import com.killer560.hub.luck.LuckScreen;
import com.killer560.hub.luck.LuckShare;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** How Lucky Are You (doc-rng-1101) - see {@link com.killer560.hub.luck.LuckFeature}. Legit: it only reads chat, the
 *  sidebar, his inventory and the Croesus claim log, and sends nothing to Hypixel. */
public class LuckTab extends BaseTab {

    private static final int GAP = 6;

    public LuckTab() {
        super("How Lucky Are You");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        LuckConfig cfg = LuckConfig.getInstance();
        int y = contentY;
        int half = (contentWidth - GAP) / 2;

        widgets.add(onOffButton("RNG Tracker", cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open How Lucky Are You"), btn ->
                        McCompat.setScreen(Minecraft.getInstance(), new LuckScreen(McCompat.screen(Minecraft.getInstance()))))
                .bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (!cfg.isEnabled() && !isSearchScan()) {
            return widgets;
        }
        widgets.add(onOffButton("Drop Chat Line", cfg::isAnnounce, cfg::setAnnounce, cfg::save, null,
                contentX, y, half, 18));
        widgets.add(onOffButton("Count Dye Pickups", cfg::isInventoryDrops, cfg::setInventoryDrops, cfg::save, null,
                contentX + half + GAP, y, half, 18));
        y += 22;
        widgets.add(SettingsButtonWidget.cycleEnum(() -> chestOddsText(), cfg::getChestOdds, cfg::setChestOdds, cfg::save).bounds(contentX, y, half, 18).build());
        widgets.add(SettingsButtonWidget.cycling(mfModeText(), (btn, dir) -> {
            cfg.setMagicFindMode(SettingsButtonWidget.step(cfg.getMagicFindMode(), dir));
            cfg.save();
            requestRebuild.run();
        }).bounds(contentX + half + GAP, y, half, 18).build());
        y += 22;
        widgets.add(SettingsButtonWidget.cycleEnum(() -> mobKillsText(), cfg::getMobKills, cfg::setMobKills, cfg::save).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        if (cfg.getMagicFindMode() == LuckConfig.MagicFindMode.MANUAL) {
            widgets.add(normSlider(contentX, y, contentWidth, 18,
                    () -> "Magic Find: " + cfg.getManualMagicFind() + "%",
                    cfg.getManualMagicFind() / (double) LuckConfig.MAX_MAGIC_FIND,
                    v -> cfg.setManualMagicFind((int) Math.round(v * LuckConfig.MAX_MAGIC_FIND / 5.0) * 5), cfg::save));
            y += 22;
        }
        widgets.add(onOffButton("Share My Luck", cfg::isShareMyLuck, cfg::setShareMyLuck, cfg::save, requestRebuild,
                contentX, y, contentWidth, 18));
        y += 20;
        String why = LuckShare.blockReason();
        if (cfg.isShareMyLuck() && why != null) {
            widgets.add(new net.minecraft.client.gui.components.StringWidget(contentX, y, contentWidth, 14,
                    Component.literal("§7Not sent: " + why + " (Mod User Tag tab)"), Minecraft.getInstance().font));
        }
        return widgets;
    }

    private static Component chestOddsText() {
        return Component.literal("Chest Odds: " + LuckConfig.getInstance().getChestOdds().label);
    }

    private static Component mobKillsText() {
        return Component.literal("Mob Kills: " + LuckConfig.getInstance().getMobKills().label);
    }

    private static Component mfModeText() {
        return Component.literal("Magic Find: " + LuckConfig.getInstance().getMagicFindMode().label);
    }
}

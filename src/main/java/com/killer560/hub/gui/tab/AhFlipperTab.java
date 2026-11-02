package com.killer560.hub.gui.tab;

import com.killer560.hub.ahflip.AhFlipConfig;
import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AH Flipper settings (2026-11-01). Legit: Background mode puts each flip in chat with a clickable [Open]. The cheat build adds
 * Main mode (the buying macro) and its Max Buys Per Session under a red Cheat header at the bottom.
 */
public class AhFlipperTab extends BaseTab {

    public AhFlipperTab() {
        super("AH Flipper");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AhFlipConfig cfg = AhFlipConfig.getInstance();
        int y = contentY;
        w.add(onOffButton("AH Flipper", cfg::isFlipperEnabledRaw, cfg::setFlipperEnabled, cfg::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 26;
        if (!cfg.isFlipperEnabledRaw()) {
            return w;
        }
        w.add(cycling(contentX, y, contentWidth, () -> "Min Profit %: §6" + cfg.getMinProfitPercent() + "%",
                dir -> cfg.stepMinProfitPercent(dir), cfg));
        y += 22;
        w.add(cycling(contentX, y, contentWidth, () -> String.format(Locale.US, "Undercut: §6%.1f%%",
                cfg.getUndercutTenths() / 10.0), dir -> cfg.stepUndercut(dir), cfg));
        y += 22;
        w.add(cycling(contentX, y, contentWidth, () -> "Min Profit: §6" + coins(cfg.getMinProfit(), "Off"),
                dir -> cfg.stepMinProfit(dir), cfg));
        y += 22;
        w.add(cycling(contentX, y, contentWidth, () -> "Min Sell Price: §6" + coins(cfg.getMinSellPrice(), "Off"),
                dir -> cfg.stepMinSellPrice(dir), cfg));
        y += 22;
        w.add(cycling(contentX, y, contentWidth, () -> "Max Buy Price: §6" + coins(cfg.getMaxBuyPrice(), "Your purse"),
                dir -> cfg.stepMaxBuyPrice(dir), cfg));
        y += 22;
        w.add(cycling(contentX, y, contentWidth, () -> "Min Listings: §6" + cfg.getMinListings(),
                dir -> cfg.stepMinListings(dir), cfg));
        y += 22;
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += 4;
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Automation", true));
            y += 16;
            SettingsButtonWidget mode = SettingsButtonWidget.cycling(Component.literal("Run Mode: §6"
                    + cfg.getRunMode().display), (btn, dir) -> {
                cfg.stepRunMode(dir);
                cfg.save();
                requestRebuild.run();
            }).bounds(contentX, y, contentWidth, 18).build();
            w.add(mode);
            y += 22;
            if (cfg.getRunMode() == AhFlipConfig.RunMode.MAIN) {
                w.add(cycling(contentX, y, contentWidth, () -> "Max Buys Per Session: §6" + cfg.getMacroMaxBuys(),
                        dir -> cfg.stepMacroMaxBuys(dir), cfg));
                y += 22;
            }
            w.add(onOffButton("Auto Relist", cfg::isAutoRelist, cfg::setAutoRelist, cfg::save, requestRebuild,
                    contentX, y, contentWidth, 18));
            y += 22;
            if (cfg.isAutoRelist()) {
                w.add(SettingsButtonWidget.builder(Component.literal("Relist Now"),
                        btn -> com.killer560.hub.ahflip.AhRelistMacro.requestNow()).bounds(contentX, y, contentWidth, 18).build());
            }
        }
        return w;
    }

    private static String coins(long v, String zero) {
        return v <= 0 ? zero : DungeonChestValuer.formatCoins(v);
    }

    private static AbstractWidget cycling(int x, int y, int width, java.util.function.Supplier<String> label,
                                          java.util.function.IntConsumer step, AhFlipConfig cfg) {
        return SettingsButtonWidget.cycling(Component.literal(label.get()), (btn, dir) -> {
            step.accept(dir);
            cfg.save();
            btn.setMessage(Component.literal(label.get()));
        }).bounds(x, y, width, 18).build();
    }
}

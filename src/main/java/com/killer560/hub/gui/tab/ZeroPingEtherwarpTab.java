package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.leftclickew.LeftClickEtherwarpConfig;
import com.killer560.hub.zpew.ZeroPingEtherwarpConfig;
import com.killer560.hub.zpew.ZpewSource;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The Etherwarp tab (killer560, 2026-11-01, to-do Doc "ZPEW": "the new etherwarp tab. This will contain zpew and left
 * click etherwarp"): Zero Ping Etherwarp ({@link com.killer560.hub.zpew.ZeroPingEtherwarp}) and Left Click Etherwarp
 * ({@link com.killer560.hub.leftclickew.LeftClickEtherwarp}) under a red header at the bottom. The whole tab is
 * cheat-only since 2026-11-02 (killer560: "all zero ping etherwarp stuff should be cheat only nothing in legit"): it
 * is built only in DungeonTab's cheat block. Three features side by side (Zero Ping Etherwarp, Multi Warps, Left Click
 * Etherwarp), so the card's switch stands for all three ({@link #masterToggles}); Multi Warps works with Zero Ping
 * Etherwarp off (zpewunify-1111). Each row says what it does in its hover tooltip. The class keeps its old name so a
 * starred card and a toggle key (both kept by class name) carry over.
 */
public class ZeroPingEtherwarpTab extends BaseTab {

    private static final int ROW = 18;
    public static final String ZPEW = "Zero Ping Etherwarp";
    public static final String LEFT_CLICK = "Left Click Etherwarp";
    public static final String MULTI = "Multi Warps";

    public ZeroPingEtherwarpTab() {
        super("Etherwarp");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public String cardDescription() {
        return "Instant etherwarp; left click warps.";
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, ZPEW, MULTI, LEFT_CLICK);
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ZeroPingEtherwarpConfig cfg = ZeroPingEtherwarpConfig.getInstance();
        int y = contentY;
        w.add(SectionHeaders.row(contentX, y, contentWidth, ZPEW, false));
        y += 16;
        w.add(onOffButton(ZPEW, cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild, contentX, y, contentWidth, ROW));
        y += ROW + 6;
        if (cfg.isEnabled() || isSearchScan()) {
            w.add(onOffButton("Dungeons Only", cfg::isDungeonsOnly, cfg::setDungeonsOnly, cfg::save, null,
                    contentX, y, contentWidth, ROW));
            y += ROW + 4;
            w.add(onOffButton("Chain From Prediction", cfg::isChain, cfg::setChain, cfg::save, null,
                    contentX, y, contentWidth, ROW));
            y += ROW + 4;
            w.add(onOffButton("Overlay From Prediction", cfg::isOverlayFromPrediction, cfg::setOverlayFromPrediction,
                    cfg::save, null, contentX, y, contentWidth, ROW));
            y += ROW + 4;
            double min = ZeroPingEtherwarpConfig.MIN_TIMEOUT_MS;
            double span = ZeroPingEtherwarpConfig.MAX_TIMEOUT_MS - min;
            w.add(new ThemedSliderButton(contentX, y, contentWidth, ROW, timeoutText(cfg),
                    Math.max(0.0, Math.min(1.0, (cfg.getTimeoutMs() - min) / span))) {
                @Override
                protected void updateMessage() {
                    setMessage(timeoutText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setTimeoutMs((int) (Math.round((min + this.value * span) / 50.0) * 50));
                    saveOnRelease(() -> cfg.save());
                }
            });
            y += ROW + 6;
            // Works For (killer560, 2026-11-11: "the only thing i should have to select is auto routes, interactive map,
            // auto puzzles and manual"). Auto Routes is also the zero-ping chain of Auto Routes' etherwarp nodes.
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Works For", true));
            y += 16;
            y = sourceRows(w, null, cfg::getWorksForSetting, cfg::setWorksFor, cfg, contentX, y, contentWidth,
                    ZpewSource.Group.values());
            y += 6;
        }
        // Multi Warps (killer560, 2026-11-01 "Make a toggle for the multi warps"; 2026-11-11 "in etherwarp if i have zero ping
        // off it should still show multiwarp"): its own feature, built whatever Zero Ping Etherwarp's switch says.
        w.add(SectionHeaders.row(contentX, y, contentWidth, MULTI, true));
        y += 16;
        w.add(onOffButton(MULTI, cfg::isMultiWarps, cfg::setMultiWarps, cfg::save, requestRebuild, contentX, y, contentWidth, ROW));
        y += ROW + 4;
        if (cfg.isMultiWarps() || isSearchScan()) {
            w.add(SettingsButtonWidget.cycling(warpsPerTickText(cfg), (btn, dir) -> {
                        cfg.setWarpsPerTick((int) SettingsButtonWidget.step(cfg.getWarpsPerTick(), 1, 1,
                                ZeroPingEtherwarpConfig.MAX_WARPS_PER_TICK, dir));
                        cfg.save();
                        btn.setMessage(warpsPerTickText(cfg));
                    }).bounds(contentX, y, contentWidth, ROW).build());
            y += ROW + 4;
            // Multi Warp Mode (mwtick-1111, killer560 2026-11-11: "make an option for the burst or to multiwarp"): Burst or
            // Tick Split, for every source under Multi Warps For.
            w.add(SettingsButtonWidget.cycling(multiWarpModeText(cfg), (btn, dir) -> {
                        cfg.setTickSplit(!cfg.isTickSplit());
                        cfg.save();
                        btn.setMessage(multiWarpModeText(cfg));
                    }).bounds(contentX, y, contentWidth, ROW).build());
            y += ROW + 4;
            w.add(onOffButton("Auto Charge", cfg::isAutoCharge, cfg::setAutoCharge, cfg::save, null,
                    contentX, y, contentWidth, ROW));
            y += ROW + 4;
            w.add(onOffButton("Aimed Warps", cfg::isAimedWarps, cfg::setAimedWarps, cfg::save, null,
                    contentX, y, contentWidth, ROW));
            y += ROW + 6;
            // Which sources Multi Warps acts for: Manual (Warps Per Tick, Aimed Warps), Auto Routes and the Interactive
            // Map (a chain of warps in one tick from Blink Charge's bank, the old Charged Chain). Auto Puzzles sends single
            // repositions, so there is nothing to multiply there. Scoped, so their hovers differ from Works For's.
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Multi Warps For", true));
            y += 16;
            y = sourceRows(w, MULTI, cfg::getMultiWarpsForSetting, cfg::setMultiWarpsFor, cfg, contentX, y, contentWidth,
                    ZpewSource.Group.AUTO_ROUTES, ZpewSource.Group.INTERACTIVE_MAP, ZpewSource.Group.MANUAL);
        }
        y += 6;
        LeftClickEtherwarpConfig lce = LeftClickEtherwarpConfig.getInstance();
        w.add(SectionHeaders.row(contentX, y, contentWidth, LEFT_CLICK, true));
        y += 16;
        w.add(onOffButton(LEFT_CLICK, lce::isEnabled, lce::setEnabled, lce::save, null, contentX, y, contentWidth, ROW));
        return w;
    }

    private static Component multiWarpModeText(ZeroPingEtherwarpConfig cfg) {
        return Component.literal("Multi Warp Mode: " + (cfg.isTickSplit() ? "Tick Split" : "Burst"));
    }

    private static Component warpsPerTickText(ZeroPingEtherwarpConfig cfg) {
        return Component.literal("Warps Per Tick: " + cfg.getWarpsPerTick());
    }

    /** One switch per source, two to a row; {@code scope} keys their hovers ("multi warps/auto routes"). @return the y under them */
    private static int sourceRows(List<AbstractWidget> w, String scope,
                                  java.util.function.Predicate<ZpewSource.Group> get,
                                  java.util.function.BiConsumer<ZpewSource.Group, Boolean> set, ZeroPingEtherwarpConfig cfg,
                                  int x, int y, int width, ZpewSource.Group... groups) {
        int gap = 8;
        int colW = (width - gap) / 2;
        int col = 0;
        for (ZpewSource.Group g : groups) {
            String label = g.label();
            SettingsButtonWidget b = SettingsButtonWidget.builder(onOff(label, get.test(g)), btn -> {
                        set.accept(g, !get.test(g));
                        cfg.save();
                        btn.setMessage(onOff(label, get.test(g)));
                    }).bounds(x + col * (colW + gap), y, colW, ROW).build();
            SettingTooltips.scope(b, scope);
            w.add(b);
            if (++col == 2) {
                col = 0;
                y += ROW + 2;
            }
        }
        return col == 0 ? y : y + ROW + 2;
    }

    private static Component timeoutText(ZeroPingEtherwarpConfig cfg) {
        return Component.literal("Give Up After: " + cfg.getTimeoutMs() + " ms");
    }
}

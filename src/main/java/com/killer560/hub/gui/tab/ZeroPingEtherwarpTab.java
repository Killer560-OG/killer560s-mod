package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
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
 * is built only in DungeonTab's cheat block. Two features side by side, so the card's switch stands for both ({@link #masterToggles}).
 *
 * <p>No explanation lines: each row says what it does in its hover tooltip ("If you need to explain it then it should be
 * text shown when hovering a setting"). Works For is the last of ZPEW's legit rows ("Move the works for to the bottom");
 * the cheat rows follow it at the bottom, as everywhere. The
 * class keeps its old name so a starred card and a toggle key (both kept by class name) carry over.
 */
public class ZeroPingEtherwarpTab extends BaseTab {

    private static final int ROW = 18;
    public static final String ZPEW = "Zero Ping Etherwarp";
    public static final String LEFT_CLICK = "Left Click Etherwarp";

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
        return switchRows(built, ZPEW, LEFT_CLICK);
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ZeroPingEtherwarpConfig cfg = ZeroPingEtherwarpConfig.getInstance();
        boolean cheat = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
        int y = contentY;
        w.add(SectionHeaders.row(contentX, y, contentWidth, ZPEW, true));
        y += 16;
        w.add(onOffButton(ZPEW, cfg::isEnabled, cfg::setEnabled, cfg::save, requestRebuild, contentX, y, contentWidth, ROW));
        y += ROW + 6;
        if (cfg.isEnabled()) {
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
            // Works For (killer560, 2026-10-27), last of the legit rows since 2026-11-01 ("Move the works for to the
            // bottom"): one switch per sender. The cheat rows (its cheat senders, Multi Warps, Left Click Etherwarp) stay
            // under it, as every cheat setting goes at the bottom of its tab (testkit 512).
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Works For", false));
            y += 16;
            y = sourceRows(w, cfg, contentX, y, contentWidth, false);
            if (cheat) {
                y += 6;
                w.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Works For", true));
                y += 16;
                y = sourceRows(w, cfg, contentX, y, contentWidth, true);
            }
            if (cheat) {
                y += 6;
                // Multi Warps (killer560, 2026-11-01: "Make a toggle for the multi warps. Change that setting to be called
                // multi warps"): the switch over Warps Per Tick and Aimed Warps, which show only while it is on.
                w.add(SectionHeaders.row(contentX, y, contentWidth, "Multi Warps", true));
                y += 16;
                w.add(onOffButton("Multi Warps", cfg::isMultiWarps, cfg::setMultiWarps, cfg::save, requestRebuild, contentX, y, contentWidth, ROW));
                y += ROW + 4;
                if (cfg.isMultiWarps()) {
                    w.add(SettingsButtonWidget.cycling(warpsPerTickText(cfg), (btn, dir) -> {
                                cfg.setWarpsPerTick((int) SettingsButtonWidget.step(cfg.getWarpsPerTick(), 1, 1,
                                        ZeroPingEtherwarpConfig.MAX_WARPS_PER_TICK, dir));
                                cfg.save();
                                btn.setMessage(warpsPerTickText(cfg));
                            }).bounds(contentX, y, contentWidth, ROW).build());
                    y += ROW + 4;
                    w.add(onOffButton("Aimed Warps", cfg::isAimedWarps, cfg::setAimedWarps, cfg::save, null,
                            contentX, y, contentWidth, ROW));
                    y += ROW + 4;
                }
                y += 2;
            }
            y += 6;
        }
        if (cheat) {
            LeftClickEtherwarpConfig lce = LeftClickEtherwarpConfig.getInstance();
            w.add(SectionHeaders.row(contentX, y, contentWidth, LEFT_CLICK, true));
            y += 16;
            w.add(onOffButton(LEFT_CLICK, lce::isEnabled, lce::setEnabled, lce::save, null, contentX, y, contentWidth, ROW));
        }
        return w;
    }

    private static Component warpsPerTickText(ZeroPingEtherwarpConfig cfg) {
        return Component.literal("Warps Per Tick: " + cfg.getWarpsPerTick());
    }

    /** A Works For row's label: Left Click Etherwarp's reads "Left Click Etherwarps" here, so it is never taken for the
     *  Left Click Etherwarp switch further down (the card's switch finds that one by its label). */
    static String sourceLabel(ZpewSource s) {
        return s == ZpewSource.LEFT_CLICK ? "Left Click Etherwarps" : s.label();
    }

    /** The Works For switches of one kind, two to a row. @return the y under them */
    private static int sourceRows(List<AbstractWidget> w, ZeroPingEtherwarpConfig cfg, int x, int y, int width,
                                  boolean cheat) {
        int gap = 8;
        int colW = (width - gap) / 2;
        int col = 0;
        for (ZpewSource s : ZpewSource.values()) {
            if (s.cheatOnly() != cheat) {
                continue;
            }
            String label = sourceLabel(s);
            w.add(SettingsButtonWidget.builder(onOff(label, cfg.getWorksForSetting(s)), btn -> {
                        cfg.setWorksFor(s, !cfg.getWorksForSetting(s));
                        cfg.save();
                        btn.setMessage(onOff(label, cfg.getWorksForSetting(s)));
                    }).bounds(x + col * (colW + gap), y, colW, ROW).build());
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

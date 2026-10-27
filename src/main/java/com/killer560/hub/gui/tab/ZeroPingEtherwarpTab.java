package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.zpew.ZeroPingEtherwarpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Zero Ping Etherwarp - both builds since 2026-10-26: camera only (see {@link com.killer560.hub.zpew.ZeroPingEtherwarp}). */
public class ZeroPingEtherwarpTab extends BaseTab {

    private static final int ROW = 18;

    public ZeroPingEtherwarpTab() {
        super("Zero Ping Etherwarp");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        var font = Minecraft.getInstance().font;
        ZeroPingEtherwarpConfig cfg = ZeroPingEtherwarpConfig.getInstance();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(onOff("Zero Ping Etherwarp", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 6;
        if (!cfg.isEnabled()) {
            return w;
        }
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Zero Ping Etherwarp", false));
        y += 16;
        String[] help = {
                "§7Your view jumps to where an etherwarp lands the moment you use it,",
                "§7instead of a round trip later. Camera only: your character still moves",
                "§7when the server says so, and nothing extra is sent."
        };
        for (String line : help) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        y += 6;
        w.add(SettingsButtonWidget.builder(onOff("Chain From Prediction", cfg.isChain()), btn -> {
                    cfg.setChain(!cfg.isChain());
                    cfg.save();
                    btn.setMessage(onOff("Chain From Prediction", cfg.isChain()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 4;
        w.add(SettingsButtonWidget.builder(onOff("Overlay From Prediction", cfg.isOverlayFromPrediction()), btn -> {
                    cfg.setOverlayFromPrediction(!cfg.isOverlayFromPrediction());
                    cfg.save();
                    btn.setMessage(onOff("Overlay From Prediction", cfg.isOverlayFromPrediction()));
                }).bounds(contentX, y, contentWidth, ROW).build());
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
        // Works For (killer560, 2026-10-27: "make an option like things that zpew works for with a list of everything"):
        // one switch per sender of an etherwarp. The legit ones here, the cheat ones under the red header at the bottom.
        w.add(SectionHeaders.row(contentX, y, contentWidth, "Works For", false));
        y += 16;
        y = sourceRows(w, cfg, contentX, y, contentWidth, false);
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            y += 6;
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Works For", true));
            y += 16;
            y = sourceRows(w, cfg, contentX, y, contentWidth, true);
            // Warps Per Tick (killer560, 2026-10-27: "multiple ether warps done in a single tick"): sends extra warps.
            y += 6;
            w.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Warps In One Tick", true));
            y += 16;
            String[] wptHelp = {
                    "§7Each of your own etherwarps becomes this many in the same tick, at the",
                    "§7same look, each from where the last one lands. 1 = off. Each costs mana."
            };
            for (String line : wptHelp) {
                w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
                y += 11;
            }
            y += 4;
            w.add(SettingsButtonWidget.cycling(warpsPerTickText(cfg), (btn, dir) -> {
                        cfg.setWarpsPerTick((int) SettingsButtonWidget.step(cfg.getWarpsPerTick(), 1, 1,
                                ZeroPingEtherwarpConfig.MAX_WARPS_PER_TICK, dir));
                        cfg.save();
                        btn.setMessage(warpsPerTickText(cfg));
                    }).bounds(contentX, y, contentWidth, ROW).build());
            y += ROW + 6;
            // Aimed Warps (killer560, 2026-10-27: "Can you make it etherwarp different directions during the same tick?")
            String[] aimedHelp = {
                    "§7Clicks inside one tick at different looks each warp their own way, all",
                    "§7in that tick. Each extra look spends one banked Blink Charge tick."
            };
            for (String line : aimedHelp) {
                w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
                y += 11;
            }
            y += 4;
            w.add(SettingsButtonWidget.builder(onOff("Aimed Warps In One Tick", cfg.isAimedWarps()), btn -> {
                        cfg.setAimedWarps(!cfg.isAimedWarps());
                        cfg.save();
                        btn.setMessage(onOff("Aimed Warps In One Tick", cfg.isAimedWarps()));
                    }).bounds(contentX, y, contentWidth, ROW).build());
        }
        return w;
    }

    private static Component warpsPerTickText(ZeroPingEtherwarpConfig cfg) {
        return Component.literal("Warps Per Tick: " + cfg.getWarpsPerTick());
    }

    /** The Works For switches of one kind, two to a row. @return the y under them */
    private static int sourceRows(List<AbstractWidget> w, ZeroPingEtherwarpConfig cfg, int x, int y, int width,
                                  boolean cheat) {
        int gap = 8;
        int colW = (width - gap) / 2;
        int col = 0;
        for (com.killer560.hub.zpew.ZpewSource s : com.killer560.hub.zpew.ZpewSource.values()) {
            if (s.cheatOnly() != cheat) {
                continue;
            }
            w.add(SettingsButtonWidget.builder(onOff(s.label(), cfg.getWorksForSetting(s)), btn -> {
                        cfg.setWorksFor(s, !cfg.getWorksForSetting(s));
                        cfg.save();
                        btn.setMessage(onOff(s.label(), cfg.getWorksForSetting(s)));
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

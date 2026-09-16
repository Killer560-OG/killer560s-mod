package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.runstats.RunStatsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Run Stats: the end-of-run per-player summary ({@link com.killer560.hub.runstats.RunStatsFeature}). */
public class RunStatsTab extends BaseTab {

    public RunStatsTab() {
        super("Run Stats");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        RunStatsConfig cfg = RunStatsConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Run Stats", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(header("What To Show", contentX, y, contentWidth));
        y += 14;
        widgets.add(toggle("Rooms Cleared", cfg::isTrackRooms, cfg::setTrackRooms, cfg::save, contentX, y, colW));
        widgets.add(toggle("Deaths", cfg::isShowDeaths, cfg::setShowDeaths, cfg::save, colBX, y, colW));
        y += 20;
        widgets.add(toggle("Secrets (Hypixel API)", cfg::isFetchSecrets, cfg::setFetchSecrets, cfg::save,
                contentX, y, contentWidth));
        y += 24;

        widgets.add(header("Timing", contentX, y, contentWidth));
        y += 14;
        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, delayText(cfg),
                cfg.getDelaySeconds() / (double) RunStatsConfig.MAX_DELAY_SECONDS) {
            @Override
            protected void updateMessage() {
                setMessage(delayText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setDelaySeconds((int) Math.round(this.value * RunStatsConfig.MAX_DELAY_SECONDS));
                cfg.save();
            }
        });
        y += 24;

        widgets.add(header("Sends To The Server", contentX, y, contentWidth));
        y += 14;
        widgets.add(toggle("Auto /showextrastats", cfg::isAutoShowExtraStats, cfg::setAutoShowExtraStats, cfg::save,
                contentX, y, colW));
        widgets.add(toggle("Announce To Party", cfg::isAnnounceToParty, cfg::setAnnounceToParty, cfg::save,
                colBX, y, colW));
        return widgets;
    }

    private static AbstractWidget header(String title, int x, int y, int width) {
        return new StringWidget(x, y, width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font);
    }

    private interface BoolGetter {
        boolean get();
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private static AbstractWidget toggle(String label, BoolGetter getter, BoolSetter setter, Runnable save,
                                         int x, int y, int w) {
        return SettingsButtonWidget.builder(onOff(label, getter.get()), btn -> {
                    setter.set(!getter.get());
                    save.run();
                    btn.setMessage(onOff(label, getter.get()));
                }).bounds(x, y, w, 18).build();
    }

    private static Component delayText(RunStatsConfig cfg) {
        return Component.literal("Summary Delay: " + cfg.getDelaySeconds() + "s");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

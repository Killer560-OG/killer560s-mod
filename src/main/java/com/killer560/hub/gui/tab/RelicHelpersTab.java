package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.witherdragons.RelicHelpersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Relic Triggerbot and Relic Look (M7 P5) - cheat build only, so the whole tab exists only on the cheat jar and its
 *  headers are red. Two features side by side, each with its own switch ({@link #masterToggles}). See
 *  {@code witherdragons.RelicHelpersFeature}. */
public class RelicHelpersTab extends BaseTab {

    private static final int ROW = 18;
    private static final int GAP = 6;

    public RelicHelpersTab() {
        super("Relic Helpers");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Relic Triggerbot", "Relic Look");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        RelicHelpersConfig cfg = RelicHelpersConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int[] y = {contentY};
        int halfW = (contentWidth - GAP) / 2;
        int rightX = contentX + halfW + GAP;
        int rightW = Math.max(1, contentWidth - halfW - GAP);

        w.add(new StringWidget(contentX, y[0], contentWidth, 12, SectionHeaders.header("Relic Triggerbot", true), font));
        y[0] += 16;
        w.add(SettingsButtonWidget.builder(onOff("Relic Triggerbot", cfg.isTriggerEnabledRaw()), btn -> {
            cfg.setTriggerEnabled(!cfg.isTriggerEnabledRaw());
            cfg.save();
            requestRebuild.run();
        }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;
        if (cfg.isTriggerEnabledRaw()) {
            w.add(toggle("Pick Up Relics", cfg::isTriggerPickup, cfg::setTriggerPickup, cfg, contentX, y[0], halfW));
            w.add(toggle("Place Relics", cfg::isTriggerPlace, cfg::setTriggerPlace, cfg, rightX, y[0], rightW));
            y[0] += ROW + GAP;
            w.add(toggle("Swap To Relic", cfg::isSwapToRelic, cfg::setSwapToRelic, cfg, contentX, y[0], halfW));
            w.add(SettingsButtonWidget.cycling(cooldownText(cfg), (btn, dir) -> {
                cfg.setCooldownMs((int) SettingsButtonWidget.step(cfg.getCooldownMs(), 50, 0, RelicHelpersConfig.MAX_COOLDOWN_MS, dir));
                cfg.save();
                btn.setMessage(cooldownText(cfg));
            }).bounds(rightX, y[0], rightW, ROW).build());
            y[0] += ROW + GAP;
        }

        w.add(new StringWidget(contentX, y[0], contentWidth, 12, SectionHeaders.header("Relic Look", true), font));
        y[0] += 16;
        w.add(SettingsButtonWidget.builder(onOff("Relic Look", cfg.isLookEnabledRaw()), btn -> {
            cfg.setLookEnabled(!cfg.isLookEnabledRaw());
            cfg.save();
            requestRebuild.run();
        }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;
        if (cfg.isLookEnabledRaw()) {
            w.add(toggle("Look At Relic", cfg::isLookAtRelic, cfg::setLookAtRelic, cfg, contentX, y[0], halfW));
            w.add(toggle("Look At Cauldron", cfg::isLookAtCauldron, cfg::setLookAtCauldron, cfg, rightX, y[0], rightW));
            y[0] += ROW + GAP;
            w.add(SettingsButtonWidget.cycling(rangeText(cfg), (btn, dir) -> {
                cfg.setLookRange((int) SettingsButtonWidget.step(cfg.getLookRange(), 1, RelicHelpersConfig.MIN_LOOK_RANGE,
                        RelicHelpersConfig.MAX_LOOK_RANGE, dir));
                cfg.save();
                btn.setMessage(rangeText(cfg));
            }).bounds(contentX, y[0], halfW, ROW).build());
            y[0] += ROW + GAP;
        }
        return w;
    }

    private static SettingsButtonWidget toggle(String label, BooleanSupplier get, Consumer<Boolean> set, RelicHelpersConfig cfg,
                                               int x, int y, int width) {
        return SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
            set.accept(!get.getAsBoolean());
            cfg.save();
            btn.setMessage(onOff(label, get.getAsBoolean()));
        }).bounds(x, y, width, ROW).build();
    }

    private static Component cooldownText(RelicHelpersConfig cfg) {
        return Component.literal("Cooldown: " + cfg.getCooldownMs() + "ms");
    }

    private static Component rangeText(RelicHelpersConfig cfg) {
        return Component.literal("Look Range: " + cfg.getLookRange() + " blocks");
    }
}

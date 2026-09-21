package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.witherdragons.WitherDragonsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** M7 Phase 5 settings - Wither Dragons and King Relics (see
 *  {@link com.killer560.hub.witherdragons.WitherDragonsFeature} / {@code KingRelicsFeature} for the Odin /
 *  NoammAddons sources every timing, coordinate and priority rule is ported from). Both masters ship OFF; every
 *  change saves immediately. Info/render only, so no cheat-only (red) headers here. */
public class WitherDragonsTab extends BaseTab {

    public WitherDragonsTab() {
        super("Wither Dragons");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        WitherDragonsConfig cfg = WitherDragonsConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;

        header(w, contentX, y, contentWidth, "Wither Dragons");
        master(w, contentX, y, contentWidth, "Wither Dragons", cfg::isEnabled, cfg::setEnabled, cfg, requestRebuild);

        if (cfg.isEnabled()) {
            toggle(w, contentX, y[0], half, "Spawn Timers", cfg::isDragonTimer, cfg::setDragonTimer, cfg);
            w.add(SettingsButtonWidget.builder(cycleLabel("Timer Style", cfg.getTimerStyle().label), btn -> {
                        cfg.setTimerStyle(cfg.getTimerStyle().next());
                        cfg.save();
                        btn.setMessage(cycleLabel("Timer Style", cfg.getTimerStyle().label));
                    }).bounds(colB, y[0], half, 18).build());
            y[0] += 22;

            toggle(w, contentX, y[0], half, "Timer Symbol", cfg::isTimerSymbol, cfg::setTimerSymbol, cfg);
            toggle(w, colB, y[0], half, "Dragon Boxes", cfg::isDragonBoxes, cfg::setDragonBoxes, cfg);
            y[0] += 22;

            toggle(w, contentX, y[0], half, "Target Tracer", cfg::isDragonTracer, cfg::setDragonTracer, cfg);
            toggle(w, colB, y[0], half, "Dragon Health", cfg::isDragonHealth, cfg::setDragonHealth, cfg);
            y[0] += 22;

            toggle(w, contentX, y[0], half, "Dragon Title", cfg::isDragonTitle, cfg::setDragonTitle, cfg);
            w.add(SettingsButtonWidget.builder(cycleLabel("Title For", cfg.getTitleMode().label), btn -> {
                        cfg.setTitleMode(cfg.getTitleMode().next());
                        cfg.save();
                        btn.setMessage(cycleLabel("Title For", cfg.getTitleMode().label));
                    }).bounds(colB, y[0], half, 18).build());
            y[0] += 22;

            toggle(w, contentX, y[0], half, "Title Sound", cfg::isTitleSound, cfg::setTitleSound, cfg);
            w.add(SettingsButtonWidget.builder(cycleLabel("Dragon Names", cfg.getNameStyle().label), btn -> {
                        cfg.setNameStyle(cfg.getNameStyle().next());
                        cfg.save();
                        btn.setMessage(cycleLabel("Dragon Names", cfg.getNameStyle().label));
                    }).bounds(colB, y[0], half, 18).build());
            y[0] += 26;

            header(w, contentX, y, contentWidth, "Dragon Alerts");
            toggle(w, contentX, y[0], half, "Send Dragon Spawned", cfg::isSendSpawned, cfg::setSendSpawned, cfg);
            toggle(w, colB, y[0], half, "Send Time Alive", cfg::isSendTime, cfg::setSendTime, cfg);
            y[0] += 22;
            toggle(w, contentX, y[0], half, "Send Ice Sprayed", cfg::isSendSpray, cfg::setSendSpray, cfg);
            toggle(w, colB, y[0], half, "Send Arrows Hit", cfg::isSendArrows, cfg::setSendArrows, cfg);
            y[0] += 22;
            toggle(w, contentX, y[0], half, "Send Dragon Counts", cfg::isSendConfirmation, cfg::setSendConfirmation, cfg);
            y[0] += 26;

            header(w, contentX, y, contentWidth, "Dragon Priority");
            toggle(w, contentX, y[0], half, "Dragon Priority", cfg::isDragonPriority, cfg::setDragonPriority, cfg);
            toggle(w, colB, y[0], half, "Paul Buff", cfg::isPaulBuff, cfg::setPaulBuff, cfg);
            y[0] += 22;

            w.add(powerSlider(cfg, contentX, y[0], half, true));
            w.add(powerSlider(cfg, colB, y[0], half, false));
            y[0] += 22;

            w.add(SettingsButtonWidget.builder(cycleLabel("Purple Solo Debuff", cfg.getSoloDebuff().label), btn -> {
                        cfg.setSoloDebuff(cfg.getSoloDebuff().next());
                        cfg.save();
                        btn.setMessage(cycleLabel("Purple Solo Debuff", cfg.getSoloDebuff().label));
                    }).bounds(contentX, y[0], half, 18).build());
            toggle(w, colB, y[0], half, "Solo Debuff On All Splits", cfg::isSoloDebuffOnAll, cfg::setSoloDebuffOnAll, cfg);
            y[0] += 22;

            w.add(SettingsButtonWidget.builder(cycleLabel("Your Class", cfg.getClassOverride().label), btn -> {
                        cfg.setClassOverride(cfg.getClassOverride().next());
                        cfg.save();
                        btn.setMessage(cycleLabel("Your Class", cfg.getClassOverride().label));
                    }).bounds(contentX, y[0], half, 18).build());
            y[0] += 24;
        }

        header(w, contentX, y, contentWidth, "King Relics");
        master(w, contentX, y, contentWidth, "King Relics", cfg::isRelicsEnabled, cfg::setRelicsEnabled, cfg, requestRebuild);

        if (cfg.isRelicsEnabled()) {
            toggle(w, contentX, y[0], half, "Relic Spawn Timer", cfg::isRelicSpawnTimer, cfg::setRelicSpawnTimer, cfg);
            w.add(new ThemedSliderButton(colB, y[0], half, 18, relicTicksLabel(cfg), cfg.getRelicSpawnTicks() / 100.0) {
                @Override
                protected void updateMessage() {
                    setMessage(relicTicksLabel(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setRelicSpawnTicks((int) Math.round(this.value * 100));
                    cfg.save();
                }
            });
            y[0] += 22;

            toggle(w, contentX, y[0], half, "Highlight Cauldron", cfg::isRelicHighlight, cfg::setRelicHighlight, cfg);
            toggle(w, colB, y[0], half, "Cauldron Tracer", cfg::isRelicTracer, cfg::setRelicTracer, cfg);
            y[0] += 22;

            toggle(w, contentX, y[0], half, "Send Place Time", cfg::isRelicPlaceTime, cfg::setRelicPlaceTime, cfg);
            toggle(w, colB, y[0], half, "Party Relic Summary", cfg::isRelicSummary, cfg::setRelicSummary, cfg);
            y[0] += 26;
        }

        return w;
    }

    private static AbstractWidget powerSlider(WitherDragonsConfig cfg, int x, int y, int width, boolean normal) {
        return new ThemedSliderButton(x, y, width, 18, powerLabel(cfg, normal),
                (normal ? cfg.getNormalPower() : cfg.getEasyPower()) / 32.0) {
            @Override
            protected void updateMessage() {
                setMessage(powerLabel(cfg, normal));
            }

            @Override
            protected void applyValue() {
                float power = (float) (Math.round(this.value * 64) / 2.0);
                if (normal) {
                    cfg.setNormalPower(power);
                } else {
                    cfg.setEasyPower(power);
                }
                cfg.save();
            }
        };
    }

    private static Component powerLabel(WitherDragonsConfig cfg, boolean normal) {
        float value = normal ? cfg.getNormalPower() : cfg.getEasyPower();
        return Component.literal(String.format(Locale.US, "%s Power: §6%.1f", normal ? "Normal" : "Easy", value));
    }

    private static Component relicTicksLabel(WitherDragonsConfig cfg) {
        return Component.literal("Relic Spawn Ticks: §6" + cfg.getRelicSpawnTicks());
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void master(List<AbstractWidget> w, int x, int[] y, int width, String label, Supplier<Boolean> get,
                               Consumer<Boolean> set, WitherDragonsConfig cfg, Runnable requestRebuild) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    set.accept(!get.get());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<Boolean> get,
                               Consumer<Boolean> set, WitherDragonsConfig cfg) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    cfg.save();
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static Component cycleLabel(String label, String value) {
        return Component.literal(label + ": §6" + value);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

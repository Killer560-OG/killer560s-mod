package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonqueue.DungeonQueueConfig;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.partyfinder.PartyFinderOverlayConfig;
import com.killer560.hub.partyfinder.PartyFinderOverlayConfig.CompactMode;
import com.killer560.hub.partyfinder.PartyFinderOverlayConfig.PbMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** Dungeon Queue: Auto Requeue ({@link com.killer560.hub.dungeonqueue.DungeonQueueFeature}) and Party Finder
 *  Overlay ({@link com.killer560.hub.partyfinder.PartyFinderOverlay}). */
public class DungeonQueueTab extends BaseTab {

    public DungeonQueueTab() {
        super("Dungeon Queue");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colBX = contentX + colW + gap;
        int y = contentY;

        // ---------------------------------------------------------------- Auto Requeue
        DungeonQueueConfig queue = DungeonQueueConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Requeue", queue.isEnabledRaw()), btn -> {
                    queue.setEnabled(!queue.isEnabledRaw());
                    queue.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (queue.isEnabledRaw()) {
            widgets.add(new ThemedSliderButton(contentX, y, colW, 18, delayText(queue),
                    queue.getDelaySeconds() / (double) DungeonQueueConfig.MAX_DELAY_SECONDS) {
                @Override
                protected void updateMessage() {
                    setMessage(delayText(queue));
                }

                @Override
                protected void applyValue() {
                    queue.setDelaySeconds((int) Math.round(this.value * DungeonQueueConfig.MAX_DELAY_SECONDS));
                    queue.save();
                }
            });
            widgets.add(toggle("Disable on Leave/Kick", queue::isDisableOnLeave, queue::setDisableOnLeave, queue::save,
                    colBX, y, colW));
            y += 24;
        }
        y += 6;

        // ---------------------------------------------------------------- Party Finder Overlay
        PartyFinderOverlayConfig pf = PartyFinderOverlayConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Party Finder Overlay", pf.isEnabledRaw()), btn -> {
                    pf.setEnabled(!pf.isEnabledRaw());
                    pf.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!pf.isEnabledRaw()) {
            return widgets;
        }

        // Highlight
        widgets.add(rebuildToggle("Highlight", pf::isHighlight, pf::setHighlight, pf::save, requestRebuild,
                contentX, y, contentWidth));
        y += 20;
        if (pf.isHighlight()) {
            widgets.add(toggle("Ignore Cata Level", pf::isIgnoreCataRequirement, pf::setIgnoreCataRequirement, pf::save,
                    contentX, y, colW));
            widgets.add(toggle("Ignore Class Level", pf::isIgnoreRoleLevel, pf::setIgnoreRoleLevel, pf::save,
                    colBX, y, colW));
            y += 20;
            widgets.add(toggle("Ignore Own Class", pf::isIgnoreOwnRole, pf::setIgnoreOwnRole, pf::save,
                    contentX, y, contentWidth));
            y += 20;
            widgets.add(colorButton("Joinable Color", pf::getJoinableColor, pf::setJoinableColor,
                    PartyFinderOverlayConfig.DEFAULT_JOINABLE_COLOR, pf::save, contentX, y, colW));
            widgets.add(colorButton("Blocked Color", pf::getBlockedColor, pf::setBlockedColor,
                    PartyFinderOverlayConfig.DEFAULT_BLOCKED_COLOR, pf::save, colBX, y, colW));
            y += 20;
        }
        y += 4;

        // Member Count
        widgets.add(rebuildToggle("Member Count", pf::isMemberCount, pf::setMemberCount, pf::save, requestRebuild,
                contentX, y, pf.isMemberCount() ? colW : contentWidth));
        if (pf.isMemberCount()) {
            widgets.add(colorButton("Count Color", pf::getCountColor, pf::setCountColor,
                    PartyFinderOverlayConfig.DEFAULT_COUNT_COLOR, pf::save, colBX, y, colW));
        }
        y += 24;

        // Tooltip Stats
        widgets.add(rebuildToggle("Tooltip Stats", pf::isTooltip, pf::setTooltip, pf::save, requestRebuild,
                contentX, y, contentWidth));
        y += 20;
        if (!pf.isTooltip()) {
            return widgets;
        }
        widgets.add(toggle("Show Missing", pf::isShowMissing, pf::setShowMissing, pf::save, contentX, y, colW));
        widgets.add(SettingsButtonWidget.builder(valueText("PB Mode", pf.getPbMode().label), btn -> {
                    PbMode[] modes = PbMode.values();
                    pf.setPbMode(modes[(pf.getPbMode().ordinal() + 1) % modes.length]);
                    pf.save();
                    btn.setMessage(valueText("PB Mode", pf.getPbMode().label));
                }).bounds(colBX, y, colW, 18).build());
        y += 20;
        widgets.add(SettingsButtonWidget.builder(valueText("Compact", pf.getCompactMode().label), btn -> {
                    CompactMode[] modes = CompactMode.values();
                    pf.setCompactMode(modes[(pf.getCompactMode().ordinal() + 1) % modes.length]);
                    pf.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(toggle("Rank Name Colors", pf::isRankNameColors, pf::setRankNameColors, pf::save, colBX, y, colW));
        y += 20;
        if (pf.getCompactMode() == CompactMode.CUSTOM) {
            EditBox style = new EditBox(Minecraft.getInstance().font, contentX, y, contentWidth, 18,
                    Component.literal("Custom Style"));
            style.setMaxLength(512);
            style.setHint(Component.literal("Custom Style"));
            style.setValue(pf.getCustomStyle());
            style.setResponder(text -> {
                pf.setCustomStyle(text);
                pf.save();
            });
            widgets.add(style);
        }
        return widgets;
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

    private static AbstractWidget rebuildToggle(String label, BoolGetter getter, BoolSetter setter, Runnable save,
                                                Runnable requestRebuild, int x, int y, int w) {
        return SettingsButtonWidget.builder(onOff(label, getter.get()), btn -> {
                    setter.set(!getter.get());
                    save.run();
                    requestRebuild.run();
                }).bounds(x, y, w, 18).build();
    }

    private static AbstractWidget colorButton(String label, IntSupplier getter, IntConsumer setter, int defaultArgb,
                                              Runnable save, int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, getter.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, label, getter.getAsInt(), defaultArgb, argb -> {
                        setter.accept(argb);
                        save.run();
                    }));
                }).bounds(x, y, w, 18).build();
    }

    private static Component delayText(DungeonQueueConfig cfg) {
        return Component.literal("Requeue Delay: " + cfg.getDelaySeconds() + "s");
    }

    private static Component valueText(String label, String value) {
        return Component.literal(label + ": §6" + value);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

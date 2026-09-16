package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.packdisabler.PackDisablerConfig;
import com.killer560.hub.packdisabler.PackDisablerFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Pack Disabler settings - see {@link PackDisablerFeature}'s class doc (blocks Hypixel's forced Skyblock pack). */
public class PackDisablerTab extends BaseTab {

    public PackDisablerTab() {
        super("Pack Disabler");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PackDisablerConfig cfg = PackDisablerConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;
        int gap = 8;
        int halfW = (contentWidth - gap) / 2;

        widgets.add(SettingsButtonWidget.builder(onOff("Pack Disabler", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Stops server resource packs (Hypixel's Skyblock pack) from downloading/loading."), font));
        y += 16;

        String conflict = com.killer560.hub.packdisabler.PackDisablerFeature.conflictingModId();
        if (conflict != null) {
            // Both mods cancel the same packet, so running both is a coin flip over which one wins. Say so
            // rather than showing an ON toggle that is doing nothing (2026-09-16).
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§c" + conflict + " is installed and does this too - this feature is standing down."), font));
            y += 14;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Remove that mod to use this one, or leave it - it also restores the old item textures."), font));
            y += 16;
        }

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Only On hypixel.net", cfg.isHypixelOnly()), btn -> {
                    cfg.setHypixelOnly(!cfg.isHypixelOnly());
                    cfg.save();
                    btn.setMessage(onOff("Only On hypixel.net", cfg.isHypixelOnly()));
                }).bounds(contentX, y, halfW, 18).build());
        widgets.add(SettingsButtonWidget.builder(onOff("Chat Notice", cfg.isChatNotice()), btn -> {
                    cfg.setChatNotice(!cfg.isChatNotice());
                    cfg.save();
                    btn.setMessage(onOff("Chat Notice", cfg.isChatNotice()));
                }).bounds(contentX + halfW + gap, y, halfW, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    cfg.setMode(cfg.getMode().next());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        String explain = switch (cfg.getMode()) {
            case SMART -> "Declines optional packs; pretends to load packs the server requires (avoids kicks).";
            case DECLINE -> "Always declines. Servers that require their pack may disconnect you.";
            case FAKE_LOADED -> "Always tells the server the pack loaded, without downloading or applying it.";
        };
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§7" + explain), font));
        y += 18;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Unload Server Packs Now"), btn ->
                    PackDisablerFeature.unloadServerPacksNow()
                ).bounds(contentX, y, halfW, 18).build());
        widgets.add(new StringWidget(contentX + halfW + gap, y + 3, halfW, 12,
                Component.literal("§7(if one loaded before enabling)"), font));
        return widgets;
    }

    private static Component modeText(PackDisablerConfig cfg) {
        return Component.literal("Mode: §b" + cfg.getMode().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.dungeonextras.DungeonExtrasConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Dialogue - see {@link com.killer560.hub.dungeonextras.DungeonExtrasFeature}. Cheat build only; split
 *  out of the old "Dungeon Extras" tab 2026-09-20 per killer560: "make auto dialoug its own category as
 *  well" - shares {@link DungeonExtrasConfig} with {@link CustomMageBeamTab} and {@link BreakerAuraTab};
 *  the config file itself was not split. */
public class AutoDialogueTab extends BaseTab {

    public AutoDialogueTab() {
        super("Auto Dialogue");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Dialogue", cfg.isAutoDialogueEnabledRaw()), btn -> {
                    cfg.setAutoDialogueEnabled(!cfg.isAutoDialogueEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        if (!cfg.isAutoDialogueEnabledRaw()) {
            return widgets;
        }

        widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, delayText(cfg),
                cfg.getAutoDialogueDelayTicks() / 40.0) {
            @Override
            protected void updateMessage() {
                setMessage(delayText(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setAutoDialogueDelayTicks((int) Math.round(this.value * 40));
                cfg.save();
            }
        });
        y += 20;

        EditBox filter = new EditBox(mc.font, contentX, y, contentWidth, 18, Component.literal("NPC filter"));
        filter.setMaxLength(200);
        filter.setHint(Component.literal("NPC names, comma-separated (blank = any)"));
        filter.setValue(cfg.getAutoDialogueNpcFilter());
        filter.setResponder(text -> {
            cfg.setAutoDialogueNpcFilter(text);
            cfg.save();
        });
        widgets.add(filter);
        y += 26;

        widgets.add(SettingsButtonWidget.builder(onOff("Outside Dungeons", cfg.isAutoDialogueOutsideDungeons()), btn -> {
                    cfg.setAutoDialogueOutsideDungeons(!cfg.isAutoDialogueOutsideDungeons());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        return widgets;
    }

    private static Component delayText(DungeonExtrasConfig cfg) {
        return Component.literal("Delay: " + cfg.getAutoDialogueDelayTicks() + " ticks");
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

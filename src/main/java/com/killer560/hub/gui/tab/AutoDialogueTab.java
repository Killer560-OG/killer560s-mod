package com.killer560.hub.gui.tab;

import com.killer560.hub.carnival.AutoAcceptGameConfig;
import com.killer560.hub.dungeonextras.DungeonExtrasConfig;
import com.killer560.hub.gui.ModuleKeybinds;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Auto Dialogue - see {@link com.killer560.hub.dungeonextras.DungeonExtrasFeature}. Cheat build only; split
 *  out of the old "Dungeon Extras" tab 2026-09-20 per killer560: "make auto dialoug its own category as
 *  well" - shares {@link DungeonExtrasConfig} with {@link CustomMageBeamTab} and {@link BreakerAuraTab};
 *  the config file itself was not split. Answers anywhere on Skyblock (no dungeon gate, 2026-11-14); its
 *  "Carnival Games" row is the old Carnival Auto Accept Game (killer560: "for carnival it should be under the npc dialogue option"). */
public class AutoDialogueTab extends BaseTab {

    public AutoDialogueTab() {
        super("Auto Dialogue");
    }

    /** Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
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
        int y = contentY;

        boolean closed = masterClosed(widgets, "Auto Dialogue", cfg::isAutoDialogueEnabledRaw, cfg::setAutoDialogueEnabled,
                cfg::save, requestRebuild, contentX, y, contentWidth);
        y += 24;
        if (!closed) {
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18, delayText(cfg),
                    cfg.getAutoDialogueDelayTicks() / 40.0) {
                @Override
                protected void updateMessage() {
                    setMessage(delayText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setAutoDialogueDelayTicks((int) Math.round(this.value * 40));
                    saveOnRelease(() -> cfg.save());
                }
            });
            y += 24;
        }

        // Carnival Games runs on its own flag, so it is built while the switch above is off.
        AutoAcceptGameConfig carnival = AutoAcceptGameConfig.getInstance();
        widgets.add(onOffButton("Carnival Games", carnival::isEnabledRaw, carnival::setEnabled, carnival::save, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        widgets.add(ModuleKeybinds.row("AutoAcceptGameTab", contentX, y, contentWidth));
        return widgets;
    }

    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Auto Dialogue", "Carnival Games");
    }

    private static Component delayText(DungeonExtrasConfig cfg) {
        return Component.literal("Delay: " + cfg.getAutoDialogueDelayTicks() + " ticks");
    }
}

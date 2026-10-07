package com.killer560.hub.gui.tab;

import com.killer560.hub.chattidy.ChatTidyConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Chat Tidy (killer560, 2026-10-07): Stack Duplicate Messages and the Damage Message Hider with its two families.
 *  See {@link com.killer560.hub.chattidy.ChatTidy}. Every row is always shown, so no row hides behind a master switch. */
public class ChatTidyTab extends BaseTab {

    public ChatTidyTab() {
        super("Chat Tidy");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ChatTidyConfig cfg = ChatTidyConfig.getInstance();
        int[] y = {contentY};
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Stack Duplicate Messages", cfg::getStackDuplicatesRaw, cfg::setStackDuplicates);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Damage Messages", cfg::getHideDamageMessagesRaw, cfg::setHideDamageMessages);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Ability Damage Lines", cfg::getHideAbilityDamageRaw, cfg::setHideAbilityDamage);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Incoming Hit Lines", cfg::getHideIncomingHitsRaw, cfg::setHideIncomingHits);
        return widgets;
    }

    private static void toggle(List<AbstractWidget> widgets, int x, int width, int[] y, ChatTidyConfig cfg,
                               Runnable requestRebuild, String label, BooleanSupplier get, Consumer<Boolean> set) {
        widgets.add(SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
                    set.accept(!get.getAsBoolean());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
    }
}

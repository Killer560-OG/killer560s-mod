package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mobesp.MobEspConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Star Mob Hitbox ESP settings - see {@link com.killer560.hub.mobesp.MobEspFeature}'s own doc for the
 *  legit (visible-only, real raycast) vs. cheat (through walls) distinction. The cheat toggle only
 *  appears at all on the cheat build - the legit build has no through-wall option whatsoever, same
 *  pattern every other real rule-violating toggle in this mod already uses. */
public class MobEspTab extends BaseTab {

    public MobEspTab() {
        super("Mob Hitbox ESP");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        MobEspConfig cfg = MobEspConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(enabledText(), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            widgets.add(SettingsButtonWidget.builder(cheatModeText(), btn -> {
                        cfg.setCheatMode(!cfg.isCheatMode());
                        cfg.save();
                        btn.setMessage(cheatModeText());
                    }).bounds(contentX, y, 260, 20).build());
            y += 24;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Cheat mode glows through walls - a real rule violation."),
                    Minecraft.getInstance().font));
            y += 20;
        } else {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("Glows a star mob only while you have real, clear line of sight."),
                    Minecraft.getInstance().font));
            y += 18;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Name filter (matches anywhere in the mob's display name):"),
                Minecraft.getInstance().font));
        y += 14;
        EditBox filterField = new EditBox(Minecraft.getInstance().font, contentX, y, 120, 18, Component.literal("Filter"));
        filterField.setMaxLength(20);
        filterField.setValue(cfg.getNameFilter());
        filterField.setResponder(text -> {
            cfg.setNameFilter(text);
            cfg.save();
        });
        widgets.add(filterField);
        y += 24;

        widgets.add(SettingsButtonWidget.builder(rangeText(cfg), btn -> {
                    double next = cfg.getRange() + 5;
                    cfg.setRange(next > 60 ? 10 : next);
                    cfg.save();
                    btn.setMessage(rangeText(cfg));
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private static Component rangeText(MobEspConfig cfg) {
        return Component.literal(String.format(java.util.Locale.US, "Range: %.0f blocks", cfg.getRange()));
    }

    private static Component enabledText() {
        return Component.literal("Mob Hitbox ESP: " + (MobEspConfig.getInstance().isEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component cheatModeText() {
        return Component.literal("Mode: §b" + (MobEspConfig.getInstance().isCheatMode() ? "Cheat (Through Walls)" : "Legit (Visible Only)"));
    }
}

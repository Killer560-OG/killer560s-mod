package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.secrets.SecretsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Secrets settings: an independent toggle for each covered block type's expanded interaction hitbox -
 *  killer560's explicit request (2026-09-09), "each should have their own toggle under a main secrets
 *  tab in dungeons." Buttons additionally gets a Flat/Full Box shape choice. See
 *  {@link com.killer560.hub.secrets.SecretsFeature} for the real mechanic (ported from and cross-checked
 *  against both quoi's and NoammAddons' own reference implementations). */
public class SecretsTab extends BaseTab {

    public SecretsTab() {
        super("Secrets");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("Expands interaction hitboxes for easier clicking."),
                Minecraft.getInstance().font));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(leversText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setLeversEnabled(!cfg.isLeversEnabled());
                    cfg.save();
                    btn.setMessage(leversText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(buttonsText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setButtonsEnabled(!cfg.isButtonsEnabled());
                    cfg.save();
                    btn.setMessage(buttonsText());
                }).bounds(contentX, y, 220, 20).build());
        y += 24;

        widgets.add(SettingsButtonWidget.builder(buttonShapeText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setButtonsFullBox(!cfg.isButtonsFullBox());
                    cfg.save();
                    btn.setMessage(buttonShapeText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(chestsText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setChestsEnabled(!cfg.isChestsEnabled());
                    cfg.save();
                    btn.setMessage(chestsText());
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.builder(essenceText(), btn -> {
                    SecretsConfig cfg = SecretsConfig.getInstance();
                    cfg.setEssenceEnabled(!cfg.isEssenceEnabled());
                    cfg.save();
                    btn.setMessage(essenceText());
                }).bounds(contentX, y, 220, 20).build());

        return widgets;
    }

    private static Component leversText() {
        return Component.literal("Levers: " + (SecretsConfig.getInstance().isLeversEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component buttonsText() {
        return Component.literal("Buttons: " + (SecretsConfig.getInstance().isButtonsEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component buttonShapeText() {
        return Component.literal("Button Shape: §b" + (SecretsConfig.getInstance().isButtonsFullBox() ? "Full Box" : "Flat"));
    }

    private static Component chestsText() {
        return Component.literal("Chests: " + (SecretsConfig.getInstance().isChestsEnabled() ? "§aON" : "§cOFF"));
    }

    private static Component essenceText() {
        return Component.literal("Wither Essence: " + (SecretsConfig.getInstance().isEssenceEnabled() ? "§aON" : "§cOFF"));
    }
}

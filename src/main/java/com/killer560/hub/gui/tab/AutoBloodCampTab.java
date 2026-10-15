package com.killer560.hub.gui.tab;

import com.killer560.hub.bloodcamp.BloodCampConfig;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Blood Camp - Blood Camp's cheat-build automation (Trigger Bot, Aura, and the Trigger Bot's timing), split
 *  out of {@link BloodCampTab}'s old "Cheat Build - Automation" section on 2026-10-07 (killer560: "make auto blood
 *  camp its own cheat tab"). Same {@link BloodCampConfig} fields and JSON keys as before, nothing renamed. Both
 *  automations act on Blood Camp's own predictions, so they still need Blood Camp itself ON (in its own tab); the
 *  note below says so when it is OFF. Only added to its category behind {@code CHEAT_FEATURES_ENABLED}. */
public class AutoBloodCampTab extends BaseTab {

    public AutoBloodCampTab() {
        super("Auto Blood Camp");
    }

    /** Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    /** The card is ON while any of its features is, OFF only when all are (killer560, 2026-10-14): Trigger Bot and Aura are separate automations. */
    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Trigger Bot", "Aura");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        int y = contentY;
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        int gap = 8;
        int col2W = (contentWidth - gap) / 2;
        int col2aX = contentX;
        int col2bX = contentX + col2W + gap;

        if (!cfg.isEnabledRaw()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7Needs Blood Camp ON (its own tab) - it predicts the mobs these act on."),
                    net.minecraft.client.Minecraft.getInstance().font));
            y += 16;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Trigger Bot", cfg.getTriggerBotRaw()), btn -> {
                    cfg.setTriggerBotEnabled(!cfg.getTriggerBotRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(col2aX, y, col2W, 18).build());

        widgets.add(SettingsButtonWidget.builder(onOff("Aura", cfg.getAuraRaw()), btn -> {
                    cfg.setAuraEnabled(!cfg.getAuraRaw());
                    cfg.save();
                    requestRebuild.run(); // shows or hides Freecam Mode
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        if (cfg.getTriggerBotRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Auto Detect Lag", cfg.isAutoDetectLag()), btn -> {
                        cfg.setAutoDetectLag(!cfg.isAutoDetectLag());
                        cfg.save();
                        btn.setMessage(onOff("Auto Detect Lag", cfg.isAutoDetectLag()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 20;

            // killer560, 2026-10-15: "Have the slider go from -10 to 10 and have it default to -1" - ticks from the kill
            // timer, positive early, negative late.
            int span = BloodCampConfig.MAX_TRIGGER_OFFSET_TICKS - BloodCampConfig.MIN_TRIGGER_OFFSET_TICKS;
            double offsetNorm = (cfg.getTriggerOffsetTicks() - BloodCampConfig.MIN_TRIGGER_OFFSET_TICKS) / (double) span;
            widgets.add(new ThemedSliderButton(contentX, y, contentWidth, 18,
                    Component.literal(offsetLabel(cfg.getTriggerOffsetTicks())), offsetNorm) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal(offsetLabel(cfg.getTriggerOffsetTicks())));
                }

                @Override
                protected void applyValue() {
                    cfg.setTriggerOffsetTicks(BloodCampConfig.MIN_TRIGGER_OFFSET_TICKS
                            + (int) Math.round(this.value * span));
                    cfg.save();
                }
            });
            y += 20;
        }

        if (cfg.getAuraRaw()) {
            widgets.add(SettingsButtonWidget.builder(onOff("Freecam Mode", cfg.isAuraFreecam()), btn -> {
                        cfg.setAuraFreecam(!cfg.isAuraFreecam());
                        cfg.save();
                        btn.setMessage(onOff("Freecam Mode", cfg.isAuraFreecam()));
                    }).bounds(contentX, y, contentWidth, 18).build());
        }

        return widgets;
    }

    /** "Trigger Offset: -1t (late)" - which way the click moves from the kill timer. */
    static String offsetLabel(int ticks) {
        String dir = ticks < 0 ? " (late)" : ticks > 0 ? " (early)" : "";
        return "Trigger Offset: " + (ticks > 0 ? "+" : "") + ticks + "t" + dir;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

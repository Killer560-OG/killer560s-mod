package com.killer560.hub.gui.tab;

import com.killer560.hub.bloodcamp.BloodCampConfig;
import com.killer560.hub.gui.SectionHeaders;
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

    /** True while the menu search builds the page to read its labels: every row is built, so the search finds Trigger
     *  Offset (and the rest of the Attack Speed group) whatever Never Miss and the switches say (HealthAndManaBarsTab's
     *  pattern). */
    private boolean scanningForSearch;

    @Override
    public List<String> searchHits(String query) {
        scanningForSearch = true;
        try {
            return super.searchHits(query);
        } finally {
            scanningForSearch = false;
        }
    }

    @Override
    protected boolean widgetsMatchSearch(String query) {
        scanningForSearch = true;
        try {
            return super.widgetsMatchSearch(query);
        } finally {
            scanningForSearch = false;
        }
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

        widgets.add(onOffButton("Trigger Bot", cfg::getTriggerBotRaw, cfg::setTriggerBotEnabled, cfg::save,
                requestRebuild, col2aX, y, col2W, 18));

        widgets.add(SettingsButtonWidget.builder(onOff("Aura", cfg.getAuraRaw()), btn -> {
                    cfg.setAuraEnabled(!cfg.getAuraRaw());
                    cfg.save();
                    requestRebuild.run(); // shows or hides Freecam Mode
                }).bounds(col2bX, y, col2W, 18).build());
        y += 22;

        // killer560, 2026-11-01: "Have an option to only toggle as mage." Both automations, so above the Aura's own row.
        widgets.add(onOffButton("Mage Only", cfg::isMageOnly, cfg::setMageOnly, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 20;
        widgets.add(onOffButton("Work Just Outside Blood Room", cfg::isOutsideRoom, cfg::setOutsideRoom, cfg::save,
                null, contentX, y, contentWidth, 18));
        y += 20;

        if (cfg.getAuraRaw()) {
            // The Aura's own option, so it sits with the switches above the Attack Speed group.
            widgets.add(onOffButton("Freecam Mode", cfg::isAuraFreecam, cfg::setAuraFreecam, cfg::save,
                    null, contentX, y, contentWidth, 18));
            y += 20;
        }

        // killer560, 2026-10-18: "For this menu make a small separator for the attack speed related stuff." Everything
        // about WHEN a click goes - Auto Detect Lag, Trigger Offset, Never Miss - under one small header. Cheat-only
        // tab, so the red SectionHeaders style.
        boolean timing = cfg.getTriggerBotRaw() || cfg.getAuraRaw() || scanningForSearch;
        if (timing) {
            y += 2;
            widgets.add(SectionHeaders.row(contentX, y, contentWidth, ATTACK_SPEED_HEADER, true));
            y += 14;
        }

        // killer560, 2026-10-19: Auto Detect Lag and Trigger Offset show (and act) for the Aura as well - the Aura's first
        // click on a mob on its way already goes at triggerTargetTicks, and every Blood Camp click waits for the kill
        // timer moved by the offset (BloodCampFeature#tick).
        if (timing) {
            widgets.add(onOffButton("Auto Detect Lag", cfg::isAutoDetectLag, cfg::setAutoDetectLag, cfg::save,
                    null, contentX, y, contentWidth, 18));
            y += 20;
        }

        // killer560, 2026-11-03: with Never Miss on, Trigger Offset "shouldn't control it" - it does not apply
        // (BloodCampFeature#effectiveTriggerOffsetTicks), so the row is hidden; the saved value is kept for when Never Miss
        // goes off again. The search scan still builds it.
        if (timing && (!cfg.isNeverMiss() || scanningForSearch)) {

            // killer560, 2026-10-15: "Have the slider go from -10 to 10"; 2026-11-01: "if i slide it left it is early and
            // right it is late". Ticks from the kill timer, negative (left) early, positive (right) late, default +1.
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
                    saveOnRelease(() -> cfg.save());
                }
            });
            y += 20;
        }

        if (timing) {
            // killer560, 2026-10-15: "add a never miss setting where it attacks the tick it detects the mob is actually
            // there not just when it thinks it will spawn". Rebuilds: it hides or shows Trigger Offset.
            widgets.add(onOffButton("Never Miss", cfg::isNeverMiss, cfg::setNeverMiss, cfg::save,
                    requestRebuild, contentX, y, contentWidth, 18));
            y += 20;
        }

        return widgets;
    }

    /** The small section header over the attack timing settings. */
    static final String ATTACK_SPEED_HEADER = "Attack Speed";

    /** "Trigger Offset: +1t (late)" - which way the click moves from the kill timer (positive late since 2026-11-01). */
    static String offsetLabel(int ticks) {
        String dir = ticks > 0 ? " (late)" : ticks < 0 ? " (early)" : "";
        return "Trigger Offset: " + (ticks > 0 ? "+" : "") + ticks + "t" + dir;
    }
}

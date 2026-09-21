package com.killer560.hub.gui.tab;

import com.killer560.hub.abilitycooldown.AbilityCooldownConfig;
import com.killer560.hub.abilitycooldown.ItemAbility;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Automatic Ability Cooldowns settings - see
 *  {@link com.killer560.hub.abilitycooldown.AbilityCooldownFeature} and
 *  {@link ItemAbility} for every cooldown value and where it was ported from. Master ships OFF; every
 *  change saves immediately. Informational only, so no cheat-only (red) headers here.
 *  <p>Explanatory text lives in tooltips (hover), not on the panel - see {@code SettingTooltipsData}'s
 *  {@code "ability cooldowns"} keys. Only dynamic/status lines are drawn directly. */
public class AbilityCooldownTab extends BaseTab {

    public AbilityCooldownTab() {
        super("Ability Cooldowns");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        AbilityCooldownConfig cfg = AbilityCooldownConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;

        header(w, contentX, y, contentWidth, "Ability Cooldowns");
        w.add(SettingsButtonWidget.builder(onOff("Ability Cooldowns", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (!cfg.isEnabledRaw()) {
            return w;
        }

        header(w, contentX, y, contentWidth, "Display");
        toggle(w, contentX, y[0], half, "Dungeon Abilities Only", cfg::isDungeonOnly, v -> {
            cfg.setDungeonOnly(v);
            requestRebuild.run();
        }, cfg);
        toggle(w, colB, y[0], half, "Text Only", cfg::isTextOnly, cfg::setTextOnly, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], half, "Show When Ready", cfg::isShowWhenReady, cfg::setShowWhenReady, cfg);
        toggle(w, colB, y[0], half, "Colour By Remaining", cfg::isColorByRemaining, cfg::setColorByRemaining, cfg);
        y[0] += 22;
        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, linesLabel(cfg),
                (cfg.getMaxLines() - 1) / 11.0) {
            @Override
            protected void updateMessage() {
                setMessage(linesLabel(cfg));
            }

            @Override
            protected void applyValue() {
                cfg.setMaxLines(1 + (int) Math.round(this.value * 11.0));
                cfg.save();
            }
        });
        y[0] += 24;

        header(w, contentX, y, contentWidth, "Detection");
        toggle(w, contentX, y[0], half, "Sound Detection", cfg::isSoundDetection, cfg::setSoundDetection, cfg);
        toggle(w, colB, y[0], half, "Action Bar Detection", cfg::isActionBarDetection,
                cfg::setActionBarDetection, cfg);
        y[0] += 22;
        w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, clickWindowLabel(cfg),
                (cfg.getClickWindowMs() - AbilityCooldownConfig.MIN_CLICK_WINDOW_MS)
                        / (double) (AbilityCooldownConfig.MAX_CLICK_WINDOW_MS
                        - AbilityCooldownConfig.MIN_CLICK_WINDOW_MS)) {
            @Override
            protected void updateMessage() {
                setMessage(clickWindowLabel(cfg));
            }

            @Override
            protected void applyValue() {
                int range = AbilityCooldownConfig.MAX_CLICK_WINDOW_MS - AbilityCooldownConfig.MIN_CLICK_WINDOW_MS;
                cfg.setClickWindowMs((int) (Math.round(
                        (AbilityCooldownConfig.MIN_CLICK_WINDOW_MS + this.value * range) / 25.0) * 25));
                cfg.save();
            }
        });
        y[0] += 24;

        header(w, contentX, y, contentWidth, "Mage Cooldown Reduction");
        // killer560, 2026-09-21: "am I a Mage" is now auto-detected (AbilityCooldownState#multiplier,
        // PartyTracker.selfClass()) - the old "Unique Class" / "Mage Class Level" toggles are gone. Only the
        // SIZE of the cut stays a setting, same shape as RagAxeTab's "Mage Cut" button.
        toggle(w, contentX, y[0], half, "Mage Reduction", cfg::isMageReduction, cfg::setMageReduction, cfg);
        if (cfg.isMageReduction()) {
            w.add(SettingsButtonWidget.builder(mageCutLabel(cfg), btn -> {
                        float next = cfg.getMageCooldownReductionPercent() + 5f;
                        cfg.setMageCooldownReductionPercent(
                                next > AbilityCooldownConfig.MAX_MAGE_REDUCTION_PERCENT ? 0f : next);
                        cfg.save();
                        btn.setMessage(mageCutLabel(cfg));
                    }).bounds(colB, y[0], half, 18).build());
        }
        y[0] += 24;

        header(w, contentX, y, contentWidth, "Abilities");
        boolean left = true;
        for (ItemAbility ability : ItemAbility.values()) {
            if (cfg.isDungeonOnly() && !ability.isDungeon()) {
                continue;
            }
            String label = ability.label() + " (" + ability.cooldownSeconds() + "s)";
            toggle(w, left ? contentX : colB, y[0], half, label,
                    () -> cfg.isAbilityEnabled(ability), v -> cfg.setAbilityEnabled(ability, v), cfg);
            if (!left) {
                y[0] += 20;
            }
            left = !left;
        }
        if (!left) {
            y[0] += 20;
        }
        return w;
    }

    private static Component linesLabel(AbilityCooldownConfig cfg) {
        return Component.literal("Max Lines: " + cfg.getMaxLines());
    }

    private static Component clickWindowLabel(AbilityCooldownConfig cfg) {
        return Component.literal("Click Window: " + cfg.getClickWindowMs() + "ms");
    }

    private static Component mageCutLabel(AbilityCooldownConfig cfg) {
        return Component.literal(String.format(Locale.US, "Mage Cut: §6%.0f%%",
                cfg.getMageCooldownReductionPercent()));
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label,
                               Supplier<Boolean> get, Consumer<Boolean> set, AbilityCooldownConfig cfg) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    boolean now = !get.get();
                    set.accept(now);
                    cfg.save();
                    btn.setMessage(onOff(label, now));
                }).bounds(x, y, width, 18).build());
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

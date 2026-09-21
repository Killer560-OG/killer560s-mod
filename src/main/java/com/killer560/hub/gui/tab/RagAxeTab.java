package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.ragaxe.RagAxeConfig;
import com.killer560.hub.ragaxe.RagAxePrompt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Rag Axe settings - Ragnarock cast detection, the channel/buff/cooldown timers and the built-in "rag now"
 *  prompts (see {@link com.killer560.hub.ragaxe.RagAxeFeature} and {@link RagAxePrompt} for every timing and
 *  its source). Master and all prompts ship OFF; every change saves immediately. Informational only, so no
 *  cheat-only (red) headers here. */
public class RagAxeTab extends BaseTab {

    public RagAxeTab() {
        super("Rag Axe");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        RagAxeConfig cfg = RagAxeConfig.getInstance();
        int[] y = {contentY};
        int gap = 8;
        int half = Math.max(100, (contentWidth - gap) / 2);
        int colB = contentX + half + gap;

        header(w, contentX, y, contentWidth, "Rag Axe");
        master(w, contentX, y, contentWidth, "Rag Axe", cfg::isEnabled, cfg::setEnabled, cfg, requestRebuild);

        if (!cfg.isEnabled()) {
            w.add(new StringWidget(contentX, y[0], contentWidth, 12,
                    Component.literal("§7Ragnarock Axe: 3s channel, 10s strength buff, 20s cooldown."),
                    Minecraft.getInstance().font));
            return w;
        }

        header(w, contentX, y, contentWidth, "Cast");
        toggle(w, contentX, y[0], half, "Cast Alert", cfg::isCastAlert, cfg::setCastAlert, cfg);
        toggle(w, colB, y[0], half, "Cancel Alert", cfg::isCancelAlert, cfg::setCancelAlert, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], half, "Strength Message", cfg::isStrengthMessage, cfg::setStrengthMessage, cfg);
        toggle(w, colB, y[0], half, "Announce in Party", cfg::isAnnounceStrength, cfg::setAnnounceStrength, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], contentWidth, "Sound Is Buff Start", cfg::isSoundIsBuffStart, cfg::setSoundIsBuffStart, cfg);
        y[0] += 26;

        header(w, contentX, y, contentWidth, "Timers");
        toggle(w, contentX, y[0], half, "Channel Countdown", cfg::isChannelTimer, cfg::setChannelTimer, cfg);
        toggle(w, colB, y[0], half, "Buff Countdown", cfg::isBuffTimer, cfg::setBuffTimer, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], half, "Cooldown Countdown", cfg::isCooldownTimer, cfg::setCooldownTimer, cfg);
        w.add(new ThemedSliderButton(colB, y[0], half, 18, cooldownLabel(cfg),
                (cfg.getCooldownSeconds() - RagAxeConfig.MIN_COOLDOWN_S)
                        / (RagAxeConfig.MAX_COOLDOWN_S - RagAxeConfig.MIN_COOLDOWN_S)) {
            @Override
            protected void updateMessage() {
                setMessage(cooldownLabel(cfg));
            }

            @Override
            protected void applyValue() {
                float range = RagAxeConfig.MAX_COOLDOWN_S - RagAxeConfig.MIN_COOLDOWN_S;
                cfg.setCooldownSeconds(Math.round((RagAxeConfig.MIN_COOLDOWN_S + this.value * range) * 2f) / 2f);
                cfg.save();
            }
        });
        y[0] += 22;
        toggle(w, contentX, y[0], half, "Buff Ended Alert", cfg::isEndAlert, cfg::setEndAlert, cfg);
        toggle(w, colB, y[0], half, "Off Cooldown Alert", cfg::isReadyAlert, cfg::setReadyAlert, cfg);
        y[0] += 22;
        toggle(w, contentX, y[0], half, "Mage Reduction", cfg::isMageCooldownReduction,
                cfg::setMageCooldownReduction, cfg);
        if (cfg.isMageCooldownReduction()) {
            w.add(SettingsButtonWidget.builder(
                    Component.literal(String.format(Locale.US, "Mage Cut: §6%.0f%%",
                            cfg.getMageCooldownReductionPercent())),
                    btn -> {
                        float next = cfg.getMageCooldownReductionPercent() + 5f;
                        cfg.setMageCooldownReductionPercent(next > 50f ? 0f : next);
                        cfg.save();
                        btn.setMessage(Component.literal(String.format(Locale.US, "Mage Cut: §6%.0f%%",
                                cfg.getMageCooldownReductionPercent())));
                    }).bounds(colB, y[0], half, 18).build());
        }
        y[0] += 24;

        header(w, contentX, y, contentWidth, "Rag Prompts");
        toggle(w, contentX, y[0], half, "Also Show Title", cfg::isPromptTitle, cfg::setPromptTitle, cfg);
        toggle(w, colB, y[0], half, "Skip Tank/Healer", cfg::isSkipTankHealer, cfg::setSkipTankHealer, cfg);
        y[0] += 24;
        w.add(new StringWidget(contentX, y[0], contentWidth, 12,
                Component.literal("§7Prompt text (supports § color codes):"), Minecraft.getInstance().font));
        y[0] += 14;
        EditBox text = new EditBox(Minecraft.getInstance().font, contentX, y[0], contentWidth, 18,
                Component.literal("Prompt text"));
        text.setMaxLength(64);
        text.setValue(cfg.getPromptText());
        text.setResponder(v -> {
            cfg.setPromptText(v);
            cfg.save();
        });
        w.add(text);
        y[0] += 26;

        for (RagAxePrompt prompt : RagAxePrompt.values()) {
            toggle(w, contentX, y[0], half, prompt.label, () -> cfg.isPromptEnabled(prompt),
                    v -> cfg.setPromptEnabled(prompt, v), cfg);
            toggle(w, colB, y[0], half, "Sound", () -> cfg.isPromptSound(prompt),
                    v -> cfg.setPromptSound(prompt, v), cfg);
            y[0] += 22;
            w.add(new ThemedSliderButton(contentX, y[0], contentWidth, 18, leadLabel(cfg, prompt),
                    cfg.getPromptLeadMs(prompt) / (double) RagAxeConfig.MAX_LEAD_MS) {
                @Override
                protected void updateMessage() {
                    setMessage(leadLabel(cfg, prompt));
                }

                @Override
                protected void applyValue() {
                    cfg.setPromptLeadMs(prompt, (int) (Math.round(this.value * RagAxeConfig.MAX_LEAD_MS / 250.0) * 250));
                    cfg.save();
                }
            });
            y[0] += 24;
        }

        w.add(new StringWidget(contentX, y[0], contentWidth, 12,
                Component.literal("§7Lead = how early to prompt, so the 3s channel finishes on the moment."),
                Minecraft.getInstance().font));
        y[0] += 14;
        w.add(new StringWidget(contentX, y[0], contentWidth, 12,
                Component.literal("§7M7 Dragon Spawns needs Wither Dragons (or King Relics) turned on."),
                Minecraft.getInstance().font));
        y[0] += 14;
        w.add(new StringWidget(contentX, y[0], contentWidth, 12,
                Component.literal("§7Move the Ragnarock Timers / Rag Prompt HUDs in the HUD editor."),
                Minecraft.getInstance().font));
        return w;
    }

    private static Component cooldownLabel(RagAxeConfig cfg) {
        return Component.literal(String.format(Locale.US, "Cooldown: §6%.1fs", cfg.getCooldownSeconds()));
    }

    private static Component leadLabel(RagAxeConfig cfg, RagAxePrompt prompt) {
        return Component.literal(String.format(Locale.US, "%s Lead: §6%dms", prompt.label, cfg.getPromptLeadMs(prompt)));
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String title) {
        y[0] += 4;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y[0] += 14;
    }

    private static void master(List<AbstractWidget> w, int x, int[] y, int width, String label, Supplier<Boolean> get,
                               Consumer<Boolean> set, RagAxeConfig cfg, Runnable requestRebuild) {
        w.add(SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    set.accept(!get.get());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
    }

    private static void toggle(List<AbstractWidget> w, int x, int y, int width, String label, Supplier<Boolean> get,
                               Consumer<Boolean> set, RagAxeConfig cfg) {
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

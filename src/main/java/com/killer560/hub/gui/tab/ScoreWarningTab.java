package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.scorecalc.ScoreCalculatorConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Score Warning: the pre-boss warning and the crypt reminder (see {@link com.killer560.hub.scorecalc.ScoreWarnings}).
 *  Two independent switches; the card is ON while either is. Works with Score Calculator off. Backed by
 *  {@link ScoreCalculatorConfig}; every change is saved at once. */
public class ScoreWarningTab extends BaseTab {

    public ScoreWarningTab() {
        super("Score Warning");
    }

    @Override
    public List<SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Pre-Boss Warning", "Crypt Reminder");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
        int y = contentY;

        w.add(SettingsButtonWidget.builder(onOff("Pre-Boss Warning", cfg.isPreBossWarning()), btn -> {
                    cfg.setPreBossWarning(!cfg.isPreBossWarning());
                    cfg.save();
                    btn.setMessage(onOff("Pre-Boss Warning", cfg.isPreBossWarning()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        w.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Crypt Reminder", false),
                Minecraft.getInstance().font));
        y += 16;
        w.add(SettingsButtonWidget.builder(onOff("Crypt Reminder", cfg.isCryptReminder()), btn -> {
                    cfg.setCryptReminder(!cfg.isCryptReminder());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isCryptReminder()) {
            int span = ScoreCalculatorConfig.MAX_CRYPT_REMINDER_SECONDS - ScoreCalculatorConfig.MIN_CRYPT_REMINDER_SECONDS;
            w.add(new ThemedSliderButton(contentX, y, contentWidth, 18, timeLabel(cfg),
                    (cfg.getCryptReminderSeconds() - ScoreCalculatorConfig.MIN_CRYPT_REMINDER_SECONDS) / (double) span) {
                @Override
                protected void updateMessage() {
                    setMessage(timeLabel(cfg));
                }

                @Override
                protected void applyValue() {
                    int raw = ScoreCalculatorConfig.MIN_CRYPT_REMINDER_SECONDS + (int) Math.round(this.value * span);
                    int v = Math.round(raw / 15f) * 15;
                    if (v != cfg.getCryptReminderSeconds()) {
                        cfg.setCryptReminderSeconds(v);
                        saveOnRelease(() -> cfg.save());
                    }
                }
            });
            y += 22;
            w.add(SettingsButtonWidget.builder(onOff("Tell Party", cfg.isCryptReminderParty()), btn -> {
                        cfg.setCryptReminderParty(!cfg.isCryptReminderParty());
                        cfg.save();
                        btn.setMessage(onOff("Tell Party", cfg.isCryptReminderParty()));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }
        if (cfg.isPreBossWarning() || cfg.isCryptReminder()) {
            w.add(SettingsButtonWidget.builder(onOff("Warning Sound", cfg.isScoreWarningSound()), btn -> {
                        cfg.setScoreWarningSound(!cfg.isScoreWarningSound());
                        cfg.save();
                        btn.setMessage(onOff("Warning Sound", cfg.isScoreWarningSound()));
                    }).bounds(contentX, y, contentWidth, 18).build());
        }
        return w;
    }

    private static Component timeLabel(ScoreCalculatorConfig cfg) {
        int s = cfg.getCryptReminderSeconds();
        return Component.literal("Remind At: " + (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

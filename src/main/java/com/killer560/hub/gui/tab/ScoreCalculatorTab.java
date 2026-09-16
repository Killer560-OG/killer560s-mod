package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.scorecalc.ScoreCalculator;
import com.killer560.hub.scorecalc.ScoreCalculatorConfig;
import com.killer560.hub.scorecalc.ScoreCalculatorFeature;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Dungeon Score Calculator settings - see {@link ScoreCalculatorFeature} / {@link ScoreCalculator}. */
public class ScoreCalculatorTab extends BaseTab {

    public ScoreCalculatorTab() {
        super("Score Calculator");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ScoreCalculatorConfig cfg = ScoreCalculatorConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int gap = 4;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Score Calculator", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Estimates the dungeon score from the tab list and sidebar. Move it with the HUD editor."), font));
        y += 16;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        // ---- HUD ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("HUD", false), font));
        y += 16;
        widgets.add(toggle("Show Breakdown", cfg.isShowBreakdown(), v -> cfg.setShowBreakdown(v), cfg, contentX, y, colW));
        widgets.add(toggle("Secrets Needed", cfg.isShowSecretsNeeded(), v -> cfg.setShowSecretsNeeded(v), cfg, col2X, y, colW));
        y += 22;
        widgets.add(toggle("Crypts & Deaths", cfg.isShowCryptsDeaths(), v -> cfg.setShowCryptsDeaths(v), cfg, contentX, y, colW));
        widgets.add(toggle("Mimic & Prince", cfg.isShowMimicPrince(), v -> cfg.setShowMimicPrince(v), cfg, col2X, y, colW));
        y += 22;
        widgets.add(toggle("Score Text Shadow", cfg.isTextShadow(), v -> cfg.setTextShadow(v), cfg, contentX, y, colW));
        y += 28;

        // ---- Formula ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Formula", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(paulLabel(cfg), btn -> {
                    cfg.setPaulMode(cfg.getPaulMode().next());
                    cfg.save();
                    btn.setMessage(paulLabel(cfg));
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(toggle("Assume Spirit Pet", cfg.isAssumeSpiritPet(), v -> cfg.setAssumeSpiritPet(v), cfg, col2X, y, colW));
        y += 28;

        // ---- 270 ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("270 Score Alert", false), font));
        y += 16;
        widgets.add(toggle("270 Title", cfg.isTitle270(), v -> cfg.setTitle270(v), cfg, contentX, y, colW));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Preview 270 Title"),
                btn -> ScoreCalculatorFeature.previewAlert(270)).bounds(col2X, y, colW, 18).build());
        y += 20;
        y = textBox(widgets, "270 title text", cfg.getTitle270Text(), v -> cfg.setTitle270Text(v), cfg, contentX, y, contentWidth);
        widgets.add(toggle("270 Party Message", cfg.isParty270(), v -> cfg.setParty270(v), cfg, contentX, y, contentWidth));
        y += 20;
        y = textBox(widgets, "270 party message", cfg.getParty270Message(), v -> cfg.setParty270Message(v), cfg, contentX, y, contentWidth);
        y += 6;

        // ---- 300 ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("300 Score Alert", false), font));
        y += 16;
        widgets.add(toggle("300 Title", cfg.isTitle300(), v -> cfg.setTitle300(v), cfg, contentX, y, colW));
        widgets.add(SettingsButtonWidget.builder(Component.literal("Preview 300 Title"),
                btn -> ScoreCalculatorFeature.previewAlert(300)).bounds(col2X, y, colW, 18).build());
        y += 20;
        y = textBox(widgets, "300 title text", cfg.getTitle300Text(), v -> cfg.setTitle300Text(v), cfg, contentX, y, contentWidth);
        widgets.add(toggle("300 Party Message", cfg.isParty300(), v -> cfg.setParty300(v), cfg, contentX, y, contentWidth));
        y += 20;
        y = textBox(widgets, "300 party message", cfg.getParty300Message(), v -> cfg.setParty300Message(v), cfg, contentX, y, contentWidth);
        y += 6;

        widgets.add(toggle("Score Chat Note", cfg.isChatNote(), v -> cfg.setChatNote(v), cfg, contentX, y, colW));
        widgets.add(toggle("Alert Sound", cfg.isAlertSound(), v -> cfg.setAlertSound(v), cfg, col2X, y, colW));
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Party messages send at most once per run. Titles accept & color codes."), font));

        return widgets;
    }

    private static SettingsButtonWidget toggle(String label, boolean initial, Consumer<Boolean> setter,
                                               ScoreCalculatorConfig cfg, int x, int y, int w) {
        boolean[] state = {initial};
        return SettingsButtonWidget.builder(onOff(label, state[0]), btn -> {
            state[0] = !state[0];
            setter.accept(state[0]);
            cfg.save();
            btn.setMessage(onOff(label, state[0]));
        }).bounds(x, y, w, 18).build();
    }

    private static int textBox(List<AbstractWidget> widgets, String label, String value, Consumer<String> setter,
                               ScoreCalculatorConfig cfg, int x, int y, int w) {
        EditBox box = new EditBox(Minecraft.getInstance().font, x, y, w, 18, Component.literal(label));
        box.setMaxLength(200);
        box.setValue(value);
        box.setResponder(text -> {
            setter.accept(text);
            cfg.save();
        });
        widgets.add(box);
        return y + 22;
    }

    private static Component paulLabel(ScoreCalculatorConfig cfg) {
        return Component.literal("Paul (EZPZ): §6" + cfg.getPaulMode().label);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

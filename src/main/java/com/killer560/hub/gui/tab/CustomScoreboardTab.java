package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.scoreboard.CustomScoreboardConfig;
import com.killer560.hub.scoreboard.CustomScoreboardConfig.Row;
import com.killer560.hub.scoreboard.ScoreboardEntry;
import com.killer560.hub.scoreboard.ScoreboardEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Custom Scoreboard settings - see {@link com.killer560.hub.scoreboard.CustomScoreboardFeature}. */
public class CustomScoreboardTab extends BaseTab {

    private enum Page {
        GENERAL("General"), LINES("Lines"), EVENTS("Events"), BACKGROUND("Background");

        final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private Page page = Page.GENERAL;

    public CustomScoreboardTab() {
        super("Custom Scoreboard");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        CustomScoreboardConfig cfg = CustomScoreboardConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Scoreboard", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 28;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        int gap = 4;
        int pageW = (contentWidth - gap * 3) / 4;
        for (Page p : Page.values()) {
            int px = contentX + p.ordinal() * (pageW + gap);
            String label = p == page ? "§6" + p.label : p.label;
            widgets.add(SettingsButtonWidget.builder(Component.literal(label), btn -> {
                        page = p;
                        requestRebuild.run();
                    }).bounds(px, y, pageW, 18).build());
        }
        y += 26;

        switch (page) {
            case GENERAL -> buildGeneral(widgets, cfg, contentX, y, contentWidth, requestRebuild);
            case LINES -> buildRows(widgets, cfg, cfg.entries(), e -> e.label, contentX, y, contentWidth, requestRebuild, true);
            case EVENTS -> buildEvents(widgets, cfg, contentX, y, contentWidth, requestRebuild);
            case BACKGROUND -> buildBackground(widgets, cfg, contentX, y, contentWidth, requestRebuild);
        }
        return widgets;
    }

    private void buildGeneral(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                              Runnable requestRebuild) {
        int gap = 8;
        int colW = (width - gap) / 2;
        int colBX = x + colW + gap;

        widgets.add(toggle("Hide Vanilla Scoreboard", cfg::isHideVanillaScoreboard, cfg::setHideVanillaScoreboard, cfg, x, y, colW));
        widgets.add(toggle("Custom Lines", cfg::isUseCustomLines, cfg::setUseCustomLines, cfg, colBX, y, colW));
        y += 22;

        widgets.add(cycle(() -> "Text Align: §6" + cfg.getTextAlignment().label,
                () -> cfg.setTextAlignment(cfg.getTextAlignment().next()), cfg, x, y, colW));
        widgets.add(toggle("Text Shadow", cfg::isTextShadow, cfg::setTextShadow, cfg, colBX, y, colW));
        y += 22;

        widgets.add(cycle(() -> "Title Align: §6" + cfg.getTitleAlignment().label,
                () -> cfg.setTitleAlignment(cfg.getTitleAlignment().next()), cfg, x, y, colW));
        widgets.add(cycle(() -> "Footer Align: §6" + cfg.getFooterAlignment().label,
                () -> cfg.setFooterAlignment(cfg.getFooterAlignment().next()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(cycle(() -> "Snap X: §6" + cfg.getHorizontalSnap().label,
                () -> cfg.setHorizontalSnap(cfg.getHorizontalSnap().next()), cfg, x, y, colW));
        widgets.add(cycle(() -> "Snap Y: §6" + cfg.getVerticalSnap().label,
                () -> cfg.setVerticalSnap(cfg.getVerticalSnap().next()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(slider(x, y, colW, cfg.getLineSpacing(), 0, 10, v -> "Line Spacing: " + v, cfg::setLineSpacing, cfg));
        widgets.add(slider(colBX, y, colW, cfg.getMaxPartyMembers(), 1, 25, v -> "Max Party Members: " + v,
                cfg::setMaxPartyMembers, cfg));
        y += 22;

        widgets.add(cycle(() -> "Numbers: §6" + cfg.getNumberFormat().label,
                () -> cfg.setNumberFormat(cfg.getNumberFormat().next()), cfg, x, y, colW));
        widgets.add(cycle(() -> "Number Style: " + cfg.getNumberDisplayFormat().label,
                () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().next()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Hide Empty Lines", cfg::isHideEmptyLines, cfg::setHideEmptyLines, cfg, x, y, colW));
        widgets.add(toggle("Hide Irrelevant Lines", cfg::isHideIrrelevantLines, cfg::setHideIrrelevantLines, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Hide Double Separators", cfg::isHideConsecutiveEmptyLines, cfg::setHideConsecutiveEmptyLines, cfg, x, y, colW));
        widgets.add(toggle("Hide Edge Separators", cfg::isHideEmptyLinesAtTopAndBottom, cfg::setHideEmptyLinesAtTopAndBottom, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Show Profile Name", cfg::isShowProfileName, cfg::setShowProfileName, cfg, x, y, colW));
        widgets.add(toggle("Party Everywhere", cfg::isShowPartyEverywhere, cfg::setShowPartyEverywhere, cfg, colBX, y, colW));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Custom Title", cfg.isUseCustomTitle()), btn -> {
                    cfg.setUseCustomTitle(!cfg.isUseCustomTitle());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y, width, 18).build());
        y += 22;

        Minecraft client = Minecraft.getInstance();
        if (cfg.isUseCustomTitle()) {
            EditBox title = new EditBox(client.font, x, y, width, 18, Component.literal("Title"));
            title.setMaxLength(256);
            title.setValue(cfg.getCustomTitle());
            title.setHint(Component.literal("§8Title"));
            title.setResponder(text -> {
                cfg.setCustomTitle(text);
                cfg.save();
            });
            widgets.add(title);
            y += 22;
        }

        EditBox footer = new EditBox(client.font, x, y, width, 18, Component.literal("Footer"));
        footer.setMaxLength(256);
        footer.setValue(cfg.getCustomFooter());
        footer.setHint(Component.literal("§8Footer"));
        footer.setResponder(text -> {
            cfg.setCustomFooter(text);
            cfg.save();
        });
        widgets.add(footer);
    }

    private void buildEvents(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                             Runnable requestRebuild) {
        widgets.add(toggle("Show All Active Events", cfg::isShowAllActiveEvents, cfg::setShowAllActiveEvents, cfg, x, y, width));
        y += 24;
        buildRows(widgets, cfg, cfg.events(), e -> e.label, x, y, width, requestRebuild, false);
    }

    private <E extends Enum<E>> void buildRows(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, List<Row<E>> rows,
                                               java.util.function.Function<E, String> label, int x, int y, int width,
                                               Runnable requestRebuild, boolean entries) {
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Order"), btn -> {
                    if (entries) {
                        cfg.resetEntries();
                    } else {
                        cfg.resetEvents();
                    }
                    cfg.save();
                    requestRebuild.run();
                }).bounds(x, y, width, 18).build());
        y += 24;

        int arrowW = 20;
        int toggleW = width - 2 * (arrowW + 4);
        for (int i = 0; i < rows.size(); i++) {
            Row<E> row = rows.get(i);
            int index = i;
            widgets.add(SettingsButtonWidget.builder(onOff((i + 1) + ". " + label.apply(row.id), row.enabled), btn -> {
                        row.enabled = !row.enabled;
                        cfg.save();
                        btn.setMessage(onOff((index + 1) + ". " + label.apply(row.id), row.enabled));
                    }).bounds(x, y, toggleW, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("▲"), btn -> {
                        CustomScoreboardConfig.move(rows, index, -1);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(x + toggleW + 4, y, arrowW, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("▼"), btn -> {
                        CustomScoreboardConfig.move(rows, index, 1);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(x + toggleW + 4 + arrowW + 4, y, arrowW, 18).build());
            y += 20;
        }
    }

    private void buildBackground(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                                 Runnable requestRebuild) {
        int gap = 8;
        int colW = (width - gap) / 2;
        int colBX = x + colW + gap;

        widgets.add(toggle("Background", cfg::isBackgroundEnabled, cfg::setBackgroundEnabled, cfg, x, y, colW));
        widgets.add(colorButton("Background Color", cfg.getBackgroundColor(), CustomScoreboardConfig.DEFAULT_BACKGROUND_COLOR,
                cfg::setBackgroundColor, cfg, colBX, y, colW));
        y += 22;

        widgets.add(slider(x, y, colW, cfg.getPadding(), 0, 20, v -> "Padding: " + v, cfg::setPadding, cfg));
        widgets.add(toggle("Rounded Corners", cfg::isRoundedCorners, cfg::setRoundedCorners, cfg, colBX, y, colW));
        y += 22;

        widgets.add(slider(x, y, colW, cfg.getCornerRadius(), 1, 20, v -> "Corner Radius: " + v, cfg::setCornerRadius, cfg));
        widgets.add(toggle("Border", cfg::isBorderEnabled, cfg::setBorderEnabled, cfg, colBX, y, colW));
        y += 22;

        widgets.add(colorButton("Border Color", cfg.getBorderColor(), CustomScoreboardConfig.DEFAULT_BORDER_COLOR,
                cfg::setBorderColor, cfg, x, y, colW));
        widgets.add(slider(colBX, y, colW, cfg.getBorderThickness(), 1, 5, v -> "Border Thickness: " + v,
                cfg::setBorderThickness, cfg));
    }

    // ---- widget helpers ----

    private interface BoolGetter {
        boolean get();
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private static AbstractWidget toggle(String label, BoolGetter getter, BoolSetter setter, CustomScoreboardConfig cfg,
                                         int x, int y, int w) {
        return SettingsButtonWidget.builder(onOff(label, getter.get()), btn -> {
                    setter.set(!getter.get());
                    cfg.save();
                    btn.setMessage(onOff(label, getter.get()));
                }).bounds(x, y, w, 18).build();
    }

    private static AbstractWidget cycle(java.util.function.Supplier<String> label, Runnable advance, CustomScoreboardConfig cfg,
                                        int x, int y, int w) {
        return SettingsButtonWidget.builder(Component.literal(label.get()), btn -> {
                    advance.run();
                    cfg.save();
                    btn.setMessage(Component.literal(label.get()));
                }).bounds(x, y, w, 18).build();
    }

    private static AbstractWidget colorButton(String label, int current, int def, IntConsumer setter, CustomScoreboardConfig cfg,
                                              int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, current), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, label, current, def, argb -> {
                        setter.accept(argb);
                        cfg.save();
                    }));
                }).bounds(x, y, w, 18).build();
    }

    private static ThemedSliderButton slider(int x, int y, int w, int current, int min, int max, IntFunction<String> label,
                                             IntConsumer setter, CustomScoreboardConfig cfg) {
        int range = Math.max(1, max - min);
        return new ThemedSliderButton(x, y, w, 18, Component.literal(label.apply(current)),
                (current - min) / (double) range) {
            private int valueInt() {
                return min + (int) Math.round(this.value * range);
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal(label.apply(valueInt())));
            }

            @Override
            protected void applyValue() {
                setter.accept(valueInt());
                cfg.save();
            }
        };
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

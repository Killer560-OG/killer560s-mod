package com.killer560.hub.gui.tab;

import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.scoreboard.CustomScoreboardConfig;
import com.killer560.hub.scoreboard.CustomScoreboardFeature;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.scoreboard.ScoreboardEntry;
import com.killer560.hub.scoreboard.ScoreboardListEditor;
import com.killer560.hub.scoreboard.ScoreboardListEditor.Section;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import com.killer560.hub.compat.McCompat;

/**
 * Custom Scoreboard settings - see {@link com.killer560.hub.scoreboard.CustomScoreboardFeature}.
 * <p>
 * Redone 2026-10-07 (killer560: "redo the custom scoreboard such that things are draggable to be in order and it has
 * the add and trashcan system SkyHanni uses instead of an individual toggle system"). Lines, Events and Chunked Stats
 * are each one {@link ScoreboardListEditor}: an Add dropdown of what is not on the board, Reset Order, and a list you
 * drag to reorder with a trash can per row. The board draws exactly that list, top to bottom. Lines opens first.
 * <p>
 * The old Line Options page is gone: each of its settings belongs to one line, so it now shows beside the list when
 * that line is clicked (Mayor's perks under Mayor, Tuning Amount under Maxwell Tuning, ...), as do the General
 * settings that only one line reads (Show Profile Name, the Party ones, Date In Lobby Code, Exact SkyBlock Minutes).
 * Settings read by more than one place (title, footer, date format, 24h time - the off-Skyblock board uses them too)
 * stay on General.
 */
public class CustomScoreboardTab extends BaseTab {

    private enum Page {
        LINES("Lines"), EVENTS("Events"), STATS("Chunked Stats"), GENERAL("General"), BACKGROUND("Background");

        final String label;

        Page(String label) {
            this.label = label;
        }
    }

    private static final int GAP = 8;
    /** Rows the list shows before it scrolls: the menu is always laid out about 480 units tall (Auto Scale), so
     *  this keeps the page buttons, the Add row and the whole list on one screen. */
    private static final int LIST_ROWS = 13;

    private Page page = Page.LINES;

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
        y += 24;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Visual Editor"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new com.killer560.hub.scoreboard.ScoreboardEditorScreen(McCompat.screen(client)));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;

        int gap = 4;
        int n = Page.values().length;
        int pageW = (contentWidth - gap * (n - 1)) / n;
        for (Page p : Page.values()) {
            int px = contentX + p.ordinal() * (pageW + gap);
            String label = p == page ? "§6" + p.label : p.label;
            widgets.add(SettingsButtonWidget.builder(Component.literal(label), btn -> {
                        page = p;
                        requestRebuild.run();
                    }).bounds(px, y, p.ordinal() == n - 1 ? contentX + contentWidth - px : pageW, 18).build());
        }
        y += 26;

        switch (page) {
            case LINES -> buildLines(widgets, cfg, contentX, y, contentWidth, requestRebuild);
            case EVENTS -> buildEvents(widgets, cfg, contentX, y, contentWidth, requestRebuild);
            case STATS -> buildStats(widgets, cfg, contentX, y, contentWidth, requestRebuild);
            case GENERAL -> buildGeneral(widgets, cfg, contentX, y, contentWidth, requestRebuild);
            case BACKGROUND -> buildBackground(widgets, cfg, contentX, y, contentWidth, requestRebuild);
        }
        return widgets;
    }

    /** Left: the list. Right: the clicked line's own settings. */
    private void buildLines(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                            Runnable requestRebuild) {
        y = ScoreboardListEditor.buildControls(widgets, Section.LINES, x, y, width, requestRebuild);
        int listW = (width - GAP) * 11 / 20;
        int rightX = x + listW + GAP;
        int rightW = x + width - rightX;
        ScoreboardListEditor.buildList(widgets, Section.LINES, x, y, listW, LIST_ROWS, requestRebuild);
        ScoreboardEntry selected = (ScoreboardEntry) ScoreboardListEditor.selected(Section.LINES);
        if (selected == null) {
            hint(widgets, rightX, y, rightW, "Click a line for its options.");
            hint(widgets, rightX, y + 14, rightW, "Drag a line to move it.");
            hint(widgets, rightX, y + 28, rightW, "The trash can takes it off.");
            return;
        }
        widgets.add(new StringWidget(rightX, y, rightW, 12, SectionHeaders.header(selected.label, false),
                Minecraft.getInstance().font));
        y += 16;
        if (!lineOptions(widgets, cfg, selected, rightX, y, rightW, requestRebuild)) {
            hint(widgets, rightX, y, rightW, "This line has no options.");
        }
    }

    /** The settings only {@code entry} reads, stacked from {@code y}. Returns false if it has none. */
    private boolean lineOptions(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, ScoreboardEntry entry, int x,
                                int y, int w, Runnable requestRebuild) {
        List<AbstractWidget> rows = new ArrayList<>();
        switch (entry) {
            case MAYOR -> {
                rows.add(toggle("Mayor Perks", cfg::isShowMayorPerks, cfg::setShowMayorPerks, cfg, x, 0, w));
                rows.add(toggle("Next Mayor Timer", cfg::isShowMayorTime, cfg::setShowMayorTime, cfg, x, 0, w));
                rows.add(toggle("Show Minister", cfg::isShowMinister, cfg::setShowMinister, cfg, x, 0, w));
                rows.add(toggle("Perkpocalypse Mayor", cfg::isShowJerryMayor, cfg::setShowJerryMayor, cfg, x, 0, w));
            }
            case POWER -> rows.add(toggle("Magical Power", cfg::isShowMagicalPower, cfg::setShowMagicalPower, cfg, x, 0, w));
            case TUNING -> {
                rows.add(toggle("Compact Tuning", cfg::isCompactTuning, cfg::setCompactTuning, cfg, x, 0, w));
                rows.add(slider(x, 0, w, cfg.getTuningAmount(), 1, 8, v -> "Tuning Amount: " + v, cfg::setTuningAmount, cfg));
            }
            case QUIVER -> {
                rows.add(cycle(() -> "Arrow Amount: §6" + cfg.getArrowDisplay().label,
                        () -> cfg.setArrowDisplay(cfg.getArrowDisplay().next()),
                        () -> cfg.setArrowDisplay(cfg.getArrowDisplay().previous()), cfg, x, 0, w));
                rows.add(toggle("Color Arrow Amount", cfg::isColorArrowAmount, cfg::setColorArrowAmount, cfg, x, 0, w));
            }
            case PLAYER_AMOUNT -> rows.add(toggle("Max Island Players", cfg::isShowMaxIslandPlayers,
                    cfg::setShowMaxIslandPlayers, cfg, x, 0, w));
            case PARTY -> {
                rows.add(toggle("Party Leader", cfg::isShowPartyLeader, cfg::setShowPartyLeader, cfg, x, 0, w));
                rows.add(slider(x, 0, w, cfg.getMaxPartyMembers(), 1, 25, v -> "Max Party Members: " + v,
                        cfg::setMaxPartyMembers, cfg));
                rows.add(toggle("Party Everywhere", cfg::isShowPartyEverywhere, cfg::setShowPartyEverywhere, cfg, x, 0, w));
            }
            case BITS -> rows.add(toggle("Unclaimed Bits", cfg::isShowUnclaimedBits, cfg::setShowUnclaimedBits, cfg, x, 0, w));
            case POWDER -> rows.add(cycle(() -> "Powder Display: §6" + cfg.getPowderDisplay().label,
                    () -> cfg.setPowderDisplay(cfg.getPowderDisplay().next()),
                    () -> cfg.setPowderDisplay(cfg.getPowderDisplay().previous()), cfg, x, 0, w));
            case PURSE -> rows.add(toggle("Hide Purse In Dungeons", cfg::isHidePurseInDungeons, cfg::setHidePurseInDungeons,
                    cfg, x, 0, w));
            case PROFILE -> rows.add(toggle("Show Profile Name", cfg::isShowProfileName, cfg::setShowProfileName, cfg, x, 0, w));
            case LOBBY_CODE -> rows.add(toggle("Date In Lobby Code", cfg::isDateInLobbyCode, cfg::setDateInLobbyCode, cfg,
                    x, 0, w));
            case TIME -> rows.add(toggle("Exact SkyBlock Minutes", cfg::isTimeExactMinutes, cfg::setTimeExactMinutes, cfg,
                    x, 0, w));
            case EVENTS -> rows.add(SettingsButtonWidget.builder(Component.literal("Edit Events"), btn -> {
                        page = Page.EVENTS;
                        requestRebuild.run();
                    }).bounds(x, 0, w, 18).build());
            case CHUNKED_STATS -> rows.add(SettingsButtonWidget.builder(Component.literal("Edit Chunked Stats"), btn -> {
                        page = Page.STATS;
                        requestRebuild.run();
                    }).bounds(x, 0, w, 18).build());
            default -> {
            }
        }
        for (AbstractWidget r : rows) {
            r.setY(y);
            widgets.add(r);
            y += 22;
        }
        return !rows.isEmpty();
    }

    private static void hint(List<AbstractWidget> widgets, int x, int y, int w, String text) {
        widgets.add(new StringWidget(x, y, w, 10, Component.literal("§7" + text), Minecraft.getInstance().font));
    }

    private void buildEvents(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                             Runnable requestRebuild) {
        y = ScoreboardListEditor.buildControls(widgets, Section.EVENTS, x, y, width, requestRebuild);
        int listW = (width - GAP) * 11 / 20;
        int rightX = x + listW + GAP;
        int rightW = x + width - rightX;
        ScoreboardListEditor.buildList(widgets, Section.EVENTS, x, y, listW, LIST_ROWS, requestRebuild);
        widgets.add(toggle("Show All Active Events", cfg::isShowAllActiveEvents, cfg::setShowAllActiveEvents, cfg,
                rightX, y, rightW));
        widgets.add(toggle("Separator Between Events", cfg::isSeparatorBetweenEvents, cfg::setSeparatorBetweenEvents, cfg,
                rightX, y + 22, rightW));
        hint(widgets, rightX, y + 46, rightW, "Higher in the list wins when");
        hint(widgets, rightX, y + 58, rightW, "only one event is shown.");
    }

    private void buildStats(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                            Runnable requestRebuild) {
        y = ScoreboardListEditor.buildControls(widgets, Section.STATS, x, y, width, requestRebuild);
        int listW = (width - GAP) * 11 / 20;
        int rightX = x + listW + GAP;
        int rightW = x + width - rightX;
        ScoreboardListEditor.buildList(widgets, Section.STATS, x, y, listW, LIST_ROWS, requestRebuild);
        widgets.add(slider(rightX, y, rightW, cfg.getStatsPerLine(), 1, 10, v -> "Stats Per Line: " + v,
                cfg::setStatsPerLine, cfg));
        if (!cfg.isEntryEnabled(ScoreboardEntry.CHUNKED_STATS)) {
            hint(widgets, rightX, y + 24, rightW, "Add the Chunked Stats line");
            hint(widgets, rightX, y + 36, rightW, "to show these on the board.");
        }
    }

    private void buildGeneral(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                              Runnable requestRebuild) {
        int colW = (width - GAP) / 2;
        int colBX = x + colW + GAP;

        widgets.add(toggle("Hide Vanilla Scoreboard", cfg::isHideVanillaScoreboard, cfg::setHideVanillaScoreboard, cfg, x, y, colW));
        widgets.add(toggle("Custom Lines", cfg::isUseCustomLines, cfg::setUseCustomLines, cfg, colBX, y, colW));
        y += 22;

        widgets.add(cycle(() -> "Text Align: §6" + cfg.getTextAlignment().label,
                () -> cfg.setTextAlignment(cfg.getTextAlignment().next()), () -> cfg.setTextAlignment(cfg.getTextAlignment().previous()), cfg, x, y, colW));
        widgets.add(toggle("Text Shadow", cfg::isTextShadow, cfg::setTextShadow, cfg, colBX, y, colW));
        y += 22;

        widgets.add(cycle(() -> "Title Align: §6" + cfg.getTitleAlignment().label,
                () -> cfg.setTitleAlignment(cfg.getTitleAlignment().next()), () -> cfg.setTitleAlignment(cfg.getTitleAlignment().previous()), cfg, x, y, colW));
        widgets.add(cycle(() -> "Footer Align: §6" + cfg.getFooterAlignment().label,
                () -> cfg.setFooterAlignment(cfg.getFooterAlignment().next()), () -> cfg.setFooterAlignment(cfg.getFooterAlignment().previous()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(cycle(() -> "Snap X: §6" + cfg.getHorizontalSnap().label,
                () -> cfg.setHorizontalSnap(cfg.getHorizontalSnap().next()), () -> cfg.setHorizontalSnap(cfg.getHorizontalSnap().previous()), cfg, x, y, colW));
        widgets.add(cycle(() -> "Snap Y: §6" + cfg.getVerticalSnap().label,
                () -> cfg.setVerticalSnap(cfg.getVerticalSnap().next()), () -> cfg.setVerticalSnap(cfg.getVerticalSnap().previous()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(slider(x, y, colW, cfg.getLineSpacing(), 0, 10, v -> "Line Spacing: " + v, cfg::setLineSpacing, cfg));
        widgets.add(cycle(() -> "Numbers: §6" + cfg.getNumberFormat().label,
                () -> cfg.setNumberFormat(cfg.getNumberFormat().next()), () -> cfg.setNumberFormat(cfg.getNumberFormat().previous()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(cycle(() -> "Number Style: " + cfg.getNumberDisplayFormat().label,
                () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().next()), () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().previous()), cfg, x, y, colW));
        widgets.add(toggle("Hide Empty Lines", cfg::isHideEmptyLines, cfg::setHideEmptyLines, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Hide Irrelevant Lines", cfg::isHideIrrelevantLines, cfg::setHideIrrelevantLines, cfg, x, y, colW));
        widgets.add(toggle("Hide Double Separators", cfg::isHideConsecutiveEmptyLines, cfg::setHideConsecutiveEmptyLines, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Hide Edge Separators", cfg::isHideEmptyLinesAtTopAndBottom, cfg::setHideEmptyLinesAtTopAndBottom, cfg, x, y, colW));
        widgets.add(toggle("Hide With Tab List", cfg::isHideWhenTab, cfg::setHideWhenTab, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Hide With Chat Open", cfg::isHideWhenChat, cfg::setHideWhenChat, cfg, x, y, colW));
        widgets.add(cycle(() -> "Outside Skyblock: §6" + cfg.getOutsideSkyblockMode().label,
                () -> cfg.setOutsideSkyblockMode(cfg.getOutsideSkyblockMode().next()), () -> cfg.setOutsideSkyblockMode(cfg.getOutsideSkyblockMode().previous()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Cache On Island Switch", cfg::isCacheOnIslandSwitch, cfg::setCacheOnIslandSwitch, cfg, x, y, colW));
        widgets.add(toggle("Clickable Lines", cfg::isLineActions, cfg::setLineActions, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Show Number Changes", cfg::isShowNumberDifference, cfg::setShowNumberDifference, cfg, x, y, colW));
        widgets.add(toggle("Unknown Line Warning", cfg::isUnknownLinesWarning, cfg::setUnknownLinesWarning, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("24h SkyBlock Time", cfg::isTime24h, cfg::setTime24h, cfg, x, y, colW));
        widgets.add(cycle(() -> "Date Format: §6" + cfg.getDateFormat().pattern,
                () -> cfg.setDateFormat(cfg.getDateFormat().next()), () -> cfg.setDateFormat(cfg.getDateFormat().previous()), cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Custom Title Off Skyblock", cfg::isUseCustomTitleOutsideSkyblock,
                cfg::setUseCustomTitleOutsideSkyblock, cfg, x, y, colW));
        widgets.add(SettingsButtonWidget.builder(onOff("Custom Title", cfg.isUseCustomTitle()), btn -> {
                    cfg.setUseCustomTitle(!cfg.isUseCustomTitle());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(colBX, y, colW, 18).build());
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
        y += 22;

        EditBox alphaFooter = new EditBox(client.font, x, y, width, 18, Component.literal("Alpha Footer"));
        alphaFooter.setMaxLength(256);
        alphaFooter.setValue(cfg.getCustomAlphaFooter());
        alphaFooter.setHint(Component.literal("§8Alpha Footer"));
        alphaFooter.setResponder(text -> {
            cfg.setCustomAlphaFooter(text);
            cfg.save();
        });
        widgets.add(alphaFooter);
    }

    private void buildBackground(List<AbstractWidget> widgets, CustomScoreboardConfig cfg, int x, int y, int width,
                                 Runnable requestRebuild) {
        int colW = (width - GAP) / 2;
        int colBX = x + colW + GAP;

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
        y += 22;

        widgets.add(toggle("Gradient Border", cfg::isBorderGradient, cfg::setBorderGradient, cfg, x, y, colW));
        widgets.add(colorButton("Border Bottom Color", cfg.getBorderColorBottom(), CustomScoreboardConfig.DEFAULT_BORDER_BOTTOM_COLOR,
                cfg::setBorderColorBottom, cfg, colBX, y, colW));
        y += 22;

        widgets.add(toggle("Chroma Border", cfg::isChromaBorder, cfg::setChromaBorder, cfg, x, y, colW));
        widgets.add(slider(colBX, y, colW, cfg.getChromaSpeed(), 1, 20, v -> "Chroma Speed: " + v, cfg::setChromaSpeed, cfg));
        y += 22;

        widgets.add(slider(x, y, colW, cfg.getBorderSoftness(), 0, 10, v -> "Border Softness: " + v, cfg::setBorderSoftness, cfg));
        widgets.add(slider(colBX, y, colW, cfg.getMargin(), 0, 50, v -> "Screen Margin: " + v, cfg::setMargin, cfg));
        y += 22;

        widgets.add(toggle("Background Blur (restart)", cfg::isBackgroundBlur, cfg::setBackgroundBlur, cfg, x, y, colW));
        widgets.add(slider(colBX, y, colW, cfg.getBlurStrength(), 1, 20, v -> "Blur Strength: " + v, cfg::setBlurStrength, cfg));
        y += 22;

        widgets.add(slider(x, y, colW, cfg.getMinWidth(), 0, 400, v -> "Min Width: " + v, cfg::setMinWidth, cfg));
        widgets.add(slider(colBX, y, colW, cfg.getMinHeight(), 0, 400, v -> "Min Height: " + v, cfg::setMinHeight, cfg));
        y += 22;

        widgets.add(toggle("Image Background", cfg::isImageBackground, cfg::setImageBackground, cfg, x, y, colW));
        widgets.add(slider(colBX, y, colW, cfg.getImageOpacity(), 5, 100, v -> "Image Opacity: " + v + "%", cfg::setImageOpacity, cfg));
        y += 22;

        widgets.add(SettingsButtonWidget.builder(Component.literal(CustomScoreboardFeature.backgroundImageExists()
                        ? "Reload Image" : "Reload Image §c(no background.png)"), btn -> {
                    CustomScoreboardFeature.reloadBackgroundImage();
                    requestRebuild.run();
                }).bounds(x, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Image Folder"), btn -> {
                    try {
                        java.nio.file.Files.createDirectories(CustomScoreboardConfig.DATA_DIR);
                        ExternalOpen.path(CustomScoreboardConfig.DATA_DIR);
                    } catch (Exception ignored) {
                    }
                }).bounds(colBX, y, colW, 18).build());
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

    private static AbstractWidget cycle(java.util.function.Supplier<String> label, Runnable advance, Runnable regress,
                                        CustomScoreboardConfig cfg, int x, int y, int w) {
        return SettingsButtonWidget.builder(Component.literal(label.get()), btn -> {
                    advance.run();
                    cfg.save();
                    btn.setMessage(Component.literal(label.get()));
                }).secondaryPress(btn -> {
                    // killer560, 2026-09-27: "if i right click then it goes back one".
                    regress.run();
                    cfg.save();
                    btn.setMessage(Component.literal(label.get()));
                }).bounds(x, y, w, 18).build();
    }

    private static AbstractWidget colorButton(String label, int current, int def, IntConsumer setter, CustomScoreboardConfig cfg,
                                              int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, current), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, current, def, argb -> {
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

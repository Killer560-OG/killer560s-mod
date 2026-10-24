package com.killer560.hub.scoreboard;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.ScreenChrome;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.DragListWidget;
import com.killer560.hub.gui.MenuThemedScreen;
import com.killer560.hub.gui.OverlayThemes;
import com.killer560.hub.gui.ScreenThemes;
import com.killer560.hub.gui.ThemedEditBox;
import com.killer560.hub.hud.GuiRects;
import com.killer560.hub.gui.SettingTooltips;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.scoreboard.CustomScoreboardConfig.Align;
import com.killer560.hub.scoreboard.ScoreboardListEditor.Section;
import com.killer560.hub.util.ExternalOpen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * The Custom Scoreboard's editor, and since 2026-10-11 the only place it is edited (killer560: "make it so the main way
 * of editing it is through that open visual editor. The other things on that page should be removed. Then inside of
 * the custom editor thing make it so that you aren't moving things on the right side but instead moving things inside
 * of the menu itself. The menu itself can be filled with fake stuff to show what it would look like in game. but you
 * should drag around all of that stuff ... If you remove it have a thing on the right side showing things not active
 * that you can add back in.").
 * <p>
 * Left: the board itself, drawn by {@link CustomScoreboardFeature#drawBoard} with every active line on its sample text
 * (never the live board, whose rows cannot be told apart by line). Each line is a block you press and drag: drop it on
 * the board to move it, drop it on the right-hand panel to take it off. Clicking a block selects it and shows its own
 * settings on the right (Events and Chunked Stats show their own ordered lists there). Separators, which draw as blank
 * rows in game, show as a faint dashed row here so they can be grabbed.
 * <p>
 * Right, two pages: Board (the selected line's settings, then "Not Active": everything that is not on the board -
 * click one to add it at the bottom, or drag it onto the board where it should go) and Settings. Settings was the
 * General and Background pages until 2026-10-15 (killer560: "can you make the custom scoreboard simpler"): now the
 * handful people use, then one closed "Advanced" section with everything else. Snap X, Snap Y and Screen Margin went
 * (the board is anchored to the right edge and dragged in Edit HUD; Reset Position puts it back), and the title and
 * footer rows that repeated the Title and Footer lines' own settings went too. The panel scrolls with the wheel when
 * its page is taller than the screen.
 */
public class ScoreboardEditorScreen extends Screen implements MenuThemedScreen {

    // No Theme of its own (killer560, 2026-10-18): the editor always draws in the mod menu's theme. A ScreenThemes entry
    // saved under the old "scoreboard" id is still read and written by ScreenThemes, and does nothing here.

    /** {x, y, w, h} of the themed panel as last drawn (the testkit samples it). */
    private int[] themedBounds = new int[4];

    @Override
    public int[] themedPanelBounds() {
        return themedBounds.clone();
    }

    private enum Page {
        BOARD("Board"), SETTINGS("Settings");

        final String label;

        Page(String label) {
            this.label = label;
        }
    }

    /** Tooltip scope: the settings keep the keys they had on the Custom Scoreboard tab. */
    private static final String TOOLTIP_SCOPE = "Custom Scoreboard";
    private static final int MARGIN = 10;
    private static final int ROW = 18;
    private static final int STEP = 22;
    private static final int COL_GAP = 6;
    private static final int PICK_H = 12;
    /** Mouse travel before a press on a block becomes a drag. */
    private static final double DRAG_START = 3.0;

    private static Page page = Page.BOARD;
    private static ScoreboardEntry selected;
    private static int panelScroll;
    private static int inactiveScroll;

    private final Screen parent;
    private CustomScoreboardConfig cfg;

    private int boardX, boardY, boardW, boardH;
    private int panelX, panelTop, panelW, panelBottom;
    private int inactiveX, inactiveY, inactiveW, inactiveH;
    private int inactiveCols = 2;
    private int maxPanelScroll;

    // ---- drag state ----
    private ScoreboardEntry pressed;
    private boolean pressedFromInactive;
    private boolean dragging;
    private double pressX, pressY;
    private double grabDX, grabDY;
    private double mouseX, mouseY;

    // ---- tooltip ----
    private AbstractWidget tooltipWidget;
    private long tooltipSinceMs;

    /** One active entry's rows on the drawn board. */
    private record Block(ScoreboardEntry entry, int first, int count) {
    }

    /** The board as drawn this frame: its lines, which entry each row came from, and where it sits on screen. */
    private record Board(List<ScoreboardLine> lines, List<Block> blocks, int x, int y, float scale, int boxW, int boxH,
                         int inset, int lineStep, int lineHeight, int titleRows) {

        double rowTop(int row) {
            return y + (inset + row * (double) lineStep) * scale;
        }

        double blockTop(Block b) {
            return rowTop(b.first) - (lineStep - lineHeight) * scale / 2.0;
        }

        double blockBottom(Block b) {
            return rowTop(b.first + b.count - 1) + lineHeight * scale + (lineStep - lineHeight) * scale / 2.0;
        }

        int right() {
            return x + Math.round(boxW * scale);
        }

        int bottom() {
            return y + Math.round(boxH * scale);
        }
    }

    public ScoreboardEditorScreen(Screen parent) {
        super(Component.literal("Custom Scoreboard Editor"));
        this.parent = parent;
    }

    // ---- layout ----

    @Override
    protected void init() {
        // The theme's palette now, so the section headings and hints built below take its colours.
        MenuStyle.use();
        cfg = CustomScoreboardConfig.getInstance();
        if (selected != null && !cfg.entries().contains(selected)) {
            selected = null;
        }
        boardX = MARGIN;
        boardY = 34;
        boardW = Math.max(180, Math.min(320, this.width * 2 / 5));
        boardH = Math.max(80, this.height - boardY - 34);
        panelX = boardX + boardW + 14;
        panelW = Math.max(200, this.width - panelX - MARGIN);
        panelBottom = this.height - 34;

        int pageGap = 4;
        int pages = Page.values().length;
        int pageW = (panelW - pageGap * (pages - 1)) / pages;
        for (Page p : Page.values()) {
            int px = panelX + p.ordinal() * (pageW + pageGap);
            SettingsButtonWidget.Builder b = SettingsButtonWidget.builder(Component.literal(p.label), btn -> {
                        page = p;
                        panelScroll = 0;
                        rebuildWidgets();
                    }).bounds(px, boardY, p.ordinal() == pages - 1 ? panelX + panelW - px : pageW, ROW);
            // The chosen page is lit in the accent; the other is a dimmer, recessed card (killer560, 2026-10-18).
            b.toggle(p == page);
            addRenderableWidget(b.build());
        }
        panelTop = boardY + ROW + 8;

        List<AbstractWidget> panel = new ArrayList<>();
        if (page == Page.BOARD) {
            buildBoardPage(panel);
            maxPanelScroll = 0;
            for (AbstractWidget w : panel) {
                addRenderableWidget(w);
            }
        } else {
            int contentEnd = buildPage(panel);
            maxPanelScroll = Math.max(0, contentEnd + panelScroll - panelBottom);
            if (panelScroll > maxPanelScroll) {
                // The page got shorter (a window resize, Custom Title off): lay it out again at the clamped scroll.
                panelScroll = maxPanelScroll;
                panel.clear();
                buildPage(panel);
            }
            // Only rows wholly inside the panel: a row scrolled under the page buttons must not take their clicks.
            for (AbstractWidget w : panel) {
                if (w.getY() >= panelTop && w.getY() + w.getHeight() <= panelBottom) {
                    addRenderableWidget(w);
                }
            }
        }

        addRenderableWidget(SettingsButtonWidget.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 50, this.height - 26, 100, 20).build());
    }

    private int buildPage(List<AbstractWidget> panel) {
        return buildSettings(panel, panelX, panelTop - panelScroll, panelW);
    }

    /** The selected line's settings on top, "Not Active" below. Returns the content's bottom. */
    private int buildBoardPage(List<AbstractWidget> out) {
        Font font = Minecraft.getInstance().font;
        int panelH = panelBottom - panelTop;
        inactiveH = Math.max(90, panelH * 2 / 5);
        inactiveX = panelX;
        inactiveW = panelW;
        inactiveCols = panelW >= 330 ? 3 : 2;
        int headerY = panelBottom - inactiveH;
        inactiveY = headerY + STEP;

        out.add(label(panelX, headerY + 4, panelW - 90, 12, heading("Not Active")));
        out.add(SettingsButtonWidget.builder(Component.literal("Reset Board"), btn -> {
                    cfg.resetEntries();
                    cfg.save();
                    selected = null;
                    rebuildWidgets();
                }).bounds(panelX + panelW - 84, headerY, 84, ROW).build());

        int y = panelTop;
        int optionsBottom = headerY - 6;
        if (selected == null) {
            hint(out, panelX, y, panelW, "Click a line on the board for its settings.");
            hint(out, panelX, y + 12, panelW, "Drag lines on the board to reorder them.");
            hint(out, panelX, y + 24, panelW, "Drag a line over here to take it off.");
            hint(out, panelX, y + 36, panelW, "Click or drag a Not Active line to add it.");
            return panelBottom;
        }
        out.add(label(panelX, y + 4, panelW - 90, 12, heading(selected.label)));
        ScoreboardEntry sel = selected;
        out.add(SettingsButtonWidget.builder(Component.literal("§cRemove"), btn -> {
                    cfg.entries().remove(sel);
                    cfg.save();
                    selected = null;
                    rebuildWidgets();
                }).bounds(panelX + panelW - 84, y, 84, ROW).build());
        y += STEP;
        switch (sel) {
            case EVENTS -> {
                int half = (panelW - COL_GAP) / 2;
                out.add(toggle("Show All Active Events", cfg::isShowAllActiveEvents, cfg::setShowAllActiveEvents,
                        panelX, y, half));
                out.add(toggle("Separator Between Events", cfg::isSeparatorBetweenEvents, cfg::setSeparatorBetweenEvents,
                        panelX + half + COL_GAP, y, panelW - half - COL_GAP));
                y += STEP;
                listSection(out, Section.EVENTS, y, optionsBottom);
            }
            case CHUNKED_STATS -> {
                out.add(slider(panelX, y, panelW, cfg.getStatsPerLine(), 1, 10, v -> "Stats Per Line: " + v,
                        cfg::setStatsPerLine));
                y += STEP;
                listSection(out, Section.STATS, y, optionsBottom);
            }
            default -> {
                List<AbstractWidget> rows = lineOptions(sel, panelX, panelW);
                if (rows.isEmpty()) {
                    hint(out, panelX, y, panelW, "This line has no settings.");
                }
                for (AbstractWidget r : rows) {
                    if (y + r.getHeight() > optionsBottom) {
                        break;
                    }
                    r.setY(y);
                    out.add(r);
                    y += STEP;
                }
            }
        }
        return panelBottom;
    }

    /** Events / Chunked Stats: their Add and Reset row and drag list, fitted between {@code y} and {@code bottom}. */
    private void listSection(List<AbstractWidget> out, Section section, int y, int bottom) {
        List<AbstractWidget> list = new ArrayList<>();
        int listTop = ScoreboardListEditor.buildControls(list, section, panelX, y, panelW, this::rebuildWidgets);
        int rows = Math.max(3, (bottom - listTop - 4) / DragListWidget.ROW_H);
        ScoreboardListEditor.buildList(list, section, panelX, listTop, panelW, rows, this::rebuildWidgets);
        out.addAll(list);
    }

    /** The settings only {@code entry} reads (they sat beside the tab's list before). */
    private List<AbstractWidget> lineOptions(ScoreboardEntry entry, int x, int w) {
        List<AbstractWidget> rows = new ArrayList<>();
        switch (entry) {
            case MAYOR -> {
                rows.add(toggle("Mayor Perks", cfg::isShowMayorPerks, cfg::setShowMayorPerks, x, 0, w));
                rows.add(toggle("Next Mayor Timer", cfg::isShowMayorTime, cfg::setShowMayorTime, x, 0, w));
                rows.add(toggle("Show Minister", cfg::isShowMinister, cfg::setShowMinister, x, 0, w));
                rows.add(toggle("Perkpocalypse Mayor", cfg::isShowJerryMayor, cfg::setShowJerryMayor, x, 0, w));
            }
            case POWER -> rows.add(toggle("Magical Power", cfg::isShowMagicalPower, cfg::setShowMagicalPower, x, 0, w));
            case TUNING -> {
                rows.add(toggle("Compact Tuning", cfg::isCompactTuning, cfg::setCompactTuning, x, 0, w));
                rows.add(slider(x, 0, w, cfg.getTuningAmount(), 1, 8, v -> "Tuning Amount: " + v, cfg::setTuningAmount));
            }
            case QUIVER -> {
                rows.add(cycle(() -> "Arrow Amount: §6" + cfg.getArrowDisplay().label,
                        () -> cfg.setArrowDisplay(cfg.getArrowDisplay().next()),
                        () -> cfg.setArrowDisplay(cfg.getArrowDisplay().previous()), x, 0, w));
                rows.add(toggle("Color Arrow Amount", cfg::isColorArrowAmount, cfg::setColorArrowAmount, x, 0, w));
            }
            case PLAYER_AMOUNT -> rows.add(toggle("Max Island Players", cfg::isShowMaxIslandPlayers,
                    cfg::setShowMaxIslandPlayers, x, 0, w));
            case PARTY -> {
                rows.add(toggle("Party Leader", cfg::isShowPartyLeader, cfg::setShowPartyLeader, x, 0, w));
                rows.add(slider(x, 0, w, cfg.getMaxPartyMembers(), 1, 25, v -> "Max Party Members: " + v,
                        cfg::setMaxPartyMembers));
                rows.add(toggle("Party Everywhere", cfg::isShowPartyEverywhere, cfg::setShowPartyEverywhere, x, 0, w));
            }
            case BITS -> rows.add(toggle("Unclaimed Bits", cfg::isShowUnclaimedBits, cfg::setShowUnclaimedBits, x, 0, w));
            case POWDER -> rows.add(cycle(() -> "Powder Display: §6" + cfg.getPowderDisplay().label,
                    () -> cfg.setPowderDisplay(cfg.getPowderDisplay().next()),
                    () -> cfg.setPowderDisplay(cfg.getPowderDisplay().previous()), x, 0, w));
            case PURSE -> rows.add(toggle("Hide Purse In Dungeons", cfg::isHidePurseInDungeons,
                    cfg::setHidePurseInDungeons, x, 0, w));
            case PROFILE -> rows.add(toggle("Show Profile Name", cfg::isShowProfileName, cfg::setShowProfileName, x, 0, w));
            case LOBBY_CODE -> rows.add(toggle("Date In Lobby Code", cfg::isDateInLobbyCode, cfg::setDateInLobbyCode,
                    x, 0, w));
            case TIME -> rows.add(toggle("Exact SkyBlock Minutes", cfg::isTimeExactMinutes, cfg::setTimeExactMinutes,
                    x, 0, w));
            case TITLE -> {
                rows.add(SettingsButtonWidget.builder(onOff("Custom Title", cfg.isUseCustomTitle()), btn -> {
                            cfg.setUseCustomTitle(!cfg.isUseCustomTitle());
                            cfg.save();
                            rebuildWidgets();
                        }).bounds(x, 0, w, ROW).build());
                if (cfg.isUseCustomTitle()) {
                    rows.add(textBox("Title", cfg.getCustomTitle(), cfg::setCustomTitle, x, 0, w));
                    rows.add(toggle("Custom Title Off Skyblock", cfg::isUseCustomTitleOutsideSkyblock,
                            cfg::setUseCustomTitleOutsideSkyblock, x, 0, w));
                }
                rows.add(cycle(() -> "Title Align: §6" + cfg.getTitleAlignment().label,
                        () -> cfg.setTitleAlignment(cfg.getTitleAlignment().next()),
                        () -> cfg.setTitleAlignment(cfg.getTitleAlignment().previous()), x, 0, w));
            }
            case FOOTER -> {
                rows.add(textBox("Footer", cfg.getCustomFooter(), cfg::setCustomFooter, x, 0, w));
                rows.add(textBox("Alpha Footer", cfg.getCustomAlphaFooter(), cfg::setCustomAlphaFooter, x, 0, w));
                rows.add(cycle(() -> "Footer Align: §6" + cfg.getFooterAlignment().label,
                        () -> cfg.setFooterAlignment(cfg.getFooterAlignment().next()),
                        () -> cfg.setFooterAlignment(cfg.getFooterAlignment().previous()), x, 0, w));
            }
            default -> {
            }
        }
        return rows;
    }

    /**
     * The Settings page, two columns from {@code y}. Returns the bottom. On top the settings people actually change;
     * everything else is in one "Advanced" section, closed by default and remembered. Every setting is the same field
     * it always was (old saves load unchanged); only where its row sits changed.
     */
    private int buildSettings(List<AbstractWidget> out, int x, int y, int width) {
        int colW = (width - COL_GAP) / 2;
        int colBX = x + colW + COL_GAP;

        // The top of the page, in killer560's order (2026-10-18): 1 to 11. Every row is a setting that already existed;
        // only its place moved. Two columns, so one row is two settings.
        // 1. Theme: the board's Menu Theme. Default is the mod menu's theme; the editor itself always draws in that theme.
        out.add(ScreenThemes.overlayButton(OverlayThemes.CUSTOM_SCOREBOARD, "Theme", x, y, colW, ROW));
        y += STEP;
        // 2. Text Align, 3. Reset Position.
        out.add(cycle(() -> "Text Align: §6" + cfg.getTextAlignment().label,
                () -> cfg.setTextAlignment(cfg.getTextAlignment().next()),
                () -> cfg.setTextAlignment(cfg.getTextAlignment().previous()), x, y, colW));
        out.add(SettingsButtonWidget.builder(Component.literal("Reset Position"), btn -> {
                    cfg.resetAnchor();
                    cfg.save();
                }).bounds(colBX, y, colW, ROW).build());
        y += STEP;
        // 4. Hide when Tab is held, 5. Hide when chat is open.
        out.add(toggle("Hide With Tab List", cfg::isHideWhenTab, cfg::setHideWhenTab, x, y, colW));
        out.add(toggle("Hide With Chat Open", cfg::isHideWhenChat, cfg::setHideWhenChat, colBX, y, colW));
        y += STEP;
        out.add(toggle("Hide In Menus", cfg::isHideInMenus, cfg::setHideInMenus, x, y, colW));
        y += STEP;
        // 6. Number style.
        out.add(cycle(() -> "Numbers: §6" + cfg.getNumberFormat().label,
                () -> cfg.setNumberFormat(cfg.getNumberFormat().next()),
                () -> cfg.setNumberFormat(cfg.getNumberFormat().previous()), x, y, colW));
        out.add(cycle(() -> "Number Style: " + cfg.getNumberDisplayFormat().label,
                () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().next()),
                () -> cfg.setNumberDisplayFormat(cfg.getNumberDisplayFormat().previous()), colBX, y, colW));
        y += STEP;
        // 7. Line spacing, 8. Text shadow.
        out.add(slider(x, y, colW, cfg.getLineSpacing(), 0, 10, v -> "Line Spacing: " + v, cfg::setLineSpacing));
        out.add(toggle("Text Shadow", cfg::isTextShadow, cfg::setTextShadow, colBX, y, colW));
        y += STEP;
        // 9. Custom image: the background.png in the image folder (there is no file picker; see Open Image Folder).
        out.add(toggle("Image Background", cfg::isImageBackground, cfg::setImageBackground, x, y, colW));
        out.add(slider(colBX, y, colW, cfg.getImageOpacity(), 5, 100, v -> "Image Opacity: " + v + "%",
                cfg::setImageOpacity));
        y += STEP;
        out.add(SettingsButtonWidget.builder(Component.literal(CustomScoreboardFeature.backgroundImageExists()
                        ? "Reload Image" : "Reload Image §c(none)"), btn -> {
                    CustomScoreboardFeature.reloadBackgroundImage();
                    rebuildWidgets();
                }).bounds(x, y, colW, ROW).build());
        out.add(SettingsButtonWidget.builder(Component.literal("Open Image Folder"), btn -> {
                    try {
                        java.nio.file.Files.createDirectories(CustomScoreboardConfig.DATA_DIR);
                        ExternalOpen.path(CustomScoreboardConfig.DATA_DIR);
                    } catch (Exception ignored) {
                    }
                }).bounds(colBX, y, colW, ROW).build());
        y += STEP;
        // 10. 24-hour clock (the sidebar's time line), 11. Date format (the lobby code's date).
        out.add(toggle("24h SkyBlock Time", cfg::isTime24h, cfg::setTime24h, x, y, colW));
        out.add(cycle(() -> "Date Format: §6" + cfg.getDateFormat().pattern,
                () -> cfg.setDateFormat(cfg.getDateFormat().next()),
                () -> cfg.setDateFormat(cfg.getDateFormat().previous()), colBX, y, colW));
        y += STEP;
        // Not in the list above, kept on the page: what the board does outside SkyBlock, and clicking lines.
        out.add(cycle(() -> "Outside Skyblock: §6" + cfg.getOutsideSkyblockMode().label,
                () -> cfg.setOutsideSkyblockMode(cfg.getOutsideSkyblockMode().next()),
                () -> cfg.setOutsideSkyblockMode(cfg.getOutsideSkyblockMode().previous()), x, y, colW));
        out.add(toggle("Clickable Lines", cfg::isLineActions, cfg::setLineActions, colBX, y, colW));
        y += STEP;
        hint(out, x, y, width, "Move it in Edit HUD. Title and footer text: click them on the Board.");
        y += 14;

        boolean open = cfg.isEditorAdvancedOpen();
        y = com.killer560.hub.gui.tab.CollapsibleSection.header(out, x, y, width, "Advanced", false, open, () -> {
            cfg.setEditorAdvancedOpen(!cfg.isEditorAdvancedOpen());
            cfg.save();
            rebuildWidgets();
        });
        if (!open) {
            return y;
        }

        // ---- lines ----
        out.add(label(x, y + 2, width, 12, heading("Lines")));
        y += 16;
        out.add(toggle("Hide Vanilla Scoreboard", cfg::isHideVanillaScoreboard, cfg::setHideVanillaScoreboard, x, y, colW));
        out.add(toggle("Custom Lines", cfg::isUseCustomLines, cfg::setUseCustomLines, colBX, y, colW));
        y += STEP;
        out.add(toggle("Hide Empty Lines", cfg::isHideEmptyLines, cfg::setHideEmptyLines, x, y, colW));
        out.add(toggle("Hide Irrelevant Lines", cfg::isHideIrrelevantLines, cfg::setHideIrrelevantLines, colBX, y, colW));
        y += STEP;
        out.add(toggle("Hide Double Separators", cfg::isHideConsecutiveEmptyLines, cfg::setHideConsecutiveEmptyLines,
                x, y, colW));
        out.add(toggle("Hide Edge Separators", cfg::isHideEmptyLinesAtTopAndBottom,
                cfg::setHideEmptyLinesAtTopAndBottom, colBX, y, colW));
        y += STEP;
        out.add(toggle("Cache On Island Switch", cfg::isCacheOnIslandSwitch, cfg::setCacheOnIslandSwitch, x, y, colW));
        out.add(toggle("Show Number Changes", cfg::isShowNumberDifference, cfg::setShowNumberDifference, colBX, y, colW));
        y += STEP;
        out.add(toggle("Unknown Line Warning", cfg::isUnknownLinesWarning, cfg::setUnknownLinesWarning, x, y, colW));
        y += STEP;

        // ---- box: grouped by what each row changes; the image rows moved to the top of this page ----
        out.add(label(x, y + 2, width, 12, heading("Box")));
        y += 16;
        y = subHeading(out, x, y, width, "Background");
        out.add(toggle("Background", cfg::isBackgroundEnabled, cfg::setBackgroundEnabled, x, y, colW));
        out.add(colorButton("Background Color", cfg::getBackgroundColor, CustomScoreboardConfig.DEFAULT_BACKGROUND_COLOR,
                cfg::setBackgroundColor, colBX, y, colW));
        y += STEP;
        // Background opacity: the alpha of the background colour, shown as a percentage (killer560, 2026-10-18).
        out.add(slider(x, y, colW, (cfg.getBackgroundColor() >>> 24) * 100 / 255, 0, 100,
                v -> "Background Opacity: " + v + "%",
                v -> cfg.setBackgroundColor((Math.round(v * 255f / 100f) << 24) | (cfg.getBackgroundColor() & 0xFFFFFF))));
        y += STEP;
        out.add(slider(x, y, colW, cfg.getPadding(), 0, 20, v -> "Padding: " + v, cfg::setPadding));
        out.add(slider(colBX, y, colW, cfg.getMinWidth(), 0, 400, v -> "Min Width: " + v, cfg::setMinWidth));
        y += STEP;
        out.add(slider(x, y, colW, cfg.getMinHeight(), 0, 400, v -> "Min Height: " + v, cfg::setMinHeight));
        y += STEP;
        y = subHeading(out, x, y, width, "Blur");
        out.add(toggle("Blur (restart)", cfg::isBackgroundBlur, cfg::setBackgroundBlur, x, y, colW));
        out.add(slider(colBX, y, colW, cfg.getBlurStrength(), 1, 20, v -> "Blur Strength: " + v, cfg::setBlurStrength));
        y += STEP;
        y = subHeading(out, x, y, width, "Corners");
        out.add(toggle("Rounded Corners", cfg::isRoundedCorners, cfg::setRoundedCorners, x, y, colW));
        out.add(slider(colBX, y, colW, cfg.getCornerRadius(), 1, 20, v -> "Corner Radius: " + v, cfg::setCornerRadius));
        y += STEP;
        y = subHeading(out, x, y, width, "Border");
        out.add(toggle("Border", cfg::isBorderEnabled, cfg::setBorderEnabled, x, y, colW));
        out.add(slider(colBX, y, colW, cfg.getBorderThickness(), 1, 5, v -> "Border Thickness: " + v,
                cfg::setBorderThickness));
        y += STEP;
        // Default: the menu's accent. Custom: the colours below (killer560, 2026-10-18).
        out.add(cycle(() -> "Border Style: §6" + cfg.getBorderStyle().label,
                () -> cfg.setBorderStyle(cfg.getBorderStyle().next()),
                () -> cfg.setBorderStyle(cfg.getBorderStyle().previous()), x, y, colW));
        out.add(slider(colBX, y, colW, cfg.getBorderSoftness(), 0, 10, v -> "Border Softness: " + v, cfg::setBorderSoftness));
        y += STEP;
        if (cfg.getBorderStyle() == CustomScoreboardConfig.BorderStyle.CUSTOM) {
            out.add(colorButton("Border Color", cfg::getBorderColor, CustomScoreboardConfig.DEFAULT_BORDER_COLOR,
                    cfg::setBorderColor, x, y, colW));
            out.add(toggle("Gradient Border", cfg::isBorderGradient, cfg::setBorderGradient, colBX, y, colW));
            y += STEP;
            out.add(colorButton("Bottom Color", cfg::getBorderColorBottom,
                    CustomScoreboardConfig.DEFAULT_BORDER_BOTTOM_COLOR, cfg::setBorderColorBottom, x, y, colW));
            y += STEP;
        }
        out.add(toggle("Chroma Border", cfg::isChromaBorder, cfg::setChromaBorder, x, y, colW));
        out.add(slider(colBX, y, colW, cfg.getChromaSpeed(), 1, 20, v -> "Chroma Speed: " + v, cfg::setChromaSpeed));
        y += STEP;
        return y;
    }

    // ---- the board ----

    /** The rows an entry draws in the editor: its sample text (never live data), a placeholder when it has none. */
    static List<ScoreboardLine> editorLines(ScoreboardEntry e, CustomScoreboardConfig cfg) {
        if (e.isSeparator()) {
            return List.of(new ScoreboardLine("§8- - - - - - - -", Align.CENTER));
        }
        List<ScoreboardLine> out;
        try {
            out = (e == ScoreboardEntry.TITLE || e == ScoreboardEntry.FOOTER) ? e.lines(cfg) : ScoreboardLine.of(e.sample());
        } catch (RuntimeException ex) {
            out = List.of();
        }
        boolean blank = true;
        for (ScoreboardLine l : out) {
            if (!l.isBlank()) {
                blank = false;
                break;
            }
        }
        return blank ? List.of(ScoreboardLine.of("§7" + e.label)) : out;
    }

    /** The board with {@code without} lifted out (null = every active entry). */
    private Board board(ScoreboardEntry without) {
        Font font = this.font;
        List<ScoreboardLine> lines = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        for (ScoreboardEntry e : cfg.entries().active()) {
            if (e == without) {
                continue;
            }
            List<ScoreboardLine> el = editorLines(e, cfg);
            blocks.add(new Block(e, lines.size(), el.size()));
            lines.addAll(el);
        }
        int titleRows = !blocks.isEmpty() && blocks.get(0).entry() == ScoreboardEntry.TITLE ? blocks.get(0).count() : 0;
        int boxW = Math.max(1, CustomScoreboardFeature.boxWidth(font, lines, cfg));
        int boxH = Math.max(1, CustomScoreboardFeature.boxHeight(font, lines, cfg));
        float scale = Math.min(1.5f, Math.min((boardW - 8f) / boxW, (boardH - 8f) / boxH));
        scale = Math.max(0.3f, scale);
        int x = boardX + (boardW - Math.round(boxW * scale)) / 2;
        int y = boardY + 4;
        return new Board(lines, blocks, x, y, scale, boxW, boxH, CustomScoreboardFeature.textInset(cfg),
                font.lineHeight + cfg.getLineSpacing(), font.lineHeight, titleRows);
    }

    private static Block blockAt(Board b, double mx, double my) {
        if (mx < b.x() || mx > b.right()) {
            return null;
        }
        for (Block bl : b.blocks()) {
            if (my >= b.blockTop(bl) && my < b.blockBottom(bl)) {
                return bl;
            }
        }
        return null;
    }

    /** The insertion slot for a drop at {@code my}: how many blocks' middles lie above it. */
    private static int dropSlot(Board b, double my) {
        int slot = 0;
        for (Block bl : b.blocks()) {
            if ((b.blockTop(bl) + b.blockBottom(bl)) / 2.0 < my) {
                slot++;
            }
        }
        return slot;
    }

    private boolean overPanel(double mx, double my) {
        return mx >= panelX - 7 && my >= boardY - 4 && my <= panelBottom + 4;
    }

    /** What Not Active offers, one Separator for the sixteen. */
    private List<ScoreboardEntry> inactive() {
        return ScoreboardListEditor.picks(cfg.entries(), e -> e.label);
    }

    private int inactiveVisibleH() {
        return panelBottom - inactiveY;
    }

    private int maxInactiveScroll() {
        int rows = (inactive().size() + inactiveCols - 1) / inactiveCols;
        return Math.max(0, rows * PICK_H - inactiveVisibleH());
    }

    /** Screen rectangle of the Not Active row for {@code e}, or null when it is not listed or scrolled out. */
    private int[] inactiveRect(int index) {
        int colW = (inactiveW - COL_GAP * (inactiveCols - 1)) / inactiveCols;
        int x = inactiveX + (index % inactiveCols) * (colW + COL_GAP);
        int y = inactiveY + (index / inactiveCols) * PICK_H - inactiveScroll;
        if (y < inactiveY || y + PICK_H > panelBottom) {
            return null;
        }
        return new int[]{x, y, colW, PICK_H};
    }

    private ScoreboardEntry inactiveAt(double mx, double my) {
        if (page != Page.BOARD) {
            return null;
        }
        List<ScoreboardEntry> list = inactive();
        for (int i = 0; i < list.size(); i++) {
            int[] r = inactiveRect(i);
            if (r != null && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                return list.get(i);
            }
        }
        return null;
    }

    // ---- test hooks (testkit drives the real mouse at these) ----

    /** Screen rectangle {x, y, w, h} of {@code entry}'s block on the board, or null when it is not on it. */
    public int[] blockRectOf(ScoreboardEntry entry) {
        Board b = board(null);
        for (Block bl : b.blocks()) {
            if (bl.entry() == entry) {
                int top = (int) Math.ceil(b.blockTop(bl));
                int bottom = (int) Math.floor(b.blockBottom(bl));
                return new int[]{b.x(), top, b.right() - b.x(), Math.max(1, bottom - top)};
            }
        }
        return null;
    }

    /** Screen rectangle of {@code entry}'s Not Active row (Board page), or null. */
    public int[] inactiveRectOf(ScoreboardEntry entry) {
        List<ScoreboardEntry> list = inactive();
        int i = list.indexOf(entry);
        return i < 0 ? null : inactiveRect(i);
    }

    /** {x, y, w, h} of the right-hand panel (a board line dropped here comes off). */
    public int[] panelRect() {
        return new int[]{panelX, boardY, panelW, panelBottom - boardY};
    }

    /** True while a board line or Not Active row is being dragged. */
    public boolean isDraggingLine() {
        return dragging;
    }

    public static void resetSession() {
        page = Page.BOARD;
        selected = null;
        panelScroll = 0;
        inactiveScroll = 0;
    }

    public static ScoreboardEntry selectedEntry() {
        return selected;
    }

    // ---- input ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        mouseX = event.x();
        mouseY = event.y();
        if (event.button() == 0) {
            Block b = blockAt(board(null), event.x(), event.y());
            ScoreboardEntry from = b != null ? b.entry() : inactiveAt(event.x(), event.y());
            if (from != null) {
                pressed = from;
                pressedFromInactive = b == null;
                dragging = false;
                pressX = event.x();
                pressY = event.y();
                if (b != null) {
                    Board bd = board(null);
                    grabDX = event.x() - bd.x();
                    grabDY = event.y() - bd.blockTop(b);
                } else {
                    grabDX = 8;
                    grabDY = 5;
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        mouseX = event.x();
        mouseY = event.y();
        if (pressed != null) {
            if (!dragging && Math.hypot(event.x() - pressX, event.y() - pressY) >= DRAG_START) {
                dragging = true;
            }
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        mouseX = event.x();
        mouseY = event.y();
        if (pressed == null) {
            return super.mouseReleased(event);
        }
        ScoreboardEntry e = pressed;
        boolean fromInactive = pressedFromInactive;
        boolean wasDrag = dragging;
        pressed = null;
        dragging = false;
        EntryOrder<ScoreboardEntry> order = cfg.entries();
        boolean changed = false;
        if (!wasDrag) {
            if (fromInactive) {
                order.add(e);
                selected = e;
                changed = true;
            } else {
                selected = selected == e ? null : e;
            }
        } else if (fromInactive) {
            if (!overPanel(event.x(), event.y())) {
                order.insert(e, dropSlot(board(null), event.y()));
                selected = e;
                changed = true;
            }
        } else if (overPanel(event.x(), event.y())) {
            order.remove(e);
            if (selected == e) {
                selected = null;
            }
            changed = true;
        } else {
            int from = order.indexOf(e);
            int to = dropSlot(board(e), event.y());
            if (from >= 0 && from != to) {
                order.move(from, to);
                changed = true;
            }
        }
        if (changed) {
            cfg.save();
        }
        rebuildWidgets();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (page == Page.BOARD && mx >= inactiveX && mx <= inactiveX + inactiveW && my >= inactiveY && my <= panelBottom) {
            inactiveScroll = Math.max(0, Math.min(maxInactiveScroll(), inactiveScroll - (int) Math.round(scrollY * PICK_H * 2)));
            return true;
        }
        if (page != Page.BOARD && mx >= panelX && my >= panelTop && my <= panelBottom) {
            int next = Math.max(0, Math.min(maxPanelScroll, panelScroll - (int) Math.round(scrollY * STEP)));
            if (next != panelScroll) {
                panelScroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    // ---- rendering ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partialTick) {
        if (pressed == null) {
            mouseX = mx;
            mouseY = my;
        }
        // The menu theme (screen sweep, 2026-10-13): one panel over the window with the two help lines in its header
        // bar, the board in a recessed well, the settings side on a raised box, every colour from the theme.
        ScreenChrome.backdrop(g, this.width, this.height);
        themedBounds = new int[]{3, 3, this.width - 6, this.height - 6};
        ScreenChrome.frame(g, 3, 3, this.width - 6, this.height - 6);
        GuiRects chrome = GuiRects.begin(g);
        MenuStyle.bevel(chrome, 5, 5, this.width - 10, 27, MenuStyle.HEADER_TOP, MenuStyle.HEADER_BOTTOM);
        chrome.submit();
        ScreenChrome.card(g, panelX - 6, boardY - 1, panelW + 10, panelBottom - boardY + 5, false);
        ScreenChrome.text(g, "Drag lines on the board to move them. Drag one to the right to take it off.", MARGIN, 9, MenuStyle.OR);
        ScreenChrome.text(g, "The board shows sample text; in game each line shows your real data.", MARGIN, 20, MenuStyle.DIM);

        ScreenChrome.well(g, boardX, boardY, boardW, boardH);
        ScoreboardEntry lifted = dragging && !pressedFromInactive ? pressed : null;
        Board b = board(lifted);
        drawBoard(g, b);

        if (dragging) {
            boolean removing = !pressedFromInactive && overPanel(mouseX, mouseY);
            boolean adding = pressedFromInactive && !overPanel(mouseX, mouseY);
            if (removing) {
                g.fill(panelX - 6, boardY - 4, panelX + panelW + 4, panelBottom + 4, 0x40FF3333);
                g.outline(panelX - 6, boardY - 4, panelW + 10, panelBottom - boardY + 8, 0xFFFF5555);
            } else if (!pressedFromInactive || adding) {
                int slot = dropSlot(b, mouseY);
                double lineY;
                if (b.blocks().isEmpty()) {
                    lineY = b.rowTop(0);
                } else if (slot < b.blocks().size()) {
                    lineY = b.blockTop(b.blocks().get(slot));
                } else {
                    lineY = b.blockBottom(b.blocks().get(b.blocks().size() - 1));
                }
                int ly = (int) Math.round(lineY);
                g.fill(b.x() - 2, ly - 1, b.right() + 2, ly + 1, MenuStyle.OR);
            }
        } else {
            Block hover = blockAt(b, mx, my);
            for (Block bl : b.blocks()) {
                int top = (int) Math.round(b.blockTop(bl));
                int bottom = (int) Math.round(b.blockBottom(bl));
                if (bl == hover) {
                    g.fill(b.x(), top, b.right(), bottom, MenuStyle.alpha(MenuStyle.TXT, 0x24));
                }
                if (bl.entry() == selected) {
                    g.outline(b.x() - 1, top, b.right() - b.x() + 2, Math.max(1, bottom - top), MenuStyle.OR);
                }
            }
        }

        if (page == Page.BOARD) {
            drawInactive(g, mx, my);
        }

        super.extractRenderState(g, mx, my, partialTick);

        if (dragging && pressed != null) {
            drawFloating(g, pressed);
        } else {
            drawTooltip(g, mx, my);
        }
    }

    private void drawBoard(GuiGraphicsExtractor g, Board b) {
        g.pose().pushMatrix();
        try {
            g.pose().translate(b.x(), b.y());
            g.pose().scale(b.scale(), b.scale());
            CustomScoreboardFeature.drawBoard(g, this.font, 0, 0, b.lines(), b.titleRows(), cfg, false);
        } catch (RuntimeException ignored) {
            // A broken preview frame must never take the editor down with it.
        } finally {
            g.pose().popMatrix();
        }
    }

    private void drawInactive(GuiGraphicsExtractor g, int mx, int my) {
        List<ScoreboardEntry> list = inactive();
        if (list.isEmpty()) {
            MenuStyle.text(g, "Every line is on the board.", inactiveX, inactiveY + 2, MenuStyle.DIM);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            int[] r = inactiveRect(i);
            if (r == null) {
                continue;
            }
            ScoreboardEntry e = list.get(i);
            boolean hover = !dragging && mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
            GuiRects row = GuiRects.begin(g);
            MenuStyle.bevel(row, r[0], r[1], r[2], r[3] - 1, hover ? MenuStyle.CARD_HOVER_TOP : MenuStyle.CARD_TOP,
                    hover ? MenuStyle.CARD_HOVER_BOTTOM : MenuStyle.CARD_BOTTOM);
            if (hover) {
                row.outline(r[0] - 1, r[1] - 1, r[2] + 2, r[3] + 1, MenuStyle.ACCENT_EDGE);
            }
            row.submit();
            MenuStyle.text(g, "+ ", r[0] + 3, r[1] + 2, hover ? MenuStyle.OR : ScreenChrome.readable(0xFF55FF55));
            MenuStyle.text(g, e.label, r[0] + 3 + this.font.width("+ "), r[1] + 2, MenuStyle.TXT);
        }
        int max = maxInactiveScroll();
        if (max > 0) {
            int trackH = inactiveVisibleH();
            int thumbH = Math.max(10, trackH * trackH / (trackH + max));
            int thumbY = inactiveY + (trackH - thumbH) * inactiveScroll / max;
            g.fill(inactiveX + inactiveW + 1, thumbY, inactiveX + inactiveW + 3, thumbY + thumbH, MenuStyle.OR);
        }
    }

    private void drawFloating(GuiGraphicsExtractor g, ScoreboardEntry e) {
        List<ScoreboardLine> lines = editorLines(e, cfg);
        int w = 0;
        for (ScoreboardLine l : lines) {
            w = Math.max(w, this.font.width(l.text()));
        }
        int h = lines.size() * (this.font.lineHeight + 1);
        int x = (int) Math.round(mouseX - Math.min(grabDX, w));
        int y = (int) Math.round(mouseY - grabDY);
        g.fill(x - 3, y - 2, x + w + 3, y + h + 1, 0xE0101010); // the board's own dark, so its colours read as in game
        g.outline(x - 3, y - 2, w + 6, h + 3, MenuStyle.OR);
        int ly = y;
        for (ScoreboardLine l : lines) {
            g.text(this.font, l.text(), x, ly, 0xFFFFFFFF, true);
            ly += this.font.lineHeight + 1;
        }
    }

    /** The settings keep their Custom Scoreboard tab tooltips (same pattern as AutoRoutesEditScreen). */
    private void drawTooltip(GuiGraphicsExtractor g, int mx, int my) {
        AbstractWidget hovered = null;
        for (var child : this.children()) {
            if (child instanceof AbstractWidget w && w.visible && !(w instanceof EditBox) && !(w instanceof StringWidget)
                    && mx >= w.getX() && mx < w.getX() + w.getWidth() && my >= w.getY() && my < w.getY() + w.getHeight()) {
                hovered = w;
                break;
            }
        }
        long now = System.currentTimeMillis();
        if (hovered != tooltipWidget) {
            tooltipWidget = hovered;
            tooltipSinceMs = now;
        }
        if (hovered == null || now - tooltipSinceMs < 350L) {
            return;
        }
        String text = SettingTooltips.describe(TOOLTIP_SCOPE, hovered.getMessage().getString());
        if (text == null || text.isBlank()) {
            return;
        }
        List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(Component.literal(text), 220);
        int w = 0;
        for (var line : lines) {
            w = Math.max(w, this.font.width(line));
        }
        int h = lines.size() * 10 - 2;
        int x = mx + 12;
        int y = my + 12;
        if (x + w + 8 > this.width) {
            x = Math.max(4, mx - w - 16);
        }
        if (y + h + 8 > this.height) {
            y = Math.max(4, my - h - 16);
        }
        ScreenChrome.tooltip(g, lines, x, y, w, h);
    }

    // ---- widget helpers ----

    private interface BoolGetter {
        boolean get();
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private static void hint(List<AbstractWidget> out, int x, int y, int w, String text) {
        out.add(label(x, y, w, 10, Component.literal(text).withColor(MenuStyle.DIM & 0xFFFFFF)));
    }

    /** A small sub-heading inside a section (a 12px row); returns the y of the row beneath it. */
    private static int subHeading(List<AbstractWidget> out, int x, int y, int w, String title) {
        hint(out, x, y, w, title);
        return y + 12;
    }

    /** A text line as a StringWidget (the tooltip and layout code treat it as one) drawn the theme's way: a vanilla
     *  StringWidget always shadows its text, which smears on a light theme. */
    private static StringWidget label(int x, int y, int w, int h, Component text) {
        return new StringWidget(x, y, w, h, text, Minecraft.getInstance().font) {
            @Override
            public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
                MenuStyle.text(g, getMessage(), getX(), getY() + (getHeight() - 8) / 2, MenuStyle.TXT);
            }
        };
    }

    /** A section heading in the theme's accent, bold (a StringWidget colours its own text). */
    private static Component heading(String title) {
        return Component.literal(title).withStyle(net.minecraft.ChatFormatting.BOLD).withColor(MenuStyle.OR & 0xFFFFFF);
    }

    private AbstractWidget toggle(String label, BoolGetter getter, BoolSetter setter, int x, int y, int w) {
        return SettingsButtonWidget.builder(onOff(label, getter.get()), btn -> {
                    setter.set(!getter.get());
                    cfg.save();
                    btn.setMessage(onOff(label, getter.get()));
                }).bounds(x, y, w, ROW).build();
    }

    private AbstractWidget cycle(Supplier<String> label, Runnable advance, Runnable regress, int x, int y, int w) {
        return SettingsButtonWidget.cycling(Component.literal(label.get()), (btn, dir) -> {
                    // killer560, 2026-09-27: "if i right click then it goes back one".
                    (dir > 0 ? advance : regress).run();
                    cfg.save();
                    btn.setMessage(Component.literal(label.get()));
                }).bounds(x, y, w, ROW).build();
    }

    private AbstractWidget colorButton(String label, java.util.function.IntSupplier current, int def, IntConsumer setter,
                                       int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, current.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(this, label, current.getAsInt(), def, argb -> {
                        setter.accept(argb);
                        cfg.save();
                    }));
                }).bounds(x, y, w, ROW).build();
    }

    private ThemedSliderButton slider(int x, int y, int w, int current, int min, int max, IntFunction<String> label,
                                      IntConsumer setter) {
        int range = Math.max(1, max - min);
        CustomScoreboardConfig c = cfg;
        return new ThemedSliderButton(x, y, w, ROW, Component.literal(label.apply(current)),
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
                saveOnRelease(() -> c.save());
            }
        }.steps(range); // a whole number: the knob stops on each one
    }

    private EditBox textBox(String name, String value, java.util.function.Consumer<String> setter, int x, int y, int w) {
        EditBox box = new ThemedEditBox(Minecraft.getInstance().font, x, y, w, ROW, Component.literal(name));
        box.setMaxLength(256);
        box.setValue(value);
        ((ThemedEditBox) box).hint(name);
        box.setResponder(text -> {
            setter.accept(text);
            cfg.save();
        });
        return box;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }

    @Override
    public void onClose() {
        McCompat.setScreen(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

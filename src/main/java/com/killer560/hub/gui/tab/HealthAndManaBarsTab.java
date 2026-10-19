package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.objecthider.ObjectHiderConfig;
import com.killer560.hub.playerstats.PlayerStatsConfig;
import com.killer560.hub.playerstats.StatElements;
import com.killer560.hub.playerstats.StatElements.Readout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/** Health and Mana Bars - the home for the mod's own player-status bars.
 *  <p>
 *  Created 2026-09-30 from killer560's request to move the vanilla bar hides out of Object Hider; the custom bars
 *  and texts ({@link StatElements}) arrived 2026-10-04. Every vanilla-bar hide here still reads and writes the SAME
 *  {@link ObjectHiderConfig} fields under the same JSON keys it always did.
 *  <p>
 *  Redone 2026-10-07, killer560: "redo the bar section. It is way too complicated looking. Also no one needs to
 *  adjust scale there, they just use the edit hud menu portion." The four nested dropdowns are gone and the per-readout
 *  Scale sliders were removed: they wrote the very same {@code HudConfig} scale the HUD editor scrolls
 *  ({@code hud.setScale(r.hudId, ...)}), so every saved size is already the editor's and keeps drawing through
 *  {@code HudElementRegistry.resolveScale}. Bar Width and Bar Height stay: they set the bars' SHAPE (length against
 *  thickness, independently, and leave the number on the bar its normal size), which a uniform scale cannot do. Since
 *  the same day each bar can also be resized on its own in the HUD editor; the sliders set every bar at once.
 *  <p>
 *  Regrouped later that day, killer560: "Make the menu have text next to text and bars next to bars", and "remove the
 *  classic display option, that is not needed". The page is the Stat Bars switch, then <b>Bars</b> - every bar in a
 *  two-column grid, each with its colour once it is on, then the bar settings - then <b>Text</b> - every text readout
 *  in the same grid, then Text Shadow - then <b>Hide</b>: the vanilla bars Stat Bars hides, and what is always hidden
 *  (Hypixel's own stat text and the vanilla XP bar included). Classic Display is gone (see
 *  {@code PlayerStatsConfig.migrateClassic}). Vitality and XP joined both grids; while an XP readout is on, the vanilla
 *  XP bar's hide is offered right under it too, since a custom XP bar mostly makes sense instead of the vanilla one. */
public class HealthAndManaBarsTab extends BaseTab {

    private static final int GAP = 6;
    private static final int ROW_H = 18;
    private static final int ROW = 22;
    /** Width of the "Colour: ■" button beside a readout's toggle. */
    private static final int SWATCH_W = 52;
    /** Bar Size moves in halves (killer560, 2026-10-19). */
    private static final double BAR_SIZE_STEP = 0.5;

    /** The Bars grid, two to a row, in reading order. */
    static final Readout[] BARS = {
            Readout.HEALTH_BAR, Readout.MANA_BAR,
            Readout.DEFENCE_BAR, Readout.VITALITY_BAR,
            Readout.OTHER_BAR, Readout.XP_BAR,
    };

    /** The Text grid, two to a row: the same stats in the same places as the bars, then the text-only ones. */
    static final Readout[] TEXTS = {
            Readout.HEALTH_TEXT, Readout.MANA_TEXT,
            Readout.DEFENCE_TEXT, Readout.VITALITY_TEXT,
            Readout.OTHER_TEXT, Readout.XP_TEXT,
            Readout.OVERFLOW_TEXT, Readout.INTELLIGENCE_TEXT,
            Readout.EFFECTIVE_HEALTH_TEXT,
    };

    public HealthAndManaBarsTab() {
        super("Health and Mana Bars");
    }

    /** True while {@link #widgetsMatchSearch} builds the page to read its labels: Advanced is built open, and the bar
     *  rows as if Stat Bars were on, so the menu search finds every setting here (Auto Routes' and AP3's pattern). */
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

    /**
     * Simplified 2026-10-12 (killer560: "go through and simplify the health and mana bar menu, there are just way too many
     * things going on there and it should be far simpler"). The page is what most people touch - Stat Bars, Edit Bars,
     * which bars are on, Style and Text Size - and one {@link CollapsibleSection} "Advanced", closed by default and
     * remembered ({@code PlayerStatsConfig.menuAdvancedOpen}), holding everything else: Layout, the bar look (corners,
     * icons, number, smooth fill, damage ghost, background, length and thickness), the colours in one grid, the text
     * readouts and the vanilla hides. Every setting is the same field as before; only where its row sits changed. The
     * "Hide Vanilla XP Bar" row was a second copy of Hide's "XP Bar And Level" and is gone (the setting stays there).
     */
    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
        ObjectHiderConfig oh = ObjectHiderConfig.getInstance();
        int half = (contentWidth - GAP) / 2;
        int col2 = contentX + half + GAP;
        int[] y = {contentY};
        boolean master = ps.isEnabledRaw();
        boolean showAll = master || scanningForSearch;

        widgets.add(SettingsButtonWidget.builder(onOff("Stat Bars", master), btn -> {
                    ps.setEnabled(!ps.isEnabledRaw());
                    ps.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (showAll) {
            // The bars' own editor (2026-10-08): Edit HUD no longer lists them; this and Home's "Edit Bars" open it.
            widgets.add(SettingsButtonWidget.builder(Component.literal("Edit Bars"), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        if (com.killer560.hub.gui.ModScreen.refuseWithoutWorld("Edit Bars")) {
                            return;
                        }
                        McCompat.setScreen(client, com.killer560.hub.hud.HudEditorScreen.forBars(McCompat.screen(client)));
                    }).bounds(contentX, y[0], contentWidth, ROW_H).build());
            y[0] += ROW;
            // Which bars are shown: on/off only (their colours are in Advanced).
            for (int i = 0; i < BARS.length; i++) {
                Readout r = BARS[i];
                toggle(widgets, i % 2 == 0 ? contentX : col2, y[0], half, r.label, () -> ps.isReadoutOn(r),
                        v -> ps.setReadoutOn(r, v), ps::save, requestRebuild);
                if (i % 2 == 1) {
                    y[0] += ROW;
                }
            }
            widgets.add(SettingsButtonWidget.cycling(barStyleText(ps), (btn, dir) -> {
                        ps.setBarStyle(SettingsButtonWidget.step(ps.getBarStyle(), dir));
                        ps.save();
                        btn.setMessage(barStyleText(ps));
                    }).bounds(contentX, y[0], half, ROW_H).build());
            widgets.add(SettingsButtonWidget.cycling(textSizeText(ps), (btn, dir) -> {
                        ps.setBarTextSize(SettingsButtonWidget.step(ps.getBarTextSize(), dir));
                        ps.save();
                        btn.setMessage(textSizeText(ps));
                    }).bounds(col2, y[0], half, ROW_H).build());
            y[0] += ROW;
        }

        // ---------------- Advanced ----------------
        boolean open = scanningForSearch || ps.isMenuAdvancedOpen();
        y[0] = CollapsibleSection.header(widgets, contentX, y[0], contentWidth, "Advanced", false, open, () -> {
            ps.setMenuAdvancedOpen(!ps.isMenuAdvancedOpen());
            ps.save();
            requestRebuild.run();
        });
        if (!open) {
            return widgets;
        }

        if (showAll) {
            note(widgets, contentX, contentWidth, y, "Look");
            // Layout (2026-10-07): Predefined puts the readouts in areas round the hotbar (StatLayout), Custom leaves
            // each where Edit Bars put it.
            widgets.add(SettingsButtonWidget.cycling(Component.literal("Layout: "
                            + (ps.isPredefinedLayout() ? "§6Predefined" : "§6Custom")), (btn, dir) -> {
                        ps.setPredefinedLayout(!ps.isPredefinedLayout());
                        ps.save();
                        requestRebuild.run();
                    }).bounds(contentX, y[0], contentWidth, ROW_H).build());
            y[0] += ROW;
            widgets.add(SettingsButtonWidget.cycling(cornersText(ps), (btn, dir) -> {
                        ps.setBarRounded(!ps.isBarRounded());
                        ps.save();
                        btn.setMessage(cornersText(ps));
                    }).bounds(contentX, y[0], half, ROW_H).build());
            toggle(widgets, col2, y[0], half, "Icons", ps::isBarIcons, ps::setBarIcons, ps::save, null);
            y[0] += ROW;
            toggle(widgets, contentX, y[0], half, "Show Value", ps::isBarShowValue, ps::setBarShowValue, ps::save,
                    requestRebuild);
            if (ps.isBarShowValue() || scanningForSearch) {
                widgets.add(SettingsButtonWidget.cycling(valuePositionText(ps), (btn, dir) -> {
                            ps.setBarValuePosition(SettingsButtonWidget.step(ps.getBarValuePosition(), dir));
                            ps.save();
                            btn.setMessage(valuePositionText(ps));
                        }).bounds(col2, y[0], half, ROW_H).build());
            }
            y[0] += ROW;
            toggle(widgets, contentX, y[0], half, "Smooth Fill", ps::isBarSmooth, ps::setBarSmooth, ps::save, null);
            toggle(widgets, col2, y[0], half, "Damage Ghost", ps::isBarDamageGhost, ps::setBarDamageGhost, ps::save,
                    null);
            y[0] += ROW;
            // Every bar at once; one bar on its own is sized by dragging its edges in Edit Bars. Predefined's shared
            // size has had its own Bar Size row below since 2026-10-19 (he asked for it in 0.5 steps); per-readout scale
            // sliders stay gone (killer560, 2026-10-07: "no one needs to adjust scale there"; testkit 393).
            slider(widgets, contentX, y[0], half, () -> "Bar Width: " + ps.getBarWidth(),
                    (ps.getBarWidth() - PlayerStatsConfig.MIN_BAR_WIDTH)
                            / (double) (PlayerStatsConfig.MAX_BAR_WIDTH - PlayerStatsConfig.MIN_BAR_WIDTH),
                    v -> {
                        ps.setBarWidth((int) Math.round(PlayerStatsConfig.MIN_BAR_WIDTH
                                + v * (PlayerStatsConfig.MAX_BAR_WIDTH - PlayerStatsConfig.MIN_BAR_WIDTH)));
                        ps.save();
                    });
            slider(widgets, col2, y[0], half, () -> "Bar Height: " + ps.getBarHeight(),
                    (ps.getBarHeight() - PlayerStatsConfig.MIN_BAR_HEIGHT)
                            / (double) (PlayerStatsConfig.MAX_BAR_HEIGHT - PlayerStatsConfig.MIN_BAR_HEIGHT),
                    v -> {
                        ps.setBarHeight((int) Math.round(PlayerStatsConfig.MIN_BAR_HEIGHT
                                + v * (PlayerStatsConfig.MAX_BAR_HEIGHT - PlayerStatsConfig.MIN_BAR_HEIGHT)));
                        ps.save();
                    });
            y[0] += ROW;
            // Bar Size (killer560, 2026-10-19: the bar size slider should move in 0.5 increments): the size every readout
            // shares in the Predefined layout, the same value scrolling a bar in Edit Bars changes (that scroll keeps its
            // 0.1 step). 0.5x to 3.0x, six stops. Custom sizes each readout on its own in Edit Bars, so no row there.
            // Show Symbols (killer560, 2026-10-19, default on) sits beside it: Hypixel's stat glyph before each number.
            toggle(widgets, col2, y[0], half, "Show Symbols", ps::isBarSymbols, ps::setBarSymbols, ps::save, null);
            if (ps.isPredefinedLayout() || scanningForSearch) {
                double span = PlayerStatsConfig.MAX_PREDEFINED_SCALE - PlayerStatsConfig.MIN_PREDEFINED_SCALE;
                int stops = (int) Math.round(span / BAR_SIZE_STEP);
                slider(widgets, contentX, y[0], half,
                        () -> String.format(java.util.Locale.ROOT, "Bar Size: %.1fx", ps.getPredefinedScale()),
                        (ps.getPredefinedScale() - PlayerStatsConfig.MIN_PREDEFINED_SCALE) / span,
                        v -> {
                            ps.setPredefinedScale((float) (PlayerStatsConfig.MIN_PREDEFINED_SCALE
                                    + Math.round(v * stops) * BAR_SIZE_STEP));
                            ps.save();
                        }, stops);
            }
            y[0] += ROW;

            // Colours, one compact grid: each bar that is on, the overflow layers of the bars that have one, and the
            // empty track.
            note(widgets, contentX, contentWidth, y, "Colors");
            int cols = contentWidth >= 420 ? 3 : 2;
            int cellW = (contentWidth - GAP * (cols - 1)) / cols;
            int[] cell = {0};
            for (Readout r : BARS) {
                if (ps.isReadoutOn(r) || scanningForSearch) {
                    gridColour(widgets, contentX, y, cols, cellW, cell, colourLabel(r), ps.getReadoutColor(r),
                            r.defaultColor, c -> ps.setReadoutColor(r, c));
                }
            }
            if (ps.isReadoutOn(Readout.HEALTH_BAR) || scanningForSearch) {
                gridColour(widgets, contentX, y, cols, cellW, cell, "Absorption Color", ps.getAbsorptionColor(),
                        PlayerStatsConfig.DEFAULT_ABSORPTION_COLOR, ps::setAbsorptionColor);
            }
            if (ps.isReadoutOn(Readout.MANA_BAR) || scanningForSearch) {
                gridColour(widgets, contentX, y, cols, cellW, cell, "Overflow Mana Color", ps.getOverflowManaColor(),
                        PlayerStatsConfig.DEFAULT_OVERFLOW_MANA_COLOR, ps::setOverflowManaColor);
            }
            gridColour(widgets, contentX, y, cols, cellW, cell, "Background", ps.getBarBackground(),
                    PlayerStatsConfig.DEFAULT_BAR_BACKGROUND, ps::setBarBackground);
            if (cell[0] % cols != 0) {
                y[0] += ROW;
            }

            // Text readouts: the same stats as text, plus the text-only ones, each with its colour once it is on.
            note(widgets, contentX, contentWidth, y, "Text readouts");
            grid(widgets, contentX, col2, half, y, TEXTS, requestRebuild);
            // The grid's odd last cell leaves the right column free: Text Shadow sits there.
            y[0] -= ROW;
            toggle(widgets, col2, y[0], half, "Text Shadow", ps::isTextShadow, ps::setTextShadow, ps::save, null);
            y[0] += ROW;
        }

        // ---------------- Hide ----------------
        header(widgets, contentX, contentWidth, y, "Hide");
        if (showAll) {
            note(widgets, contentX, contentWidth, y, "While Stat Bars is on:");
            toggle(widgets, contentX, y[0], half, "Hearts", ps::isHideVanillaHearts, ps::setHideVanillaHearts,
                    ps::save, requestRebuild);
            toggle(widgets, col2, y[0], half, "Hunger Bar", ps::isHideVanillaHunger, ps::setHideVanillaHunger,
                    ps::save, null);
            y[0] += ROW;
            toggle(widgets, contentX, y[0], half, "Armor Bar", ps::isHideVanillaArmour, ps::setHideVanillaArmour,
                    ps::save, null);
            toggle(widgets, col2, y[0], half, "Air Bar", ps::isHideVanillaAir, ps::setHideVanillaAir, ps::save, null);
            y[0] += ROW;
            if (ps.isHideVanillaHearts() || scanningForSearch) {
                toggle(widgets, contentX, y[0], half, "Unhide Hearts In Rift", ps::isShowHeartsInRift,
                        ps::setShowHeartsInRift, ps::save, null);
                y[0] += ROW;
            }
        }
        // These never depended on Stat Bars, so they show whether it is on or not.
        note(widgets, contentX, contentWidth, y, "Always:");
        toggle(widgets, contentX, y[0], half, "Health", oh::getHideHealthBarRaw, oh::setHideHealthBar, oh::save, null);
        toggle(widgets, col2, y[0], half, "Absorption", oh::getHideAbsorptionHeartsRaw, oh::setHideAbsorptionHearts,
                oh::save, null);
        y[0] += ROW;
        toggle(widgets, contentX, y[0], half, "Mount Health", oh::getHideMountHealthBarRaw, oh::setHideMountHealthBar,
                oh::save, null);
        toggle(widgets, col2, y[0], half, "Regeneration Bounce", oh::getHideRegenBounceRaw, oh::setHideRegenBounce,
                oh::save, null);
        y[0] += ROW;
        toggle(widgets, contentX, y[0], half, "Armor", oh::getHideArmorBarRaw, oh::setHideArmorBar, oh::save, null);
        toggle(widgets, col2, y[0], half, "Hunger", oh::getHideHungerBarRaw, oh::setHideHungerBar, oh::save, null);
        y[0] += ROW;
        toggle(widgets, contentX, y[0], half, "XP Bar And Level", ps::isHideXpBar, ps::setHideXpBar, ps::save,
                requestRebuild);
        // Hypixel's own numbers on the action bar: independent of Stat Bars, like the hides above.
        toggle(widgets, col2, y[0], half, "Hypixel Stat Text", ps::isHideHypixelStatText, ps::setHideHypixelStatText,
                ps::save, null);
        y[0] += ROW;
        // Vanilla's held-item name over the hotbar (2026-10-07): Shown / Hidden Over Bars (the default) / Hidden.
        widgets.add(SettingsButtonWidget.cycling(heldItemNameText(ps), (btn, dir) -> {
                    ps.setHeldItemName(SettingsButtonWidget.step(ps.getHeldItemName(), dir));
                    ps.save();
                    btn.setMessage(heldItemNameText(ps));
                }).bounds(contentX, y[0], contentWidth, ROW_H).build());
        y[0] += ROW;
        return widgets;
    }

    /** "Health Colour" for the Health Bar: the bar's label without "Bar", for the colour grid. */
    private static String colourLabel(Readout r) {
        return switch (r) {
            case OTHER_BAR -> "Other Color";
            default -> r.label.replace(" Bar", "") + " Color";
        };
    }

    /** One colour cell of a {@code cols}-wide grid; {@code cell[0]} counts cells and moves {@code y} on a row end. */
    private static void gridColour(List<AbstractWidget> widgets, int x0, int[] y, int cols, int cellW, int[] cell,
                                   String label, int argb, int defaultArgb, IntConsumer apply) {
        int col = cell[0] % cols;
        colour(widgets, x0 + col * (cellW + GAP), y[0], cellW, label, argb, defaultArgb, apply);
        cell[0]++;
        if (col == cols - 1) {
            y[0] += ROW;
        }
    }

    private static Component barStyleText(PlayerStatsConfig ps) {
        return Component.literal("Style: §6" + ps.getBarStyle().label);
    }

    private static Component textSizeText(PlayerStatsConfig ps) {
        return Component.literal("Text Size: §6" + ps.getBarTextSize().label);
    }

    private static Component cornersText(PlayerStatsConfig ps) {
        return Component.literal("Corners: §6" + (ps.isBarRounded() ? "Rounded" : "Square"));
    }

    private static Component valuePositionText(PlayerStatsConfig ps) {
        return Component.literal("Value Position: §6" + ps.getBarValuePosition().label);
    }

    private static Component heldItemNameText(PlayerStatsConfig ps) {
        return Component.literal("Held Item Name: §6" + ps.getHeldItemName().label);
    }

    /** Readouts two to a row, left column then right; leaves {@code y} under the last row. */
    private static void grid(List<AbstractWidget> widgets, int left, int right, int half, int[] y, Readout[] cells,
                             Runnable requestRebuild) {
        for (int i = 0; i < cells.length; i++) {
            readout(widgets, i % 2 == 0 ? left : right, half, y[0], cells[i], requestRebuild);
            if (i % 2 == 1 || i == cells.length - 1) {
                y[0] += ROW;
            }
        }
    }

    /** One readout in a half-width cell: "<Name>: ON/OFF", and its colour beside it while it is on. */
    private static void readout(List<AbstractWidget> widgets, int x, int width, int y, Readout r,
                                Runnable requestRebuild) {
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
        boolean on = ps.isReadoutOn(r);
        int toggleW = on ? width - SWATCH_W - 4 : width;
        widgets.add(SettingsButtonWidget.builder(onOff(r.label, on), btn -> {
                    ps.setReadoutOn(r, !ps.isReadoutOn(r));
                    ps.save();
                    requestRebuild.run();
                }).bounds(x, y, Math.max(1, toggleW), ROW_H).build());
        if (on) {
            colour(widgets, x + width - SWATCH_W, y, SWATCH_W, "Color", ps.getReadoutColor(r), r.defaultColor,
                    c -> ps.setReadoutColor(r, c));
        }
    }

    private static void header(List<AbstractWidget> widgets, int x, int width, int[] y, String title) {
        y[0] += 4;
        widgets.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(title, false),
                Minecraft.getInstance().font));
        y[0] += 16;
    }

    private static void note(List<AbstractWidget> widgets, int x, int width, int[] y, String text) {
        y[0] += 2;
        widgets.add(new StringWidget(x, y[0], width, 10, Component.literal("§7" + text),
                Minecraft.getInstance().font));
        y[0] += 13;
    }

    private static void toggle(List<AbstractWidget> widgets, int x, int y, int width, String label,
                               BooleanSupplier get, Consumer<Boolean> set, Runnable save, Runnable rebuildOrNull) {
        widgets.add(SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
                    set.accept(!get.getAsBoolean());
                    save.run();
                    if (rebuildOrNull != null) {
                        rebuildOrNull.run();
                    } else {
                        btn.setMessage(onOff(label, get.getAsBoolean()));
                    }
                }).bounds(x, y, Math.max(1, width), ROW_H).build());
    }

    private static void colour(List<AbstractWidget> widgets, int x, int y, int width, String label, int argb,
                               int defaultArgb, IntConsumer apply) {
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label(label, argb), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, argb, defaultArgb,
                            picked -> {
                                apply.accept(picked);
                                PlayerStatsConfig.getInstance().save();
                            }));
                }).bounds(x, y, Math.max(1, width), ROW_H).build());
    }

    private static void slider(List<AbstractWidget> widgets, int x, int y, int width, Supplier<String> text,
                               double normalized, DoubleConsumer apply) {
        slider(widgets, x, y, width, text, normalized, apply, 0);
    }

    /** {@code steps} > 0 snaps the knob to that many equal steps ({@link ThemedSliderButton#steps}). */
    private static void slider(List<AbstractWidget> widgets, int x, int y, int width, Supplier<String> text,
                               double normalized, DoubleConsumer apply, int steps) {
        ThemedSliderButton s = new ThemedSliderButton(x, y, Math.max(1, width), ROW_H, Component.literal(text.get()),
                Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
            }
        };
        if (steps > 0) {
            s.steps(steps);
        }
        widgets.add(s);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

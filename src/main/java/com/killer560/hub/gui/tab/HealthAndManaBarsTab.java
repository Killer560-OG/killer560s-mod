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

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
        ObjectHiderConfig oh = ObjectHiderConfig.getInstance();
        int half = (contentWidth - GAP) / 2;
        int col2 = contentX + half + GAP;
        int[] y = {contentY};
        boolean master = ps.isEnabledRaw();

        widgets.add(SettingsButtonWidget.builder(onOff("Stat Bars", master), btn -> {
                    ps.setEnabled(!ps.isEnabledRaw());
                    ps.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        if (master) {
            // ---------------- Bars ----------------
            header(widgets, contentX, contentWidth, y, "Bars");
            grid(widgets, contentX, col2, half, y, BARS, requestRebuild);
            boolean anyBar = false;
            for (Readout r : BARS) {
                anyBar |= ps.isReadoutOn(r);
            }
            if (anyBar) {
                y[0] += 4;
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
                toggle(widgets, contentX, y[0], half, "Show Value", ps::isBarShowValue, ps::setBarShowValue,
                        ps::save, null);
                colour(widgets, col2, y[0], half, "Background", ps.getBarBackground(),
                        PlayerStatsConfig.DEFAULT_BAR_BACKGROUND, ps::setBarBackground);
                y[0] += ROW;
                if (ps.isReadoutOn(Readout.HEALTH_BAR)) {
                    colour(widgets, contentX, y[0], half, "Absorption Colour", ps.getAbsorptionColor(),
                            PlayerStatsConfig.DEFAULT_ABSORPTION_COLOR, ps::setAbsorptionColor);
                    y[0] += ROW;
                }
            }

            // ---------------- Text ----------------
            header(widgets, contentX, contentWidth, y, "Text");
            grid(widgets, contentX, col2, half, y, TEXTS, requestRebuild);
            // The grid's odd last cell leaves the right column free: Text Shadow sits there.
            y[0] -= ROW;
            toggle(widgets, col2, y[0], half, "Text Shadow", ps::isTextShadow, ps::setTextShadow, ps::save, null);
            y[0] += ROW;
            if (ps.isReadoutOn(Readout.XP_BAR) || ps.isReadoutOn(Readout.XP_TEXT)) {
                // A custom XP readout mostly replaces the vanilla bar: offer its hide here, beside where it was turned
                // on. The same setting as Hide's "XP Bar And Level".
                toggle(widgets, contentX, y[0], contentWidth, "Hide Vanilla XP Bar", ps::isHideXpBar, ps::setHideXpBar,
                        ps::save, requestRebuild);
                y[0] += ROW;
            }
        }

        // ---------------- Hide ----------------
        header(widgets, contentX, contentWidth, y, "Hide");
        if (master) {
            note(widgets, contentX, contentWidth, y, "While Stat Bars is on:");
            toggle(widgets, contentX, y[0], half, "Hearts", ps::isHideVanillaHearts, ps::setHideVanillaHearts,
                    ps::save, requestRebuild);
            toggle(widgets, col2, y[0], half, "Hunger Bar", ps::isHideVanillaHunger, ps::setHideVanillaHunger,
                    ps::save, null);
            y[0] += ROW;
            toggle(widgets, contentX, y[0], half, "Armour Bar", ps::isHideVanillaArmour, ps::setHideVanillaArmour,
                    ps::save, null);
            toggle(widgets, col2, y[0], half, "Air Bar", ps::isHideVanillaAir, ps::setHideVanillaAir, ps::save, null);
            y[0] += ROW;
            if (ps.isHideVanillaHearts()) {
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
        toggle(widgets, contentX, y[0], half, "Armour", oh::getHideArmorBarRaw, oh::setHideArmorBar, oh::save, null);
        toggle(widgets, col2, y[0], half, "Hunger", oh::getHideHungerBarRaw, oh::setHideHungerBar, oh::save, null);
        y[0] += ROW;
        toggle(widgets, contentX, y[0], half, "XP Bar And Level", ps::isHideXpBar, ps::setHideXpBar, ps::save,
                requestRebuild);
        // Hypixel's own numbers on the action bar: independent of Stat Bars, like the hides above.
        toggle(widgets, col2, y[0], half, "Hypixel Stat Text", ps::isHideHypixelStatText, ps::setHideHypixelStatText,
                ps::save, null);
        y[0] += ROW;
        return widgets;
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
            colour(widgets, x + width - SWATCH_W, y, SWATCH_W, "Colour", ps.getReadoutColor(r), r.defaultColor,
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
        widgets.add(new ThemedSliderButton(x, y, Math.max(1, width), ROW_H, Component.literal(text.get()),
                Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
            }
        });
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

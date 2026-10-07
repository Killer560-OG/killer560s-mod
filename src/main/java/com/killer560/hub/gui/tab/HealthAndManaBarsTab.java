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
 *  adjust scale there, they just use the edit hud menu portion." The four nested dropdowns are gone; the page is the
 *  Stat Bars switch, then one row per stat (bar and text side by side, each with its colour once it is on), then
 *  Vanilla HUD, then Text, with Classic Display folded away at the bottom. The per-readout Scale sliders were removed:
 *  they wrote the very same {@code HudConfig} scale the HUD editor scrolls ({@code hud.setScale(r.hudId, ...)}), so
 *  there is nothing to migrate - every saved size is already the editor's and keeps drawing through
 *  {@code HudElementRegistry.resolveScale}. Bar Width and Bar Height stay: they set the bars' SHAPE (length against
 *  thickness, independently, and leave the number on the bar its normal size), which a uniform scale cannot do. */
public class HealthAndManaBarsTab extends BaseTab {

    private static final int GAP = 6;
    private static final int ROW_H = 18;
    private static final int ROW = 22;
    /** Width of the "Colour: ■" button beside a readout's toggle. */
    private static final int SWATCH_W = 52;
    /** Classic Display stays folded away (session-only, like FolderTab's accordion state): it is the old one-line
     *  element most people never use. */
    private static boolean classicOpen;

    /** The rows of the bar list: a stat's bar on the left and its text on the right, then the text-only stats. */
    private static final Readout[][] PAIRS = {
            {Readout.HEALTH_BAR, Readout.HEALTH_TEXT},
            {Readout.MANA_BAR, Readout.MANA_TEXT},
            {Readout.DEFENCE_BAR, Readout.DEFENCE_TEXT},
            {Readout.OTHER_BAR, Readout.OTHER_TEXT},
            {Readout.OVERFLOW_TEXT, Readout.INTELLIGENCE_TEXT},
            {Readout.EFFECTIVE_HEALTH_TEXT, null},
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

        // ---------------- Bars and text: one row per stat ----------------
        if (master) {
            header(widgets, contentX, contentWidth, y, "Bars");
            for (Readout[] pair : PAIRS) {
                readout(widgets, contentX, half, y[0], pair[0], requestRebuild);
                if (pair[1] != null) {
                    readout(widgets, col2, half, y[0], pair[1], requestRebuild);
                }
                y[0] += ROW;
            }
            boolean anyBar = false;
            for (Readout r : Readout.values()) {
                anyBar |= r.bar && ps.isReadoutOn(r);
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
        }

        // ---------------- Vanilla HUD ----------------
        header(widgets, contentX, contentWidth, y, "Vanilla HUD");
        if (master) {
            note(widgets, contentX, contentWidth, y, "Hidden while Stat Bars is on:");
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
        note(widgets, contentX, contentWidth, y, "Always hidden:");
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
        toggle(widgets, contentX, y[0], half, "XP Bar And Level", ps::isHideXpBar, ps::setHideXpBar, ps::save, null);
        y[0] += ROW;

        // ---------------- Text ----------------
        header(widgets, contentX, contentWidth, y, "Text");
        // Hypixel's own numbers on the action bar: independent of Stat Bars, like the hides above.
        toggle(widgets, contentX, y[0], half, "Hide Hypixel Stat Text", ps::isHideHypixelStatText,
                ps::setHideHypixelStatText, ps::save, null);
        if (master) {
            toggle(widgets, col2, y[0], half, "Text Shadow", ps::isTextShadow, ps::setTextShadow, ps::save, null);
        }
        y[0] += ROW;

        // ---------------- Classic Display (the original one-line Stat Bars element) ----------------
        if (master) {
            y[0] = CollapsibleSection.header(widgets, contentX, y[0], contentWidth, "Classic Display", false,
                    classicOpen, () -> {
                        classicOpen = !classicOpen;
                        requestRebuild.run();
                    });
            if (classicOpen) {
                toggle(widgets, contentX, y[0], half, "Show Health", ps::isShowHealth, ps::setShowHealth, ps::save,
                        null);
                toggle(widgets, col2, y[0], half, "Show Mana", ps::isShowMana, ps::setShowMana, ps::save, null);
                y[0] += ROW;
                toggle(widgets, contentX, y[0], half, "Show Defense", ps::isShowDefense, ps::setShowDefense,
                        ps::save, null);
                y[0] += ROW;
                // killer560: "I should have an option to hide or show the text and the bar when the bars are
                // working as well" - independent of which of Health/Mana/Defense above are on.
                toggle(widgets, contentX, y[0], half, "Show Text", ps::isShowText, ps::setShowText, ps::save, null);
                toggle(widgets, col2, y[0], half, "Show Bar", ps::isShowBar, ps::setShowBar, ps::save, null);
                y[0] += ROW;
            }
        }
        return widgets;
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

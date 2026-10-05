package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.hud.HudConfig;
import com.killer560.hub.objecthider.ObjectHiderConfig;
import com.killer560.hub.playerstats.PlayerStatsConfig;
import com.killer560.hub.playerstats.StatElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Health and Mana Bars - the home for the mod's own player-status bars.
 *  <p>
 *  Created 2026-09-30 from killer560's request: "move the player display hide to another section called
 *  something like fancy bars or something where we will reskin the health hearts into a custom health bar
 *  and make a mana bar and whatnot." Every vanilla-bar hide here still reads and writes the SAME
 *  {@link ObjectHiderConfig} fields under the same JSON keys it always did.
 *  <p>
 *  2026-10-04, killer560: "There should be a few dropdowns inside of the overarching dropdown. First the hide
 *  stuff ... Then I want you to make custom health, intel, vitality, defence, true defence, and other such
 *  bars ... Also add an option to hide the text Hypixel has like 3000/3000 with the heart symbol ... Also add
 *  an option to hide the enchanting bar and its level. But make options for custom text, custom bars and
 *  whatnot all scalable." So the old Stat Bars section (it lived in New) moved in here whole, and the section
 *  is now the Stat Bars master toggle over four nested dropdowns: Hide, Bars, Text and Classic Display. Each
 *  custom bar and text is its own HUD element ({@link StatElements}) with its own colour and scale; the scale
 *  slider writes the same {@link HudConfig} scale the HUD editor does. No saved setting changed key. */
public class HealthAndManaBarsTab extends BaseTab {

    private static final int GAP = 6;
    // Which nested dropdowns are open. Session-only, like FolderTab's own accordion state.
    private static boolean hideOpen;
    private static boolean barsOpen;
    private static boolean textOpen;
    private static boolean classicOpen;

    public HealthAndManaBarsTab() {
        super("Health and Mana Bars");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
        int half = (contentWidth - GAP) / 2;
        int col2 = contentX + half + GAP;
        int[] y = {contentY};

        widgets.add(SettingsButtonWidget.builder(onOff("Stat Bars", ps.isEnabledRaw()), btn -> {
                    ps.setEnabled(!ps.isEnabledRaw());
                    ps.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;

        // ---------------- Hide ----------------
        y[0] = CollapsibleSection.header(widgets, contentX, y[0], contentWidth, "Hide", false, hideOpen, () -> {
            hideOpen = !hideOpen;
            requestRebuild.run();
        });
        if (hideOpen) {
            buildHide(widgets, contentX, contentWidth, half, col2, y, requestRebuild);
        }

        // ---------------- Bars ----------------
        y[0] = CollapsibleSection.header(widgets, contentX, y[0], contentWidth, "Bars", false, barsOpen, () -> {
            barsOpen = !barsOpen;
            requestRebuild.run();
        });
        if (barsOpen) {
            if (!needsMaster(widgets, contentX, contentWidth, y, ps)) {
                buildBars(widgets, contentX, contentWidth, half, col2, y, requestRebuild);
            }
        }

        // ---------------- Text ----------------
        y[0] = CollapsibleSection.header(widgets, contentX, y[0], contentWidth, "Text", false, textOpen, () -> {
            textOpen = !textOpen;
            requestRebuild.run();
        });
        if (textOpen) {
            if (!needsMaster(widgets, contentX, contentWidth, y, ps)) {
                for (StatElements.Readout r : StatElements.Readout.values()) {
                    if (!r.bar) {
                        readoutRows(widgets, contentX, contentWidth, half, col2, y, r, requestRebuild);
                    }
                }
                toggle(widgets, contentX, y[0], contentWidth, "Text Shadow", ps::isTextShadow, ps::setTextShadow,
                        ps, null);
                y[0] += 24;
            }
        }

        // ---------------- Classic Display (the original one-line Stat Bars element) ----------------
        y[0] = CollapsibleSection.header(widgets, contentX, y[0], contentWidth, "Classic Display", false,
                classicOpen, () -> {
                    classicOpen = !classicOpen;
                    requestRebuild.run();
                });
        if (classicOpen) {
            if (!needsMaster(widgets, contentX, contentWidth, y, ps)) {
                toggle(widgets, contentX, y[0], half, "Show Health", ps::isShowHealth, ps::setShowHealth, ps, null);
                toggle(widgets, col2, y[0], half, "Show Mana", ps::isShowMana, ps::setShowMana, ps, null);
                y[0] += 22;
                toggle(widgets, contentX, y[0], contentWidth, "Show Defense", ps::isShowDefense, ps::setShowDefense,
                        ps, null);
                y[0] += 22;
                // killer560: "I should have an option to hide or show the text and the bar when the bars are
                // working as well" - independent of which of Health/Mana/Defense above are on.
                toggle(widgets, contentX, y[0], half, "Show Text", ps::isShowText, ps::setShowText, ps, null);
                toggle(widgets, col2, y[0], half, "Show Bar", ps::isShowBar, ps::setShowBar, ps, null);
                y[0] += 24;
            }
        }

        return widgets;
    }

    private static void buildHide(List<AbstractWidget> widgets, int contentX, int contentWidth, int half, int col2,
                                  int[] y, Runnable requestRebuild) {
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();

        ObjectHiderTab.header(widgets, contentX, contentWidth, y, "Vanilla Bars");
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Health", cfg::getHideHealthBarRaw, cfg::setHideHealthBar);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Absorption", cfg::getHideAbsorptionHeartsRaw, cfg::setHideAbsorptionHearts);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Mount Health", cfg::getHideMountHealthBarRaw, cfg::setHideMountHealthBar);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Regeneration Bounce", cfg::getHideRegenBounceRaw, cfg::setHideRegenBounce);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Armour", cfg::getHideArmorBarRaw, cfg::setHideArmorBar);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hunger", cfg::getHideHungerBarRaw, cfg::setHideHungerBar);

        ObjectHiderTab.header(widgets, contentX, contentWidth, y, "Hypixel Text and XP");
        toggle(widgets, contentX, y[0], contentWidth, "Hypixel Stat Text", ps::isHideHypixelStatText,
                ps::setHideHypixelStatText, ps, null);
        y[0] += 22;
        toggle(widgets, contentX, y[0], contentWidth, "XP Bar And Level", ps::isHideXpBar, ps::setHideXpBar, ps, null);
        y[0] += 24;

        // The Stat Bars set (they moved here from the old Stat Bars section on 2026-10-04): these only act while
        // Stat Bars is on, which is why they are listed separately from the always-on hides above.
        if (ps.isEnabledRaw()) {
            ObjectHiderTab.header(widgets, contentX, contentWidth, y, "While Stat Bars Is On");
            toggle(widgets, contentX, y[0], half, "Hearts", ps::isHideVanillaHearts, ps::setHideVanillaHearts, ps,
                    requestRebuild);
            toggle(widgets, col2, y[0], half, "Hunger Bar", ps::isHideVanillaHunger, ps::setHideVanillaHunger, ps,
                    null);
            y[0] += 22;
            toggle(widgets, contentX, y[0], half, "Armor Bar", ps::isHideVanillaArmour, ps::setHideVanillaArmour, ps,
                    null);
            toggle(widgets, col2, y[0], half, "Air Bar", ps::isHideVanillaAir, ps::setHideVanillaAir, ps, null);
            y[0] += 22;
            if (ps.isHideVanillaHearts()) {
                toggle(widgets, contentX, y[0], contentWidth, "Unhide Hearts In Rift", ps::isShowHeartsInRift,
                        ps::setShowHeartsInRift, ps, null);
                y[0] += 22;
            }
            y[0] += 2;
        }
    }

    private static void buildBars(List<AbstractWidget> widgets, int contentX, int contentWidth, int half, int col2,
                                  int[] y, Runnable requestRebuild) {
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
        for (StatElements.Readout r : StatElements.Readout.values()) {
            if (r.bar) {
                readoutRows(widgets, contentX, contentWidth, half, col2, y, r, requestRebuild);
            }
        }
        ObjectHiderTab.header(widgets, contentX, contentWidth, y, "All Bars");
        slider(widgets, contentX, y[0], half,
                () -> "Bar Width: " + ps.getBarWidth(),
                (ps.getBarWidth() - PlayerStatsConfig.MIN_BAR_WIDTH)
                        / (double) (PlayerStatsConfig.MAX_BAR_WIDTH - PlayerStatsConfig.MIN_BAR_WIDTH),
                v -> {
                    ps.setBarWidth((int) Math.round(PlayerStatsConfig.MIN_BAR_WIDTH
                            + v * (PlayerStatsConfig.MAX_BAR_WIDTH - PlayerStatsConfig.MIN_BAR_WIDTH)));
                    ps.save();
                });
        slider(widgets, col2, y[0], half,
                () -> "Bar Height: " + ps.getBarHeight(),
                (ps.getBarHeight() - PlayerStatsConfig.MIN_BAR_HEIGHT)
                        / (double) (PlayerStatsConfig.MAX_BAR_HEIGHT - PlayerStatsConfig.MIN_BAR_HEIGHT),
                v -> {
                    ps.setBarHeight((int) Math.round(PlayerStatsConfig.MIN_BAR_HEIGHT
                            + v * (PlayerStatsConfig.MAX_BAR_HEIGHT - PlayerStatsConfig.MIN_BAR_HEIGHT)));
                    ps.save();
                });
        y[0] += 22;
        toggle(widgets, contentX, y[0], half, "Show Value", ps::isBarShowValue, ps::setBarShowValue, ps, null);
        colour(widgets, col2, y[0], half, "Background", ps.getBarBackground(),
                PlayerStatsConfig.DEFAULT_BAR_BACKGROUND, ps::setBarBackground);
        y[0] += 22;
        colour(widgets, contentX, y[0], contentWidth, "Absorption Colour", ps.getAbsorptionColor(),
                PlayerStatsConfig.DEFAULT_ABSORPTION_COLOR, ps::setAbsorptionColor);
        y[0] += 24;
    }

    /** One readout: "<Name>: ON/OFF" beside its colour, and its scale slider underneath while it is on. */
    private static void readoutRows(List<AbstractWidget> widgets, int contentX, int contentWidth, int half, int col2,
                                    int[] y, StatElements.Readout r, Runnable requestRebuild) {
        PlayerStatsConfig ps = PlayerStatsConfig.getInstance();
        boolean on = ps.isReadoutOn(r);
        widgets.add(SettingsButtonWidget.builder(onOff(r.label, on), btn -> {
                    ps.setReadoutOn(r, !ps.isReadoutOn(r));
                    ps.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], on ? half : contentWidth, 18).build());
        if (on) {
            colour(widgets, col2, y[0], half, "Colour", ps.getReadoutColor(r), r.defaultColor,
                    c -> ps.setReadoutColor(r, c));
            y[0] += 20;
            HudConfig hud = HudConfig.getInstance();
            slider(widgets, contentX, y[0], contentWidth,
                    () -> String.format(Locale.US, "Scale: %.2fx", hud.getScale(r.hudId, 1.0f)),
                    (hud.getScale(r.hudId, 1.0f) - MIN_SCALE) / (MAX_SCALE - MIN_SCALE),
                    v -> {
                        float s = (float) (MIN_SCALE + v * (MAX_SCALE - MIN_SCALE));
                        hud.setScale(r.hudId, Math.round(s * 20f) / 20f);
                        hud.save();
                    });
            y[0] += 24;
        } else {
            y[0] += 22;
        }
    }

    private static final double MIN_SCALE = 0.5;
    private static final double MAX_SCALE = 4.0;

    /** Shows a one-line note instead of a section's rows while Stat Bars itself is off. @return true if shown. */
    private static boolean needsMaster(List<AbstractWidget> widgets, int x, int width, int[] y, PlayerStatsConfig ps) {
        if (ps.isEnabledRaw()) {
            return false;
        }
        widgets.add(new StringWidget(x, y[0], width, 12,
                Component.literal("§7Turn on Stat Bars above to use these."), Minecraft.getInstance().font));
        y[0] += 18;
        return true;
    }

    private static void toggle(List<AbstractWidget> widgets, int x, int y, int width, String label,
                               BooleanSupplier get, Consumer<Boolean> set, PlayerStatsConfig ps,
                               Runnable rebuildOrNull) {
        widgets.add(SettingsButtonWidget.builder(onOff(label, get.getAsBoolean()), btn -> {
                    set.accept(!get.getAsBoolean());
                    ps.save();
                    if (rebuildOrNull != null) {
                        rebuildOrNull.run();
                    } else {
                        btn.setMessage(onOff(label, get.getAsBoolean()));
                    }
                }).bounds(x, y, Math.max(1, width), 18).build());
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
                }).bounds(x, y, Math.max(1, width), 18).build());
    }

    private static void slider(List<AbstractWidget> widgets, int x, int y, int width,
                               java.util.function.Supplier<String> text, double normalized,
                               java.util.function.DoubleConsumer apply) {
        widgets.add(new ThemedSliderButton(x, y, Math.max(1, width), 18, Component.literal(text.get()),
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

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.mobesp.MobEspConfig;
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
import java.util.function.IntSupplier;
import com.killer560.hub.compat.McCompat;

/**
 * Starred mob / bat / wither highlight settings - see {@link com.killer560.hub.mobesp.MobEspFeature}. Sections, in
 * order (2026-09-16 killer560: "make it so there is a starred mob hitbox's section for bats and starred mobs then
 * there should be a red section under it that is esp"; 2026-09-20: "for mob esp make wither esp its own section ...
 * also add a regular wither highlight that isn't esp"):
 * <ul>
 * <li><b>Starred Mob Hitboxes</b> (orange) - starred mobs + bats, colors, box style, line width. Starred mobs are never
 *     range-limited (QUOI's starred ESP: drawn until they die); bats follow Room Scoped or the Range slider. Always depth-tested / line-of-sight.</li>
 * <li><b>Wither Boss Highlight</b> (orange) - the non-ESP F7/M7 wither highlight: Glow Hitbox or Hitbox Fill, never
 *     through walls, never range-limited, with an optional Tracer line to a wither you can see. Exists on both builds.</li>
 * <li><b>ESP</b> (red, cheat jar only) - ONE switch for everything ESP (2026-10-09): the starred mobs and bats above
 *     through walls, and the F7/M7 wither bosses through walls at any range. It uses the Wither Boss Highlight's
 *     color and Tracer, the tracer then going to every wither through walls.</li>
 * </ul>
 * The legit jar never constructs a single widget of the red sections (killer560: "If you are on the legit version it
 * shouldnt mention cheat things at all"), which is also why the tab itself is named "Starred Mob Hitboxes" there and
 * only "Dungeon ESP" on the cheat build.
 */
public class MobEspTab extends BaseTab {

    public MobEspTab() {
        super(com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? "Dungeon ESP" : "Starred Mob Hitboxes");
    }

    /** The card is ON while any of its features is, OFF only when all are (killer560, 2026-10-14): Starred Mob and Bat hitboxes, the Wither Boss Highlight and the cheat ESP are separate features. */
    @Override
    public List<com.killer560.hub.gui.SettingsButtonWidget> masterToggles(List<AbstractWidget> built) {
        return switchRows(built, "Starred Mobs", "Bats", "Blood Mobs", "Wither Boss Highlight", "ESP");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MobEspConfig cfg = MobEspConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        // ---- Starred Mob Hitboxes (legit) ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Starred Mob Hitboxes", false), mc.font));
        y += 16;
        targetRow(widgets, contentX, col2X, y, colW, "Starred Mobs", cfg::getStarredMobsRaw, cfg::setStarredMobs,
                "Starred Mob", cfg::getStarredColor, cfg::setStarredColor, MobEspConfig.DEFAULT_STARRED_COLOR);
        y += 22;
        targetRow(widgets, contentX, col2X, y, colW, "Bats", cfg::getBatsRaw, cfg::setBats,
                "Bat", cfg::getBatColor, cfg::setBatColor, MobEspConfig.DEFAULT_BAT_COLOR);
        y += 22;
        // killer560, 2026-10-15: "add in blood mob ESP, which is the same as starred mob ESP just for blood mobs and
        // will highlight them red". Same style and rules as the rows above; never limited by Range or Room Scoped.
        targetRow(widgets, contentX, col2X, y, colW, "Blood Mobs", cfg::getBloodMobsRaw, cfg::setBloodMobs,
                "Blood Mob", cfg::getBloodColor, cfg::setBloodColor, MobEspConfig.DEFAULT_BLOOD_COLOR);
        y += 26;

        widgets.add(SettingsButtonWidget.cycling(styleText(cfg), (btn, dir) -> {
                    cfg.cycleStyle(dir);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.getStyle() != MobEspConfig.Style.GLOW) {
            float min = MobEspConfig.MIN_LINE_WIDTH;
            float max = MobEspConfig.MAX_LINE_WIDTH;
            widgets.add(new ThemedSliderButton(col2X, y, colW, 18, lineWidthText(cfg), (cfg.getLineWidth() - min) / (max - min)) {
                @Override
                protected void updateMessage() {
                    setMessage(lineWidthText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setLineWidth((float) (min + this.value * (max - min)));
                    saveOnRelease(() -> cfg.save());
                }
            });
        }
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Room Scoped", cfg.isRoomScoped()), btn -> {
                    cfg.setRoomScoped(!cfg.isRoomScoped());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.isRoomScoped()) {
            double minMargin = MobEspConfig.MIN_ROOM_MARGIN;
            double maxMargin = MobEspConfig.MAX_ROOM_MARGIN;
            widgets.add(new ThemedSliderButton(col2X, y, colW, 18, roomMarginText(cfg),
                    (cfg.getRoomMargin() - minMargin) / (maxMargin - minMargin)) {
                @Override
                protected void updateMessage() {
                    setMessage(roomMarginText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setRoomMargin(minMargin + this.value * (maxMargin - minMargin));
                    saveOnRelease(() -> cfg.save());
                }
            });
        } else {
            double minRange = MobEspConfig.MIN_RANGE;
            double maxRange = MobEspConfig.MAX_RANGE;
            widgets.add(new ThemedSliderButton(col2X, y, colW, 18, rangeText(cfg), (cfg.getRange() - minRange) / (maxRange - minRange)) {
                @Override
                protected void updateMessage() {
                    setMessage(rangeText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setRange(minRange + this.value * (maxRange - minRange));
                    saveOnRelease(() -> cfg.save());
                }
            });
        }
        y += 28;

        // ---- Wither Boss Highlight (legit, its own section since 2026-09-20; renamed 2026-10-09, killer560: "make it
        // 'wither boss highlight' instead of 'wither highlight' for the name") ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Wither Boss Highlight", false), mc.font));
        y += 16;
        targetRow(widgets, contentX, col2X, y, colW, "Wither Boss Highlight", cfg::getWitherHighlightRaw,
                cfg::setWitherHighlight, "Wither Boss Highlight", cfg::getWitherHighlightColor,
                cfg::setWitherHighlightColor, MobEspConfig.DEFAULT_WITHER_COLOR);
        y += 22;
        widgets.add(SettingsButtonWidget.cycling(witherStyleText(cfg), (btn, dir) -> {
                    cfg.cycleWitherHighlightStyle(dir);
                    cfg.save();
                    btn.setMessage(witherStyleText(cfg));
                }).bounds(contentX, y, colW, 18).build());
        y += 22;
        // killer560, 2026-09-21: "for the esp also have an option to have a tracer line to him"; 2026-10-15: "for legit
        // have the tracer option there but only have tracers appear for the bosses if the boss can be seen legitly."
        // One Tracer for both modes: the legit highlight draws it only to a wither it can see, the cheat ESP to every one.
        widgets.add(SettingsButtonWidget.builder(onOff("Tracer", cfg.getWitherTracerRaw()), btn -> {
                    cfg.setWitherTracer(!cfg.getWitherTracerRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.getWitherTracerRaw()) {
            float minT = MobEspConfig.MIN_LINE_WIDTH;
            float maxT = MobEspConfig.MAX_LINE_WIDTH;
            widgets.add(new ThemedSliderButton(col2X, y, colW, 18, tracerThicknessText(cfg),
                    (cfg.getWitherTracerThickness() - minT) / (maxT - minT)) {
                @Override
                protected void updateMessage() {
                    setMessage(tracerThicknessText(cfg));
                }

                @Override
                protected void applyValue() {
                    cfg.setWitherTracerThickness((float) (minT + this.value * (maxT - minT)));
                    saveOnRelease(() -> cfg.save());
                }
            });
        }
        y += 22;

        // ---- ESP (cheat jar only - not disabled, absent) ----
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        // One switch for the whole section (killer560, 2026-10-09: "Make it so there is one toggle for ESP and that is
        // for all things in that section"): starred mobs and bats through walls, and the wither bosses through walls.
        // The old Through Walls and Wither Bosses toggles are gone. The wither color and the tracer are the Wither Boss
        // Highlight's above (killer560, 2026-10-15), so the ESP has no color or tracer of its own.
        y += 6;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("ESP", true), mc.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("ESP", cfg.isEspRaw()), btn -> {
                    cfg.setEsp(!cfg.isEspRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        return widgets;
    }

    private static void targetRow(List<AbstractWidget> widgets, int x, int col2X, int y, int width, String name,
                                  BooleanSupplier getter, Consumer<Boolean> setter, String colorName,
                                  IntSupplier colorGetter, IntConsumer colorSetter, int defaultColor) {
        widgets.add(SettingsButtonWidget.builder(onOff(name, getter.getAsBoolean()), btn -> {
                    setter.accept(!getter.getAsBoolean());
                    MobEspConfig.getInstance().save();
                    btn.setMessage(onOff(name, getter.getAsBoolean()));
                }).bounds(x, y, width, 18).build());
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Color", colorGetter.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), colorName + " Color",
                            colorGetter.getAsInt(), defaultColor, argb -> {
                        colorSetter.accept(argb);
                        MobEspConfig.getInstance().save();
                    }));
                }).bounds(col2X, y, width, 18).build());
    }

    private static Component styleText(MobEspConfig cfg) {
        return Component.literal("Style: §b" + cfg.getStyle().label);
    }

    private static Component witherStyleText(MobEspConfig cfg) {
        return Component.literal("Wither Style: §b" + cfg.getWitherHighlightStyle().label);
    }

    private static Component lineWidthText(MobEspConfig cfg) {
        return Component.literal(String.format(Locale.US, "Line Width: %.1f", cfg.getLineWidth()));
    }

    private static Component tracerThicknessText(MobEspConfig cfg) {
        return Component.literal(String.format(Locale.US, "Tracer Thickness: %.1f", cfg.getWitherTracerThickness()));
    }

    private static Component rangeText(MobEspConfig cfg) {
        return Component.literal(String.format(Locale.US, "Range: %.0f", cfg.getRange()));
    }

    private static Component roomMarginText(MobEspConfig cfg) {
        return Component.literal(String.format(Locale.US, "Room Margin: %.0f", cfg.getRoomMargin()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

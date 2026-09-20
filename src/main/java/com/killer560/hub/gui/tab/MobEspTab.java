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

/**
 * Starred mob / bat / wither highlight settings - see {@link com.killer560.hub.mobesp.MobEspFeature}. Sections, in
 * order (2026-09-16 killer560: "make it so there is a starred mob hitbox's section for bats and starred mobs then
 * there should be a red section under it that is esp"; 2026-09-20: "for mob esp make wither esp its own section ...
 * also add a regular wither highlight that isn't esp"):
 * <ul>
 * <li><b>Starred Mob Hitboxes</b> (orange) - starred mobs + bats, colours, box style, line width, and the selection
 *     rule: Room Scoped (current dungeon room + margin) or the Range slider. Always depth-tested / line-of-sight.</li>
 * <li><b>Wither Highlight</b> (orange) - the non-ESP F7/M7 wither highlight: Glow Hitbox or Hitbox Fill, never
 *     through walls, never range-limited. Exists on both builds.</li>
 * <li><b>ESP</b> (red, cheat jar only) - Through Walls for the starred mobs and bats above.</li>
 * <li><b>Wither ESP</b> (red, cheat jar only) - the F7/M7 wither bosses through walls at any range.</li>
 * </ul>
 * The legit jar never constructs a single widget of the red sections (killer560: "If you are on the legit version it
 * shouldnt mention cheat things at all"), which is also why the tab itself is named "Starred Mob Hitboxes" there and
 * only "Dungeon ESP" on the cheat build.
 */
public class MobEspTab extends BaseTab {

    public MobEspTab() {
        super(com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED ? "Dungeon ESP" : "Starred Mob Hitboxes");
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
        y += 26;

        widgets.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    cfg.cycleStyle();
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
                    cfg.save();
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
                    cfg.save();
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
                    cfg.save();
                }
            });
        }
        y += 28;

        // ---- Wither Highlight (legit, its own section since 2026-09-20) ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Wither Highlight", false), mc.font));
        y += 16;
        targetRow(widgets, contentX, col2X, y, colW, "Wither Highlight", cfg::getWitherHighlightRaw,
                cfg::setWitherHighlight, "Wither Highlight", cfg::getWitherHighlightColor,
                cfg::setWitherHighlightColor, MobEspConfig.DEFAULT_WITHER_COLOR);
        y += 22;
        widgets.add(SettingsButtonWidget.builder(witherStyleText(cfg), btn -> {
                    cfg.cycleWitherHighlightStyle();
                    cfg.save();
                    btn.setMessage(witherStyleText(cfg));
                }).bounds(contentX, y, colW, 18).build());
        y += 22;

        // ---- ESP (cheat jar only - not disabled, absent) ----
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        y += 6;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("ESP", true), mc.font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(onOff("Through Walls", cfg.getThroughWallsRaw()), btn -> {
                    cfg.setThroughWalls(!cfg.getThroughWallsRaw());
                    cfg.save();
                    btn.setMessage(onOff("Through Walls", cfg.getThroughWallsRaw()));
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 28;

        // ---- Wither ESP (cheat jar only, its own section since 2026-09-20) ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Wither ESP", true), mc.font));
        y += 16;
        targetRow(widgets, contentX, col2X, y, colW, "Wither Bosses", cfg::getWithersRaw, cfg::setWithers,
                "Wither", cfg::getWitherColor, cfg::setWitherColor, MobEspConfig.DEFAULT_WITHER_COLOR);
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
                    client.setScreen(new ColorPickerScreen(client.screen, colorName + " Color",
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

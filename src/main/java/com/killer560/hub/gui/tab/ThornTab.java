package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.thorn.ThornConfig;
import com.killer560.hub.thorn.ThornFeature;
import com.killer560.hub.util.ModChat;
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

/** Thorn (F4/M4 boss) settings - see {@link ThornFeature}. Spirit Bear counter, Thorn ESP (Through Walls is cheat build
 *  only, in its own red section) and stun-spot waypoints. */
public class ThornTab extends BaseTab {

    public ThornTab() {
        super("Thorn (F4)");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ThornConfig cfg = ThornConfig.getInstance();
        Minecraft mc = Minecraft.getInstance();
        boolean cheat = com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED;
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int col2X = contentX + colW + gap;
        int y = contentY;

        // ---- Spirit Bear counter ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Spirit Bear Counter", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Spirit Bear HUD", cfg::getBearHudRaw, cfg::setBearHud));
        widgets.add(toggle(col2X, y, colW, "Show Overkill", cfg::getShowOverkillRaw, cfg::setShowOverkill));
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Overkill Chat", cfg::getOverkillChatRaw, cfg::setOverkillChat));
        y += 28;

        // ---- Thorn Highlights (depth-tested unless the cheat-only Through Walls is on) ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Thorn Highlights", false), mc.font));
        y += 16;
        targetRow(widgets, contentX, col2X, y, colW, "Spirit Bear Highlight", cfg::getBearEspRaw, cfg::setBearEsp,
                "Spirit Bear", cfg::getBearColor, cfg::setBearColor, ThornConfig.DEFAULT_BEAR_COLOR);
        y += 22;
        targetRow(widgets, contentX, col2X, y, colW, "Spirit Mob Highlight", cfg::getMobEspRaw, cfg::setMobEsp,
                "Spirit Mob", cfg::getMobColor, cfg::setMobColor, ThornConfig.DEFAULT_MOB_COLOR);
        y += 22;
        targetRow(widgets, contentX, col2X, y, colW, "Spirit Bow Highlight", cfg::getBowEspRaw, cfg::setBowEsp,
                "Spirit Bow", cfg::getBowColor, cfg::setBowColor, ThornConfig.DEFAULT_BOW_COLOR);
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Thorn ESP Style: §b" + cfg.getStyle().label), btn -> {
                    cfg.cycleStyle();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        if (cfg.getStyle() != ThornConfig.Style.GLOW) {
            float min = ThornConfig.MIN_LINE_WIDTH;
            float max = ThornConfig.MAX_LINE_WIDTH;
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
        y += 28;

        if (cheat) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Cheat Build - Thorn ESP", true), mc.font));
            y += 16;
            widgets.add(toggle(contentX, y, colW, "Thorn ESP Through Walls", cfg::getThroughWallsRaw, cfg::setThroughWalls));
            y += 28;
        }

        // ---- Stun spots ----
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Stun Spots", false), mc.font));
        y += 16;
        widgets.add(toggle(contentX, y, colW, "Stun Spot Waypoints", cfg::getStunSpotsRaw, cfg::setStunSpots));
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Stun Spot Color", cfg.getStunSpotColor()), btn ->
                mc.setScreen(new ColorPickerScreen(mc.screen, "Stun Spot Color", cfg.getStunSpotColor(),
                        ThornConfig.DEFAULT_STUN_SPOT_COLOR, argb -> {
                    cfg.setStunSpotColor(argb);
                    cfg.save();
                }))).bounds(col2X, y, colW, 18).build());
        y += 22;
        widgets.add(toggle(contentX, y, colW, "Stun Spot Labels", cfg::isStunSpotLabels, cfg::setStunSpotLabels));
        y += 22;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Add Stun Spot Here"), btn -> {
                    if (mc.player == null) {
                        return;
                    }
                    var pos = mc.player.blockPosition();
                    String floor = ThornFeature.thornFloor();
                    int n = cfg.getStunSpots().size() + 1;
                    cfg.addStunSpot(new ThornConfig.StunSpot(pos.getX(), pos.getY(), pos.getZ(), "Stun " + n,
                            floor == null ? "ANY" : floor));
                    cfg.save();
                    ModChat.send("Thorn", ModChat.text("Added stun spot " + n + " at "),
                            ModChat.value(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                            ModChat.dim(" (" + (floor == null ? "ANY" : floor) + ")"));
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Remove Last Stun Spot: " + cfg.getStunSpots().size() + " saved"), btn -> {
                    if (cfg.removeLastStunSpot()) {
                        cfg.save();
                    }
                    requestRebuild.run();
                }).bounds(col2X, y, colW, 18).build());
        return widgets;
    }

    private static AbstractWidget toggle(int x, int y, int width, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        return SettingsButtonWidget.builder(onOff(label, getter.getAsBoolean()), btn -> {
                    setter.accept(!getter.getAsBoolean());
                    ThornConfig.getInstance().save();
                    btn.setMessage(onOff(label, getter.getAsBoolean()));
                }).bounds(x, y, width, 18).build();
    }

    private static void targetRow(List<AbstractWidget> widgets, int x, int col2X, int y, int width, String name,
                                  BooleanSupplier getter, Consumer<Boolean> setter, String colorName,
                                  IntSupplier colorGetter, IntConsumer colorSetter, int defaultColor) {
        widgets.add(toggle(x, y, width, name, getter, setter));
        widgets.add(SettingsButtonWidget.builder(ColorSwatch.label(colorName + " Color", colorGetter.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, colorName + " Color",
                            colorGetter.getAsInt(), defaultColor, argb -> {
                        colorSetter.accept(argb);
                        ThornConfig.getInstance().save();
                    }));
                }).bounds(col2X, y, width, 18).build());
    }

    private static Component lineWidthText(ThornConfig cfg) {
        return Component.literal(String.format(Locale.US, "Thorn ESP Line Width: %.1f", cfg.getLineWidth()));
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

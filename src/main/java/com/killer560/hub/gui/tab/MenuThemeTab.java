package com.killer560.hub.gui.tab;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.MenuAnim;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Menu Theme section (killer560, 2026-10-12): the colour preset the mod menu draws in - Amber (the default),
 * Dark, Light or Custom - and Custom's five colours, each opened in the usual colour picker. Changing a colour while a
 * preset is in use starts Custom from that preset; Reset puts Custom back to the preset it started from. Saved in
 * {@code killer560smod-menu.json} with Favourites ({@link MenuConfig}).
 * <p>Saved Themes (killer560, 2026-10-13): Save as New Theme keeps the colours on screen as a named theme; each saved
 * theme has Use, a name box to rename it, and Delete (a second click confirms). They are kept in the same file.
 */
public class MenuThemeTab extends BaseTab {

    /** The saved theme whose Delete was clicked once (a second click within {@link #ARM_MS} deletes it), or -1. */
    private int armedDelete = -1;
    private long armedAt;
    private static final long ARM_MS = 4000;

    public MenuThemeTab() {
        super("Menu Theme");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        MenuConfig cfg = MenuConfig.getInstance();
        int y = contentY;
        widgets.add(SettingsButtonWidget.cycling(themeText(), (btn, dir) -> {
                    step(dir);
                    btn.setMessage(themeText());
                    requestRebuild.run(); // the colour rows show the new theme's colours
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;
        // killer560, 2026-10-15: "for the mod menu can you make animations for it smooth" (gui/MenuAnim).
        widgets.add(SettingsButtonWidget.cycling(animationsText(), (btn, dir) -> {
                    MenuConfig c = MenuConfig.getInstance(); // a profile load replaces the instance this tab was built with
                    c.setAnimations(SettingsButtonWidget.step(c.getAnimations(), dir));
                    c.save();
                    btn.setMessage(animationsText());
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 22;
        // killer560, 2026-10-15: "add a slider for the speed of animations ... fast, then make an extra fast, a normal
        // and a slow". Four stops, Slow on the left; the label names the stop.
        MenuAnim.Speed[] speeds = MenuAnim.Speed.values();
        widgets.add(new com.killer560.hub.gui.ThemedSliderButton(contentX, y, contentWidth, 20, speedText(),
                cfg.getAnimationSpeed().ordinal() / (double) (speeds.length - 1)) {
            @Override
            protected void updateMessage() {
                setMessage(speedText());
            }

            @Override
            protected void applyValue() {
                MenuConfig c = MenuConfig.getInstance(); // a profile load replaces the instance this tab was built with
                c.setAnimationSpeed(speeds[(int) Math.round(this.value * (speeds.length - 1))]);
                c.save();
            }
        }.steps(speeds.length - 1));
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Custom Colors", false),
                Minecraft.getInstance().font));
        y += 16;
        int[] colours = cfg.activeColours();
        for (MenuTheme.Slot slot : MenuTheme.Slot.values()) {
            int argb = colours[slot.ordinal()];
            int preset = cfg.getTheme() == MenuTheme.CUSTOM ? cfg.getCustomBase().base()[slot.ordinal()] : argb;
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label(slot.label, argb), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), slot.label, argb, preset,
                                picked -> setColour(slot, picked)));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 22;
        }
        widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Custom to "
                        + cfg.getCustomBase().label), btn -> {
                    resetCustom();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 26;

        // ---- Saved Themes ----
        var font = Minecraft.getInstance().font;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Saved Themes", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Save as New Theme"), btn -> {
                    MenuConfig c = MenuConfig.getInstance(); // a profile load replaces the instance this tab was built with
                    c.saveCurrentAsTheme();
                    c.save();
                    armedDelete = -1;
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 18).build());
        y += 22;
        List<MenuConfig.SavedTheme> saved = cfg.getSavedThemes();
        if (saved.isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7None yet - Save as New Theme keeps the colors you see now."), font));
            y += 16;
        }
        int useW = 54;
        int deleteW = 58;
        int gap = 4;
        int nameW = Math.max(40, contentWidth - useW - deleteW - 2 * gap);
        int active = cfg.getActiveSaved();
        for (int i = 0; i < saved.size(); i++) {
            int index = i;
            MenuConfig.SavedTheme t = saved.get(i);
            boolean using = i == active;
            widgets.add(SettingsButtonWidget.builder(Component.literal(using ? "§aIn Use" : "Use"), btn -> {
                        MenuConfig c = MenuConfig.getInstance();
                        c.useSavedTheme(index);
                        c.save();
                        armedDelete = -1;
                        requestRebuild.run();
                    }).bounds(contentX, y, useW, 18).build());
            EditBox name = new EditBox(font, contentX + useW + gap, y, nameW, 18, Component.literal("Theme name"));
            name.setMaxLength(MenuConfig.MAX_THEME_NAME);
            name.setTextShadow(com.killer560.hub.gui.MenuStyle.TEXT_SHADOW);
            name.setValue(t.name);
            name.setResponder(text -> {
                MenuConfig c = MenuConfig.getInstance();
                c.renameSavedTheme(index, text);
                c.save();
            });
            widgets.add(name);
            boolean armed = armedDelete == i && System.currentTimeMillis() - armedAt <= ARM_MS;
            widgets.add(SettingsButtonWidget.builder(Component.literal(armed ? "Confirm" : "Delete"), btn -> {
                        if (armedDelete != index || System.currentTimeMillis() - armedAt > ARM_MS) {
                            armedDelete = index;
                            armedAt = System.currentTimeMillis();
                            requestRebuild.run();
                            return;
                        }
                        armedDelete = -1;
                        MenuConfig c = MenuConfig.getInstance();
                        c.deleteSavedTheme(index);
                        c.save();
                        requestRebuild.run();
                    }).bounds(contentX + useW + gap + nameW + gap, y, deleteW, 18).danger(armed).build());
            y += 22;
        }
        // ---- Font and Main Menu (killer560, 2026-10-15) ----
        y += 6;
        y = com.killer560.hub.fonts.FontSettings.build(widgets, contentX, y, contentWidth, requestRebuild);
        y += 6;
        com.killer560.hub.mainmenu.MainMenuSettings.build(widgets, contentX, y, contentWidth, requestRebuild);
        return widgets;
    }

    private static Component speedText() {
        return Component.literal("Animation Speed: " + MenuConfig.getInstance().getAnimationSpeed().label);
    }

    private static Component animationsText() {
        return Component.literal("Animations: §6" + MenuConfig.getInstance().getAnimations().label);
    }

    private static Component themeText() {
        MenuConfig cfg = MenuConfig.getInstance();
        int active = cfg.getActiveSaved();
        String shown = active >= 0 ? cfg.getTheme().label + " (" + MenuConfig.shownName(cfg.getSavedThemes().get(active)) + ")"
                : cfg.getTheme().label;
        return Component.literal("Menu Theme: §6" + shown);
    }

    /** The next (dir +1) or previous (-1) theme, Amber - Dark - Light - Custom, wrapping; saved. */
    public static void step(int dir) {
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.setTheme(SettingsButtonWidget.step(cfg.getTheme(), dir));
        cfg.save();
    }

    /** Sets one Custom colour (the colour picker's answer) and saves; the menu switches to Custom. */
    public static void setColour(MenuTheme.Slot slot, int argb) {
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.setCustomColour(slot, argb);
        cfg.save();
    }

    /** Custom's colours back to the preset it started from, saved. */
    public static void resetCustom() {
        MenuConfig cfg = MenuConfig.getInstance();
        cfg.resetCustom();
        cfg.save();
    }

    @Override
    public String cardDescription() {
        return "Amber, Dark, Light, Ocean or Custom.";
    }
}

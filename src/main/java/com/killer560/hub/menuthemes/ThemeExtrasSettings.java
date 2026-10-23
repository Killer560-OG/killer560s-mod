package com.killer560.hub.menuthemes;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.gui.MenuConfig;
import com.killer560.hub.gui.ModScreen;
import com.killer560.hub.gui.MenuTheme;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.gui.tab.MenuThemeTab;
import com.killer560.hub.mainmenu.MainMenuLook;
import com.killer560.hub.util.ExternalOpen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The Menu Theme tab's Theme Extras section (killer560, 2026-10-21): what each theme brings besides its colours. "Extras
 * For" picks the theme being set up (it starts on the menu's own theme); every row below it acts on that theme. Built by
 * {@code MenuThemeTab}; every row reads {@link ThemeExtrasConfig#getInstance()} at press time, so a profile load that
 * replaced the config object is honoured.
 */
public final class ThemeExtrasSettings {

    /** The theme the rows edit, or null for the menu's own theme. Per session, not saved. */
    private static MenuTheme editing;

    private ThemeExtrasSettings() {
    }

    /** The theme the section is editing now. */
    public static MenuTheme editing() {
        return editing != null ? editing : MenuConfig.getInstance().getTheme();
    }

    public static void setEditing(MenuTheme theme) {
        editing = theme;
    }

    /** Adds the section at {@code y}; returns the y under it. */
    public static int build(List<AbstractWidget> widgets, int x, int y, int w, Runnable requestRebuild) {
        var font = Minecraft.getInstance().font;
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Theme Extras", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(editingText(), (btn, dir) -> {
                    editing = SettingsButtonWidget.step(editing(), dir);
                    btn.setMessage(editingText());
                    requestRebuild.run(); // every row below names the new theme's choices
                }).bounds(x, y, w, 20).build());
        y += 24;

        // ---- Theme Music ----
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Theme Music", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(musicOnText(), btn -> {
                    ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
                    c.setMusicOn(!c.isMusicOn());
                    c.save();
                    btn.setMessage(musicOnText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        // Play Preview (killer560, 2026-10-22), under the Music row: the chosen track once, at Music Volume; a second
        // press, the track ending, a new Music choice or the mod menu closing stops it.
        SettingsButtonWidget[] preview = new SettingsButtonWidget[1];
        widgets.add(SettingsButtonWidget.cycling(musicText(), (btn, dir) -> {
                    stepMusic(dir);
                    btn.setMessage(musicText());
                    refreshPreview(preview[0]);
                }).bounds(x, y, w, 20).build());
        y += 24;
        preview[0] = SettingsButtonWidget.builder(previewText(), btn -> {
                    ThemeMusic.togglePreview(editing());
                    refreshPreview(btn);
                }).bounds(x, y, w, 20).build();
        refreshPreview(preview[0]);
        ThemeMusic.onPreviewEnded(() -> refreshPreview(preview[0]));
        widgets.add(preview[0]);
        y += 24;
        int stops = (ThemeExtrasConfig.MAX_VOLUME - ThemeExtrasConfig.MIN_VOLUME) / ThemeExtrasConfig.VOLUME_STEP;
        widgets.add(new ThemedSliderButton(x, y, w, 20, volumeText(),
                ThemeExtrasConfig.getInstance().getMusicVolume() / (double) ThemeExtrasConfig.MAX_VOLUME) {
            @Override
            protected void updateMessage() {
                setMessage(volumeText());
            }

            @Override
            protected void applyValue() {
                ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
                c.setMusicVolume((int) Math.round(this.value * ThemeExtrasConfig.MAX_VOLUME));
                c.save();
            }
        }.steps(stops));
        y += 24;
        int half = (w - 4) / 2;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Import Music"), btn -> {
                    MenuTheme theme = editing();
                    ThemeMusicFiles.importClipboardAsync(Minecraft.getInstance().keyboardHandler.getClipboard(),
                            id -> Minecraft.getInstance().execute(() -> {
                                if (id != null) {
                                    ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
                                    c.setMusic(theme, id);
                                    c.save();
                                    ThemeMusic.forgetResolved();
                                }
                                rebuildIfOpen(requestRebuild);
                            }));
                    requestRebuild.run(); // shows "Importing..."
                }).bounds(x, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Music Folder"), btn -> {
                    try {
                        ExternalOpen.path(ThemeMusicFiles.folder());
                    } catch (Exception ignored) {
                    }
                }).bounds(x + w - half, y, half, 18).build());
        y += 22;
        String musicStatus = ThemeMusicFiles.lastImport();
        widgets.add(new StringWidget(x, y, w, 12, Component.literal(musicStatus != null ? musicStatus
                : "§7Plays on the main menu, which shows " + MainMenuLook.current().theme().label + " now."), font));
        y += 16;

        // ---- Theme Font ----
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Theme Font", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(fontText(), (btn, dir) -> {
                    ThemeFonts.step(editing(), dir);
                    btn.setMessage(fontText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(new StringWidget(x, y, w, 12, Component.literal("§7Used while the mod menu's theme is "
                + editing().label + "; Font Applies To and Font Size stay as set above."), font));
        y += 16;

        // ---- Theme Background ----
        widgets.add(new StringWidget(x, y, w, 12, SectionHeaders.header("Theme Background", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(backgroundText(), (btn, dir) -> {
                    stepBackground(dir);
                    btn.setMessage(backgroundText());
                }).bounds(x, y, w, 20).build());
        y += 24;
        widgets.add(SettingsButtonWidget.builder(Component.literal("Import Background"), btn -> {
                    MenuTheme theme = editing();
                    ThemeBackgrounds.importClipboardAsync(Minecraft.getInstance().keyboardHandler.getClipboard(),
                            id -> Minecraft.getInstance().execute(() -> {
                                if (id != null) {
                                    ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
                                    c.setBackground(theme, id);
                                    c.save();
                                }
                                rebuildIfOpen(requestRebuild);
                            }));
                    requestRebuild.run(); // shows "Importing..."
                }).bounds(x, y, half, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Backgrounds Folder"), btn -> {
                    try {
                        ExternalOpen.path(ThemeBackgrounds.folder());
                    } catch (Exception ignored) {
                    }
                }).bounds(x + w - half, y, half, 18).build());
        y += 22;
        String bgStatus = ThemeBackgrounds.lastImport();
        widgets.add(new StringWidget(x, y, w, 12, Component.literal(bgStatus != null ? bgStatus
                : "§7Copy a video or GIF's path (Shift + right click, Copy as path), then Import Background."), font));
        y += 16;
        return y;
    }

    /** Rebuilds the Menu Theme tab if it is still the open page (an import finished after he moved on: nothing to do). */
    static void rebuildIfOpen(Runnable requestRebuild) {
        Minecraft mc = Minecraft.getInstance();
        if (McCompat.screen(mc) instanceof ModScreen menu && menu.openFeature() instanceof MenuThemeTab) {
            requestRebuild.run();
        }
    }

    /** The theme's next (+1) or previous (-1) background, saved. */
    public static void stepBackground(int dir) {
        ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
        MenuTheme t = editing();
        c.setBackground(t, ThemeBackgrounds.step(c.getBackground(t), dir));
        c.save();
    }

    static Component backgroundText() {
        return Component.literal("Theme Background: " + ThemeBackgrounds.label(ThemeExtrasConfig.getInstance().getBackground(editing())));
    }

    static Component fontText() {
        return Component.literal("Theme Font: " + ThemeFonts.label(ThemeExtrasConfig.getInstance().getFont(editing())));
    }

    /** The theme's next (+1) or previous (-1) music choice, saved. */
    public static void stepMusic(int dir) {
        ThemeExtrasConfig c = ThemeExtrasConfig.getInstance();
        MenuTheme t = editing();
        c.setMusic(t, ThemeMusic.step(c.getMusic(t), dir));
        c.save();
        ThemeMusic.stopPreview(); // a preview of the old choice ends with it
    }

    /** What Play Preview would play for the theme being edited, or null (Off, or an imported file that is gone). */
    static ThemeMusic.Track previewTrack() {
        MenuTheme t = editing();
        return ThemeMusic.resolve(t, ThemeExtrasConfig.getInstance().getMusic(t));
    }

    static Component previewText() {
        if (ThemeMusic.previewing()) {
            return Component.literal("Stop Preview");
        }
        if (previewTrack() == null) {
            return Component.literal("Play Preview: §7no track");
        }
        MenuTheme t = editing();
        String choice = ThemeExtrasConfig.getInstance().getMusic(t);
        ThemeMusic.Bundled own = ThemeExtrasConfig.MUSIC_THEME.equals(choice) ? ThemeMusic.themeTrack(t) : null;
        return Component.literal("Play Preview: " + (own != null ? own.label() : ThemeMusic.label(t, choice)));
    }

    /** The Play Preview button's label and whether it can be pressed (not when the choice plays nothing). */
    static void refreshPreview(net.minecraft.client.gui.components.AbstractWidget btn) {
        if (btn != null) {
            btn.setMessage(previewText());
            btn.active = ThemeMusic.previewing() || previewTrack() != null;
        }
    }

    static Component editingText() {
        return Component.literal("Extras For: §6" + editing().label);
    }

    static Component musicOnText() {
        return Component.literal("Theme Music: " + (ThemeExtrasConfig.getInstance().isMusicOn() ? "§aON" : "§cOFF"));
    }

    static Component musicText() {
        MenuTheme t = editing();
        return Component.literal("Music: " + ThemeMusic.label(t, ThemeExtrasConfig.getInstance().getMusic(t)));
    }

    static Component volumeText() {
        return Component.literal("Music Volume: " + ThemeExtrasConfig.getInstance().getMusicVolume() + "%");
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.livemap.MapMarks;
import com.killer560.hub.livemap.MapSchemeEditorScreen;
import com.killer560.hub.livemap.MapThemes;
import com.killer560.hub.livemap.predict.PredictiveRoomsConfig;
import com.killer560.hub.mapping.MappingConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import com.killer560.hub.compat.McCompat;

/**
 * Dungeon Map - the HUD map itself, how both maps look, and its themes.
 * <p>
 * killer560, 2026-09-17: "make the section related to dungeon map called Dungeon Map ... However Interactive Map should
 * be in its own tab" ({@link InteractiveMapTab}). killer560, 2026-10-15 rebuilt this tab:
 * <ul>
 *   <li>no Room Size slider ("they scroll to adjust it" - the HUD editor's scroll sizes the map);</li>
 *   <li>Player Heads only under Show Teammates when that is on; Recolor by Class also rings a teammate's head in his
 *       class color; a Player Marker Size slider;</li>
 *   <li>Custom Checkmark Sprite (off = Hypixel's own marks, on = the mod's or his own PNGs);</li>
 *   <li>Map Theme: Real Map, each menu preset, Match Menu Theme or Custom; room colors only exist under Custom, in
 *       {@link MapSchemeEditorScreen} with a sample map, and New Custom Scheme makes another saved scheme;</li>
 *   <li>Funny Map (the scan) with Show Mimic Room and its color, cheat build only, at the bottom.</li>
 * </ul>
 * A row that has no partner is drawn the full width, so its switch sits on the right edge with every other row's.
 */
public class LiveMapTab extends BaseTab implements KeyCaptureTab {

    private enum KeyTarget { PEEK }

    private KeyTarget capturing = null;

    public LiveMapTab() {
        super("Dungeon Map");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        MappingConfig mapping = MappingConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colB = contentX + colW + gap;
        int full = contentWidth;
        int y = contentY;

        widgets.add(onOffButton("Dungeon Map", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, contentWidth, 20));
        y += 24;
        if (cfg.isEnabledRaw()) {
            // Predictive Rooms lives here, under the map's switch like every other map setting, not in a card of its own
            // (killer560, 2026-10-18: "predictive rooms should be inside of the map not its own toggle"). Same config and
            // key as before; the Special Room Guess split rides with it.
            PredictiveRoomsConfig pcfg = PredictiveRoomsConfig.getInstance();
            widgets.add(onOffButton("Predictive Rooms", pcfg::isEnabledRaw, pcfg::setEnabled, pcfg::save,
                    null, contentX, y, contentWidth, 18));
            y += 20;
            widgets.add(onOffButton("Special Room Guess", pcfg::isSpecialGuessRaw, pcfg::setSpecialGuess, pcfg::save,
                    null, contentX, y, contentWidth, 18));
            y += 24;
            widgets.add(cycle("Room Labels", LiveMapConfig.ROOM_LABEL_NAMES, cfg::getRoomLabels, cfg::setRoomLabels,
                    cfg, contentX, y, full));
            y += 20;
            widgets.add(onOffButton("Show Teammates", cfg::isShowTeammates, cfg::setShowTeammates, cfg::save,
                    requestRebuild, contentX, y, full, 18));
            y += 20;
            if (cfg.isShowTeammates()) {
                // killer560, 2026-10-15: "For show teammates if that is on then have it show the player heads option
                // as well below it."
                widgets.add(toggle("Player Heads", cfg::isPlayerHeads, cfg::setPlayerHeads, cfg, contentX, y, full));
                y += 20;
            }
            widgets.add(toggle("Recolor by Class", cfg::isClassRecolorTeammates, cfg::setClassRecolorTeammates, cfg,
                    contentX, y, colW));
            widgets.add(toggle("Names Holding Leap", cfg::isLeapNames, cfg::setLeapNames, cfg, colB, y, colW));
            y += 20;
            widgets.add(slider("Player Marker Size", cfg::getIconScale, v -> cfg.setIconScale((float) v), 0.5, 3, "x",
                    cfg, contentX, y, colW));
            widgets.add(toggle("Mark Reported Rooms", cfg::isMarkReportedRooms, cfg::setMarkReportedRooms, cfg, colB, y,
                    colW));
            y += 20;
            widgets.add(keyButton("Peek Key", KeyTarget.PEEK, cfg.getPeekKeyCode(), contentX, y, colW));
            widgets.add(slider("Peek Scale", cfg::getPeekScale, v -> cfg.setPeekScale((float) v), 1.25, 4, "x", cfg, colB, y, colW));
            y += 24;
        }

        // ---------------------------------------------------------------- how both maps look
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Map Appearance", false));
        y += 16;
        widgets.add(toggle("Color Rooms by Type", cfg::isColourByType, cfg::setColourByType, cfg, contentX, y, colW));
        widgets.add(slider("Darken Unexplored", cfg::getDarkenUnopened, v -> cfg.setDarkenUnopened((float) v), 0, 0.9, "",
                cfg, colB, y, colW));
        y += 20;
        widgets.add(slider("Font Scale", cfg::getFontScale, v -> cfg.setFontScale((float) v), 0.5, 3, "x", cfg, contentX, y, colW));
        widgets.add(toggle("Text Shadow", cfg::isTextShadow, cfg::setTextShadow, cfg, colB, y, colW));
        y += 20;
        widgets.add(onOffButton("Custom Checkmark Sprite", cfg::isCustomCheckmarks, cfg::setCustomCheckmarks, cfg::save,
                requestRebuild, contentX, y, full, 18));
        y += 20;
        if (cfg.isCustomCheckmarks()) {
            widgets.add(SettingsButtonWidget.cycling(sourceText(cfg), (btn, dir) -> {
                        cfg.setCheckmarkSource(SettingsButtonWidget.step(cfg.getCheckmarkSource(), dir,
                                LiveMapConfig.CHECKMARK_SOURCE_NAMES.length));
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, full, 18).build());
            y += 20;
            if (cfg.getCheckmarkSource() == 1) {
                widgets.add(SettingsButtonWidget.builder(Component.literal("Open Sprite Folder"), btn -> {
                            MapMarks.reloadUserSprites();
                            MapMarks.openSpriteFolder();
                        }).bounds(contentX, y, full, 18).build());
                y += 20;
            }
        }
        y += 4;

        // ---------------------------------------------------------------- theme
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Map Theme", false));
        y += 16;
        widgets.add(SettingsButtonWidget.cycling(themeText(cfg), (btn, dir) -> {
                    cfg.setMapTheme(SettingsButtonWidget.step(cfg.getMapTheme(), dir));
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, full, 18).build());
        y += 20;
        if (cfg.getMapTheme() == MapThemes.Theme.CUSTOM && cfg.getActiveScheme() >= 0) {
            // Room colors exist only here: "Make it so you can only adjust room colors on custom otherwise it doesnt
            // show room colors and make custom its own menu where you are shown a dummy map to edit it."
            widgets.add(SettingsButtonWidget.cycling(schemeText(cfg), (btn, dir) -> {
                        cfg.useScheme(SettingsButtonWidget.step(cfg.getActiveScheme(), dir, cfg.getSchemes().size()));
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, full, 18).build());
            y += 20;
            widgets.add(SettingsButtonWidget.builder(Component.literal("Edit Scheme"), btn -> openEditor(cfg.getActiveScheme()))
                    .bounds(contentX, y, colW, 18).build());
            widgets.add(SettingsButtonWidget.builder(Component.literal("New Custom Scheme"), btn -> {
                        LiveMapConfig c = LiveMapConfig.getInstance();
                        int made = c.newScheme();
                        c.save();
                        openEditor(made);
                    }).bounds(colB, y, colW, 18).build());
        } else {
            widgets.add(SettingsButtonWidget.builder(Component.literal("New Custom Scheme"), btn -> {
                        LiveMapConfig c = LiveMapConfig.getInstance();
                        int made = c.newScheme();
                        c.save();
                        openEditor(made);
                    }).bounds(contentX, y, full, 18).build());
        }
        y += 20;
        widgets.add(colour("Map Background", cfg::getMapBackground, cfg::setMapBackground,
                LiveMapConfig.DEFAULT_MAP_BACKGROUND, cfg, contentX, y, colW));
        widgets.add(colour("Border Color", cfg::getMapBorderColor, cfg::setMapBorderColor, LiveMapConfig.DEFAULT_MAP_BORDER,
                cfg, colB, y, colW));
        y += 24;

        // ---------------------------------------------------------------- extras
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Map Extras", false));
        y += 16;
        widgets.add(mappingToggle("Extra Info Overlay", mapping::isExtraInfoEnabled, mapping::setExtraInfoEnabled, mapping,
                contentX, y, full));
        y += 24;

        // ---------------------------------------------------------------- cheat build: Funny Map, at the bottom
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Cheat Build - Funny Map", true));
            y += 16;
            widgets.add(onOffButton("Funny Map", cfg::isFunnyMapRaw, cfg::setFunnyMap, cfg::save,
                    requestRebuild, contentX, y, full, 18));
            y += 20;
            if (cfg.isFunnyMapRaw()) {
                widgets.add(toggle("Show Mimic Room", cfg::isShowMimicRoomRaw, cfg::setShowMimicRoom, cfg, contentX, y, colW));
                widgets.add(colour("Mimic Room Color", cfg::getMimicColorRaw, cfg::setMimicColor, 0x33FF0000, cfg, colB, y,
                        colW));
                y += 20;
            }
        }
        return widgets;
    }

    private static Component themeText(LiveMapConfig cfg) {
        return Component.literal("Map Theme: " + cfg.getMapTheme().label);
    }

    private static Component schemeText(LiveMapConfig cfg) {
        MapThemes.Scheme s = cfg.activeSchemeOrNull();
        return Component.literal("Scheme: " + (s == null ? "-" : s.name));
    }

    private static Component sourceText(LiveMapConfig cfg) {
        return Component.literal("Sprite: " + LiveMapConfig.CHECKMARK_SOURCE_NAMES[cfg.getCheckmarkSource()]);
    }

    private static void openEditor(int scheme) {
        Minecraft client = Minecraft.getInstance();
        McCompat.setScreen(client, new MapSchemeEditorScreen(McCompat.screen(client), scheme));
    }

    // ------------------------------------------------------------------------------------------- helpers

    interface BoolGetter {
        boolean get();
    }

    interface BoolSetter {
        void set(boolean v);
    }

    static AbstractWidget toggle(String label, BoolGetter get, BoolSetter set, LiveMapConfig cfg, int x, int y, int w) {
        return onOffButton(label, get::get, set::set, cfg::save, null, x, y, w, 18);
    }

    /** Same shape as {@link #toggle}, for {@link MappingConfig}, which persists itself. */
    static AbstractWidget mappingToggle(String label, BoolGetter get, BoolSetter set, MappingConfig cfg, int x, int y, int w) {
        return onOffButton(label, get::get, set::set, cfg::save, null, x, y, w, 18);
    }

    /** A setting that steps through {@code names}: left click forward, right click back, wrapping. */
    static AbstractWidget cycle(String label, String[] names, java.util.function.IntSupplier get, IntConsumer set,
                                LiveMapConfig cfg, int x, int y, int w) {
        Supplier<Component> text = () -> Component.literal(label + ": " + names[get.getAsInt()]);
        return SettingsButtonWidget.cycling(text.get(), (btn, dir) -> {
                    set.accept(SettingsButtonWidget.step(get.getAsInt(), dir, names.length));
                    cfg.save();
                    btn.setMessage(text.get());
                }).bounds(x, y, w, 18).build();
    }

    static AbstractWidget slider(String label, DoubleSupplier get, DoubleConsumer set, double min, double max, String unit,
                                 LiveMapConfig cfg, int x, int y, int w) {
        Supplier<Component> text = () -> {
            double v = get.getAsDouble();
            String num = v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
            return Component.literal(label + ": " + num + unit);
        };
        return new ThemedSliderButton(x, y, w, 18, text.get(), (get.getAsDouble() - min) / (max - min)) {
            @Override
            protected void updateMessage() {
                setMessage(text.get());
            }

            @Override
            protected void applyValue() {
                set.accept(min + this.value * (max - min));
                saveOnRelease(() -> cfg.save());
            }
        };
    }

    static AbstractWidget colour(String label, java.util.function.IntSupplier get, IntConsumer set, int def,
                                 LiveMapConfig cfg, int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, get.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, get.getAsInt(), def, argb -> {
                        set.accept(argb);
                        cfg.save();
                    }));
                }).bounds(x, y, w, 18).build();
    }

    private AbstractWidget keyButton(String label, KeyTarget target, int code, int x, int y, int w) {
        Component text = capturing == target ? Component.literal(label + ": Press any key...") : keyText(label, code);
        return SettingsButtonWidget.builder(text, btn -> {
                    capturing = target;
                    btn.setMessage(Component.literal(label + ": Press any key..."));
                }).bounds(x, y, w, 18).build();
    }

    static Component keyText(String label, int code) {
        String name = code < 0 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal(label + ": §b" + name);
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int code = keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode;
        if (capturing == KeyTarget.PEEK) {
            cfg.setPeekKeyCode(code);
        }
        capturing = null;
        cfg.save();
    }
}

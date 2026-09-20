package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.livemap.LiveMapConfig;
import com.killer560.hub.mapping.MappingConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Dungeon Map - the HUD map itself, the room/door palette both maps paint with, and (2026-09-20) everything moved
 * here from the now-deleted Mapping tab.
 * <p>
 * killer560, 2026-09-17: "make the section related to dungeon map called Dungeon Map. That should be the only setting
 * related to the dungeon map at all. However Interactive Map should be in its own tab" - the Interactive Map half of
 * the old Live Map tab (and the cheat automation that only runs from that screen) now lives in
 * {@link InteractiveMapTab}. Everything here is shared by both renderers, so the palette has one home.
 * <p>
 * killer560, 2026-09-20 (screenshot 14.52.05 feedback): "delete the mapping tab entirely. move funny map, extra
 * info, mimic room and the player-head option under dungeon map" - those 4 placeholders (see
 * {@link MappingConfig}'s class doc for why they don't draw anything yet) now live in the "Map Extras" section
 * below. The Mapping tab's top two features - the "Diagnostic logging" toggle and the "Dump Held Map Now" button,
 * both data-gathering tools for reverse-engineering those placeholders - were dropped rather than moved; see the
 * staging notes for why. Same pass also added switchable map themes and moved recolouring into its own subheader,
 * and removed the current-room colour changer, Room Name Below Map, and the player-head marker option entirely.
 */
public class LiveMapTab extends BaseTab implements KeyCaptureTab {

    private enum KeyTarget { PEEK }

    private KeyTarget capturing = null;
    /** Not persisted - just whether the colour list is unfolded in the GUI right now. */
    private boolean showColours = false;

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
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Dungeon Map", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isEnabledRaw()) {
            // killer560, 2026-09-20: "the room-labels option should sit nearer the top of the tab" - it's now the
            // very first setting under the on/off switch instead of sharing a row with Room Size.
            widgets.add(cycle("Room Labels", LiveMapConfig.ROOM_LABEL_NAMES, cfg::getRoomLabels, cfg::setRoomLabels,
                    cfg, contentX, y, colW));
            widgets.add(slider("Room Size", cfg::getRoomPx, v -> cfg.setRoomPx((int) Math.round(v)), 8, 40, "px",
                    cfg, colB, y, colW));
            y += 20;
            widgets.add(toggle("Show Teammates", cfg::isShowTeammates, cfg::setShowTeammates, cfg, contentX, y, colW));
            widgets.add(toggle("Recolor by Class", cfg::isClassRecolorTeammates, cfg::setClassRecolorTeammates, cfg, colB, y, colW));
            y += 20;
            widgets.add(keyButton("Peek Key", KeyTarget.PEEK, cfg.getPeekKeyCode(), contentX, y, colW));
            widgets.add(slider("Peek Scale", cfg::getPeekScale, v -> cfg.setPeekScale((float) v), 1.25, 4, "x", cfg, colB, y, colW));
            y += 24;
        }

        // ---------------------------------------------------------------- shared map appearance
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Map Appearance", false), Minecraft.getInstance().font));
        y += 16;
        widgets.add(toggle("Colour Rooms by Type", cfg::isColourByType, cfg::setColourByType, cfg, contentX, y, colW));
        widgets.add(toggle("Checkmark Sprites", cfg::isCheckmarkSprites, cfg::setCheckmarkSprites, cfg, colB, y, colW));
        y += 20;
        widgets.add(slider("Darken Unexplored", cfg::getDarkenUnopened, v -> cfg.setDarkenUnopened((float) v), 0, 0.9, "",
                cfg, contentX, y, colW));
        widgets.add(slider("Font Scale", cfg::getFontScale, v -> cfg.setFontScale((float) v), 0.5, 3, "x", cfg, colB, y, colW));
        y += 20;
        widgets.add(slider("Icon Scale", cfg::getIconScale, v -> cfg.setIconScale((float) v), 0.5, 3, "x", cfg, contentX, y, colW));
        widgets.add(toggle("Text Shadow", cfg::isTextShadow, cfg::setTextShadow, cfg, colB, y, colW));
        y += 24;

        // ---------------------------------------------------------------- recolouring (killer560, 2026-09-20:
        // "map themes: switchable, including a custom one matching the mod's amber look. the recolour feature
        // should be able to save a theme" + "move recolouring into its own subheader under dungeon map")
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Recolouring", false), Minecraft.getInstance().font));
        y += 16;
        Supplier<Component> themeText = () -> Component.literal("Map Theme: " + LiveMapConfig.THEME_NAMES[cfg.getMapTheme()]);
        widgets.add(SettingsButtonWidget.builder(themeText.get(), btn -> {
                    cfg.applyTheme((cfg.getMapTheme() + 1) % LiveMapConfig.THEME_NAMES.length);
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        widgets.add(SettingsButtonWidget.builder(Component.literal("Save Theme"), btn -> {
                    cfg.saveCurrentAsCustomTheme();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(colB, y, colW, 18).build());
        y += 20;
        widgets.add(colour("Map Background", cfg::getMapBackground, cfg::setMapBackground, 0x99000000, cfg, contentX, y, colW));
        widgets.add(colour("Border Colour", cfg::getMapBorderColor, cfg::setMapBorderColor, 0xFFCC6600, cfg, colB, y, colW));
        y += 20;
        widgets.add(SettingsButtonWidget.builder(Component.literal((showColours ? "▼ " : "▶ ") + "Room Colours"), btn -> {
                    showColours = !showColours;
                    requestRebuild.run();
                }).bounds(contentX, y, colW, 18).build());
        y += 20;
        if (showColours) {
            // Editing any swatch by hand means the palette is no longer exactly a stock preset (themedColour marks
            // the theme Custom); Reset/the theme cycle's Real Map option both put the real map's own colours back.
            widgets.add(themedColour("Normal", cfg::getColorNormal, cfg::setColorNormal, 0xFF724318, cfg, contentX, y, colW));
            widgets.add(themedColour("Entrance", cfg::getColorEntrance, cfg::setColorEntrance, 0xFF00FF00, cfg, colB, y, colW));
            y += 20;
            widgets.add(themedColour("Puzzle", cfg::getColorPuzzle, cfg::setColorPuzzle, 0xFFB24CD8, cfg, contentX, y, colW));
            widgets.add(themedColour("Trap", cfg::getColorTrap, cfg::setColorTrap, 0xFFD87F33, cfg, colB, y, colW));
            y += 20;
            widgets.add(themedColour("Miniboss", cfg::getColorMiniboss, cfg::setColorMiniboss, 0xFFE5E533, cfg, contentX, y, colW));
            widgets.add(themedColour("Rare", cfg::getColorRare, cfg::setColorRare, 0xFFB2B2B2, cfg, colB, y, colW));
            y += 20;
            widgets.add(themedColour("Blood", cfg::getColorBlood, cfg::setColorBlood, 0xFFFF0000, cfg, contentX, y, colW));
            widgets.add(themedColour("Fairy", cfg::getColorFairy, cfg::setColorFairy, 0xFFF27FA5, cfg, colB, y, colW));
            y += 20;
            widgets.add(themedColour("Unexplored", cfg::getColorUnopened, cfg::setColorUnopened, 0xFF414141, cfg, contentX, y, colW));
            widgets.add(themedColour("Wither Door", cfg::getColorWitherDoor, cfg::setColorWitherDoor, 0xFF101010, cfg, colB, y, colW));
            y += 20;
            widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Room Colours"), btn -> {
                        cfg.applyTheme(LiveMapConfig.THEME_REAL);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, colW, 18).build());
            y += 20;
        }

        // ---------------------------------------------------------------- map extras (moved from the deleted
        // Mapping tab - see this class's doc). All 4 are still placeholders; MappingConfig's class doc has the
        // full "honesty note" on why, and the tooltips below give the short version instead of an in-panel
        // paragraph (mod-wide: explanatory paragraphs move to hover text).
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Map Extras", false), Minecraft.getInstance().font));
        y += 16;
        widgets.add(mappingToggle("Funny Map", mapping::isFunnyMapEnabled, mapping::setFunnyMapEnabled, mapping, contentX, y, colW));
        widgets.add(mappingToggle("Extra Info Overlay", mapping::isExtraInfoEnabled, mapping::setExtraInfoEnabled, mapping, colB, y, colW));
        y += 20;
        widgets.add(mappingToggle("Mimic Room Show/Hide", mapping::isMimicRoomHighlightEnabled, mapping::setMimicRoomHighlightEnabled, mapping, contentX, y, colW));
        widgets.add(mappingToggle("Player-Head Class Recolor", mapping::isClassRecolorEnabled, mapping::setClassRecolorEnabled, mapping, colB, y, colW));
        y += 20;

        return widgets;
    }

    // ------------------------------------------------------------------------------------------- helpers

    interface BoolGetter {
        boolean get();
    }

    interface BoolSetter {
        void set(boolean v);
    }

    static AbstractWidget toggle(String label, BoolGetter get, BoolSetter set, LiveMapConfig cfg, int x, int y, int w) {
        return SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    set.set(!get.get());
                    cfg.save();
                    btn.setMessage(onOff(label, get.get()));
                }).bounds(x, y, w, 18).build();
    }

    /** Same shape as {@link #toggle}, for the 4 placeholders moved over from {@link MappingConfig} - that class
     *  persists itself independently of {@link LiveMapConfig}, so it needs its own save call. */
    static AbstractWidget mappingToggle(String label, BoolGetter get, BoolSetter set, MappingConfig cfg, int x, int y, int w) {
        return SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    set.set(!get.get());
                    cfg.save();
                    btn.setMessage(onOff(label, get.get()));
                }).bounds(x, y, w, 18).build();
    }

    static AbstractWidget cycle(String label, String[] names, java.util.function.IntSupplier get, IntConsumer set,
                                LiveMapConfig cfg, int x, int y, int w) {
        Supplier<Component> text = () -> Component.literal(label + ": " + names[get.getAsInt()]);
        return SettingsButtonWidget.builder(text.get(), btn -> {
                    set.accept((get.getAsInt() + 1) % names.length);
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
                cfg.save();
            }
        };
    }

    static AbstractWidget colour(String label, java.util.function.IntSupplier get, IntConsumer set, int def,
                                 LiveMapConfig cfg, int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, get.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, label, get.getAsInt(), def, argb -> {
                        set.accept(argb);
                        cfg.save();
                    }));
                }).bounds(x, y, w, 18).build();
    }

    /** A Room Colours swatch: same as {@link #colour}, but hand-editing one also flips the Recolouring "Map Theme"
     *  cycle to Custom (killer560, 2026-09-20's theme system - see {@link LiveMapConfig#markCustomTheme()}). The
     *  "default" a picker resets to is always the real map's own colour for that type, so Room Colours always has
     *  somewhere sane to fall back to regardless of which theme is active. */
    static AbstractWidget themedColour(String label, java.util.function.IntSupplier get, IntConsumer set, int def,
                                       LiveMapConfig cfg, int x, int y, int w) {
        return SettingsButtonWidget.builder(ColorSwatch.label(label, get.getAsInt()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, label, get.getAsInt(), def, argb -> {
                        set.accept(argb);
                        cfg.markCustomTheme();
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

    static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
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

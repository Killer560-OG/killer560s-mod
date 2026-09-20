package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.livemap.LiveMapConfig;
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
 * Dungeon Map - the HUD map itself plus the room/door palette both maps paint with.
 * <p>
 * killer560, 2026-09-17: "make the section related to dungeon map called Dungeon Map. That should be the only setting
 * related to the dungeon map at all. However Interactive Map should be in its own tab" - the Interactive Map half of
 * the old Live Map tab (and the cheat automation that only runs from that screen) now lives in
 * {@link InteractiveMapTab}. Everything here is shared by both renderers, so the palette has one home.
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
            widgets.add(slider("Room Size", cfg::getRoomPx, v -> cfg.setRoomPx((int) Math.round(v)), 8, 40, "px",
                    cfg, contentX, y, colW));
            widgets.add(cycle("Room Labels", LiveMapConfig.ROOM_LABEL_NAMES, cfg::getRoomLabels, cfg::setRoomLabels,
                    cfg, colB, y, colW));
            y += 20;
            widgets.add(toggle("Show Teammates", cfg::isShowTeammates, cfg::setShowTeammates, cfg, contentX, y, colW));
            widgets.add(toggle("Recolor by Class", cfg::isClassRecolorTeammates, cfg::setClassRecolorTeammates, cfg, colB, y, colW));
            y += 20;
            widgets.add(toggle("Room Name Below Map", cfg::isRoomNameBelowMap, cfg::setRoomNameBelowMap, cfg, contentX, y, colW));
            widgets.add(toggle("Player Heads", cfg::isPlayerHeads, cfg::setPlayerHeads, cfg, colB, y, colW));
            y += 20;
            widgets.add(keyButton("Peek Key", KeyTarget.PEEK, cfg.getPeekKeyCode(), contentX, y, colW));
            widgets.add(slider("Peek Scale", cfg::getPeekScale, v -> cfg.setPeekScale((float) v), 1.25, 4, "x", cfg, colB, y, colW));
            y += 24;
        }

        // ---------------------------------------------------------------- shared appearance
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
        y += 20;
        widgets.add(colour("Map Background", cfg::getMapBackground, cfg::setMapBackground, 0x99000000, cfg, contentX, y, colW));
        widgets.add(colour("Border Colour", cfg::getMapBorderColor, cfg::setMapBorderColor, 0xFFCC6600, cfg, colB, y, colW));
        y += 20;
        widgets.add(colour("Current Room", cfg::getHighlightColor, cfg::setHighlightColor, 0x80808080, cfg, contentX, y, colW));
        widgets.add(SettingsButtonWidget.builder(Component.literal((showColours ? "▼ " : "▶ ") + "Room Colours"), btn -> {
                    showColours = !showColours;
                    requestRebuild.run();
                }).bounds(colB, y, colW, 18).build());
        y += 20;
        if (showColours) {
            widgets.add(colour("Normal", cfg::getColorNormal, cfg::setColorNormal, 0xFF724318, cfg, contentX, y, colW));
            widgets.add(colour("Entrance", cfg::getColorEntrance, cfg::setColorEntrance, 0xFF00FF00, cfg, colB, y, colW));
            y += 20;
            widgets.add(colour("Puzzle", cfg::getColorPuzzle, cfg::setColorPuzzle, 0xFFB24CD8, cfg, contentX, y, colW));
            widgets.add(colour("Trap", cfg::getColorTrap, cfg::setColorTrap, 0xFFD87F33, cfg, colB, y, colW));
            y += 20;
            widgets.add(colour("Miniboss", cfg::getColorMiniboss, cfg::setColorMiniboss, 0xFFE5E533, cfg, contentX, y, colW));
            widgets.add(colour("Rare", cfg::getColorRare, cfg::setColorRare, 0xFFB2B2B2, cfg, colB, y, colW));
            y += 20;
            widgets.add(colour("Blood", cfg::getColorBlood, cfg::setColorBlood, 0xFFFF0000, cfg, contentX, y, colW));
            widgets.add(colour("Fairy", cfg::getColorFairy, cfg::setColorFairy, 0xFFF27FA5, cfg, colB, y, colW));
            y += 20;
            widgets.add(colour("Unexplored", cfg::getColorUnopened, cfg::setColorUnopened, 0xFF414141, cfg, contentX, y, colW));
            widgets.add(colour("Wither Door", cfg::getColorWitherDoor, cfg::setColorWitherDoor, 0xFF101010, cfg, colB, y, colW));
            y += 20;
            widgets.add(SettingsButtonWidget.builder(Component.literal("Reset Room Colours"), btn -> {
                        cfg.resetMapColours();
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(contentX, y, colW, 18).build());
            y += 20;
        }
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

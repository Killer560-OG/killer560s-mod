package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.livemap.LiveMapConfig;
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

/** Live Dungeon Map + Interactive Map settings - see {@link com.killer560.hub.livemap.LiveMapFeature} and
 *  {@link com.killer560.hub.livemap.InteractiveMapFeature}. Teleport Pathing / Auto Blood Rush only exist in cheat builds. */
public class LiveMapTab extends BaseTab implements KeyCaptureTab {

    private enum KeyTarget { PEEK, OPEN, START, LOCKED_DOOR, BLOOD_RUSH }

    private KeyTarget capturing = null;

    public LiveMapTab() {
        super("Live Map");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        LiveMapConfig cfg = LiveMapConfig.getInstance();
        int gap = 8;
        int colW = (contentWidth - gap) / 2;
        int colB = contentX + colW + gap;
        int y = contentY;

        // ---------------------------------------------------------------- HUD map
        widgets.add(SettingsButtonWidget.builder(onOff("Live Map", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isEnabledRaw()) {
            widgets.add(toggle("Show Teammates", cfg::isShowTeammates, cfg::setShowTeammates, cfg, contentX, y, colW));
            widgets.add(toggle("Recolor by Class", cfg::isClassRecolorTeammates, cfg::setClassRecolorTeammates, cfg, colB, y, colW));
            y += 20;
            widgets.add(SettingsButtonWidget.builder(Component.literal("Cell Size: " + cfg.getCellSize() + "px"), btn -> {
                        int next = cfg.getCellSize() + 2;
                        cfg.setCellSize(next > 16 ? 4 : next);
                        cfg.save();
                        btn.setMessage(Component.literal("Cell Size: " + cfg.getCellSize() + "px"));
                    }).bounds(contentX, y, colW, 18).build());
            widgets.add(cycle("Room Labels", LiveMapConfig.ROOM_LABEL_NAMES, cfg::getRoomLabels, cfg::setRoomLabels, cfg, colB, y, colW));
            y += 20;
            widgets.add(keyButton("Peek Key", KeyTarget.PEEK, cfg.getPeekKeyCode(), contentX, y, colW));
            widgets.add(slider("Peek Scale", cfg::getPeekScale, v -> cfg.setPeekScale((float) v), 1.25, 4, "x", cfg, colB, y, colW));
            y += 24;
        }

        // ---------------------------------------------------------------- interactive map
        widgets.add(SettingsButtonWidget.builder(onOff("Interactive Map", cfg.isInteractiveMapEnabledRaw()), btn -> {
                    cfg.setInteractiveMapEnabled(!cfg.isInteractiveMapEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isInteractiveMapEnabledRaw()) {
            widgets.add(keyButton("Open Key", KeyTarget.OPEN, cfg.getOpenKeyCode(), contentX, y, colW));
            widgets.add(SettingsButtonWidget.builder(closeOnText(cfg), btn -> {
                        cfg.setCloseOnRepress(!cfg.isCloseOnRepress());
                        cfg.save();
                        btn.setMessage(closeOnText(cfg));
                    }).bounds(colB, y, colW, 18).build());
            y += 20;
            widgets.add(toggle("Open From HUD Click", cfg::isOpenFromHudClick, cfg::setOpenFromHudClick, cfg, contentX, y, colW));
            widgets.add(cycle("Room Labels", LiveMapConfig.ROOM_LABEL_NAMES, cfg::getMapRoomLabels, cfg::setMapRoomLabels, cfg, colB, y, colW));
            y += 20;
            widgets.add(slider("Map Scale", cfg::getMapScale, v -> cfg.setMapScale((float) v), 1, 10, "", cfg, contentX, y, colW));
            widgets.add(slider("Font Scale", cfg::getFontScale, v -> cfg.setFontScale((float) v), 0.5, 3, "x", cfg, colB, y, colW));
            y += 20;
            widgets.add(toggle("Text Shadow", cfg::isTextShadow, cfg::setTextShadow, cfg, contentX, y, colW));
            widgets.add(SettingsButtonWidget.builder(ColorSwatch.label("Highlight Colour", cfg.getHighlightColor()), btn -> {
                        Minecraft client = Minecraft.getInstance();
                        client.setScreen(new ColorPickerScreen(client.screen, "Highlight Colour", cfg.getHighlightColor(),
                                0x80808080, argb -> {
                            cfg.setHighlightColor(argb);
                            cfg.save();
                        }));
                    }).bounds(colB, y, colW, 18).build());
            y += 20;
            widgets.add(toggle("Player Heads", cfg::isPlayerHeads, cfg::setPlayerHeads, cfg, contentX, y, colW));
            widgets.add(toggle("Class Colours", cfg::isClassBorderColour, cfg::setClassBorderColour, cfg, colB, y, colW));
            y += 20;
            widgets.add(cycle("Player Names", LiveMapConfig.PLAYER_NAME_MODES, cfg::getPlayerNames, cfg::setPlayerNames, cfg, contentX, y, colW));
            widgets.add(slider("Icon Scale", cfg::getIconScale, v -> cfg.setIconScale((float) v), 0.5, 3, "x", cfg, colB, y, colW));
            y += 24;
        }

        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }

        // ---------------------------------------------------------------- teleport pathing (cheat)
        widgets.add(SettingsButtonWidget.builder(onOff("Teleport Pathing", cfg.isPathingEnabledRaw()), btn -> {
                    cfg.setPathingEnabled(!cfg.isPathingEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isPathingEnabledRaw()) {
            widgets.add(keyButton("Start Key", KeyTarget.START, cfg.getStartKeyCode(), contentX, y, colW));
            widgets.add(keyButton("Locked Door Key", KeyTarget.LOCKED_DOOR, cfg.getLockedDoorKeyCode(), colB, y, colW));
            y += 20;
            widgets.add(toggle("Face Door on Arrival", cfg::isFaceDoorOnArrival, cfg::setFaceDoorOnArrival, cfg, contentX, y, colW));
            widgets.add(toggle("Keep Chunks Loaded", cfg::isKeepChunksLoadedRaw, cfg::setKeepChunksLoaded, cfg, colB, y, colW));
            y += 20;
            widgets.add(slider("Path Threads", cfg::getThreads, v -> cfg.setThreads((int) Math.round(v)), 1, 16, "", cfg, contentX, y, colW));
            widgets.add(slider("Path Timeout", cfg::getTimeoutMs, v -> cfg.setTimeoutMs((int) (Math.round(v / 50.0) * 50)), 200, 1000, "ms",
                    cfg, colB, y, colW));
            y += 24;
        }

        // ---------------------------------------------------------------- auto blood rush (cheat)
        widgets.add(SettingsButtonWidget.builder(onOff("Auto Blood Rush", cfg.isBloodRushEnabledRaw()), btn -> {
                    cfg.setBloodRushEnabled(!cfg.isBloodRushEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 24;
        if (cfg.isBloodRushEnabledRaw()) {
            widgets.add(keyButton("Blood Rush Key", KeyTarget.BLOOD_RUSH, cfg.getBloodRushKeyCode(), contentX, y, colW));
            widgets.add(toggle("Click Door on Arrival", cfg::isBloodRushClickDoor, cfg::setBloodRushClickDoor, cfg, colB, y, colW));
            y += 20;
            widgets.add(slider("Door Timeout", cfg::getBloodRushDoorTimeoutSec, v -> cfg.setBloodRushDoorTimeoutSec((int) Math.round(v)),
                    5, 60, "s", cfg, contentX, y, colW));
            y += 20;
        }
        return widgets;
    }

    // ------------------------------------------------------------------------------------------- helpers

    private interface BoolGetter {
        boolean get();
    }

    private interface BoolSetter {
        void set(boolean v);
    }

    private static AbstractWidget toggle(String label, BoolGetter get, BoolSetter set, LiveMapConfig cfg, int x, int y, int w) {
        return SettingsButtonWidget.builder(onOff(label, get.get()), btn -> {
                    set.set(!get.get());
                    cfg.save();
                    btn.setMessage(onOff(label, get.get()));
                }).bounds(x, y, w, 18).build();
    }

    private static AbstractWidget cycle(String label, String[] names, java.util.function.IntSupplier get, IntConsumer set,
                                        LiveMapConfig cfg, int x, int y, int w) {
        Supplier<Component> text = () -> Component.literal(label + ": " + names[get.getAsInt()]);
        return SettingsButtonWidget.builder(text.get(), btn -> {
                    set.accept((get.getAsInt() + 1) % names.length);
                    cfg.save();
                    btn.setMessage(text.get());
                }).bounds(x, y, w, 18).build();
    }

    private static AbstractWidget slider(String label, DoubleSupplier get, DoubleConsumer set, double min, double max, String unit,
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

    private AbstractWidget keyButton(String label, KeyTarget target, int code, int x, int y, int w) {
        Component text = capturing == target ? Component.literal(label + ": Press any key...") : keyText(label, code);
        return SettingsButtonWidget.builder(text, btn -> {
                    capturing = target;
                    btn.setMessage(Component.literal(label + ": Press any key..."));
                }).bounds(x, y, w, 18).build();
    }

    private static Component keyText(String label, int code) {
        String name = code < 0 ? "Not Set" : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
        return Component.literal(label + ": §b" + name);
    }

    private static Component closeOnText(LiveMapConfig cfg) {
        return Component.literal("Close On: " + (cfg.isCloseOnRepress() ? "Repress" : "Release"));
    }

    private static Component onOff(String label, boolean value) {
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
        if (capturing != null) {
            switch (capturing) {
                case PEEK -> cfg.setPeekKeyCode(code);
                case OPEN -> cfg.setOpenKeyCode(code);
                case START -> cfg.setStartKeyCode(code);
                case LOCKED_DOOR -> cfg.setLockedDoorKeyCode(code);
                case BLOOD_RUSH -> cfg.setBloodRushKeyCode(code);
            }
        }
        capturing = null;
        cfg.save();
    }
}

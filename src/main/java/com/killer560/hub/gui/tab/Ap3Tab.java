package com.killer560.hub.gui.tab;

import com.killer560.hub.ap3.Ap3Area;
import com.killer560.hub.ap3.Ap3Commands;
import com.killer560.hub.ap3.Ap3Commands.Action;
import com.killer560.hub.ap3.Ap3Config;
import com.killer560.hub.ap3.Ap3ConfigScreen;
import com.killer560.hub.ap3.Ap3Executor;
import com.killer560.hub.ap3.Ap3Feature;
import com.killer560.hub.ap3.Ap3Node;
import com.killer560.hub.ap3.Ap3Store;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * AP3 settings - automated F7/M7 boss-fight movement. Cheat build only ({@link NewTab}'s cheat block), red headers,
 * collapses to the master toggle while OFF (AutoRoutesTab / LeverAuraTab pattern: an unused feature costs one line).
 * <p>
 * Top to bottom (killer560's 2026-09-21 order): the master toggle; right under it "Choose AP3 Config" (which chains
 * file is in use - {@link Ap3ConfigScreen} lists the folder and creates new ones) with Open Folder + Reload beside
 * it; the boss-only status line with Stop / Test Mode (Stop "must work at any time"); Movement - exactly "45 Degree
 * Strafe" and "Chat Feedback"; the world-label settings; the stopwatch HUD; then the two collapsible sections,
 * Colors and Keybinds ({@link CollapsibleSection}, closed by default, remembered across restarts).
 * <p>
 * Nodes are ADDED, edited and removed with {@code /ap3 ...} or the keybinds only - killer560 (2026-09-20): "Do not
 * list the add node section in the settings tab"; (2026-09-21): "Remove the chain s4 section entirely" - the per-area
 * chain list with its node rows and edit page is gone with it. Every button that reaches the executor goes through
 * {@link Action#run()} - the same path as the chat command - so the GUI can't do anything a command can't.
 */
public class Ap3Tab extends BaseTab implements KeyCaptureTab {

    private static final int BTN_W = 220;
    private static final int ROW = 18;
    private static final int GAP = 6;

    /** Which keybind row is waiting for a key, or null. */
    private Action capturing;
    /** Set only while {@link #matchesSearch} builds: both collapsible sections are laid out open so search can see
     *  every setting in them without forcing them open on screen. */
    private boolean scanningForSearch;

    public Ap3Tab() {
        super("AP3");
    }

    /** Only added to {@link NewTab} behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    // ---- KeyCaptureTab ----

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        if (capturing == null) {
            return;
        }
        Ap3Config cfg = Ap3Config.getInstance();
        // Esc clears, like every other keybind row in the mod; anything GLFW can't poll is stored as Not Set
        // rather than as a code that would spam GLFW_INVALID_ENUM every tick (see KeyUtil).
        int key = keyCode == InputConstants.KEY_ESCAPE ? KeyUtil.NONE : KeyUtil.sanitize(keyCode);
        cfg.setKeybind(capturing.id, key);
        cfg.save();
        capturing = null;
    }

    // ---- layout ----

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        Ap3Config cfg = Ap3Config.getInstance();
        int[] y = {contentY};
        int half = (contentWidth - GAP) / 2;

        header(w, contentX, y, contentWidth, "AP3");
        // Full width (killer560, 2026-09-21: "make the ap3 on button take up the full width").
        w.add(SettingsButtonWidget.builder(onOff("AP3", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;
        if (!cfg.isEnabledRaw()) {
            return w;
        }

        // Reorganised 2026-09-21 (killer560: "go through and reorganize the menu"): config file, then the controls
        // you reach for while testing, then how it moves, how it talks, how nodes look, and the collapsibles last.
        // The "AP3 status:" line is gone (killer560: "remove the ap3 status: line").
        w.add(SettingsButtonWidget.builder(Component.literal("Choose AP3 Config: \u00a76" + cfg.getChainsFile()), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new Ap3ConfigScreen(client.screen));
                }).bounds(contentX, y[0], contentWidth, 20).build());
        y[0] += 24;
        w.add(SettingsButtonWidget.builder(Component.literal("Open AP3 Folder"), btn -> openFolder())
                .bounds(contentX, y[0], half, 20).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Reload AP3 Chains"), btn -> {
                    Action.RELOAD.run();
                    requestRebuild.run();
                }).bounds(contentX + half + GAP, y[0], Math.max(1, contentWidth - half - GAP), 20).build());
        y[0] += 24;

        header(w, contentX, y, contentWidth, "Controls");
        // The Stop AP3 button is gone (killer560, 2026-09-21: "I do not need that"); the Stop AP3 keybind and
        // /ap3 stop remain. Force Dungeon ("add a force dungeon tab to the ap3 so I can config outside of dungeons")
        // is session only (Ap3Feature, not the config): off on every launch and world change.
        boolean forced = safe(Ap3Feature::isForceDungeon);
        w.add(SettingsButtonWidget.builder(onOff("Test Mode", safe(Ap3Executor::isTestMode)), btn -> {
                    Action.TEST_MODE.run();
                    requestRebuild.run();
                }).bounds(contentX, y[0], half, 20).build());
        w.add(SettingsButtonWidget.builder(onOff("Force Dungeon", forced), btn -> {
                    Ap3Feature.setForceDungeon(!Ap3Feature.isForceDungeon());
                    requestRebuild.run();
                }).bounds(contentX + half + GAP, y[0], Math.max(1, contentWidth - half - GAP), 20).build());
        y[0] += 24;
        if (forced) {
            w.add(SettingsButtonWidget.builder(forcedAreaText(), btn -> {
                        Ap3Feature.cycleForcedArea();
                        btn.setMessage(forcedAreaText());
                    }).bounds(contentX, y[0], contentWidth, 20).build());
            y[0] += 24;
        }

        header(w, contentX, y, contentWidth, "Movement");
        toggleCell(w, contentX, y[0], half, "45 Degree Strafe", cfg::isStrafe45, cfg::setStrafe45);
        toggleCell(w, contentX + half + GAP, y[0], Math.max(1, contentWidth - half - GAP), "Freeze View (Freecam)",
                cfg::isAlignFreezeView, cfg::setAlignFreezeView);
        y[0] += ROW + GAP;

        header(w, contentX, y, contentWidth, "Chat");
        toggleCell(w, contentX, y[0], half, "Chat Feedback", cfg::isChatFeedback, cfg::setChatFeedback);
        w.add(SettingsButtonWidget.builder(messageDetailText(cfg), btn -> {
                    cfg.setMessageDetail(cfg.getMessageDetail().next());
                    cfg.save();
                    btn.setMessage(messageDetailText(cfg));
                }).bounds(contentX + half + GAP, y[0], Math.max(1, contentWidth - half - GAP), ROW).build());
        y[0] += ROW + GAP;

        header(w, contentX, y, contentWidth, "Nodes");
        w.add(SettingsButtonWidget.builder(defaultSizeText(cfg), btn -> {
                    cfg.setDefaultNodeSize(cfg.getDefaultNodeSize() >= 1.0 ? 0.5 : 1.0);
                    cfg.save();
                    btn.setMessage(defaultSizeText(cfg));
                }).bounds(contentX, y[0], half, ROW).build());
        toggleCell(w, contentX + half + GAP, y[0], Math.max(1, contentWidth - half - GAP), "Stopwatch HUD",
                cfg::isStopwatchHud, cfg::setStopwatchHud);
        y[0] += ROW + GAP;
        toggleCell(w, contentX, y[0], half, "Send Stopwatch to Party", cfg::isStopwatchToParty,
                cfg::setStopwatchToParty);
        toggleCell(w, contentX + half + GAP, y[0], Math.max(1, contentWidth - half - GAP), "Show Node Lines",
                cfg::isShowChainLines, cfg::setShowChainLines);
        y[0] += ROW + GAP;
        toggleCell(w, contentX, y[0], contentWidth, "Show Nodes", cfg::isShowNodes, cfg::setShowNodes);
        y[0] += ROW + GAP;

        buildLabelSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        if (com.killer560.hub.BuildVariant.DEV_TOOLS) {
            // Compiled out of official releases (build.gradle -Prelease=true): this whole block does not exist there.
            header(w, contentX, y, contentWidth, "Dev Tools");
            toggle(w, contentX, y, "Align Timer (dev)", cfg::isAlignTimerDevRaw, cfg::setAlignTimerDev, null);
        }
        // killer560 (2026-09-21): colours "right above the keybinds section and also make that collapsible".
        buildColorSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        buildKeybindSection(w, cfg, contentX, y, contentWidth, requestRebuild);
        return w;
    }

    private void buildColorSection(List<AbstractWidget> w, Ap3Config cfg, int x, int[] y, int width, int half, Runnable rebuild) {
        boolean open = scanningForSearch || cfg.isColorsSectionOpen();
        y[0] = CollapsibleSection.header(w, x, y[0], width, "AP3 Colors", true, open, () -> {
            cfg.setColorsSectionOpen(!cfg.isColorsSectionOpen());
            cfg.save();
            rebuild.run();
        });
        if (!open) {
            return;
        }
        toggle(w, x, y, "Uniform Node Color", cfg::isUniformColor, cfg::setUniformColor, rebuild);
        if (cfg.isUniformColor()) {
            colorButton(w, x, y[0], half, "Chain Color", cfg.getUniformColorArgb(),
                    Ap3Config.DEFAULT_UNIFORM_COLOR, cfg::setUniformColorArgb, null);
            colorButton(w, x + half + GAP, y[0], half, "Current Node Color", cfg.getActiveColorArgb(), 0xFFFFFFFF,
                    cfg::setActiveColorArgb, null);
            y[0] += 24;
            return;
        }
        colorButton(w, x, y[0], half, "Current Node Color", cfg.getActiveColorArgb(), 0xFFFFFFFF, cfg::setActiveColorArgb, null);
        y[0] += 24;
        Ap3Node.Type[] types = Ap3Node.Type.values();
        for (int i = 0; i < types.length; i += 2) {
            Ap3Node.Type a = types[i];
            colorButton(w, x, y[0], half, "AP3 " + Ap3Commands.typeName(a) + " Color", cfg.getNodeColorArgb(a), defaultColor(a),
                    argb -> cfg.setNodeColorArgb(a, argb), null);
            if (i + 1 < types.length) {
                Ap3Node.Type b = types[i + 1];
                colorButton(w, x + half + GAP, y[0], half, "AP3 " + Ap3Commands.typeName(b) + " Color", cfg.getNodeColorArgb(b), defaultColor(b),
                        argb -> cfg.setNodeColorArgb(b, argb), null);
            }
            y[0] += 24;
        }
    }

    /**
     * The world labels (killer560, 2026-09-16: "have it label nodes that i make. For instance the very first node
     * is 1 the second is 2 and so on. It should be toggleable for color and if it shows"): a master switch, the
     * three parts of a label, the colour choice, and the same scale / height knobs Posmsg's waypoints have.
     */
    private void buildLabelSection(List<AbstractWidget> w, Ap3Config cfg, int x, int[] y, int width, int half, Runnable rebuild) {
        header(w, x, y, width, "Node Labels");
        // Laid out 2026-09-21 (killer560: "adjust the node labels as that section looks funny"): full-width master
        // switch, then even two-column rows - the old three narrow cells cut their text off.
        w.add(SettingsButtonWidget.builder(onOff("Show Node Labels", cfg.isShowLabels()), btn -> {
                    cfg.setShowLabels(!cfg.isShowLabels());
                    cfg.save();
                    rebuild.run();
                }).bounds(x, y[0], width, 20).build());
        y[0] += 24;
        if (!cfg.isShowLabels()) {
            return;
        }
        int right = Math.max(1, width - half - GAP);
        toggleCell(w, x, y[0], half, "Show Node Numbers", cfg::isShowNodeNumbers, cfg::setShowNodeNumbers);
        toggleCell(w, x + half + GAP, y[0], right, "Show Node Type", cfg::isShowNodeType, cfg::setShowNodeType);
        y[0] += ROW + GAP;
        toggleCell(w, x, y[0], half, "Show Node Details", cfg::isShowNodeDetails, cfg::setShowNodeDetails);
        // "toggleable for color": the node's own colour (so the number matches its box) or one fixed colour.
        w.add(SettingsButtonWidget.builder(labelColorModeText(cfg), btn -> {
                    cfg.setLabelUseNodeColor(!cfg.isLabelUseNodeColor());
                    cfg.save();
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], right, ROW).build());
        y[0] += ROW + GAP;
        if (!cfg.isLabelUseNodeColor()) {
            colorButton(w, x, y[0], width, "Fixed Label Color", cfg.getLabelColorArgb(),
                    Ap3Config.DEFAULT_LABEL_COLOR, cfg::setLabelColorArgb, null);
            y[0] += 24;
        }

        w.add(slider(x, y[0], half, labelScaleText(cfg), Ap3Config.MIN_LABEL_SCALE, Ap3Config.MAX_LABEL_SCALE,
                cfg.getLabelScale(), 0.05, v -> cfg.setLabelScale((float) v), () -> labelScaleText(cfg), cfg::save));
        w.add(slider(x + half + GAP, y[0], Math.max(1, width - half - GAP), labelHeightText(cfg), Ap3Config.MIN_LABEL_HEIGHT, Ap3Config.MAX_LABEL_HEIGHT,
                cfg.getLabelHeightOffset(), 0.05, v -> cfg.setLabelHeightOffset((float) v), () -> labelHeightText(cfg), cfg::save));
        y[0] += ROW + GAP;
    }

    private void buildKeybindSection(List<AbstractWidget> w, Ap3Config cfg, int x, int[] y, int width, Runnable rebuild) {
        boolean open = scanningForSearch || cfg.isKeybindsSectionOpen();
        y[0] = CollapsibleSection.header(w, x, y[0], width, "AP3 Keybinds", true, open, () -> {
            cfg.setKeybindsSectionOpen(!cfg.isKeybindsSectionOpen());
            cfg.save();
            rebuild.run();
        });
        if (!open) {
            return;
        }
        for (Action action : Action.values()) {
            if (java.util.Arrays.asList(FreezeStateTab.ACTIONS).contains(action)) {
                continue; // bound on the Freeze State tab
            }
            w.add(SettingsButtonWidget.builder(keyText(action, cfg.getKeybind(action.id)), btn -> {
                        capturing = action;
                        btn.setMessage(Component.literal(action.label + " Key: §ePress any key..."));
                    }).bounds(x, y[0], width, ROW).build());
            y[0] += ROW + 4;
        }
        y[0] += GAP;
    }

    // ---------------------------------------------------------------- search

    /** Search has to see the settings inside a collapsed section (Colors, Keybinds) without opening it on screen
     *  (ModScreen's rule: a match never force-expands a section), so the scan builds with both laid out open.
     *  Only ever run from the search field's responder, never per frame. */
    @Override
    public boolean matchesSearch(String query) {
        if (query.isBlank() || nameMatches(query)) {
            return true;
        }
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        List<AbstractWidget> scan;
        scanningForSearch = true;
        try {
            scan = buildWidgets(0, 0, 200, () -> {});
        } finally {
            scanningForSearch = false;
        }
        String q = query.toLowerCase(Locale.US);
        for (AbstractWidget widget : scan) {
            String text = ChatFormatting.stripFormatting(widget.getMessage().getString());
            if (text != null && text.toLowerCase(Locale.US).contains(q)) {
                return true;
            }
        }
        return false;
    }

    // ---- text helpers ----

    private static Component forcedAreaText() {
        String area;
        try {
            area = Ap3Feature.forcedArea().label();
        } catch (Exception e) {
            area = "?";
        }
        return Component.literal("Forced Area: §d" + area);
    }

    private Component keyText(Action action, int key) {
        if (capturing == action) {
            return Component.literal(action.label + " Key: §ePress any key...");
        }
        String name = key == KeyUtil.NONE ? "§7Not Set" : "§e" + InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(action.label + " Key: " + name + " §8" + action.command);
    }

    private static Component labelColorModeText(Ap3Config cfg) {
        return Component.literal("Label Color: §6" + (cfg.isLabelUseNodeColor() ? "Node's Color" : "Fixed"));
    }

    private static Component messageDetailText(Ap3Config cfg) {
        return Component.literal("Node Messages: \u00a76" + cfg.getMessageDetail().label);
    }

    private static Component defaultSizeText(Ap3Config cfg) {
        return Component.literal("Default Node Size: \u00a76" + (cfg.getDefaultNodeSize() >= 1.0 ? "1 block" : "0.5 block"));
    }

    private static Component labelScaleText(Ap3Config cfg) {
        return Component.literal(String.format(Locale.US, "Label Scale: %.0f%%", cfg.getLabelScale() * 100));
    }

    private static Component labelHeightText(Ap3Config cfg) {
        return Component.literal(String.format(Locale.US, "Label Height: %.2f", cfg.getLabelHeightOffset()));
    }

    /** Delegates to the config rather than keeping a second copy - the duplicate table had drifted from
     *  the real defaults for 10 of the 11 node types, so "reset" set a colour that was never the default
     *  (2026-09-16 review). */
    private static int defaultColor(Ap3Node.Type type) {
        return Ap3Config.defaultNodeColor(type);
    }

    private static void openFolder() {
        try {
            // A fresh install has no folder yet - openPath on a missing directory does nothing at all, silently.
            Path dir = Ap3Store.directory();
            Files.createDirectories(dir);
            net.minecraft.util.Util.getPlatform().openPath(dir);
        } catch (Exception ignored) {
        }
    }

    private static boolean safe(Supplier<Boolean> query) {
        try {
            return Boolean.TRUE.equals(query.get());
        } catch (Exception e) {
            return false;
        }
    }

    // ---- widget helpers (AutoRoutesTab pattern) ----

    private static void label(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        w.add(new StringWidget(x, y[0], width, 12, Component.literal(text), Minecraft.getInstance().font));
        y[0] += 16;
    }

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(text, true), Minecraft.getInstance().font));
        y[0] += 16;
    }

    /** ON/OFF toggle; a non-null {@code rebuild} rebuilds the tab (master toggles and anything that collapses). */
    private static void toggle(List<AbstractWidget> w, int x, int[] y, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            Ap3Config.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
    }

    /** A toggle sized to a grid cell (for rows of three), no rebuild - the caller advances y. */
    private static void toggleCell(List<AbstractWidget> w, int x, int y, int width, String name, Supplier<Boolean> getter,
                                   Consumer<Boolean> setter) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            Ap3Config.getInstance().save();
            btn.setMessage(onOff(name, getter.get()));
        }).bounds(x, y, Math.max(1, width), ROW).build());
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    /** Colour picker button. {@code save} is what persists the picked value; null = the settings file. */
    private static void colorButton(List<AbstractWidget> w, int x, int y, int width, String label, int argb, int defaultArgb,
                                    IntConsumer apply, Runnable save) {
        w.add(SettingsButtonWidget.builder(ColorSwatch.label(label, argb), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, label, argb, defaultArgb, picked -> {
                        apply.accept(picked);
                        if (save != null) {
                            save.run();
                        } else {
                            Ap3Config.getInstance().save();
                        }
                    }));
                }).bounds(x, y, width, 20).build());
    }

    /** One themed slider over {@code [min, max]} snapped to {@code step}, calling {@code save} on every change -
     *  the same helper PosmsgTab uses. */
    private static ThemedSliderButton slider(int x, int y, int w, Component label, double min, double max, double current,
                                             double step, DoubleConsumer apply, Supplier<Component> text, Runnable save) {
        double span = max - min;
        double normalized = span <= 0 ? 0 : (current - min) / span;
        return new ThemedSliderButton(x, y, Math.max(1, w), ROW, label, Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(text.get());
            }

            @Override
            protected void applyValue() {
                double raw = min + this.value * span;
                double snapped = Math.round(raw / step) * step;
                apply.accept(Math.max(min, Math.min(max, snapped)));
                save.run();
            }
        };
    }
}

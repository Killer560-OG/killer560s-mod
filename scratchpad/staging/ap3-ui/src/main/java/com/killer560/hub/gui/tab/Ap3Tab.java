package com.killer560.hub.gui.tab;

import com.killer560.hub.ap3.Ap3Commands;
import com.killer560.hub.ap3.Ap3Commands.Action;
import com.killer560.hub.ap3.Ap3Config;
import com.killer560.hub.ap3.Ap3Executor;
import com.killer560.hub.ap3.Ap3Feature;
import com.killer560.hub.ap3.Ap3Node;
import com.killer560.hub.ap3.Ap3Store;
import com.killer560.hub.dungeonclass.ClassOverrides;
import com.killer560.hub.dungeonclass.DungeonClass;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * AP3 settings - automated F7/M7 Phase 3 terminal-section movement. Cheat build only ({@link NewTab}'s cheat
 * block), red headers, collapses to the master toggle while OFF (AutoRoutesTab / LeverAuraTab pattern: an unused
 * feature costs one line).
 * <p>
 * Sections, top to bottom: master toggle; the boss-only / P3 status line (AP3 is "BOSS ONLY. It should only work
 * in the boss stages, not in regular clear" - the line says which gate is closed); the chain for the section you
 * are in with one row per node (type, modifier, snapped position, Delete); Start / Stop; breaker edit mode and
 * the node-adding buttons; Open Folder + Reload for the one shareable chains file; colours per node type or one
 * uniform colour; the class-override table read-only (edited in Dungeon > Class Overrides, shown here "so the leap
 * nodes' behaviour is explainable from where it is used"); one keybind row per command.
 * <p>
 * Every button that changes a chain or the executor goes through {@link Action#run()} - the same path as the
 * chat command - so the GUI can't do anything a command can't, and both print the same single line.
 */
public class Ap3Tab extends BaseTab implements KeyCaptureTab {

    private static final int BTN_W = 220;
    private static final int ROW = 18;
    private static final int GAP = 6;

    /** Which keybind row is waiting for a key, or null. */
    private Action capturing;
    /** Text in the wait-ms box; kept on the tab so a rebuild doesn't wipe a half-typed number. */
    private String waitText = Integer.toString(Ap3Commands.getPendingWaitMillis());

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

        label(w, contentX, y, contentWidth, "§7Walks a hand-placed node chain through each F7/M7 Phase 3 section. Boss only, never in clear. Use at your own risk.");

        header(w, contentX, y, contentWidth, "AP3");
        toggle(w, contentX, y, "AP3", cfg::isEnabledRaw, cfg::setEnabled, requestRebuild);
        if (!cfg.isEnabledRaw()) {
            return w;
        }
        label(w, contentX, y, contentWidth, statusLine());
        label(w, contentX, y, contentWidth, "§7Moving your mouse or pressing a movement key stops a running chain. A manual left-click satisfies whatever node it's waiting on.");

        buildChainSection(w, contentX, y, contentWidth, half, requestRebuild);
        buildAddSection(w, contentX, y, contentWidth, requestRebuild);
        buildFileSection(w, contentX, y, contentWidth, half, requestRebuild);
        buildColourSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        buildOverridesSection(w, contentX, y, contentWidth);
        buildKeybindSection(w, cfg, contentX, y, contentWidth);
        return w;
    }

    private void buildChainSection(List<AbstractWidget> w, int x, int[] y, int width, int half, Runnable rebuild) {
        // "Chain:" with a colon so the tooltip key stays "chain" whatever section follows (SettingTooltips cuts at ':').
        header(w, x, y, width, "Chain: " + Ap3Commands.sectionName());

        List<Ap3Node> nodes;
        try {
            nodes = new ArrayList<>(Ap3Feature.currentChainNodes());
        } catch (Exception e) {
            nodes = List.of();
        }
        boolean running = safe(Ap3Executor::isRunning);

        SettingsButtonWidget start = SettingsButtonWidget.builder(Component.literal("§aStart Chain"), btn -> {
                    Action.START.run();
                    rebuild.run();
                }).bounds(x, y[0], half, 20).build();
        start.active = !running && !nodes.isEmpty();
        w.add(start);
        // Stop is never greyed: "must work at any time" - if the executor's isRunning() ever lies, this still fires.
        w.add(SettingsButtonWidget.builder(Component.literal("§cStop Chain"), btn -> {
                    Action.STOP.run();
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], half, 20).build());
        y[0] += 24;

        int colW = (width - GAP * 2) / 3;
        w.add(SettingsButtonWidget.builder(onOff("Breaker Edit Mode", safe(Ap3Feature::isEditMode)), btn -> {
                    Action.EDIT_BREAKER.run();
                    rebuild.run();
                }).bounds(x, y[0], colW, ROW).build());
        w.add(SettingsButtonWidget.builder(Component.literal("List Chain In Chat"), btn -> Action.LIST.run())
                .bounds(x + colW + GAP, y[0], colW, ROW).build());
        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("§cClear Chain"), btn -> {
                    Action.CLEAR.run();
                    rebuild.run();
                }).bounds(x + (colW + GAP) * 2, y[0], Math.max(1, width - (colW + GAP) * 2), ROW).build();
        clear.active = !nodes.isEmpty();
        w.add(clear);
        y[0] += ROW + GAP;

        if (nodes.isEmpty()) {
            label(w, x, y, width, "§7No chain for " + Ap3Commands.sectionName() + " yet. Stand where a node goes and use the buttons below or /ap3 add <type>.");
            return;
        }
        label(w, x, y, width, "§7Nodes run top to bottom. Positions are snapped to the nearest half block. Breaker Edit Mode: right-click adds a block, shift-right-click removes.");

        int delW = 60;
        int labelW = Math.max(1, width - delW - GAP);
        for (int i = 0; i < nodes.size(); i++) {
            final int index = i;
            // Same formatter as /ap3 list - the number on this row IS the number "/ap3 delete <n>" wants.
            String text = "§8" + Ap3Commands.describeNumbered(i, nodes.get(i)).replaceFirst(" ", " §f");
            w.add(new StringWidget(x, y[0] + 3, labelW, 12, Component.literal(text), Minecraft.getInstance().font));
            w.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        Ap3Commands.delete(index);
                        rebuild.run();
                    }).bounds(x + labelW + GAP, y[0], delW, ROW).build());
            y[0] += ROW + 2;
        }
        y[0] += GAP;
    }

    /** The node-adding buttons, three per row, then the wait row (a number box + its own Add button). */
    private void buildAddSection(List<AbstractWidget> w, int x, int[] y, int width, Runnable rebuild) {
        header(w, x, y, width, "Add Node");
        label(w, x, y, width, "§7Adds a node at your feet (walk/run remember the direction you're facing; leap uses Fast Leap's target unless given a class or IGN).");
        Action[] adders = {
                Action.ADD_LINE, Action.ADD_AXIS_LINE, Action.ADD_WALK,
                Action.ADD_RUN, Action.ADD_LEAP, Action.ADD_LEAP_DETECTOR,
                Action.ADD_TERMINAL, Action.ADD_STOP, Action.ADD_LOOK,
                Action.ADD_BREAKER
        };
        int colW = (width - GAP * 2) / 3;
        for (int i = 0; i < adders.length; i++) {
            Action a = adders[i];
            int col = i % 3;
            w.add(SettingsButtonWidget.builder(Component.literal(a.label), btn -> {
                        a.run();
                        rebuild.run();
                    }).bounds(x + (colW + GAP) * col, y[0], colW, ROW).build());
            if (col == 2 || i == adders.length - 1) {
                y[0] += ROW + 2;
            }
        }

        // "add a wait modifier in milliseconds for this one" (killer560): the box feeds the same pending value the
        // /ap3 add wait <ms> command sets, so the keybind adds whatever was typed here last.
        int boxW = 90;
        EditBox ms = new EditBox(Minecraft.getInstance().font, x, y[0], boxW, ROW, Component.literal("Wait ms"));
        ms.setMaxLength(6);
        ms.setHint(Component.literal("§8ms"));
        ms.setValue(waitText);
        ms.setResponder(text -> {
            waitText = text;
            Integer parsed = parseMillis(text);
            if (parsed != null) {
                Ap3Commands.setPendingWaitMillis(parsed);
            }
        });
        w.add(ms);
        SettingsButtonWidget addWait = SettingsButtonWidget.builder(
                Component.literal(Action.ADD_WAIT.label + ": " + Ap3Commands.getPendingWaitMillis() + " ms"), btn -> {
                    Action.ADD_WAIT.run();
                    rebuild.run();
                }).bounds(x + boxW + GAP, y[0], colW, ROW).build();
        w.add(addWait);
        y[0] += ROW + GAP;
    }

    /** Same pair as Auto Routes: the folder holds the one JSON file all chains live in, share it as-is. */
    private void buildFileSection(List<AbstractWidget> w, int x, int[] y, int width, int half, Runnable rebuild) {
        header(w, x, y, width, "Chains File");
        w.add(SettingsButtonWidget.builder(Component.literal("Open AP3 Folder"), btn -> openFolder())
                .bounds(x, y[0], half, 20).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Reload AP3 Chains"), btn -> {
                    Action.RELOAD.run();
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], half, 20).build());
        y[0] += 24;
        label(w, x, y, width, "§7All chains live in one file: §f" + Ap3Commands.CHAINS_FILE_NAME);
        label(w, x, y, width, "§7Edit it in Notepad or hand it to someone as-is. Paste a friend's copy in, then Reload - no restart.");
    }

    private void buildColourSection(List<AbstractWidget> w, Ap3Config cfg, int x, int[] y, int width, int half, Runnable rebuild) {
        header(w, x, y, width, "AP3 Colours");
        toggle(w, x, y, "Uniform Node Colour", cfg::isUniformColor, cfg::setUniformColor, rebuild);
        if (cfg.isUniformColor()) {
            colorButton(w, x, y[0], half, "Chain Colour", cfg.getUniformColorArgb(), 0xFF00FFFF, cfg::setUniformColorArgb);
            colorButton(w, x + half + GAP, y[0], half, "Current Node Colour", cfg.getActiveColorArgb(), 0xFFFFFFFF, cfg::setActiveColorArgb);
            y[0] += 24;
            return;
        }
        colorButton(w, x, y[0], half, "Current Node Colour", cfg.getActiveColorArgb(), 0xFFFFFFFF, cfg::setActiveColorArgb);
        y[0] += 24;
        Ap3Node.Type[] types = Ap3Node.Type.values();
        for (int i = 0; i < types.length; i += 2) {
            Ap3Node.Type a = types[i];
            colorButton(w, x, y[0], half, "AP3 " + Ap3Commands.typeName(a) + " Colour", cfg.getNodeColorArgb(a), defaultColor(a),
                    argb -> cfg.setNodeColorArgb(a, argb));
            if (i + 1 < types.length) {
                Ap3Node.Type b = types[i + 1];
                colorButton(w, x + half + GAP, y[0], half, "AP3 " + Ap3Commands.typeName(b) + " Colour", cfg.getNodeColorArgb(b), defaultColor(b),
                        argb -> cfg.setNodeColorArgb(b, argb));
            }
            y[0] += 24;
        }
    }

    /** Read-only view of the mod-wide table so a leap node's target is explainable from here; edited elsewhere. */
    private void buildOverridesSection(List<AbstractWidget> w, int x, int[] y, int width) {
        header(w, x, y, width, "Class Overrides");
        label(w, x, y, width, "§7Leap nodes with a class modifier pick whoever this table says has that class (then the tab list). Edit it in Dungeon > Class Overrides.");
        Map<String, DungeonClass> all;
        try {
            all = ClassOverrides.all();
        } catch (Exception e) {
            all = Map.of();
        }
        if (all == null || all.isEmpty()) {
            label(w, x, y, width, "§7No overrides set - every player is the class the tab list shows.");
            return;
        }
        for (Map.Entry<String, DungeonClass> e : all.entrySet()) {
            label(w, x, y, width, "  " + ClassOverridesTab.describeOverride(e.getKey(), e.getValue()));
        }
    }

    private void buildKeybindSection(List<AbstractWidget> w, Ap3Config cfg, int x, int[] y, int width) {
        header(w, x, y, width, "AP3 Keybinds");
        label(w, x, y, width, "§7Click a row, press a key. Esc clears. Keys only work in-game, never while a menu or chat is open.");
        for (Action action : Action.values()) {
            w.add(SettingsButtonWidget.builder(keyText(action, cfg.getKeybind(action.id)), btn -> {
                        capturing = action;
                        btn.setMessage(Component.literal(action.label + " Key: §ePress any key..."));
                    }).bounds(x, y[0], width, ROW).build());
            y[0] += ROW + 4;
        }
        y[0] += GAP;
    }

    // ---- text helpers ----

    /** Which gate is closed, or what's happening - BOSS ONLY is the rule that most needs to be visible. */
    private static String statusLine() {
        boolean inBoss = safe(Floor7Tracker::inF7Boss);
        if (!inBoss) {
            return "§7AP3 status: not in the F7/M7 boss. AP3 only ever runs in Phase 3 - nothing arms in clear.";
        }
        if (!safe(Ap3Commands::inP3)) {
            return "§eAP3 status: in boss, waiting for Phase 3.";
        }
        String section = Ap3Commands.sectionName();
        if (safe(Ap3Executor::isRunning)) {
            return "§aAP3 status: Phase 3, " + section + " - chain running.";
        }
        if (safe(Ap3Feature::isEditMode)) {
            return "§eAP3 status: Phase 3, " + section + " - breaker edit mode, right-click blocks to add, shift-right-click to remove.";
        }
        return "§aAP3 status: Phase 3, " + section + " - idle.";
    }

    private Component keyText(Action action, int key) {
        if (capturing == action) {
            return Component.literal(action.label + " Key: §ePress any key...");
        }
        String name = key == KeyUtil.NONE ? "§7Not Set" : "§e" + InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(action.label + " Key: " + name + " §8" + action.command);
    }

    /** The picker's "reset" colour per node type. Mirror any change in the core config's defaults. */
    private static int defaultColor(Ap3Node.Type type) {
        return switch (type) {
            case LINE -> 0xFF00FFFF;
            case AXIS_LINE -> 0xFF00AAFF;
            case WALK -> 0xFFFFFFFF;
            case RUN -> 0xFFFFFF55;
            case LEAP -> 0xFFAA00FF;
            case LEAP_DETECTOR -> 0xFFFF55FF;
            case TERMINAL -> 0xFF55FF55;
            case WAIT -> 0xFF6B4E2E;
            case STOP -> 0xFFFF3333;
            case LOOK -> 0xFFFFAA00;
            case BREAKER -> 0xFFFFA500;
        };
    }

    private static Integer parseMillis(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty() || !t.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void openFolder() {
        try {
            // A fresh install has no folder yet - openPath on a missing directory does nothing at all, silently.
            Path dir = Ap3Store.getInstance().directory();
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

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static void colorButton(List<AbstractWidget> w, int x, int y, int width, String label, int argb, int defaultArgb,
                                    IntConsumer apply) {
        w.add(SettingsButtonWidget.builder(ColorSwatch.label(label, argb), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new ColorPickerScreen(client.screen, label, argb, defaultArgb, picked -> {
                        apply.accept(picked);
                        Ap3Config.getInstance().save();
                    }));
                }).bounds(x, y, width, 20).build());
    }
}

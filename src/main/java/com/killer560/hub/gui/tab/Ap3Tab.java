package com.killer560.hub.gui.tab;

import com.killer560.hub.ap3.Ap3Area;
import com.killer560.hub.ap3.Ap3Chain;
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
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * AP3 settings - automated F7/M7 Phase 3 terminal-section movement. Cheat build only ({@link NewTab}'s cheat
 * block), red headers, collapses to the master toggle while OFF (AutoRoutesTab / LeverAuraTab pattern: an unused
 * feature costs one line).
 * <p>
 * Two views, same tab - the Fast Leap pattern killer560 asked for by name ("on the left half it is the title...,
 * on the right half is an edit button. If you press the edit button then it opens all the settings for that
 * specific" one), applied to nodes after his 2026-09-16 request to "make it easier to edit them":
 * <ul>
 *     <li>the LIST - master toggle; the boss-only / P3 status line; the chain for the section you are in with one
 *     row per node (number, type, modifier, position, Edit, Delete); Start / Stop; breaker edit mode and the
 *     node-adding buttons; Open Folder + Reload for the one shareable chains file; colours; the world-label
 *     settings; the class-override table read-only; one keybind row per command;</li>
 *     <li>the EDITOR - "&lt; Back", then ONE node's own page: move up / down, re-place at your position / look,
 *     the fields its type actually uses (length, width, wait ms, leap target, leap count, breaker blocks), its
 *     colour override, and Delete.</li>
 * </ul>
 * Which view is showing is plain tab state ({@link #editingNode}) plus {@code requestRebuild}, not a separate
 * {@code Screen}, so the mod menu's search, scrolling and tab chrome keep working. The node is held by identity:
 * moving it up or down keeps the page open on it, and the page closes itself the moment the node is no longer in
 * the chain being edited (deleted, cleared, reloaded, or you walked into another section).
 * <p>
 * Every button that changes a chain or the executor goes through {@link Action#run()} or one of
 * {@link Ap3Commands}' public edit entry points - the same path as the chat command - so the GUI can't do
 * anything a command can't, and both print the same single line. Node numbers are 1-based everywhere here
 * ({@link Ap3Chain#numberOf}); the number on a row IS the number {@code /ap3 delete <n>} / {@code /ap3 move <n>}
 * want.
 */
public class Ap3Tab extends BaseTab implements KeyCaptureTab {

    private static final int BTN_W = 220;
    private static final int ROW = 18;
    private static final int GAP = 6;
    private static final int BACK_W = 76;
    private static final int EDIT_W = 44;
    private static final int DEL_W = 56;

    /** Which keybind row is waiting for a key, or null. */
    private Action capturing;
    /** Text in the wait-ms box; kept on the tab so a rebuild doesn't wipe a half-typed number. */
    private String waitText = Integer.toString(Ap3Commands.getPendingWaitMillis());
    /** null = showing the list; otherwise the node whose edit page is open (held by identity, see the class doc). */
    private Ap3Node editingNode;

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
        if (!cfg.isEnabledRaw()) {
            // master switch went off (here or anywhere else) - a node's page would be dead controls
            editingNode = null;
        }
        if (editingNode != null) {
            Ap3Chain chain = safeChain();
            if (chain == null || !chain.contains(editingNode)) {
                // deleted / cleared / reloaded / different section - the page has nothing to edit any more
                editingNode = null;
            } else {
                return buildEditor(chain, editingNode, contentX, contentY, contentWidth, requestRebuild);
            }
        }
        return buildList(cfg, contentX, contentY, contentWidth, requestRebuild);
    }

    // ---------------------------------------------------------------- list view

    private List<AbstractWidget> buildList(Ap3Config cfg, int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        int[] y = {contentY};
        int half = (contentWidth - GAP) / 2;

        header(w, contentX, y, contentWidth, "AP3");
        toggle(w, contentX, y, "AP3", cfg::isEnabledRaw, cfg::setEnabled, requestRebuild);
        if (!cfg.isEnabledRaw()) {
            return w;
        }
        label(w, contentX, y, contentWidth, statusLine());

        header(w, contentX, y, contentWidth, "Movement");
        // These had no control at all and were reachable only by hand-editing the JSON - including the
        // 45-degree walk killer560 specifically asked for (2026-09-16 review).
        toggle(w, contentX, y, "45° Walk Angle", cfg::isDiagonalWalk, cfg::setDiagonalWalk, null);
        // Label kept (it is the tooltip key and the settings key stays "continueIntoNextSection"); it now means the
        // next AREA - P1 -> P2 -> S1.. -> P4 -> P5 - not only the next P3 section.
        toggle(w, contentX, y, "Continue Into Next Section", cfg::isContinueIntoNextSection,
                cfg::setContinueIntoNextSection, null);
        toggle(w, contentX, y, "Chat Feedback", cfg::isChatFeedback, cfg::setChatFeedback, null);

        buildChainSection(w, contentX, y, contentWidth, half, requestRebuild);
        buildAddSection(w, contentX, y, contentWidth, requestRebuild);
        buildFileSection(w, contentX, y, contentWidth, half, requestRebuild);
        buildColourSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        buildLabelSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        buildOverridesSection(w, contentX, y, contentWidth);
        buildKeybindSection(w, cfg, contentX, y, contentWidth);
        return w;
    }

    private void buildChainSection(List<AbstractWidget> w, int x, int[] y, int width, int half, Runnable rebuild) {
        // "Chain:" with a colon so the tooltip key stays "chain" whatever section follows (SettingTooltips cuts at ':').
        header(w, x, y, width, "Chain: " + Ap3Commands.areaName());

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
            label(w, x, y, width, "§7No chain for " + Ap3Commands.areaName() + " yet. Stand where a node goes and use the buttons below or /ap3 add <type>.");
            return;
        }

        int labelW = Math.max(1, width - EDIT_W - DEL_W - GAP * 2);
        for (int i = 0; i < nodes.size(); i++) {
            final int index = i;
            final Ap3Node node = nodes.get(i);
            // Same formatter as /ap3 list - the number on this row IS the number "/ap3 delete <n>" wants.
            String text = "§8" + Ap3Commands.describeNumbered(i, node).replaceFirst(" ", " §f");
            w.add(new StringWidget(x, y[0] + 3, labelW, 12, Component.literal(text), Minecraft.getInstance().font));
            w.add(SettingsButtonWidget.builder(Component.literal("Edit"), btn -> {
                        editingNode = node;
                        rebuild.run();
                    }).bounds(x + labelW + GAP, y[0], EDIT_W, ROW).build());
            w.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        Ap3Commands.delete(index);
                        rebuild.run();
                    }).bounds(x + labelW + GAP + EDIT_W + GAP, y[0], DEL_W, ROW).build());
            y[0] += ROW + 2;
        }
        y[0] += GAP;
    }

    /** The node-adding buttons, three per row, then the wait row (a number box + its own Add button). */
    private void buildAddSection(List<AbstractWidget> w, int x, int[] y, int width, Runnable rebuild) {
        header(w, x, y, width, "Add Node");
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
            Integer parsed = parseInt(text);
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
    }

    private void buildColourSection(List<AbstractWidget> w, Ap3Config cfg, int x, int[] y, int width, int half, Runnable rebuild) {
        header(w, x, y, width, "AP3 Colours");
        toggle(w, x, y, "Uniform Node Colour", cfg::isUniformColor, cfg::setUniformColor, rebuild);
        if (cfg.isUniformColor()) {
            colorButton(w, x, y[0], half, "Chain Colour", cfg.getUniformColorArgb(),
                    Ap3Config.DEFAULT_UNIFORM_COLOR, cfg::setUniformColorArgb, null);
            colorButton(w, x + half + GAP, y[0], half, "Current Node Colour", cfg.getActiveColorArgb(), 0xFFFFFFFF,
                    cfg::setActiveColorArgb, null);
            y[0] += 24;
            return;
        }
        colorButton(w, x, y[0], half, "Current Node Colour", cfg.getActiveColorArgb(), 0xFFFFFFFF, cfg::setActiveColorArgb, null);
        y[0] += 24;
        Ap3Node.Type[] types = Ap3Node.Type.values();
        for (int i = 0; i < types.length; i += 2) {
            Ap3Node.Type a = types[i];
            colorButton(w, x, y[0], half, "AP3 " + Ap3Commands.typeName(a) + " Colour", cfg.getNodeColorArgb(a), defaultColor(a),
                    argb -> cfg.setNodeColorArgb(a, argb), null);
            if (i + 1 < types.length) {
                Ap3Node.Type b = types[i + 1];
                colorButton(w, x + half + GAP, y[0], half, "AP3 " + Ap3Commands.typeName(b) + " Colour", cfg.getNodeColorArgb(b), defaultColor(b),
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
        toggle(w, x, y, "Show Node Labels", cfg::isShowLabels, cfg::setShowLabels, rebuild);
        if (!cfg.isShowLabels()) {
            return;
        }
        int colW = (width - GAP * 2) / 3;
        toggleCell(w, x, y[0], colW, "Show Node Numbers", cfg::isShowNodeNumbers, cfg::setShowNodeNumbers);
        toggleCell(w, x + colW + GAP, y[0], colW, "Show Node Type", cfg::isShowNodeType, cfg::setShowNodeType);
        toggleCell(w, x + (colW + GAP) * 2, y[0], Math.max(1, width - (colW + GAP) * 2), "Show Node Details",
                cfg::isShowNodeDetails, cfg::setShowNodeDetails);
        y[0] += ROW + GAP;

        // "toggleable for color": the node's own colour (so the number matches its box) or one fixed colour.
        w.add(SettingsButtonWidget.builder(labelColourModeText(cfg), btn -> {
                    cfg.setLabelUseNodeColor(!cfg.isLabelUseNodeColor());
                    cfg.save();
                    rebuild.run();
                }).bounds(x, y[0], half, 20).build());
        if (!cfg.isLabelUseNodeColor()) {
            colorButton(w, x + half + GAP, y[0], half, "Fixed Label Colour", cfg.getLabelColorArgb(),
                    Ap3Config.DEFAULT_LABEL_COLOR, cfg::setLabelColorArgb, null);
        }
        y[0] += 24;

        w.add(slider(x, y[0], half, labelScaleText(cfg), Ap3Config.MIN_LABEL_SCALE, Ap3Config.MAX_LABEL_SCALE,
                cfg.getLabelScale(), 0.05, v -> cfg.setLabelScale((float) v), () -> labelScaleText(cfg), cfg::save));
        w.add(slider(x + half + GAP, y[0], half, labelHeightText(cfg), Ap3Config.MIN_LABEL_HEIGHT, Ap3Config.MAX_LABEL_HEIGHT,
                cfg.getLabelHeightOffset(), 0.05, v -> cfg.setLabelHeightOffset((float) v), () -> labelHeightText(cfg), cfg::save));
        y[0] += ROW + GAP;
    }

    /** Read-only view of the mod-wide table so a leap node's target is explainable from here; edited elsewhere. */
    private void buildOverridesSection(List<AbstractWidget> w, int x, int[] y, int width) {
        header(w, x, y, width, "Class Overrides");
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
        for (Action action : Action.values()) {
            w.add(SettingsButtonWidget.builder(keyText(action, cfg.getKeybind(action.id)), btn -> {
                        capturing = action;
                        btn.setMessage(Component.literal(action.label + " Key: §ePress any key..."));
                    }).bounds(x, y[0], width, ROW).build());
            y[0] += ROW + 4;
        }
        y[0] += GAP;
    }

    // ---------------------------------------------------------------- one node's edit page

    /**
     * ONE node's own page. {@code chain} may be null only for the search scan (a node that is in no chain); every
     * real build passes the chain the node was found in. Nothing here is hidden behind another switch: a field the
     * node's type uses is always reachable from its page.
     */
    private List<AbstractWidget> buildEditor(Ap3Chain chain, Ap3Node node, int contentX, int contentY, int contentWidth,
                                             Runnable rebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        int[] y = {contentY};
        int x = contentX;
        int width = contentWidth;
        int half = (width - GAP) / 2;
        int number = chain == null ? 0 : chain.numberOf(node);
        int index = number - 1;
        int size = chain == null ? 0 : chain.nodes().size();
        Ap3Config cfg = Ap3Config.getInstance();

        w.add(SettingsButtonWidget.builder(Component.literal("< Back"), btn -> {
                    editingNode = null;
                    rebuild.run();
                }).bounds(x, y[0], Math.min(BACK_W, Math.max(1, width)), ROW).build());
        y[0] += ROW + GAP + 2;

        // "Edit Node" stays the header (and tooltip key); the number + type go on their own line.
        header(w, x, y, width, "Edit Node");
        label(w, x, y, width, "§6#" + (number > 0 ? number : "?") + " " + node.type().label()
                + " §7of " + size + " in " + (chain == null ? "no chain" : chain.label()));
        label(w, x, y, width, String.format(Locale.US, "§7At §f%.1f, %.1f, %.1f §7facing §f%.0f°§7 / §f%.0f°",
                node.x(), node.y(), node.z(), node.yaw(), node.pitch()));

        // Order in the chain - the node object keeps its identity across a move, so this page stays open on it.
        SettingsButtonWidget up = SettingsButtonWidget.builder(Component.literal("Move Up"), btn -> {
                    Ap3Commands.move(index, index - 1);
                    rebuild.run();
                }).bounds(x, y[0], half, ROW).build();
        up.active = index > 0;
        w.add(up);
        SettingsButtonWidget down = SettingsButtonWidget.builder(Component.literal("Move Down"), btn -> {
                    Ap3Commands.move(index, index + 1);
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], half, ROW).build();
        down.active = index >= 0 && index < size - 1;
        w.add(down);
        y[0] += ROW + 2;

        // Re-placing keeps the node's number and every modifier - the alternative was delete + add + re-type.
        w.add(SettingsButtonWidget.builder(Component.literal("Move To My Position"), btn -> {
                    Ap3Commands.replace(index, true, true);
                    rebuild.run();
                }).bounds(x, y[0], half, ROW).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Set Look To Mine"), btn -> {
                    Ap3Commands.replace(index, false, true);
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], half, ROW).build());
        y[0] += ROW + GAP;

        buildTypeFields(w, node, x, y, width, half, rebuild);

        // Per-node colour override (the chains file's "colour" field), previously reachable only by hand.
        header(w, x, y, width, "Node Colour");
        Integer override = node.colour();
        colorButton(w, x, y[0], half, "Node Colour", override != null ? override : cfg.colorFor(node),
                cfg.getNodeColorArgb(node.type()), argb -> node.colour = argb, Ap3Feature::saveChains);
        SettingsButtonWidget useType = SettingsButtonWidget.builder(Component.literal("Use Type Colour"), btn -> {
                    node.colour = null;
                    Ap3Feature.saveChains();
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], half, 20).build();
        useType.active = override != null;
        w.add(useType);
        y[0] += 24;

        y[0] += GAP;
        w.add(SettingsButtonWidget.builder(Component.literal("§cDelete Node"), btn -> {
                    Ap3Commands.delete(index);
                    editingNode = null;
                    rebuild.run();
                }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
        return w;
    }

    /** The fields this node's type actually uses - every one saves through {@link Ap3Feature#saveChains()}. */
    private void buildTypeFields(List<AbstractWidget> w, Ap3Node node, int x, int[] y, int width, int half, Runnable rebuild) {
        switch (node.type()) {
            case LINE, AXIS_LINE -> {
                header(w, x, y, width, "Corridor");
                w.add(slider(x, y[0], half, lengthText(node), Ap3Node.MIN_LENGTH, Ap3Node.MAX_LENGTH, node.length(), 0.1,
                        node::setLength, () -> lengthText(node), Ap3Feature::saveChains));
                w.add(slider(x + half + GAP, y[0], half, widthText(node), Ap3Node.MIN_WIDTH, Ap3Node.MAX_WIDTH, node.width(), 0.1,
                        node::setWidth, () -> widthText(node), Ap3Feature::saveChains));
                y[0] += ROW + GAP;
                if (node.type() == Ap3Node.Type.AXIS_LINE) {
                    label(w, x, y, width, String.format(Locale.US, "§7Wall: §f%s §7at §f%.2f §7blocks (re-measured when the node is moved).",
                            node.wallAxis().name().toLowerCase(Locale.ROOT), node.wallDistance()));
                }
            }
            case WALK, RUN -> {
                header(w, x, y, width, "Travel");
                w.add(slider(x, y[0], width, lengthText(node), Ap3Node.MIN_LENGTH, Ap3Node.MAX_LENGTH, node.length(), 0.1,
                        node::setLength, () -> lengthText(node), Ap3Feature::saveChains));
                y[0] += ROW + GAP;
            }
            case WAIT -> {
                header(w, x, y, width, "Wait");
                int boxW = 90;
                w.add(new StringWidget(x, y[0] + 3, 60, 12, Component.literal("Wait ms:"), Minecraft.getInstance().font));
                // "Node Wait ms", not "Wait ms": the Add section's box already owns that tooltip key and describes
                // the NEXT node, which is not what this box edits.
                EditBox ms = new EditBox(Minecraft.getInstance().font, x + 60 + GAP, y[0], boxW, ROW, Component.literal("Node Wait ms"));
                ms.setMaxLength(6);
                ms.setHint(Component.literal("§8ms"));
                ms.setValue(Integer.toString(node.waitMs()));
                ms.setResponder(text -> {
                    Integer parsed = parseInt(text);
                    if (parsed != null && parsed >= Ap3Commands.MIN_WAIT_MS && parsed <= Ap3Commands.MAX_WAIT_MS) {
                        node.setWaitMs(parsed);
                        Ap3Feature.saveChains();
                    }
                });
                w.add(ms);
                y[0] += ROW + GAP;
            }
            case LEAP -> {
                header(w, x, y, width, "Leap Target");
                w.add(SettingsButtonWidget.builder(leapModeText(node), btn -> {
                            Ap3Node.LeapMode[] all = Ap3Node.LeapMode.values();
                            node.leapMode = all[(node.leapMode().ordinal() + 1) % all.length];
                            if (node.leapMode == Ap3Node.LeapMode.CLASS && node.leapClass == null) {
                                node.leapClass = DungeonClass.values()[0];
                            }
                            Ap3Feature.saveChains();
                            rebuild.run();
                        }).bounds(x, y[0], half, ROW).build());
                if (node.leapMode() == Ap3Node.LeapMode.CLASS) {
                    w.add(SettingsButtonWidget.builder(leapClassText(node), btn -> {
                                DungeonClass[] all = DungeonClass.values();
                                DungeonClass c = node.leapClass();
                                // CLASS mode always needs a class, so this cycles through the classes only - never to "none".
                                node.leapClass = c == null ? all[0] : all[(c.ordinal() + 1) % all.length];
                                Ap3Feature.saveChains();
                                btn.setMessage(leapClassText(node));
                            }).bounds(x + half + GAP, y[0], half, ROW).build());
                } else if (node.leapMode() == Ap3Node.LeapMode.IGN) {
                    EditBox ign = new EditBox(Minecraft.getInstance().font, x + half + GAP, y[0], half, ROW, Component.literal("Leap IGN"));
                    ign.setMaxLength(Ap3Store.MAX_IGN);
                    ign.setHint(Component.literal("§8IGN"));
                    ign.setValue(node.leapIgn() == null ? "" : node.leapIgn());
                    ign.setResponder(text -> {
                        String t = text == null ? "" : text.trim();
                        node.leapIgn = t.isEmpty() ? null : t;
                        Ap3Feature.saveChains();
                    });
                    w.add(ign);
                }
                y[0] += ROW + GAP;
            }
            case LEAP_DETECTOR -> {
                header(w, x, y, width, "Leap Detector");
                w.add(slider(x, y[0], width, leapCountText(node), 1, Ap3Node.MAX_LEAP_COUNT, node.leapCount(), 1,
                        v -> node.setLeapCount((int) Math.round(v)), () -> leapCountText(node), Ap3Feature::saveChains));
                y[0] += ROW + GAP;
            }
            case BREAKER -> {
                header(w, x, y, width, "Breaker Blocks");
                int count = node.breakerBlocks().size();
                label(w, x, y, width, "§7" + count + " block" + (count == 1 ? "" : "s") + " of " + Ap3Store.MAX_BREAKER_BLOCKS + ".");
                w.add(SettingsButtonWidget.builder(onOff("Breaker Edit Mode", safe(Ap3Feature::isEditMode)), btn -> {
                            Action.EDIT_BREAKER.run();
                            rebuild.run();
                        }).bounds(x, y[0], half, ROW).build());
                SettingsButtonWidget clearBlocks = SettingsButtonWidget.builder(Component.literal("§cClear Breaker Blocks"), btn -> {
                            node.breakerBlocks().clear();
                            Ap3Feature.saveChains();
                            rebuild.run();
                        }).bounds(x + half + GAP, y[0], half, ROW).build();
                clearBlocks.active = count > 0;
                w.add(clearBlocks);
                y[0] += ROW + GAP;
            }
            default -> {
                // TERMINAL / STOP / LOOK carry nothing beyond position and look, which the buttons above cover.
            }
        }
    }

    // ---------------------------------------------------------------- search

    /** Search has to see every node type's edit page, not just the view that happens to be open (the FastLeapTab
     *  rule) - otherwise "leap target" or "move up" would only be found while such a page was already open. Scans
     *  the list view plus an edit page for a throwaway node of each type. Only ever run from the search field's
     *  responder, never per frame. */
    @Override
    public boolean matchesSearch(String query) {
        if (query.isBlank() || nameMatches(query)) {
            return true;
        }
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return false;
        }
        List<AbstractWidget> scan = new ArrayList<>(buildList(Ap3Config.getInstance(), 0, 0, 200, () -> {}));
        for (Ap3Node.Type type : Ap3Node.Type.values()) {
            Ap3Node probe = new Ap3Node(type, 0, 0, 0, 0f, 0f);
            scan.addAll(buildEditor(null, probe, 0, 0, 200, () -> {}));
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

    /** Which gate is closed, or what's happening - BOSS ONLY is the rule that most needs to be visible. */
    private static String statusLine() {
        if (!safe(Floor7Tracker::inF7Boss)) {
            return "§7AP3 status: not in the F7/M7 boss - nothing arms in clear.";
        }
        if (!safe(Ap3Commands::inBoss)) {
            return "§eAP3 status: in boss, phase not known yet.";
        }
        String where;
        try {
            Ap3Area area = Ap3Feature.currentArea();
            where = area != null ? area.longLabel() : Ap3Feature.currentPhase().name() + " (not inside a section)";
        } catch (Exception e) {
            where = "?";
        }
        // "(p3sim)" so it's visible the gate opened from the sim's own Maxor line / your position, not Goldor's line.
        if (safe(Floor7Tracker::isOnP3Sim)) {
            where += " (p3sim)";
        }
        if (safe(Ap3Executor::isRunning)) {
            return "§aAP3 status: " + where + " - chain running.";
        }
        if (safe(Ap3Feature::isEditMode)) {
            return "§eAP3 status: " + where + " - breaker edit mode, right-click blocks to add, shift-right-click to remove.";
        }
        return "§aAP3 status: " + where + " - idle.";
    }

    private Component keyText(Action action, int key) {
        if (capturing == action) {
            return Component.literal(action.label + " Key: §ePress any key...");
        }
        String name = key == KeyUtil.NONE ? "§7Not Set" : "§e" + InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(action.label + " Key: " + name + " §8" + action.command);
    }

    private static Component labelColourModeText(Ap3Config cfg) {
        return Component.literal("Label Colour: §6" + (cfg.isLabelUseNodeColor() ? "Node's Colour" : "Fixed"));
    }

    private static Component labelScaleText(Ap3Config cfg) {
        return Component.literal(String.format(Locale.US, "Label Scale: %.0f%%", cfg.getLabelScale() * 100));
    }

    private static Component labelHeightText(Ap3Config cfg) {
        return Component.literal(String.format(Locale.US, "Label Height: %.2f", cfg.getLabelHeightOffset()));
    }

    private static Component lengthText(Ap3Node node) {
        return Component.literal(String.format(Locale.US, "Length: %.1f", node.length()));
    }

    private static Component widthText(Ap3Node node) {
        return Component.literal(String.format(Locale.US, "Width: %.1f", node.width()));
    }

    private static Component leapCountText(Ap3Node node) {
        return Component.literal("Leap Count: " + node.leapCount());
    }

    private static Component leapModeText(Ap3Node node) {
        String mode = switch (node.leapMode()) {
            case CLASS -> "Class";
            case IGN -> "IGN";
            default -> "Fast Leap target";
        };
        return Component.literal("Leap Target: §6" + mode);
    }

    private static Component leapClassText(Ap3Node node) {
        return Component.literal("Leap Class: §6" + (node.leapClass() == null ? "?" : node.leapClass().displayName()));
    }

    /** Delegates to the config rather than keeping a second copy - the duplicate table had drifted from
     *  the real defaults for 10 of the 11 node types, so "reset" set a colour that was never the default
     *  (2026-09-16 review). */
    private static int defaultColor(Ap3Node.Type type) {
        return Ap3Config.defaultNodeColor(type);
    }

    private static Integer parseInt(String text) {
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

    /** The chain being edited, or null - never throws (the tracker may not be ready outside a dungeon). */
    private static Ap3Chain safeChain() {
        try {
            return Ap3Feature.currentChain();
        } catch (Exception e) {
            return null;
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

    /** Colour picker button. {@code save} is what persists the picked value - the settings file for a config
     *  colour, {@link Ap3Feature#saveChains()} for a node's own override; null = the settings file. */
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
     *  the same helper PosmsgTab uses, with the save step passed in because node fields and settings persist to
     *  different files. */
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

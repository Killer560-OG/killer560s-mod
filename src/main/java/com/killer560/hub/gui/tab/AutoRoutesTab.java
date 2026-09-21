package com.killer560.hub.gui.tab;

import com.killer560.hub.autoroutes.AutoRoutesCommands;
import com.killer560.hub.autoroutes.AutoRoutesCommands.Action;
import com.killer560.hub.autoroutes.AutoRoutesConfig;
import com.killer560.hub.autoroutes.AutoRoutesFeature;
import com.killer560.hub.autoroutes.RouteExecutor;
import com.killer560.hub.autoroutes.RouteNode;
import com.killer560.hub.autoroutes.RouteRecorder;
import com.killer560.hub.autoroutes.RouteStore;
import com.killer560.hub.gui.ColorPickerScreen;
import com.killer560.hub.gui.ColorSwatch;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.gui.ThemedSliderButton;
import com.killer560.hub.util.KeyUtil;
import com.mojang.blaze3d.platform.InputConstants;
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
 * Auto Routes settings - see {@link AutoRoutesFeature}. Cheat build only ({@link NewTab}'s cheat block), red
 * headers, collapses to the master toggle while OFF (LeverAuraTab / PosmsgTab pattern: an unused feature costs one
 * line).
 * <p>
 * Layout follows killer560's own order (2026-09-16): "Two options at the top. First a toggle for Auto Routes as a
 * whole, second legit or obvious", then the start-node-only option "in the main settings", then recording, the
 * node list for the room you're standing in with delete buttons, colours ("per node type ... or one uniform
 * colour"), QUOI-style render style/thickness/height, and one keybind row per command. The "Open Routes Folder" /
 * "Reload Routes" pair is his later "implement a button to open the routes folder so that way it's very easy for
 * you to share your routes config" - the folder holds the one JSON file all routes live in.
 * <p>
 * Every button that changes a route or the recorder goes through {@link AutoRoutesCommands.Action#run()} - the
 * same path as the chat command - so the GUI can't do anything a command can't, and vice versa.
 */
public class AutoRoutesTab extends BaseTab implements KeyCaptureTab {

    private static final int BTN_W = 220;
    private static final int ROW = 18;
    private static final int GAP = 6;

    /** QUOI's slider ranges, which killer560 asked to match ("Thickness and height sliders like QUOI's"). */
    private static final double MIN_THICKNESS = 1.0;
    private static final double MAX_THICKNESS = 8.0;
    private static final double MIN_HEIGHT = 0.1;
    private static final double MAX_HEIGHT = 1.0;

    /** Which keybind row is waiting for a key, or null. */
    private Action capturing;

    public AutoRoutesTab() {
        super("Auto Routes");
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
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
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
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        int[] y = {contentY};
        int half = (contentWidth - GAP) / 2;

        // Tab description moved into the "Auto Routes" tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        header(w, contentX, y, contentWidth, "Auto Routes");
        toggle(w, contentX, y, "Auto Routes", cfg::isEnabledRaw, cfg::setEnabled, requestRebuild);
        if (!cfg.isEnabledRaw()) {
            return w;
        }

        // "second legit or obvious - in legit mode it rotates for every etherwarp" (killer560). Legit also forces
        // start-node-only on, so flipping it rebuilds to show that row locked.
        w.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    cfg.setLegitMode(!cfg.isLegitMode());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], BTN_W, 20).build());
        y[0] += 24;
        // Legit/Obvious explanation already covered by the "Mode" tooltip.

        SettingsButtonWidget startOnly = SettingsButtonWidget.builder(
                cfg.isLegitMode()
                        ? Component.literal("Start From Start Node Only: §aON §7(forced in Legit)")
                        : onOff("Start From Start Node Only", cfg.isStartFromStartNodeOnly()),
                btn -> {
                    cfg.setStartFromStartNodeOnly(!cfg.isStartFromStartNodeOnly());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], BTN_W, 20).build();
        // Interlock #6 in the spec: forced on in legit mode. Greyed rather than hidden so the user can see WHY
        // the route won't pick up halfway through.
        startOnly.active = !cfg.isLegitMode();
        w.add(startOnly);
        y[0] += 24;
        // Already covered by the "Start From Start Node Only" tooltip.

        w.add(SettingsButtonWidget.builder(onOff("Allow Command Nodes", cfg.isAllowCommandNodes()), btn -> {
                    cfg.setAllowCommandNodes(!cfg.isAllowCommandNodes());
                    cfg.save();
                    btn.setMessage(onOff("Allow Command Nodes", cfg.isAllowCommandNodes()));
                }).bounds(contentX, y[0], BTN_W, 20).build());
        y[0] += 24;
        // Command-injection warning already covered by the "Allow Command Nodes" tooltip.

        buildRecordingSection(w, contentX, y, contentWidth, half, requestRebuild);
        buildNodesSection(w, contentX, y, contentWidth, requestRebuild);
        buildRoutesFileSection(w, contentX, y, contentWidth, half, requestRebuild);
        buildColourSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        buildRenderSection(w, cfg, contentX, y, contentWidth, half);
        buildKeybindSection(w, cfg, contentX, y, contentWidth);
        return w;
    }

    private void buildRecordingSection(List<AbstractWidget> w, int x, int[] y, int width, int half, Runnable rebuild) {
        header(w, x, y, width, "Recording");
        label(w, x, y, width, statusLine());

        boolean recording = safe(RouteRecorder::isRecording);
        SettingsButtonWidget start = SettingsButtonWidget.builder(Component.literal("Start Recording"), btn -> {
                    Action.START_RECORD.run();
                    rebuild.run();
                }).bounds(x, y[0], half, 20).build();
        start.active = !recording;
        w.add(start);
        SettingsButtonWidget stop = SettingsButtonWidget.builder(Component.literal("Stop Recording"), btn -> {
                    Action.STOP_RECORD.run();
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], half, 20).build();
        stop.active = recording;
        w.add(stop);
        y[0] += 24;

        // No "Stop Route" button here on purpose: RouteExecutor stops on ANY screen opening, this one
        // included, so a route can never still be running by the time this tab draws. The button was
        // unreachable and its tooltip described something nobody could ever see (2026-09-16 review).
        // Usage note already covered by the "Recording" tooltip.
    }

    private void buildNodesSection(List<AbstractWidget> w, int x, int[] y, int width, Runnable rebuild) {
        // "Nodes:" with a colon so the tooltip key stays "nodes" whatever room name follows (SettingTooltips cuts at ':').
        header(w, x, y, width, "Nodes: " + AutoRoutesCommands.roomName());

        List<RouteNode> nodes;
        try {
            nodes = new ArrayList<>(AutoRoutesFeature.currentRouteNodes());
        } catch (Exception e) {
            nodes = List.of();
        }

        int colW = (width - GAP * 2) / 3;
        w.add(SettingsButtonWidget.builder(onOff("Edit Breaker Blocks", safe(AutoRoutesFeature::isEditMode)), btn -> {
                    Action.EDIT_BREAKER.run();
                    rebuild.run();
                }).bounds(x, y[0], colW, ROW).build());
        w.add(SettingsButtonWidget.builder(Component.literal("List In Chat"), btn -> Action.LIST.run())
                .bounds(x + colW + GAP, y[0], colW, ROW).build());
        SettingsButtonWidget clear = SettingsButtonWidget.builder(Component.literal("§cClear Route"), btn -> {
                    Action.CLEAR.run();
                    rebuild.run();
                }).bounds(x + (colW + GAP) * 2, y[0], Math.max(1, width - (colW + GAP) * 2), ROW).build();
        clear.active = !nodes.isEmpty();
        w.add(clear);
        y[0] += ROW + GAP;

        if (nodes.isEmpty()) {
            label(w, x, y, width, "§7No route here yet. Record one, or /ar add ew / breaker / use / walk / boom / await / start.");
            return;
        }
        // Already covered by the "Edit Breaker Blocks" tooltip.

        int delW = 60;
        int labelW = Math.max(1, width - delW - GAP);
        for (int i = 0; i < nodes.size(); i++) {
            final int index = i;
            String text = "§8#" + (i + 1) + " §f" + AutoRoutesCommands.describe(nodes.get(i));
            w.add(new StringWidget(x, y[0] + 3, labelW, 12, Component.literal(text), Minecraft.getInstance().font));
            w.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        AutoRoutesCommands.delete(index);
                        rebuild.run();
                    }).bounds(x + labelW + GAP, y[0], delW, ROW).build());
            y[0] += ROW + 2;
        }
        y[0] += GAP;
    }

    /**
     * "implement a button to open the routes folder so that way it's very easy for you to share your routes config.
     * There should also be ar reload command reload it while already in game" (killer560, 2026-09-16), and then
     * "I am fine with it being a json file as long as it is easy to edit in notepad and share really easily. I
     * should only have to share one file."
     */
    private void buildRoutesFileSection(List<AbstractWidget> w, int x, int[] y, int width, int half, Runnable rebuild) {
        header(w, x, y, width, "Routes File");
        w.add(SettingsButtonWidget.builder(Component.literal("Open Routes Folder"), btn -> openRoutesFolder())
                .bounds(x, y[0], half, 20).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Reload Routes"), btn -> {
                    Action.RELOAD.run();
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], half, 20).build());
        y[0] += 24;
        // Filename and share/reload instructions already covered by the "Open Routes Folder" and "Reload Routes" tooltips.
    }

    private void buildColourSection(List<AbstractWidget> w, AutoRoutesConfig cfg, int x, int[] y, int width, int half,
                                    Runnable rebuild) {
        header(w, x, y, width, "Colours");
        // "Colour options per node type (superboom nodes red, bat nodes bat-coloured, etc.) or one uniform colour."
        toggle(w, x, y, "Uniform Colour", cfg::isUniformColor, cfg::setUniformColor, rebuild);
        if (cfg.isUniformColor()) {
            colorButton(w, x, y[0], half, "Colour", cfg.getUniformColorArgb(), 0xFF00FFFF, cfg::setUniformColorArgb);
            colorButton(w, x + half + GAP, y[0], half, "Active Node Colour", cfg.getActiveColorArgb(), 0xFFFFFFFF, cfg::setActiveColorArgb);
            y[0] += 24;
            return;
        }
        colorButton(w, x, y[0], half, "Active Node Colour", cfg.getActiveColorArgb(), 0xFFFFFFFF, cfg::setActiveColorArgb);
        y[0] += 24;
        RouteNode.Type[] types = RouteNode.Type.values();
        for (int i = 0; i < types.length; i += 2) {
            RouteNode.Type a = types[i];
            colorButton(w, x, y[0], half, AutoRoutesCommands.typeName(a) + " Colour", cfg.getNodeColorArgb(a), defaultColor(a),
                    argb -> cfg.setNodeColorArgb(a, argb));
            if (i + 1 < types.length) {
                RouteNode.Type b = types[i + 1];
                colorButton(w, x + half + GAP, y[0], half, AutoRoutesCommands.typeName(b) + " Colour", cfg.getNodeColorArgb(b), defaultColor(b),
                        argb -> cfg.setNodeColorArgb(b, argb));
            }
            y[0] += 24;
        }
    }

    private void buildRenderSection(List<AbstractWidget> w, AutoRoutesConfig cfg, int x, int[] y, int width, int half) {
        header(w, x, y, width, "Render Style");
        // Cycles Box / Filled Box / Cylinder (QUOI's three) - whatever the config enum declares, in its order.
        w.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    AutoRoutesConfig.RenderStyle[] all = AutoRoutesConfig.RenderStyle.values();
                    cfg.setRenderStyle(all[(cfg.getRenderStyle().ordinal() + 1) % all.length]);
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(x, y[0], BTN_W, 20).build());
        y[0] += 24;
        slider(w, x, y[0], half, () -> String.format(Locale.US, "Thickness: %.1f", cfg.getThickness()),
                (cfg.getThickness() - MIN_THICKNESS) / (MAX_THICKNESS - MIN_THICKNESS),
                v -> cfg.setThickness((float) (Math.round((MIN_THICKNESS + v * (MAX_THICKNESS - MIN_THICKNESS)) * 2.0) / 2.0)));
        slider(w, x + half + GAP, y[0], half, () -> String.format(Locale.US, "Height: %.1f", cfg.getHeight()),
                (cfg.getHeight() - MIN_HEIGHT) / (MAX_HEIGHT - MIN_HEIGHT),
                v -> cfg.setHeight((float) (Math.round((MIN_HEIGHT + v * (MAX_HEIGHT - MIN_HEIGHT)) * 10.0) / 10.0)));
        y[0] += 24;
    }

    private void buildKeybindSection(List<AbstractWidget> w, AutoRoutesConfig cfg, int x, int[] y, int width) {
        header(w, x, y, width, "Keybinds");
        // Already covered by the "Keybinds" tooltip.
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

    private static String statusLine() {
        if (safe(RouteRecorder::isRecording)) {
            return "§aRecording " + AutoRoutesCommands.roomName() + "... §7Stop when you've finished the room.";
        }
        if (safe(AutoRoutesFeature::isEditMode)) {
            return "§eBreaker edit mode - right-click blocks to add, shift-right-click to remove.";
        }
        return "§7Idle - " + AutoRoutesCommands.roomName();
    }

    private static Component modeText(AutoRoutesConfig cfg) {
        return Component.literal("Mode: " + (cfg.isLegitMode() ? "§aLegit" : "§cObvious"));
    }

    private static Component styleText(AutoRoutesConfig cfg) {
        return Component.literal("Style: §e" + prettyEnum(cfg.getRenderStyle().name()));
    }

    private Component keyText(Action action, int key) {
        if (capturing == action) {
            return Component.literal(action.label + " Key: §ePress any key...");
        }
        String name = key == KeyUtil.NONE ? "§7Not Set" : "§e" + InputConstants.Type.KEYSYM.getOrCreate(key).getDisplayName().getString();
        return Component.literal(action.label + " Key: " + name + " §8" + action.command);
    }

    private static String prettyEnum(String constant) {
        String[] words = constant.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /** The picker's "reset" colour per node type - killer560's examples: "superboom nodes red, bat nodes
     *  bat-coloured" (a Spirit Sceptre use node, so a dark bat-purple). Mirror any change in the core config. */
    /** Delegates rather than keeping a second table - the duplicate had drifted from the shipped defaults
     *  for seven node types, so "reset" set a colour that was never the default (2026-09-16 review). */
    private static int defaultColor(RouteNode.Type type) {
        return AutoRoutesConfig.defaultNodeColor(type);
    }

    private static void openRoutesFolder() {
        try {
            // A fresh install has no folder yet - openPath on a missing directory does nothing at all, silently.
            Path dir = RouteStore.routesDirectory();
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

    // ---- widget helpers (LeverAuraTab pattern) ----

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
            AutoRoutesConfig.getInstance().save();
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
                        AutoRoutesConfig.getInstance().save();
                    }));
                }).bounds(x, y, width, 20).build());
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               DoubleConsumer apply) {
        w.add(new ThemedSliderButton(x, y, width, 20, Component.literal(text.get()), Math.max(0.0, Math.min(1.0, normalized))) {
            @Override
            protected void updateMessage() {
                setMessage(Component.literal(text.get()));
            }

            @Override
            protected void applyValue() {
                apply.accept(this.value);
                AutoRoutesConfig.getInstance().save();
            }
        });
    }
}

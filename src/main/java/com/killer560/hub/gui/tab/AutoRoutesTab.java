package com.killer560.hub.gui.tab;

import com.killer560.hub.util.ExternalOpen;
import com.killer560.hub.autoroutes.AutoRoutesCommands;
import com.killer560.hub.autoroutes.AutoRoutesCommands.Action;
import com.killer560.hub.autoroutes.AutoRoutesConfig;
import com.killer560.hub.autoroutes.AutoRoutesFeature;
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
import com.killer560.hub.compat.McCompat;

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
 * <p>
 * Re-laid out 2026-10-04 (killer560): Render Style is the first section under the Auto Routes header; Recording
 * keeps its Start / Stop keys under its buttons; both dropdowns are {@link CollapsibleSection}s, closed by default and
 * remembered, the same as AP3's. "Allow Command Nodes" is gone: command nodes always run.
 * <p>
 * And again the same day ("move the colors above the keybind section. Move the open routes folder near the top.
 * Remove the nodes: ___ section entirely ... Clean up that menu as a whole"), in AP3's house style - full-width master
 * switch, then even two-column rows, every row 20 high on a 24 step, the right column taking whatever the left
 * leaves so both edges line up:
 * <pre>
 *   Auto Routes: ON
 *   Mode | Start From Start Node Only
 *   Run While Map Open
 *   Open Routes Folder | Reload Routes
 *   Render Style:  Style | Show Node Numbers,  Thickness | Height
 *   Recording:     Start | Stop,  Start Key | Stop Key
 *   Colours (dropdown)
 *   Keybinds (dropdown)
 * </pre>
 * The per-room node list is gone from the menu; {@code /ar list}, {@code /ar delete}, {@code /ar undo} and
 * {@code /ar clear} (and their keybinds) still do all of it.
 */
public class AutoRoutesTab extends BaseTab implements KeyCaptureTab {

    private static final int GAP = 6;

    /** QUOI's slider ranges, which killer560 asked to match ("Thickness and height sliders like QUOI's"). */
    private static final double MIN_THICKNESS = 1.0;
    private static final double MAX_THICKNESS = 8.0;
    private static final double MIN_HEIGHT = 0.1;
    private static final double MAX_HEIGHT = 1.0;

    /** Which keybind row is waiting for a key, or null. */
    private Action capturing;
    /** Set only while {@link #matchesSearch} builds: the collapsible sections are laid out open so search can see
     *  the settings in them without forcing them open on screen (AP3's rule). */
    private boolean scanningForSearch;
    /** Bound under the Recording buttons, so the Keybinds dropdown leaves them out; and the breaker edit row, whose
     *  button left the old Nodes section (the /ar edit db command still works). */
    private static final java.util.Set<Action> NOT_IN_KEYBIND_LIST =
            java.util.EnumSet.of(Action.START_RECORD, Action.STOP_RECORD, Action.EDIT_BREAKER);

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

        int right = Math.max(1, contentWidth - half - GAP);

        // Tab description moved into the "Auto Routes" tooltip (mod-wide in-panel-paragraph cleanup, 2026-09-21).
        header(w, contentX, y, contentWidth, "Auto Routes");
        // Full width, as AP3's master switch is.
        toggle(w, contentX, y, contentWidth, "Auto Routes", cfg::isEnabledRaw, cfg::setEnabled, requestRebuild);
        if (!cfg.isEnabledRaw()) {
            return w;
        }

        // "second legit or obvious - in legit mode it rotates for every etherwarp" (killer560). Legit also forces
        // start-node-only on, so flipping it rebuilds to show that row locked. Legit/Obvious is explained by the
        // "Mode" tooltip.
        w.add(SettingsButtonWidget.builder(modeText(cfg), btn -> {
                    cfg.setLegitMode(!cfg.isLegitMode());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y[0], half, 20).build());
        SettingsButtonWidget startOnly = SettingsButtonWidget.builder(
                onOff("Start From Start Node Only", cfg.isLegitMode() || cfg.isStartFromStartNodeOnly()),
                btn -> {
                    cfg.setStartFromStartNodeOnly(!cfg.isStartFromStartNodeOnly());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX + half + GAP, y[0], right, 20).build();
        // Interlock #6 in the spec: forced on in legit mode. Greyed rather than hidden so the user can see WHY
        // the route won't pick up halfway through (the "(forced in Legit)" suffix no longer fits a half-width cell;
        // the greyed ON says the same).
        startOnly.active = !cfg.isLegitMode();
        w.add(startOnly);
        y[0] += 24;
        // killer560, 2026-10-05: work while the Interactive Map is open (not while it is warping you).
        toggleCell(w, contentX, y[0], half, "Run While Map Open", cfg::isRunWhileMapOpen, cfg::setRunWhileMapOpen, null);
        // killer560, 2026-10-05: after he clicks a trapped chest, kill the mimic - Off / Hyperion / Spirit Sceptre.
        w.add(SettingsButtonWidget.builder(killMimicText(cfg), btn -> {
                    AutoRoutesConfig.KillMimic[] all = AutoRoutesConfig.KillMimic.values();
                    cfg.setKillMimic(all[(cfg.getKillMimic().ordinal() + 1) % all.length]);
                    cfg.save();
                    btn.setMessage(killMimicText(cfg));
                }).bounds(contentX + half + GAP, y[0], right, 20).build());
        y[0] += 24;
        // killer560, 2026-10-05: what a crypt node attacks with.
        w.add(SettingsButtonWidget.builder(cryptWeaponText(cfg), btn -> {
                    AutoRoutesConfig.CryptWeapon[] all = AutoRoutesConfig.CryptWeapon.values();
                    cfg.setCryptWeapon(all[(cfg.getCryptWeapon().ordinal() + 1) % all.length]);
                    cfg.save();
                    btn.setMessage(cryptWeaponText(cfg));
                }).bounds(contentX, y[0], half, 20).build());
        y[0] += 24;

        // killer560, 2026-10-04: "Move the open routes folder near the top" - where AP3 keeps Open AP3 Folder.
        // Filename and share/reload instructions are in the two buttons' tooltips.
        w.add(SettingsButtonWidget.builder(Component.literal("Open Routes Folder"), btn -> openRoutesFolder())
                .bounds(contentX, y[0], half, 20).build());
        w.add(SettingsButtonWidget.builder(Component.literal("Reload Routes"), btn -> {
                    Action.RELOAD.run();
                    requestRebuild.run();
                }).bounds(contentX + half + GAP, y[0], right, 20).build());
        y[0] += 24;

        buildRenderSection(w, cfg, contentX, y, contentWidth, half);
        buildRecordingSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        // killer560, 2026-10-04: "move the colors above the keybind section" - the two dropdowns close the tab.
        buildColourSection(w, cfg, contentX, y, contentWidth, half, requestRebuild);
        buildKeybindSection(w, cfg, contentX, y, contentWidth, requestRebuild);
        return w;
    }

    private void buildRecordingSection(List<AbstractWidget> w, AutoRoutesConfig cfg, int x, int[] y, int width, int half,
                                       Runnable rebuild) {
        header(w, x, y, width, "Recording");

        boolean recording = safe(RouteRecorder::isRecording);
        SettingsButtonWidget start = SettingsButtonWidget.builder(Component.literal("Start Recording"), btn -> {
                    Action.START_RECORD.run();
                    rebuild.run();
                }).bounds(x, y[0], half, 20).build();
        start.active = !recording;
        w.add(start);
        int right = Math.max(1, width - half - GAP);
        SettingsButtonWidget stop = SettingsButtonWidget.builder(Component.literal("Stop Recording"), btn -> {
                    Action.STOP_RECORD.run();
                    rebuild.run();
                }).bounds(x + half + GAP, y[0], right, 20).build();
        stop.active = recording;
        w.add(stop);
        y[0] += 24;
        // killer560, 2026-10-04: "Add keybinds to the stop and start recording buttons" - right under them.
        keybindRow(w, cfg, Action.START_RECORD, x, y[0], half);
        keybindRow(w, cfg, Action.STOP_RECORD, x + half + GAP, y[0], right);
        y[0] += 24;

        // No "Stop Route" button here on purpose: RouteExecutor stops on ANY screen opening, this one
        // included, so a route can never still be running by the time this tab draws. The button was
        // unreachable and its tooltip described something nobody could ever see (2026-09-16 review).
        // Usage note already covered by the "Recording" tooltip.
    }

    private void buildColourSection(List<AbstractWidget> w, AutoRoutesConfig cfg, int x, int[] y, int width, int half,
                                    Runnable rebuild) {
        boolean open = scanningForSearch || cfg.isColorsSectionOpen();
        y[0] = CollapsibleSection.header(w, x, y[0], width, "Colours", true, open, () -> {
            cfg.setColorsSectionOpen(!cfg.isColorsSectionOpen());
            cfg.save();
            rebuild.run();
        });
        if (!open) {
            return;
        }
        // "Colour options per node type (superboom nodes red, bat nodes bat-coloured, etc.) or one uniform colour."
        int right = Math.max(1, width - half - GAP);
        // The switch and the always-there Active Node Colour share the first row.
        toggleCell(w, x, y[0], half, "Uniform Colour", cfg::isUniformColor, cfg::setUniformColor, rebuild);
        colorButton(w, x + half + GAP, y[0], right, "Active Node Colour", cfg.getActiveColorArgb(), 0xFFFFFFFF, cfg::setActiveColorArgb);
        y[0] += 24;
        if (cfg.isUniformColor()) {
            colorButton(w, x, y[0], width, "Colour", cfg.getUniformColorArgb(), 0xFF00FFFF, cfg::setUniformColorArgb);
            y[0] += 24 + GAP;
            return;
        }
        RouteNode.Type[] types = RouteNode.Type.values();
        for (int i = 0; i < types.length; i += 2) {
            RouteNode.Type a = types[i];
            colorButton(w, x, y[0], half, AutoRoutesCommands.typeName(a) + " Colour", cfg.getNodeColorArgb(a), defaultColor(a),
                    argb -> cfg.setNodeColorArgb(a, argb));
            if (i + 1 < types.length) {
                RouteNode.Type b = types[i + 1];
                colorButton(w, x + half + GAP, y[0], right, AutoRoutesCommands.typeName(b) + " Colour", cfg.getNodeColorArgb(b), defaultColor(b),
                        argb -> cfg.setNodeColorArgb(b, argb));
            }
            y[0] += 24;
        }
        y[0] += GAP;
    }

    private void buildRenderSection(List<AbstractWidget> w, AutoRoutesConfig cfg, int x, int[] y, int width, int half) {
        header(w, x, y, width, "Render Style");
        int right = Math.max(1, width - half - GAP);
        // Cycles Box / Filled Box / Cylinder (QUOI's three) - whatever the config enum declares, in its order.
        w.add(SettingsButtonWidget.builder(styleText(cfg), btn -> {
                    AutoRoutesConfig.RenderStyle[] all = AutoRoutesConfig.RenderStyle.values();
                    cfg.setRenderStyle(all[(cfg.getRenderStyle().ordinal() + 1) % all.length]);
                    cfg.save();
                    btn.setMessage(styleText(cfg));
                }).bounds(x, y[0], half, 20).build());
        // AP3's "Show Node Numbers": the number over each node is the one /ar delete and /ar remove take.
        toggleCell(w, x + half + GAP, y[0], right, "Show Node Numbers", cfg::isShowNodeNumbers, cfg::setShowNodeNumbers, null);
        y[0] += 24;
        slider(w, x, y[0], half, () -> String.format(Locale.US, "Thickness: %.1f", cfg.getThickness()),
                (cfg.getThickness() - MIN_THICKNESS) / (MAX_THICKNESS - MIN_THICKNESS),
                v -> cfg.setThickness((float) (Math.round((MIN_THICKNESS + v * (MAX_THICKNESS - MIN_THICKNESS)) * 2.0) / 2.0)));
        slider(w, x + half + GAP, y[0], right, () -> String.format(Locale.US, "Height: %.1f", cfg.getHeight()),
                (cfg.getHeight() - MIN_HEIGHT) / (MAX_HEIGHT - MIN_HEIGHT),
                v -> cfg.setHeight((float) (Math.round((MIN_HEIGHT + v * (MAX_HEIGHT - MIN_HEIGHT)) * 10.0) / 10.0)));
        y[0] += 24;
    }

    private void buildKeybindSection(List<AbstractWidget> w, AutoRoutesConfig cfg, int x, int[] y, int width,
                                     Runnable rebuild) {
        boolean open = scanningForSearch || cfg.isKeybindsSectionOpen();
        y[0] = CollapsibleSection.header(w, x, y[0], width, "Keybinds", true, open, () -> {
            cfg.setKeybindsSectionOpen(!cfg.isKeybindsSectionOpen());
            cfg.save();
            rebuild.run();
        });
        if (!open) {
            return;
        }
        for (Action action : Action.values()) {
            if (NOT_IN_KEYBIND_LIST.contains(action)) {
                continue;
            }
            keybindRow(w, cfg, action, x, y[0], width);
            y[0] += 24;
        }
        y[0] += GAP;
    }

    private void keybindRow(List<AbstractWidget> w, AutoRoutesConfig cfg, Action action, int x, int y, int width) {
        w.add(SettingsButtonWidget.builder(keyText(action, cfg.getKeybind(action.id)), btn -> {
                    capturing = action;
                    btn.setMessage(Component.literal(action.label + " Key: §ePress any key..."));
                }).bounds(x, y, Math.max(1, width), 20).build());
    }

    /** Search has to see the settings inside a collapsed section without opening it on screen, so the scan builds
     *  with both laid out open (Ap3Tab's approach). Only ever run from the search field's responder. */
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
            String text = net.minecraft.ChatFormatting.stripFormatting(widget.getMessage().getString());
            if (text != null && text.toLowerCase(Locale.US).contains(q)) {
                return true;
            }
        }
        return false;
    }

    // ---- text helpers ----

    private static Component modeText(AutoRoutesConfig cfg) {
        return Component.literal("Mode: " + (cfg.isLegitMode() ? "§aLegit" : "§cObvious"));
    }

    private static Component killMimicText(AutoRoutesConfig cfg) {
        AutoRoutesConfig.KillMimic k = cfg.getKillMimic();
        return Component.literal("Kill Mimic: " + (k == AutoRoutesConfig.KillMimic.OFF ? "§c" : "§a") + k.label());
    }

    private static Component cryptWeaponText(AutoRoutesConfig cfg) {
        return Component.literal("Crypt Weapon: §e" + cfg.getCryptWeapon().label());
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
            ExternalOpen.path(dir);
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

    private static void header(List<AbstractWidget> w, int x, int[] y, int width, String text) {
        y[0] += 6;
        w.add(new StringWidget(x, y[0], width, 12, SectionHeaders.header(text, true), Minecraft.getInstance().font));
        y[0] += 16;
    }

    /** A full row ON/OFF toggle; a non-null {@code rebuild} rebuilds the tab (master toggles and anything that
     *  collapses). */
    private static void toggle(List<AbstractWidget> w, int x, int[] y, int width, String name, Supplier<Boolean> getter,
                               Consumer<Boolean> setter, Runnable rebuild) {
        toggleCell(w, x, y[0], width, name, getter, setter, rebuild);
        y[0] += 24;
    }

    /** An ON/OFF toggle in one cell of a row (AP3's {@code toggleCell}); the caller advances the row. */
    private static void toggleCell(List<AbstractWidget> w, int x, int y, int width, String name, Supplier<Boolean> getter,
                                   Consumer<Boolean> setter, Runnable rebuild) {
        w.add(SettingsButtonWidget.builder(onOff(name, getter.get()), btn -> {
            setter.accept(!getter.get());
            AutoRoutesConfig.getInstance().save();
            if (rebuild != null) {
                rebuild.run();
            } else {
                btn.setMessage(onOff(name, getter.get()));
            }
        }).bounds(x, y, Math.max(1, width), 20).build());
    }

    private static Component onOff(String name, boolean on) {
        return Component.literal(name + ": " + (on ? "§aON" : "§cOFF"));
    }

    private static void colorButton(List<AbstractWidget> w, int x, int y, int width, String label, int argb, int defaultArgb,
                                    IntConsumer apply) {
        w.add(SettingsButtonWidget.builder(ColorSwatch.label(label, argb), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new ColorPickerScreen(McCompat.screen(client), label, argb, defaultArgb, picked -> {
                        apply.accept(picked);
                        AutoRoutesConfig.getInstance().save();
                    }));
                }).bounds(x, y, Math.max(1, width), 20).build());
    }

    private static void slider(List<AbstractWidget> w, int x, int y, int width, Supplier<String> text, double normalized,
                               DoubleConsumer apply) {
        w.add(new ThemedSliderButton(x, y, Math.max(1, width), 20, Component.literal(text.get()), Math.max(0.0, Math.min(1.0, normalized))) {
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

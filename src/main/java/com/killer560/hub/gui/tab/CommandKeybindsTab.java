package com.killer560.hub.gui.tab;

import com.killer560.hub.commandkeybinds.CommandKeybindsConfig;
import com.killer560.hub.commandshortcuts.CommandShortcutsConfig;
import com.killer560.hub.commandshortcuts.CommandShortcutsConfig.CustomShortcut;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature.Group;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat Keybinds - see {@link com.killer560.hub.commandkeybinds.CommandKeybindsFeature}. Since 2026-10-08 it is also
 * Command Shortcuts (killer560: "Chat keybinds and command shortcuts should be combined into just chat keybinds."):
 * one master switch, then four sections:
 * <ul>
 *   <li>Key Binds: one row per bind - on/off, the key (or mouse button), the exact line it sends, Delete. The old 8
 *       fixed menu binds are migrated into rows by {@link CommandKeybindsConfig}.</li>
 *   <li>Catacombs / Master Mode and Kuudra: one full-width on/off row per built-in {@link Group}, just its name and state;
 *       what it sends is in its tooltip (killer560, 2026-10-07: "Remove all that extra text next to f0 f1 f2 command
 *       shortcuts and the text explaining them below it.").</li>
 *   <li>Custom Shortcuts (killer560, 2026-10-07: "There should be a custom section on command shortcuts."): on/off, a key,
 *       the name he types (no slash), the command it sends, Delete. A name is only saved when
 *       {@link CommandShortcutsFeature#nameProblem} accepts it; a refused one stays in its box in red with the reason on
 *       the line under the list. Changes register live. The key is optional and sends the same command.</li>
 * </ul>
 */
public class CommandKeybindsTab extends BaseTab implements KeyCaptureTab {

    private static final List<Group> CATACOMBS = List.of(
            Group.CATA_F0, Group.CATA_F1, Group.CATA_F2, Group.CATA_F3,
            Group.CATA_F4, Group.CATA_F5, Group.CATA_F6, Group.CATA_F7);
    private static final List<Group> KUUDRA = List.of(
            Group.KUUDRA_BASIC, Group.KUUDRA_HOT, Group.KUUDRA_BURNING,
            Group.KUUDRA_FIERY, Group.KUUDRA_INFERNAL);

    /** EditBox's own default text color (javap, 26.1.2 and 26.2: {@code textColor = -2039584}). */
    private static final int TEXT = 0xFFE0E0E0;
    private static final int REFUSED = 0xFFFF5555;
    private static final int GAP = 6;
    private static final int TOGGLE_W = 40;
    private static final int DELETE_W = 50;

    /** The row whose key is being captured: a {@link CommandKeybindsConfig.Bind} or a {@link CustomShortcut}. */
    private Object capturing = null;
    /** A name he typed that was refused, kept across rebuilds so the box does not snap back while he fixes it. */
    private final Map<CustomShortcut, String> refusedName = new IdentityHashMap<>();

    public CommandKeybindsTab() {
        super("Chat Keybinds");
    }

    @Override
    public boolean isListeningForKey() {
        return capturing != null;
    }

    @Override
    public void onKeyCaptured(int keyCode) {
        applyCapture(keyCode == InputConstants.KEY_ESCAPE ? -1 : keyCode);
    }

    @Override
    public boolean supportsMouseCapture() {
        return true;
    }

    /** Mouse half of the capture (killer560: "make all of the keybind things compatible with mouse buttons
     *  and middle mouse buttons"). */
    @Override
    public void onMouseCaptured(int button) {
        applyCapture(CommandKeybindsConfig.codeForMouseButton(button));
    }

    private void applyCapture(int code) {
        if (capturing == null) {
            return;
        }
        CommandKeybindsConfig cfg = CommandKeybindsConfig.getInstance();
        if (capturing instanceof CommandKeybindsConfig.Bind bind) {
            bind.key = code;
        } else if (capturing instanceof CustomShortcut c) {
            cfg.shortcuts().setCustomKey(c, code);
        }
        capturing = null;
        cfg.save();
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        CommandKeybindsConfig cfg = CommandKeybindsConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Chat Keybinds", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    CommandShortcutsFeature.customsChanged();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        y = binds(widgets, cfg, contentX, y, contentWidth, requestRebuild);
        y += 6;
        CommandShortcutsConfig sc = cfg.shortcuts();
        y = section(widgets, "Catacombs / Master Mode", CATACOMBS, sc, contentX, y, contentWidth);
        y += 6;
        y = section(widgets, "Kuudra", KUUDRA, sc, contentX, y, contentWidth);
        y += 6;
        custom(widgets, cfg, contentX, y, contentWidth, requestRebuild);
        return widgets;
    }

    /** Key width shared by both kinds of row, so the key column lines up down the whole tab. */
    private static int keyWidth(int contentWidth) {
        return Math.max(60, Math.min(90, contentWidth / 5));
    }

    private int binds(List<AbstractWidget> widgets, CommandKeybindsConfig cfg, int contentX, int y, int contentWidth,
                      Runnable requestRebuild) {
        var font = Minecraft.getInstance().font;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Key Binds", false), font));
        y += 16;
        widgets.add(SettingsButtonWidget.builder(Component.literal("+ Add Bind"), btn -> {
                    cfg.addBind();
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, (contentWidth - GAP) / 2, 18).build());
        y += 24;

        int keyW = keyWidth(contentWidth);
        int textW = contentWidth - TOGGLE_W - keyW - DELETE_W - GAP * 3;
        for (CommandKeybindsConfig.Bind bind : new ArrayList<>(cfg.binds())) {
            int x = contentX;
            widgets.add(SettingsButtonWidget.builder(toggleText(bind.enabled), btn -> {
                        bind.enabled = !bind.enabled;
                        cfg.save();
                        btn.setMessage(toggleText(bind.enabled));
                    }).bounds(x, y, TOGGLE_W, 18).build());
            x += TOGGLE_W + GAP;

            widgets.add(SettingsButtonWidget.builder(keyText(bind, bind.key), btn -> {
                        capturing = bind;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(x, y, keyW, 18).build());
            x += keyW + GAP;

            EditBox textField = new EditBox(font, x, y, textW, 18, Component.literal("Command or message"));
            textField.setMaxLength(200);
            textField.setValue(bind.text);
            textField.setHint(Component.literal("§8/pets  or  gg"));
            textField.setResponder(text -> {
                bind.text = text;
                cfg.save();
            });
            widgets.add(textField);
            x += textW + GAP;

            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        cfg.removeBind(bind);
                        cfg.save();
                        requestRebuild.run();
                    }).bounds(x, y, DELETE_W, 18).build());
            y += 22;
        }

        if (cfg.binds().isEmpty()) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    Component.literal("§7No binds yet - hit \"+ Add Bind\"."), font));
            y += 16;
        }
        return y;
    }

    private int section(List<AbstractWidget> widgets, String title, List<Group> groups,
                        CommandShortcutsConfig sc, int contentX, int y, int contentWidth) {
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y += 16;
        for (Group g : groups) {
            widgets.add(SettingsButtonWidget.builder(onOff(g.label, sc.isGroupOn(g)), btn -> {
                        sc.setGroupOn(g, !sc.isGroupOn(g));
                        sc.save();
                        btn.setMessage(onOff(g.label, sc.isGroupOn(g)));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 21;
        }
        return y;
    }

    private void custom(List<AbstractWidget> widgets, CommandKeybindsConfig cfg, int contentX, int y,
                        int contentWidth, Runnable requestRebuild) {
        var font = Minecraft.getInstance().font;
        CommandShortcutsConfig sc = cfg.shortcuts();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header("Custom Shortcuts", false), font));
        y += 16;

        refusedName.keySet().retainAll(sc.customs());
        SettingsButtonWidget add = SettingsButtonWidget.builder(Component.literal("+ Add Shortcut"), btn -> {
                    if (sc.addCustom() != null) {
                        cfg.save();
                        requestRebuild.run();
                    }
                }).bounds(contentX, y, (contentWidth - GAP) / 2, 18).build();
        add.active = sc.customs().size() < CommandShortcutsConfig.MAX_CUSTOMS;
        widgets.add(add);
        y += 24;

        int keyW = keyWidth(contentWidth);
        int nameW = Math.max(60, Math.min(96, contentWidth / 5));
        int commandW = contentWidth - TOGGLE_W - keyW - nameW - DELETE_W - GAP * 4;
        // Built after the rows (its y is below them); the rows' responders reach it through this holder.
        StringWidget[] status = new StringWidget[1];
        String firstProblem = null;
        for (CustomShortcut c : new ArrayList<>(sc.customs())) {
            int x = contentX;
            widgets.add(SettingsButtonWidget.builder(toggleText(c.isEnabled()), btn -> {
                        sc.setCustomEnabled(c, !c.isEnabled());
                        cfg.save();
                        CommandShortcutsFeature.customsChanged();
                        btn.setMessage(toggleText(c.isEnabled()));
                    }).bounds(x, y, TOGGLE_W, 18).build());
            x += TOGGLE_W + GAP;

            widgets.add(SettingsButtonWidget.builder(keyText(c, c.key()), btn -> {
                        capturing = c;
                        btn.setMessage(Component.literal("Press any key..."));
                    }).bounds(x, y, keyW, 18).build());
            x += keyW + GAP;

            EditBox nameField = new EditBox(font, x, y, nameW, 18, Component.literal("Shortcut Name"));
            nameField.setMaxLength(CommandShortcutsConfig.MAX_CUSTOM_NAME);
            String pending = refusedName.get(c);
            nameField.setValue(pending != null ? pending : c.name());
            nameField.setHint(Component.literal("§8name"));
            String problem = pending != null ? CommandShortcutsFeature.nameProblem(pending, c) : standingProblem(c);
            nameField.setTextColor(problem != null ? REFUSED : TEXT);
            if (problem != null && firstProblem == null) {
                firstProblem = problem;
            }
            nameField.setResponder(text -> {
                String refused = sc.setCustomName(c, text.isEmpty() ? "" : text);
                if (refused == null) {
                    refusedName.remove(c);
                    nameField.setTextColor(TEXT);
                    status[0].setMessage(Component.empty());
                    cfg.save();
                    CommandShortcutsFeature.customsChanged();
                } else {
                    refusedName.put(c, text);
                    nameField.setTextColor(REFUSED);
                    status[0].setMessage(Component.literal("§c" + refused));
                }
            });
            widgets.add(nameField);
            x += nameW + GAP;

            EditBox commandField = new EditBox(font, x, y, commandW, 18, Component.literal("Sends Command"));
            commandField.setMaxLength(CommandShortcutsConfig.MAX_CUSTOM_COMMAND);
            commandField.setValue(c.command());
            commandField.setHint(Component.literal("§8/warp dh"));
            commandField.setResponder(text -> {
                sc.setCustomCommand(c, text);
                cfg.save();
                CommandShortcutsFeature.customsChanged();
            });
            widgets.add(commandField);
            x += commandW + GAP;

            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        sc.removeCustom(c);
                        refusedName.remove(c);
                        if (capturing == c) {
                            capturing = null;
                        }
                        cfg.save();
                        CommandShortcutsFeature.customsChanged();
                        requestRebuild.run();
                    }).bounds(x, y, DELETE_W, 18).build());
            y += 22;
        }
        status[0] = new StringWidget(contentX, y, contentWidth, 12,
                firstProblem == null ? Component.empty() : Component.literal("§c" + firstProblem), font);
        widgets.add(status[0]);
    }

    /** A saved name that should be registered and is not, because something else took the word (found at join). */
    private static String standingProblem(CustomShortcut c) {
        if (c.name().isEmpty() || !CommandShortcutsFeature.isLive(c) || CommandShortcutsFeature.isRegistered(c.name())) {
            return null;
        }
        return CommandShortcutsFeature.nameProblem(c.name(), c);
    }

    private static Component toggleText(boolean on) {
        return Component.literal(on ? "§aON" : "§cOFF");
    }

    private Component keyText(Object row, int code) {
        if (capturing == row) {
            return Component.literal("Press any key...");
        }
        return Component.literal("Key: " + bindName(code));
    }

    /** Display name for a keyboard code or a stored mouse code (see {@code CommandKeybindsConfig}). */
    public static String bindName(int code) {
        if (code == -1) {
            return "Not Set";
        }
        return CommandKeybindsConfig.isMouseCode(code)
                ? InputConstants.Type.MOUSE.getOrCreate(CommandKeybindsConfig.mouseButton(code))
                        .getDisplayName().getString()
                : InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName().getString();
    }
}

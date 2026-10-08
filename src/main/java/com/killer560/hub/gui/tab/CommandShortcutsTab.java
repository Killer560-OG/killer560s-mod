package com.killer560.hub.gui.tab;

import com.killer560.hub.commandshortcuts.CommandShortcutsConfig;
import com.killer560.hub.commandshortcuts.CommandShortcutsConfig.CustomShortcut;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature.Group;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
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
 * Command Shortcuts (killer560's item 8.3): one full-width on/off row per {@link Group} - just its name and state.
 * What each one sends is in its hover tooltip ({@code SettingTooltipsData}); the text that used to sit beside every
 * row and the note under them were removed on 2026-10-07 (killer560: "Remove all that extra text next to f0 f1 f2
 * command shortcuts and the text explaining them below it.").
 * <p>
 * Below them, the Custom section (killer560, 2026-10-07: "There should be a custom section on command shortcuts."),
 * laid out like {@link CommandKeybindsTab}: one row per shortcut - on/off, the name he types (no slash), the command it
 * sends, Delete. A name is only saved when {@link CommandShortcutsFeature#nameProblem} accepts it; a refused one stays
 * in its box in red with the reason on the line under the list. Changes register live (see the feature's comment).
 */
public class CommandShortcutsTab extends BaseTab {

    private static final List<Group> CATACOMBS = List.of(
            Group.CATA_F0, Group.CATA_F1, Group.CATA_F2, Group.CATA_F3,
            Group.CATA_F4, Group.CATA_F5, Group.CATA_F6, Group.CATA_F7);
    private static final List<Group> KUUDRA = List.of(
            Group.KUUDRA_BASIC, Group.KUUDRA_HOT, Group.KUUDRA_BURNING,
            Group.KUUDRA_FIERY, Group.KUUDRA_INFERNAL);

    /** EditBox's own default text colour (javap, 26.1.2 and 26.2: {@code textColor = -2039584}). */
    private static final int TEXT = 0xFFE0E0E0;
    private static final int REFUSED = 0xFFFF5555;

    /** A name he typed that was refused, kept across rebuilds so the box does not snap back while he fixes it. */
    private final Map<CustomShortcut, String> refusedName = new IdentityHashMap<>();

    public CommandShortcutsTab() {
        super("Command Shortcuts");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        CommandShortcutsConfig cfg = CommandShortcutsConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Command Shortcuts", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    CommandShortcutsFeature.customsChanged();
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        y = section(widgets, "Catacombs / Master Mode", CATACOMBS, cfg, contentX, y, contentWidth);
        y += 6;
        y = section(widgets, "Kuudra", KUUDRA, cfg, contentX, y, contentWidth);
        y += 6;
        custom(widgets, cfg, contentX, y, contentWidth, requestRebuild);
        return widgets;
    }

    private int section(List<AbstractWidget> widgets, String title, List<Group> groups,
                        CommandShortcutsConfig cfg, int contentX, int y, int contentWidth) {
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y += 16;
        for (Group g : groups) {
            widgets.add(SettingsButtonWidget.builder(onOff(g.label, cfg.isGroupOn(g)), btn -> {
                        cfg.setGroupOn(g, !cfg.isGroupOn(g));
                        cfg.save();
                        btn.setMessage(onOff(g.label, cfg.isGroupOn(g)));
                    }).bounds(contentX, y, contentWidth, 18).build());
            y += 21;
        }
        return y;
    }

    private void custom(List<AbstractWidget> widgets, CommandShortcutsConfig cfg, int contentX, int y,
                        int contentWidth, Runnable requestRebuild) {
        var font = Minecraft.getInstance().font;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Custom", false), font));
        y += 16;

        int gap = 6;
        refusedName.keySet().retainAll(cfg.customs());
        SettingsButtonWidget add = SettingsButtonWidget.builder(Component.literal("+ Add Shortcut"), btn -> {
                    if (cfg.addCustom() != null) {
                        cfg.save();
                        requestRebuild.run();
                    }
                }).bounds(contentX, y, (contentWidth - gap) / 2, 18).build();
        add.active = cfg.customs().size() < CommandShortcutsConfig.MAX_CUSTOMS;
        widgets.add(add);
        y += 24;

        int toggleW = 40;
        int nameW = 96;
        int deleteW = 50;
        int commandW = contentWidth - toggleW - nameW - deleteW - gap * 3;
        // Built after the rows (its y is below them); the rows' responders reach it through this holder.
        StringWidget[] status = new StringWidget[1];
        String firstProblem = null;
        for (CustomShortcut c : new ArrayList<>(cfg.customs())) {
            int x = contentX;
            widgets.add(SettingsButtonWidget.builder(Component.literal(c.isEnabled() ? "§aON" : "§cOFF"), btn -> {
                        cfg.setCustomEnabled(c, !c.isEnabled());
                        cfg.save();
                        CommandShortcutsFeature.customsChanged();
                        btn.setMessage(Component.literal(c.isEnabled() ? "§aON" : "§cOFF"));
                    }).bounds(x, y, toggleW, 18).build());
            x += toggleW + gap;

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
                String refused = cfg.setCustomName(c, text.isEmpty() ? "" : text);
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
            x += nameW + gap;

            EditBox commandField = new EditBox(font, x, y, commandW, 18, Component.literal("Sends Command"));
            commandField.setMaxLength(CommandShortcutsConfig.MAX_CUSTOM_COMMAND);
            commandField.setValue(c.command());
            commandField.setHint(Component.literal("§8/warp dh"));
            commandField.setResponder(text -> {
                cfg.setCustomCommand(c, text);
                cfg.save();
                CommandShortcutsFeature.customsChanged();
            });
            widgets.add(commandField);
            x += commandW + gap;

            widgets.add(SettingsButtonWidget.builder(Component.literal("§cDelete"), btn -> {
                        cfg.removeCustom(c);
                        refusedName.remove(c);
                        cfg.save();
                        CommandShortcutsFeature.customsChanged();
                        requestRebuild.run();
                    }).bounds(x, y, deleteW, 18).build());
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

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

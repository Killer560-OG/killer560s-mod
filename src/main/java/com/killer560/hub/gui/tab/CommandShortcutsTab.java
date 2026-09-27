package com.killer560.hub.gui.tab;

import com.killer560.hub.commandshortcuts.CommandShortcutsConfig;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature.Group;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Command Shortcuts (killer560's item 8.3) - one row per {@link Group}: an on/off toggle plus, right next
 * to it, the exact {@code /joininstance ...} line(s) it sends, so a collision with another mod's command
 * of the same name is easy to spot and switch off individually.
 * <p>
 * 2026-09-27 regroup (killer560: "clump them into groups like catacombs/mastermode as one toggle for each
 * floor. Same for kuudra.") - this used to be one row per individual {@code Shortcut} (20 rows: Catacombs,
 * Master Mode and Kuudra as three separate sections). Now Catacombs and Master Mode are ONE section with
 * one toggle per floor (F1-F7 also cover that floor's Master Mode shortcut; F0 has no Master Mode so it's
 * on its own), and Kuudra keeps its own section with one toggle per tier - see
 * {@link com.killer560.hub.commandshortcuts.CommandShortcutsFeature.Group} for exactly which real
 * shortcuts each toggle covers.
 * <p>
 * See {@link com.killer560.hub.commandshortcuts.CommandShortcutsFeature} for why a toggle flipped here
 * needs a rejoin to take effect (it changes what actually gets registered, not just a runtime check) and
 * for where the {@code /joininstance} ids came from.
 */
public class CommandShortcutsTab extends BaseTab {

    private static final List<Group> CATACOMBS = List.of(
            Group.CATA_F0, Group.CATA_F1, Group.CATA_F2, Group.CATA_F3,
            Group.CATA_F4, Group.CATA_F5, Group.CATA_F6, Group.CATA_F7);
    private static final List<Group> KUUDRA = List.of(
            Group.KUUDRA_BASIC, Group.KUUDRA_HOT, Group.KUUDRA_BURNING,
            Group.KUUDRA_FIERY, Group.KUUDRA_INFERNAL);

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
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(note(contentX, y, contentWidth,
                "Each one re-sends the real Hypixel command(s) below it. Takes effect next time you join a world."));
        y += 16;

        y = section(widgets, "Catacombs / Master Mode", CATACOMBS, cfg, contentX, y, contentWidth);
        y += 6;
        section(widgets, "Kuudra", KUUDRA, cfg, contentX, y, contentWidth);

        return widgets;
    }

    private int section(List<AbstractWidget> widgets, String title, List<Group> groups,
                        CommandShortcutsConfig cfg, int contentX, int y, int contentWidth) {
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y += 16;
        int gap = 8;
        int toggleW = 170;
        int textW = contentWidth - toggleW - gap;
        for (Group g : groups) {
            widgets.add(SettingsButtonWidget.builder(onOff(g.label, cfg.isGroupOn(g)), btn -> {
                        cfg.setGroupOn(g, !cfg.isGroupOn(g));
                        cfg.save();
                        btn.setMessage(onOff(g.label, cfg.isGroupOn(g)));
                    }).bounds(contentX, y, toggleW, 18).build());
            widgets.add(new StringWidget(contentX + toggleW + gap, y + 4, textW, 12,
                    Component.literal("§7" + g.expandsTo()),
                    Minecraft.getInstance().font));
            y += 21;
        }
        return y;
    }

    private static StringWidget note(int x, int y, int width, String text) {
        return new StringWidget(x, y, width, 12, Component.literal("§7" + text), Minecraft.getInstance().font);
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

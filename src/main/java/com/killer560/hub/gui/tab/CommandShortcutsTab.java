package com.killer560.hub.gui.tab;

import com.killer560.hub.commandshortcuts.CommandShortcutsConfig;
import com.killer560.hub.commandshortcuts.CommandShortcutsFeature.Shortcut;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Command Shortcuts (killer560's item 8.3) - one row per alias: an on/off toggle plus, right next to it, the
 * exact {@code /joininstance ...} line it sends, so a collision with another mod's command of the same name
 * is easy to spot and switch off individually. See {@link com.killer560.hub.commandshortcuts.CommandShortcutsFeature}
 * for why a toggle flipped here needs a rejoin to take effect (it changes what actually gets registered, not
 * just a runtime check) and for where the {@code /joininstance} ids came from.
 */
public class CommandShortcutsTab extends BaseTab {

    private static final List<Shortcut> CATACOMBS = List.of(
            Shortcut.F0, Shortcut.F1, Shortcut.F2, Shortcut.F3,
            Shortcut.F4, Shortcut.F5, Shortcut.F6, Shortcut.F7);
    private static final List<Shortcut> MASTER_MODE = List.of(
            Shortcut.M1, Shortcut.M2, Shortcut.M3, Shortcut.M4, Shortcut.M5, Shortcut.M6, Shortcut.M7);
    private static final List<Shortcut> KUUDRA = List.of(
            Shortcut.KUUDRA_BASIC, Shortcut.KUUDRA_HOT, Shortcut.KUUDRA_BURNING,
            Shortcut.KUUDRA_FIERY, Shortcut.KUUDRA_INFERNAL);

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
                "Each one re-sends the real Hypixel command below it. Takes effect next time you join a world."));
        y += 16;

        y = section(widgets, "Catacombs", CATACOMBS, cfg, contentX, y, contentWidth);
        y += 6;
        y = section(widgets, "Master Mode", MASTER_MODE, cfg, contentX, y, contentWidth);
        y += 6;
        section(widgets, "Kuudra", KUUDRA, cfg, contentX, y, contentWidth);

        return widgets;
    }

    private int section(List<AbstractWidget> widgets, String title, List<Shortcut> shortcuts,
                        CommandShortcutsConfig cfg, int contentX, int y, int contentWidth) {
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                SectionHeaders.header(title, false), Minecraft.getInstance().font));
        y += 16;
        int gap = 8;
        int toggleW = 150;
        int textW = contentWidth - toggleW - gap;
        for (Shortcut s : shortcuts) {
            widgets.add(SettingsButtonWidget.builder(onOff("/" + s.literal, cfg.isOn(s)), btn -> {
                        cfg.setOn(s, !cfg.isOn(s));
                        cfg.save();
                        btn.setMessage(onOff("/" + s.literal, cfg.isOn(s)));
                    }).bounds(contentX, y, toggleW, 18).build());
            widgets.add(new StringWidget(contentX + toggleW + gap, y + 4, textW, 12,
                    Component.literal("§7" + s.expandsTo() + " §8(" + s.label + ")"),
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

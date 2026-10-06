package com.killer560.hub.gui.tab;

import com.killer560.hub.autotrap.AutoTrap;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * "Auto Trap" - CHEAT BUILD ONLY (killer560, 2026-10-06). The switch, then per trap room: what each of its two route slots
 * (Full Trap, Just Cleared) holds - captured or bundled, node count, when saved - the selected mode, Capture (copies his
 * Auto Routes route for that room into the selected mode's slot) and Clear (that slot). See {@link AutoTrap}.
 */
public class AutoTrapTab extends BaseTab {

    private static final int GAP = 6;
    private static final int ROW = 24;
    private static String lastMessage = "";

    public AutoTrapTab() {
        super("Auto Trap");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> w = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return w;
        }
        var font = Minecraft.getInstance().font;
        int third = (contentWidth - 2 * GAP) / 3;
        int last = Math.max(1, contentWidth - 2 * third - 2 * GAP);
        int y = contentY + 6;
        w.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Auto Trap", true), font));
        y += 16;
        w.add(SettingsButtonWidget.builder(Component.literal("Auto Trap: " + (AutoTrap.isEnabledRaw() ? "§aON" : "§cOFF")),
                btn -> {
                    AutoTrap.setEnabled(!AutoTrap.isEnabledRaw());
                    requestRebuild.run();
                }).bounds(contentX, y, contentWidth, 20).build());
        y += ROW;
        w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                "§7Plays through Auto Routes (switch it on). One warp onto the start node, none inside."), font));
        y += 16;
        SimpleDateFormat when = new SimpleDateFormat("MM-dd HH:mm");
        for (String trap : AutoTrap.TRAPS) {
            y += 6;
            w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§f" + trap), font));
            y += 14;
            for (AutoTrap.Mode mode : AutoTrap.Mode.values()) {
                AutoTrap.Entry e = AutoTrap.entry(trap, mode);
                String state = e == null ? "§cno route" : "§a" + e.source() + "§7, " + e.route().nodes().size() + " nodes"
                        + (e.savedMs() > 0 ? ", " + when.format(new Date(e.savedMs())) : "");
                w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§7  " + mode.label() + ": " + state),
                        font));
                y += 12;
            }
            y += 2;
            AutoTrap.Mode mode = AutoTrap.selectedMode(trap);
            w.add(SettingsButtonWidget.builder(Component.literal("Mode: §e" + mode.label()), btn -> {
                AutoTrap.setSelectedMode(trap, mode == AutoTrap.Mode.FULL ? AutoTrap.Mode.CLEARED : AutoTrap.Mode.FULL);
                requestRebuild.run();
            }).bounds(contentX, y, third, 20).build());
            w.add(SettingsButtonWidget.builder(Component.literal("Capture " + mode.label()), btn -> {
                lastMessage = AutoTrap.capture(trap, mode);
                requestRebuild.run();
            }).bounds(contentX + third + GAP, y, third, 20).build());
            w.add(SettingsButtonWidget.builder(Component.literal("Clear " + mode.label()), btn -> {
                lastMessage = AutoTrap.clear(trap, mode);
                requestRebuild.run();
            }).bounds(contentX + 2 * (third + GAP), y, last, 20).build());
            y += ROW;
        }
        if (!lastMessage.isEmpty()) {
            w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal("§e" + lastMessage), font));
            y += 16;
        }
        w.add(new StringWidget(contentX, y, contentWidth, 12, Component.literal(
                "§8Saved in " + AutoTrap.file().getFileName() + "; bundled: " + AutoTrap.BUNDLED), font));
        return w;
    }
}

package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.social.FriendsListConfig;
import com.killer560.hub.social.FriendsListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Custom Friends List settings - see {@link com.killer560.hub.social.FriendsListCommands}. New tab, off by
 *  default: the "Use Our /fl" toggle especially must never hijack {@code /fl} until killer560 turns it on. */
public class FriendsListTab extends BaseTab {

    public FriendsListTab() {
        super("Friends List");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        FriendsListConfig cfg = FriendsListConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Use Our /fl", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal(cfg.isEnabled()
                        ? "§7/fl opens our list. Use §f/flhypixel §7for Hypixel's own."
                        : "§7/fl passes straight through to Hypixel. Use §f/flcustom §7for ours."),
                Minecraft.getInstance().font));
        y += 16;

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Friends List (/flcustom)"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    client.setScreen(new FriendsListScreen(client.screen));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        int count = cfg.friends().size();
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7" + count + " friend" + (count == 1 ? "" : "s") + " on your list."),
                Minecraft.getInstance().font));
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

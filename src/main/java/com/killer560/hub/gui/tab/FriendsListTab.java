package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.social.FriendsListConfig;
import com.killer560.hub.social.FriendsListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import com.killer560.hub.compat.McCompat;

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
        // killer560 (2026-10-08) asked for the "/fl opens our list..." line and the "N friends on your list." line
        // to go; the Use Our /fl tooltip says what /fl, /flcustom and /flhypixel do.

        widgets.add(SettingsButtonWidget.builder(Component.literal("Open Friends List (/flcustom)"), btn -> {
                    Minecraft client = Minecraft.getInstance();
                    McCompat.setScreen(client, new FriendsListScreen(McCompat.screen(client)));
                }).bounds(contentX, y, contentWidth, 20).build());
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

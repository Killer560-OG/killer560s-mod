package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.leftclickew.LeftClickEtherwarpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Left Click Etherwarp - cheat-only (see {@link com.killer560.hub.leftclickew.LeftClickEtherwarp}). Dungeons' cheat block. */
public class LeftClickEtherwarpTab extends BaseTab {

    private static final int ROW = 18;

    public LeftClickEtherwarpTab() {
        super("Left Click Etherwarp");
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
        LeftClickEtherwarpConfig cfg = LeftClickEtherwarpConfig.getInstance();
        int y = contentY;
        w.add(SettingsButtonWidget.builder(onOff("Left Click Etherwarp", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    btn.setMessage(onOff("Left Click Etherwarp", cfg.isEnabled()));
                }).bounds(contentX, y, contentWidth, ROW).build());
        y += ROW + 6;
        w.add(new StringWidget(contentX, y, contentWidth, 12, SectionHeaders.header("Left Click Etherwarp", true), font));
        y += 16;
        String[] help = {
                "§7With an etherwarp item in hand, a left click etherwarps:",
                "§7sneak goes down, the warp goes the next tick (at once if you",
                "§7already sneak), then sneak comes back up. No attack or swing."
        };
        for (String line : help) {
            w.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(line), font));
            y += 11;
        }
        return w;
    }

    private static Component onOff(String label, boolean on) {
        return Component.literal(label + ": " + (on ? "§aON" : "§cOFF"));
    }
}

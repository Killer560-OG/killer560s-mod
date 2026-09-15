package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.splittimers.SplitTimersConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Split Timers settings - see {@link com.killer560.hub.splittimers.SplitTimersFeature}'s class doc
 *  for the real Odin-ported boss-dialogue split points this is built on. */
public class SplitTimersTab extends BaseTab {

    public SplitTimersTab() {
        super("Split Timers");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        SplitTimersConfig cfg = SplitTimersConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Split Timers", cfg.isEnabled()), btn -> {
                    cfg.setEnabled(!cfg.isEnabled());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabled()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Announce In Chat", cfg.isAnnounceInChat()), btn -> {
                    cfg.setAnnounceInChat(!cfg.isAnnounceInChat());
                    cfg.save();
                    btn.setMessage(onOff("Announce In Chat", cfg.isAnnounceInChat()));
                }).bounds(contentX, y, 220, 18).build());
        y += 24;

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Odin's splits: Blood Open/Clear, Portal Entry, each boss phase"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7(E-F7, e.g. Maxor/Storm/Terminals/Goldor/Necron) and Total - armed"),
                Minecraft.getInstance().font));
        y += 12;
        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7on \"Starting in 1 second.\", clock starts on Mort's line."),
                Minecraft.getInstance().font));

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

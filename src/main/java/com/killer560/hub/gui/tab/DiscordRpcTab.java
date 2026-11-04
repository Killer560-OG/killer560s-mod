package com.killer560.hub.gui.tab;

import com.killer560.hub.discordrpc.DiscordRpcConfig;
import com.killer560.hub.discordrpc.DiscordRpcFeature;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Discord Rich Presence settings - see {@link DiscordRpcFeature}. Shows the mod's Discord application (its id
 *  ships in DiscordRpcConfig). */
public class DiscordRpcTab extends BaseTab {

    public DiscordRpcTab() {
        super("Discord Rich Presence");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        DiscordRpcConfig cfg = DiscordRpcConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int y = contentY;
        int gap = 8;
        int halfW = (contentWidth - gap) / 2;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "Discord Rich Presence", false));
        y += 16;

        widgets.add(onOffButton("Discord Rich Presence", cfg::isEnabled, cfg::setEnabled, () -> { cfg.save(); DiscordRpcFeature.onSettingsChanged(); }, requestRebuild, contentX, y, contentWidth, 20));
        y += 24;

        if (!cfg.isEnabled() && !isSearchScan()) {
            return widgets;
        }

        widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                Component.literal("§7Status: §f" + DiscordRpcFeature.status()
                        + "  §8|  §7Now showing: §f" + safePreview()), font));
        y += 18;

        widgets.add(SectionHeaders.row(contentX, y, contentWidth, "What To Show", false));
        y += 14;

        widgets.add(onOffButton("Hide Details", cfg::isHideDetails, cfg::setHideDetails, () -> { cfg.save(); DiscordRpcFeature.onSettingsChanged(); }, requestRebuild, contentX, y, halfW, 18));
        widgets.add(onOffButton("Show Elapsed Time", cfg::isShowElapsed, cfg::setShowElapsed, () -> { cfg.save(); DiscordRpcFeature.onSettingsChanged(); }, null, contentX + halfW + gap, y, halfW, 18));
        y += 22;

        if (!cfg.isHideDetails()) {
            widgets.add(onOffButton("Show Island / Area", cfg::isShowArea, cfg::setShowArea, () -> { cfg.save(); DiscordRpcFeature.onSettingsChanged(); }, null, contentX, y, halfW, 18));
            widgets.add(onOffButton("Show Dungeon Info", cfg::isShowDungeonInfo, cfg::setShowDungeonInfo, () -> { cfg.save(); DiscordRpcFeature.onSettingsChanged(); }, null, contentX + halfW + gap, y, halfW, 18));
            y += 22;
        }
        y += 4;

        return widgets;
    }

    private String safePreview() {
        try {
            return DiscordRpcFeature.previewText();
        } catch (Throwable t) {
            return "-";
        }
    }
}

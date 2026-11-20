package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.MenuStyle;
import com.killer560.hub.gui.SectionHeaders;
import com.killer560.hub.sharesite.ShareToWebsite;
import com.killer560.hub.sharesite.ShareToWebsiteConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Home's Share To Website card (sharesite-1119, killer560 2026-11-19: ON by default, offered in the tour): the switch,
 * then what goes to the website's Player Stats page and what never does. See {@code sharesite/ShareToWebsite}.
 */
public class ShareToWebsiteTab extends BaseTab {

    public static final String NAME = "Share To Website";

    public ShareToWebsiteTab() {
        super(NAME);
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        widgets.add(SectionHeaders.row(contentX, y, contentWidth, NAME, false));
        y += 16;
        ShareToWebsiteConfig cfg = ShareToWebsiteConfig.getInstance();
        widgets.add(onOffButton(NAME, cfg::isEnabled, on -> { ShareToWebsite.setEnabled(on); ShareToWebsite.acknowledgeNotice(); }, () -> { }, requestRebuild,
                contentX, y, contentWidth, 20));
        y += 24;
        for (String line : LINES) {
            String color = line.substring(0, 2);
            String body = line.substring(2);
            for (String part : MenuStyle.wrap(body, Math.max(40, contentWidth - 2), 4)) {
                widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal(color + part),
                        Minecraft.getInstance().font));
                y += 11;
            }
        }
        return widgets;
    }

    /** Kept to what {@code ShareToWebsite} really sends. */
    public static final String[] LINES = {
            "§eAfter each dungeon run this adds to your page on killer560smod.pages.dev (Tools > Player Stats):",
            "§7- the floor, how long it took and whether the boss was beaten",
            "§7- the secrets you found",
            "§7- your own terminal solve times",
            "§eAnyone can look up your page by your Minecraft name. It never shows:",
            "§7- other players' names, your IP or anything else",
            "§eTurning it off deletes your page from the website."
    };
}

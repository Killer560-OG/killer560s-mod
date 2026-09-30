package com.killer560.hub.gui.tab;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** The chat-line hides that used to sit under Object Hider's "Chat Replacements" header, moved into the
 *  Chat folder 2026-09-30 per killer560: "move the hide chat stuff into the chat section."
 *  <p>
 *  Presentation only - every toggle still reads and writes the SAME {@link ObjectHiderConfig} fields under
 *  the same JSON keys, so an existing config keeps whatever was set. Hide Actionbar travels with the group
 *  because it is the same QUOI "Chat Replacements" set and the same message-suppression mixins underneath,
 *  even though the action bar is not literally chat. */
public class HideChatMessagesTab extends BaseTab {

    public HideChatMessagesTab() {
        super("Hide Chat Messages");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        int[] y = {contentY};

        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Useless Messages", cfg::getHideUselessMessagesRaw, cfg::setHideUselessMessages);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Discord Warnings", cfg::getHideDiscordWarningsRaw, cfg::setHideDiscordWarnings);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Microsoft Warnings", cfg::getHideMicrosoftWarningsRaw, cfg::setHideMicrosoftWarnings);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Empty Chat Messages", cfg::getHideEmptyChatMessagesRaw, cfg::setHideEmptyChatMessages);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Actionbar", cfg::getHideActionbarRaw, cfg::setHideActionbar);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Non-Rank Invites", cfg::getHideNonRankInvitesRaw, cfg::setHideNonRankInvites);

        return widgets;
    }
}

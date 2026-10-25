package com.killer560.hub.gui.tab;

import com.killer560.hub.chattidy.ChatTidyConfig;
import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Chat Hider (killer560, 2026-10-08: "Combine hide chat and tidy chat into one setting called Chat Hider"): what were
 *  the Hide Chat Messages and Chat Tidy tabs, under one master switch. Hide Damage Messages hides ability-damage AND
 *  incoming-hit lines; its two family switches were removed the same day. Every row is always shown, so nothing hides
 *  behind the master switch. See {@link ChatTidyConfig}. */
public class ChatHiderTab extends BaseTab {

    public ChatHiderTab() {
        super("Chat Hider");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ChatTidyConfig cfg = ChatTidyConfig.getInstance();
        int[] y = {contentY};
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Chat Hider", cfg::getEnabledRaw, cfg::setEnabled);

        header(widgets, contentX, contentWidth, y, "Tidy");
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Stack Duplicate Messages", cfg::getStackDuplicatesRaw, cfg::setStackDuplicates);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Damage Messages", cfg::getHideDamageMessagesRaw, cfg::setHideDamageMessages);

        header(widgets, contentX, contentWidth, y, "Hide");
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Useless Messages", cfg::getHideUselessMessagesRaw, cfg::setHideUselessMessages);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Discord Warnings", cfg::getHideDiscordWarningsRaw, cfg::setHideDiscordWarnings);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Microsoft Warnings", cfg::getHideMicrosoftWarningsRaw, cfg::setHideMicrosoftWarnings);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Empty Chat Messages", cfg::getHideEmptyChatMessagesRaw, cfg::setHideEmptyChatMessages);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Actionbar", cfg::getHideActionbarRaw, cfg::setHideActionbar);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Non-Rank Invites", cfg::getHideNonRankInvitesRaw, cfg::setHideNonRankInvites);
        toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hide Ban Announcements", cfg::getHideBanAnnouncementsRaw, cfg::setHideBanAnnouncements);
        return widgets;
    }

    private static void header(List<AbstractWidget> widgets, int x, int width, int[] y, String title) {
        y[0] += 2;
        widgets.add(SectionHeaders.row(x, y[0], width, title, false));
        y[0] += 16;
    }

    private static void toggle(List<AbstractWidget> widgets, int x, int width, int[] y, ChatTidyConfig cfg,
                               Runnable requestRebuild, String label, BooleanSupplier get, Consumer<Boolean> set) {
        widgets.add(onOffButton(label, get, set, cfg::save, requestRebuild, x, y[0], width, 20));
        y[0] += 24;
    }
}

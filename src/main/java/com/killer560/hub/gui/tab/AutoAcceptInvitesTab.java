package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.partyinvites.AutoAcceptInvitesConfig;
import com.killer560.hub.partyinvites.AutoAcceptRule;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Auto Accept Invites (killer560, 2026-10-20): the switch, then the mode - All Invites or Reinvites Only. */
public class AutoAcceptInvitesTab extends BaseTab {

    public AutoAcceptInvitesTab() {
        super("Auto Accept Invites");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        AutoAcceptInvitesConfig cfg = AutoAcceptInvitesConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Accept Invites", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Auto Accept Invites", cfg.isEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(SettingsButtonWidget.cycling(modeText(cfg), (btn, dir) -> {
                    cfg.setMode(SettingsButtonWidget.step(cfg.getMode(), dir));
                    cfg.save();
                    btn.setMessage(modeText(cfg));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(onOffButton("Only From Friends", cfg::isFromFriends, cfg::setFromFriends, cfg::save, null, contentX, y, contentWidth, 20));
        y += 26;
        widgets.add(onOffButton("Only From Guild Members", cfg::isFromGuild, cfg::setFromGuild, cfg::save, null, contentX, y, contentWidth, 20));
        y += 26;
        widgets.add(onOffButton("Only From Best Friends", cfg::isFromBestFriends, cfg::setFromBestFriends, cfg::save, null, contentX, y, contentWidth, 20));
        return widgets;
    }

    private static Component modeText(AutoAcceptInvitesConfig cfg) {
        AutoAcceptRule.Mode m = cfg.getMode();
        return Component.literal("Mode: " + m.label);
    }
}

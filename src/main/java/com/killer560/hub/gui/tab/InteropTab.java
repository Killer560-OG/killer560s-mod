package com.killer560.hub.gui.tab;

import com.killer560.hub.partydata.PartyDataConfig;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.interop.InteropConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Party Interop settings - see {@link com.killer560.hub.interop.InteropFeature}. Each source of party
 *  knowledge is switchable on its own. (The live status block was removed 2026-10-18.) */
public class InteropTab extends BaseTab {

    public InteropTab() {
        super("Party Interop");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        int y = contentY;
        InteropConfig cfg = InteropConfig.getInstance();

        widgets.add(SettingsButtonWidget.builder(onOff("Party Interop", cfg.isEnabledRaw()), btn -> {
                    cfg.setEnabled(!cfg.isEnabledRaw());
                    cfg.save();
                    requestRebuild.run();
                }).bounds(contentX, y, 220, 20).build());
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(SettingsButtonWidget.builder(onOff("Work It Out Myself", cfg.isSelfDerivation()), btn -> {
                    cfg.setSelfDerivation(!cfg.isSelfDerivation());
                    cfg.save();
                    btn.setMessage(onOff("Work It Out Myself", cfg.isSelfDerivation()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Read Party Chat", cfg.isChatParsing()), btn -> {
                    cfg.setChatParsing(!cfg.isChatParsing());
                    cfg.save();
                    btn.setMessage(onOff("Read Party Chat", cfg.isChatParsing()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Use Mod Relay", cfg.isRelayData()), btn -> {
                    cfg.setRelayData(!cfg.isRelayData());
                    cfg.save();
                    btn.setMessage(onOff("Use Mod Relay", cfg.isRelayData()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;
        PartyDataConfig dataCfg = PartyDataConfig.getInstance();
        widgets.add(SettingsButtonWidget.builder(onOff("Share Dungeon Data With Party", dataCfg.isShareEnabled()), btn -> {
                    dataCfg.setShareEnabled(!dataCfg.isShareEnabled());
                    dataCfg.save();
                    btn.setMessage(onOff("Share Dungeon Data With Party", dataCfg.isShareEnabled()));
                }).bounds(contentX, y, 220, 18).build());
        y += 22;

        widgets.add(SettingsButtonWidget.builder(onOff("Local Mod Bridge", cfg.isLocalBridge()), btn -> {
                    cfg.setLocalBridge(!cfg.isLocalBridge());
                    cfg.save();
                    btn.setMessage(onOff("Local Mod Bridge", cfg.isLocalBridge()));
                }).bounds(contentX, y, 220, 18).build());
        y += 26;

        // Cross-Mod Bridge (Devonian / NoammAddons / Odin party sockets) - its own block, see BridgeSettingsBlock.
        y = com.killer560.hub.bridge.BridgeSettingsBlock.addWidgets(widgets, contentX, y, contentWidth, requestRebuild);

        // The live status lines (other mods here, flags, counters, recent events) are no longer drawn on this tab
        // (killer560, 2026-10-18: "hide all that extra info at the bottom of party interop").

        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

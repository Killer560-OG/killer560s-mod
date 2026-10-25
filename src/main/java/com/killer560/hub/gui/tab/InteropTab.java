package com.killer560.hub.gui.tab;

import com.killer560.hub.partydata.PartyDataConfig;

import com.killer560.hub.interop.InteropConfig;
import net.minecraft.client.gui.components.AbstractWidget;

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

        widgets.add(onOffButton("Party Interop", cfg::isEnabledRaw, cfg::setEnabled, cfg::save,
                requestRebuild, contentX, y, 220, 20));
        y += 26;

        if (!cfg.isEnabledRaw()) {
            return widgets;
        }

        widgets.add(onOffButton("Work It Out Myself", cfg::isSelfDerivation, cfg::setSelfDerivation, cfg::save,
                null, contentX, y, 220, 18));
        y += 22;

        widgets.add(onOffButton("Read Party Chat", cfg::isChatParsing, cfg::setChatParsing, cfg::save,
                null, contentX, y, 220, 18));
        y += 22;

        widgets.add(onOffButton("Use Mod Relay", cfg::isRelayData, cfg::setRelayData, cfg::save,
                null, contentX, y, 220, 18));
        y += 22;
        PartyDataConfig dataCfg = PartyDataConfig.getInstance();
        widgets.add(onOffButton("Share Dungeon Data With Party", dataCfg::isShareEnabled, dataCfg::setShareEnabled,
                dataCfg::save, null, contentX, y, 220, 18));
        y += 22;

        widgets.add(onOffButton("Local Mod Bridge", cfg::isLocalBridge, cfg::setLocalBridge, cfg::save,
                null, contentX, y, 220, 18));
        y += 26;

        // Cross-Mod Bridge (Devonian / NoammAddons / Odin party sockets) - its own block, see BridgeSettingsBlock.
        y = com.killer560.hub.bridge.BridgeSettingsBlock.addWidgets(widgets, contentX, y, contentWidth, requestRebuild);

        // The live status lines (other mods here, flags, counters, recent events) are no longer drawn on this tab
        // (killer560, 2026-10-18: "hide all that extra info at the bottom of party interop").

        return widgets;
    }
}

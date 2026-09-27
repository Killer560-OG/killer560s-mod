package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SettingsButtonWidget;
import com.killer560.hub.mining.MiningAutomationConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Nucleus Run (killer560, verbatim: "auto nuc run") - a full mine-to-Nucleus-and-back automation.
 * <p>
 * <b>NOT WIRED. Setting only.</b> See {@link MiningAutomationConfig}'s class doc for the general reasoning.
 * Specific to this one: an Auto Nucleus Run would need to mine towards crystals, break/place them correctly
 * (see {@link AutoCrystalTab}'s own "not wired" note - this depends on that), navigate real Crystal Hollows
 * terrain to the Nucleus, fight through however many hits the Nucleus needs, and collect the loot bundle -
 * a multi-minute, multi-stage loop with no existing building block in this codebase to start from (the
 * closest thing, {@code mining.nucleus.NucleusRunProfitTracker}, only WATCHES for that same loot bundle in
 * chat - it never acts). Half of this loop (e.g. "path to the Nucleus but never actually mine crystals")
 * would just get the player stuck or killed, which is worse than not having the feature.
 */
public class AutoNucleusRunTab extends BaseTab {

    public AutoNucleusRunTab() {
        super("Auto Nucleus Run");
    }

    @Override
    public boolean isCheatOnly() {
        return true;
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        var font = Minecraft.getInstance().font;
        MiningAutomationConfig cfg = MiningAutomationConfig.getInstance();
        int y = contentY;

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Nucleus Run", cfg.isAutoNucleusRunEnabledRaw()), btn -> {
                    cfg.setAutoNucleusRunEnabled(!cfg.isAutoNucleusRunEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Auto Nucleus Run", cfg.isAutoNucleusRunEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§c§lNOT WIRED YET"), font));
        y += 14;
        String[] lines = {
                "This switch is saved and gated the same way a real cheat feature would be, but nothing in the",
                "mod reads it yet - turning it ON does nothing in-game.",
                "",
                "What real automation here would still need (see Auto Crystal for the crystal-handling half):",
                "- Real Crystal Hollows terrain pathfinding towards crystal spawns, then to the Nucleus",
                "- Combat handling for however many hits break the Nucleus open",
                "- Collecting/confirming the loot bundle before starting the next run",
                "- Every step wired through ActionGate, one automated interaction per tick, discrete keys only",
                "",
                "See Nucleus Run Profit for the part of this that IS built: watching for Hypixel's own real",
                "loot-bundle chat message and pricing what it contains - purely observational, no automation.",
        };
        for (String line : lines) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§7" + line), font));
            y += 11;
        }
        return widgets;
    }

    private static Component onOff(String label, boolean value) {
        return Component.literal(label + ": " + (value ? "§aON" : "§cOFF"));
    }
}

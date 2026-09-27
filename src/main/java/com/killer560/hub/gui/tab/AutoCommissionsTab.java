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
 * Auto Commissions (killer560, verbatim: "auto commissions") - Dwarven Mines commission automation.
 * <p>
 * <b>NOT WIRED. Setting only.</b> This toggle exists (persisted, cheat-gated, {@code ActionGate}-ready) but
 * nothing reads it to actually act - see {@link MiningAutomationConfig}'s class doc for exactly why: doing
 * this correctly needs live commission-objective parsing (which mob/ore/item each active commission wants),
 * mob targeting or ore-vein detection, and real Dwarven Mines terrain pathfinding, none of which exist
 * anywhere in this codebase (the only pathfinding here, {@code ap3}/{@code livemap.autoclear}, walks the
 * dungeon's fixed room grid, not open cave terrain). Shipping a version that half-parses commissions and
 * swings at the wrong thing would be worse than shipping nothing - see the brief's own instruction on this.
 */
public class AutoCommissionsTab extends BaseTab {

    public AutoCommissionsTab() {
        super("Auto Commissions");
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

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Commissions", cfg.isAutoCommissionsEnabledRaw()), btn -> {
                    cfg.setAutoCommissionsEnabled(!cfg.isAutoCommissionsEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Auto Commissions", cfg.isAutoCommissionsEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§c§lNOT WIRED YET"), font));
        y += 14;
        String[] lines = {
                "This switch is saved and gated the same way a real cheat feature would be, but nothing in the",
                "mod reads it yet - turning it ON does nothing in-game.",
                "",
                "What real automation here would still need:",
                "- Parsing each active commission's target (mob/ore/item) from the commission menu or HUD",
                "- Mob targeting or ore-vein detection for that target",
                "- Real Dwarven Mines terrain pathfinding (this mod's only pathfinder, ap3/livemap.autoclear,",
                "  only walks the dungeon's fixed room grid, not open cave terrain)",
                "- Wiring every click/attack through ActionGate (Kind.WORLD) like every other aura in this mod",
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

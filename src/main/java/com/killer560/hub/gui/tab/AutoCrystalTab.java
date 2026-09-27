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
 * Auto Crystal (killer560, verbatim: "auto crystal") - automatic crystal collection/placement in Crystal
 * Hollows (Jungle Key crystals, Precursor Apparatus, robot parts, etc).
 * <p>
 * <b>NOT WIRED. Setting only.</b> See {@link MiningAutomationConfig}'s class doc for the general reasoning.
 * Specific to this one: correctly telling a real crystal apart from ordinary terrain needs either a block-ID
 * lookup this mod doesn't have a verified list for, or a proper world scan/highlight pass like
 * {@code dungeonextras.BreakerAuraFeature} runs for dungeon blocks - nothing equivalent exists for Crystal
 * Hollows crystals. Guessing at "break whatever block is highlighted-ish" is exactly the kind of half-built
 * clicker the brief says not to ship, especially for something that breaks/places real blocks.
 */
public class AutoCrystalTab extends BaseTab {

    public AutoCrystalTab() {
        super("Auto Crystal");
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

        widgets.add(SettingsButtonWidget.builder(onOff("Auto Crystal", cfg.isAutoCrystalEnabledRaw()), btn -> {
                    cfg.setAutoCrystalEnabled(!cfg.isAutoCrystalEnabledRaw());
                    cfg.save();
                    btn.setMessage(onOff("Auto Crystal", cfg.isAutoCrystalEnabledRaw()));
                }).bounds(contentX, y, contentWidth, 20).build());
        y += 26;

        widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§c§lNOT WIRED YET"), font));
        y += 14;
        String[] lines = {
                "This switch is saved and gated the same way a real cheat feature would be, but nothing in the",
                "mod reads it yet - turning it ON does nothing in-game.",
                "",
                "What real automation here would still need:",
                "- A verified way to tell a real crystal block apart from ordinary Crystal Hollows terrain",
                "  (nothing equivalent to dungeonextras.BreakerAuraFeature's dungeon block list exists for CH)",
                "- Real terrain pathfinding to reach each one",
                "- Every break/place wired through ActionGate (Kind.WORLD), one interaction per tick",
                "- Discrete key presses only for any movement involved - never position/velocity writes",
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

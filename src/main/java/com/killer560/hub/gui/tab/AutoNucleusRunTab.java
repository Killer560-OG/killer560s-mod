// Restored 2026-10-25 at killer560's request; this one stub overrides his 2026-10-07 "mining stays off the menu until after 2.0".
package com.killer560.hub.gui.tab;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto Nucleus Run (killer560, verbatim: "auto nuc run") - a planned mine-to-Nucleus-and-back automation. CHEAT BUILD
 * ONLY: added to Helpers' cheat block (Misc category) behind {@code BuildVariant.CHEAT_FEATURES_ENABLED}.
 * <p>
 * <b>A stub: nothing is built.</b> The tab holds only its label and what real automation would still need. It has no
 * switch and no saved setting on purpose: d990a2ee removed the old one as dead config (a persisted key nothing read),
 * and a setting comes back alongside the code that reads it. Mining itself is shelved until after 2.0
 * ({@code shelved/mining/README.md}), which is where the Planned page this tab points at lives.
 */
public class AutoNucleusRunTab extends BaseTab {

    public AutoNucleusRunTab() {
        super("Auto Nucleus Run");
    }

    /** Only added to its category behind {@code BuildVariant.CHEAT_FEATURES_ENABLED} - red title. */
    @Override
    public boolean isCheatOnly() {
        return true;
    }

    /** No switch, so the card's line cannot come from a master toggle's tooltip. */
    @Override
    public String cardDescription() {
        return "Planned, not built yet: a full mine-to-Nucleus-and-back run. Nothing here acts in-game.";
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return widgets;
        }
        var font = Minecraft.getInstance().font;
        int y = contentY;

        widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§c§lNOT WIRED YET"), font));
        y += 14;
        String[] lines = {
                "Nothing in this tab acts in-game; it has no switch until the feature exists.",
                "",
                "What real automation here would still need:",
                "- Crystal Hollows terrain pathfinding toward crystal spawns, then to the Nucleus",
                "  (AP3 and Auto Clear only path the dungeon's fixed room grid)",
                "- A reliable way to tell a real crystal block from terrain; there is none in this mod yet",
                "- Combat handling for however many hits break the Nucleus open",
                "- Collecting and confirming the loot bundle before starting the next run",
                "- Every step wired through ActionGate, one automated interaction per tick, discrete keys only",
                "",
                "Planned: listed with the rest of mining on the Mining Planned page, shelved until after 2.0.",
        };
        for (String line : lines) {
            widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§7" + line), font));
            y += 11;
        }
        return widgets;
    }
}

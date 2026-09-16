package com.killer560.hub.gui.tab;

import com.killer560.hub.gui.SectionHeaders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Parking spot for mining features (2026-09-15, killer560: put mining work in "a new mining tab... not the actual
 *  mining tab but a separate tab than the New tab so I know to work on fixing them later"). Deliberately separate
 *  from {@link NewTab}: the 1.3 release ships without mining, so nothing here is expected to work yet. Feature tabs
 *  get added to {@link #buildTabs()} as they're built; until then the page lists what's planned. */
public class MiningWipTab extends FolderTab {

    public MiningWipTab() {
        super("Mining (WIP)", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>();
        tabs.add(new PlannedTab());
        return tabs;
    }

    /** Read-only roadmap page so the tab isn't empty. */
    private static final class PlannedTab extends BaseTab {

        private static final String[] PLANNED = {
                "Commission display - HUD with every commission's progress + completion alerts",
                "Crystal Nucleus helper - crystals found/placed tracker, part locations per zone",
                "Auto Crystal Nucleus runs (cheat) - island pathfinding + etherwarp, Jungle Temple auto parkour, auto bow",
                "Carpet highlight while on a mining island",
                "Mines of Divan metal detector solver",
                "Crystal Hollows chest lockpick solver + treasure chest highlight",
                "Goblin Queen's Den / Jungle Temple / Precursor City / Khazad-dum waypoints + shared coords",
                "Powder tracker (mithril / gemstone / glacite per hour)",
                "Mining ability cooldown + Sky Mall perk display",
                "Glacite Mineshaft helpers - corpse ESP, mineshaft entrance alert, shaft type",
                "Fossil Excavator solver",
                "Golden / Diamond Goblin + Scatha / worm spawn alerts",
                "Fallen Star / Star Sentry waypoint",
                "Titanium + rare ore alerts, gemstone vein highlight in Glacite Tunnels",
        };

        PlannedTab() {
            super("Planned");
        }

        @Override
        public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
            List<AbstractWidget> widgets = new ArrayList<>();
            var font = Minecraft.getInstance().font;
            int y = contentY;
            widgets.add(new StringWidget(contentX, y, contentWidth, 12,
                    SectionHeaders.header("Mining - planned (not in 1.3)", false), font));
            y += 16;
            widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                    Component.literal("§7Work parked here until the dungeon side is finished."), font));
            y += 16;
            for (String line : PLANNED) {
                widgets.add(new StringWidget(contentX, y, contentWidth, 10, Component.literal("§6- §f" + line), font));
                y += 13;
            }
            return widgets;
        }
    }
}

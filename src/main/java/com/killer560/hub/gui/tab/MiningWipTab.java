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
 *  from {@link NewTab}: the 1.3 release ships without most mining work, so most of what's left in the "Planned"
 *  sub-tab below is not expected to work yet. Real, working tabs get added to {@link #buildTabs()} as they're
 *  built (2026-09-27: Profit Per Hour, Nucleus Run Profit and the Crystal Hollows Map are wired and working;
 *  Auto Commissions/Auto Nucleus Run/Auto Crystal are cheat-build settings only - see each tab's own class doc). */
public class MiningWipTab extends FolderTab {

    public MiningWipTab() {
        super("Mining (WIP)", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>();
        // Added 2026-09-27 per killer560's mining feature request (verbatim: "auto commissions auto nuc
        // run interactive map for ch auto crystal crystal hollows map. Profit per hour tracker nuc run
        // profit tracker"). See each tab's own class doc for what is actually wired vs setting-only.
        tabs.add(new MiningProfitTab());
        tabs.add(new NucleusRunProfitTab());
        tabs.add(new CrystalHollowsMapTab());
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // NOT WIRED - settings only, see each tab's own class doc for exactly why.
            tabs.add(new AutoCommissionsTab());
            tabs.add(new AutoNucleusRunTab());
            tabs.add(new AutoCrystalTab());
        }
        tabs.add(new PlannedTab());
        return tabs;
    }

    /** Read-only roadmap page so the tab isn't empty. */
    private static final class PlannedTab extends BaseTab {

        /** Cheat-build-only plans, listed separately so the legit jar's roadmap never names them
         *  (killer560, 2026-09-16: "If you are on the legit version it shouldnt mention cheat things at all"). */
        private static final String[] PLANNED_CHEAT = {
                // "Auto Crystal Nucleus runs" moved out of this parking-lot list 2026-09-27: it now has real
                // tabs (Auto Nucleus Run / Auto Crystal) that say plainly they are NOT wired yet and exactly
                // what real automation would still need, instead of one vague roadmap line.
        };

        private static final String[] PLANNED = {
                "Commission display - HUD with every commission's progress + completion alerts",
                "Crystal Nucleus helper - crystals found/placed tracker, part locations per zone (profit"
                        + " tracking itself is done - see the Nucleus Run Profit tab)",
                "Carpet highlight while on a mining island",
                "Mines of Divan metal detector solver",
                "Crystal Hollows chest lockpick solver + treasure chest highlight",
                "Goblin Queen's Den / Jungle Temple / Precursor City / Khazad-dum waypoints + shared coords",
                "Powder tracker (mithril / gemstone / glacite per hour)",
                "Mining ability cooldown + Sky Mall perk display",
                "Glacite Mineshaft helpers - corpse highlight, mineshaft entrance alert, shaft type",
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
            }            if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                for (String line : PLANNED_CHEAT) {
                    widgets.add(new StringWidget(contentX, y, contentWidth, 10,
                            Component.literal("§c- §f" + line), font));
                    y += 13;
                }
            }

            return widgets;
        }
    }
}

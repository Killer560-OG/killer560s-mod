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
        // Only things that actually WORK get a tab. killer560 (2026-09-27): "there should be another like
        // planned mining stuff tab that also is just things coming soon. Make sure all of that is under that
        // original tab not two separate ones." Auto Commissions / Auto Nucleus Run / Auto Crystal briefly had a
        // tab each, which meant coming-soon work lived in two places at once - three stub tabs whose only content
        // was "NOT WIRED YET", plus the Planned page. They are lines on the Planned page now, where the rest of
        // the not-yet-built mining work already lives.
        tabs.add(new MiningProfitTab());
        tabs.add(new NucleusRunProfitTab());
        tabs.add(new CrystalHollowsMapTab());
        tabs.add(new PlannedTab());
        return tabs;
    }

    /** Read-only roadmap page so the tab isn't empty. */
    private static final class PlannedTab extends BaseTab {

        /** Cheat-build-only plans, listed separately so the legit jar's roadmap never names them
         *  (killer560, 2026-09-16: "If you are on the legit version it shouldnt mention cheat things at all"). */
        private static final String[] PLANNED_CHEAT = {
                // These briefly had a tab each, holding nothing but a red "NOT WIRED YET" label. That put
                // coming-soon work in two places at once, so they are back here as what they are - planned.
                // The blockers are real and worth keeping written down rather than rediscovering:
                "Auto Commissions - needs Dwarven Mines terrain pathfinding (AP3 and autoclear only path the"
                        + " dungeon's fixed room grid) and commission-objective parsing",
                "Auto Crystal - needs a reliable way to tell a real crystal block from terrain; there is none"
                        + " in this mod yet",
                "Auto Nucleus Run - needs both of the above, plus Nucleus combat and looting",
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

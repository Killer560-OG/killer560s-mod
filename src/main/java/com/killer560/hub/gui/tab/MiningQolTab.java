package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mining category's tabs before 2.0 (killer560's to-do Doc, 2026-11-01: only these mining features before 2.0; the
 * rest of mining stays on the post-2.0 staging branches). {@link MenuCategories} puts them under "Mining".
 *
 * <p>Grouped under three titled runs of cards, like Secrets and Puzzle Solvers (killer560, 2026-11-03: "make a separation
 * in mining for mithril, glacite powder, and gemstone and stuff like that so it is easier to see them"): Commissions and
 * Mithril, Powder Mining and General. A group's cheat-only cards follow its legit ones under a red "Cheat" header (the
 * Structure Scanner under the Crystal Hollows Map, killer560 2026-11-04: "the cheat map scanner should be under the crystal
 * hollows map"); a cheat card of no group (Swap To Drill On Pigeon) sits in the category's one cheat block.
 */
public class MiningQolTab extends FolderTab {

    public MiningQolTab() {
        super("Mining", tabs());
    }

    private static List<BaseTab> tabs() {
        List<BaseTab> t = new ArrayList<>(List.of(new CommissionsMithrilGroup(), new DwarvenGlaciteGroup(),
                new PowderMiningGroup(), new CrystalHollowsGroup(), new MiningGeneralGroup()));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            t.add(new MiningPigeonDrillTab());
            // From staging-mining-3 (2026-11-04): draws through walls. (The Structure Scanner is in the Crystal Hollows group.)
            t.add(new GhostEspTab());
        }
        return t;
    }

    /** Commission HUD / Route, Mithril As Stone, Titanium Highlight, Raffle Ticket Highlight (the Dwarven Mines). */
    static final class CommissionsMithrilGroup extends FolderTab {
        CommissionsMithrilGroup() {
            super("Commissions and Mithril", List.of(new MiningCommissionHudTab(), new MiningCommissionRouteTab(),
                    new MiningMithrilStoneTab(), new MiningTitaniumTab(), new MiningRaffleTab()));
        }
    }

    /** The Crystal Hollows powder grinding tabs (com.killer560.hub.powdermining) and the Powder Ghast. */
    static final class PowderMiningGroup extends FolderTab {
        PowderMiningGroup() {
            super("Powder Mining", PowderMiningTabs.with(List.of(new MiningPowderGhastTab())));
        }
    }

    /** Fetchur, Puzzler, the Glacite Tunnels' Cold and the Fossil Excavator (from staging-mining-3, 2026-11-04). */
    static final class DwarvenGlaciteGroup extends FolderTab {
        DwarvenGlaciteGroup() {
            super("Dwarven Mines and Glacite", List.of(new FetchurSolverTab(), new PuzzlerSolverTab(),
                    new ColdOverlayTab(), new FossilSolverTab()));
        }
    }

    /** The Crystal Hollows: its map, crystals, solvers, worms, lobby and Nucleus runs (staging-mining-3, 2026-11-04). */
    static final class CrystalHollowsGroup extends FolderTab {
        CrystalHollowsGroup() {
            super("Crystal Hollows", tabs());
        }

        private static List<BaseTab> tabs() {
            List<BaseTab> t = new ArrayList<>(List.of(new CrystalHollowsMapTab(), new CrystalTrackerTab(),
                    new WishingCompassTab(), new MetalDetectorTab(), new WormAlertTab(), new LobbyTrackerTab(),
                    new NucleusRunProfitTab()));
            if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
                t.add(new StructureScannerTab()); // reads blocks no player could see
            }
            return t;
        }
    }

    /** Everything that is not tied to one kind of mining. */
    static final class MiningGeneralGroup extends FolderTab {
        MiningGeneralGroup() {
            super("General", List.of(new MiningAbilityReadyTab(), new MiningDarkStoneTab(), new MiningPickobulusTab(), new MiningCarpetTab(),
                    new MiningResumeBreakTab(), new MiningWrongArmorTab(), new MiningEventsTab(), new MiningProfitTab(),
                    new SkyMallDisplayTab(), new MiningGoldenGoblinTab()));
        }
    }
}

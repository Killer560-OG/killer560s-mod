package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/**
 * The Mining category's tabs before 2.0 (killer560's to-do Doc, 2026-11-01: only these mining features before 2.0; the
 * rest of mining stays on the post-2.0 staging branches). {@link MenuCategories} puts them under "Mining".
 *
 * <p>Grouped under three titled runs of cards, like Secrets and Puzzle Solvers (killer560, 2026-11-03: "make a separation
 * in mining for mithril, glacite powder, and gemstone and stuff like that so it is easier to see them"): Commissions &
 * Mithril, Powder Mining and General. A group holds legit cards only; the cheat-only card (Swap To Drill On Pigeon) stays
 * outside the groups, in the category's one cheat block.
 */
public class MiningQolTab extends FolderTab {

    public MiningQolTab() {
        super("Mining", tabs());
    }

    private static List<BaseTab> tabs() {
        List<BaseTab> t = new ArrayList<>(List.of(new CommissionsMithrilGroup(), new PowderMiningGroup(), new MiningGeneralGroup()));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            t.add(new MiningPigeonDrillTab());
        }
        return t;
    }

    /** Commission HUD / Route, Mithril As Stone, Titanium Highlight, Raffle Ticket Highlight (the Dwarven Mines). */
    static final class CommissionsMithrilGroup extends FolderTab {
        CommissionsMithrilGroup() {
            super("Commissions & Mithril", List.of(new MiningCommissionHudTab(), new MiningCommissionRouteTab(),
                    new MiningMithrilStoneTab(), new MiningTitaniumTab(), new MiningRaffleTab()));
        }
    }

    /** The Crystal Hollows powder grinding tabs (com.killer560.hub.powdermining) and the Powder Ghast. */
    static final class PowderMiningGroup extends FolderTab {
        PowderMiningGroup() {
            super("Powder Mining", PowderMiningTabs.with(List.of(new MiningPowderGhastTab())));
        }
    }

    /** Everything that is not tied to one kind of mining. */
    static final class MiningGeneralGroup extends FolderTab {
        MiningGeneralGroup() {
            super("General", List.of(new MiningAbilityReadyTab(), new MiningPickobulusTab(), new MiningCarpetTab(),
                    new MiningResumeBreakTab(), new MiningWrongArmorTab()));
        }
    }
}

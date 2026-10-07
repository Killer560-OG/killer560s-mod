package com.killer560.hub.gui.tab;

import java.util.ArrayList;
import java.util.List;

/** The Items category (2026-10-07): the README's "Items, Inventory & Trading" features, moved here out of the New
 *  category when killer560 removed it. The cheat block is the same set, gated the same way, as it was in New. */
public class ItemsTab extends FolderTab {

    public ItemsTab() {
        super("Items", buildTabs());
    }

    private static List<BaseTab> buildTabs() {
        List<BaseTab> tabs = new ArrayList<>(List.of(
                new InventoryHudTab(),
                new InventorySearchTab(),
                new InventoryThemeTab(),
                new StorageSearchTab(),
                new SlotBindsTab(),
                new LoadoutKeybindsTab(),
                new PetWheelTab(),
                new ItemProtectTab(),
                new ArmourDyeTab(),
                new EnchantColorsTab(),
                new TooltipScrollTab(),
                new RevertMasterStarsTab(),
                new ItemBrowserTab(),
                new AuctionHouseTab(),
                new BazaarTab()
        ));
        if (com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            // Container-click and coin-spending automation: cheat build only, exactly as in New's cheat block.
            tabs.add(new InventorySorterTab());
            tabs.add(new AutoSellTab());
            tabs.add(new AutoAnvilTab());
            tabs.add(new AutoGfsTab());
            tabs.add(new BazaarFlipTab());
        }
        return tabs;
    }
}

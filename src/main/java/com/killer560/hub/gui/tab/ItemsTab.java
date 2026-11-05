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
                // Armor HUD (killer560, 2026-11-03).
                new ArmorHudTab(),
                new InventoryHudTab(),
                new InventorySearchTab(),
                new InventoryThemeTab(),
                // Hide Potion Effects (killer560, 2026-11-02): its own card again, beside the Inventory Theme it left.
                new HidePotionEffectsTab(),
                new StorageSearchTab(),
                new SlotBindsTab(),
                new SlotTextTab(),
                new CompactorPreviewTab(),
                // Gyrokinetic Wand Overlay (to-do Doc 2026-11-01): Skyblocker's gyro ring.
                new GyroOverlayTab(),
                new LoadoutKeybindsTab(),
                new PetWheelTab(),
                new ItemProtectTab(),
                // Armor Recolor is Custom Items in the Cosmetics category since 2026-10-08.
                new EnchantColorsTab(),
                new PriceTooltipTab(),
                new TooltipScrollTab(),
                new RevertMasterStarsTab(),
                new ReforgeHelperTab(),
                new ItemBrowserTab(),
                new AuctionHouseTab(),
                new BazaarTab(),
                // Bazaar Order Helper (killer560, 2026-11-02): SkyHanni's order colors in Manage Orders.
                new BazaarOrderHelperTab(),
                new BazaarAdvisorTab(),
                new AhFlipperTab(),
                new AhPriceButtonsTab()
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

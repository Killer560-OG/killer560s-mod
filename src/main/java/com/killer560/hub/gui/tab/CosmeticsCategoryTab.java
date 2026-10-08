package com.killer560.hub.gui.tab;

import java.util.List;

/** The Cosmetics category (killer560, 2026-10-08: "Make a new cosmetics tab as a whole to move some things to, up to
 *  you. Move the armor recolor there and rename it Custom Items"). Purely visual, client-side features: Player
 *  Cosmetics (supporter names/sizes, Name Changer, player size, held item - the old Social > Cosmetics), Nickhider
 *  (from Social), Custom Items (Armour Recolour, from Items) and Trail (from Hud Elements). */
public class CosmeticsCategoryTab extends FolderTab {

    public CosmeticsCategoryTab() {
        super("Cosmetics", List.of(
                new CosmeticsTab(),
                new CustomItemsTab(),
                new NickhiderTab(),
                new TrailTab()
        ));
    }
}

package com.killer560.hub.gui.tab;

import java.util.List;

/** Folder tab grouping the mod's on-screen decorative HUD elements: GIF Player and DVD - split out
 *  from their own top-level slots (2026-09-07) per killer560's re-categorization request. Renamed from
 *  "Random Hud Elements" to just "Hud Elements" the same day. Object Hider added (2026-09-08) to hold
 *  toggles that hide specific vanilla screen elements, starting with the fire overlay.
 *  <p>
 *  2026-09-30: Object Hider's "Player Display: Hide" and "Hide Players" headers became sections of their
 *  own here (Health and Mana Bars / Hide Players) and its "Chat Replacements" header moved to the Chat
 *  folder, per killer560's re-grouping request. None of that changed a saved setting - all three still
 *  drive {@code ObjectHiderConfig} under its existing keys. */
public class HudElementsTab extends FolderTab {

    public HudElementsTab() {
        super("Hud Elements", List.of(
                new GifPlayerTab(),
                new DvdTab(),
                new ObjectHiderTab(),
                // Sits right after Object Hider, which is where both of these used to be a header.
                new HealthAndManaBarsTab(),
                new HidePlayersTab(),
                new StorageOverlayTab(),
                // Out of the New category, removed 2026-10-07 (killer560): the README's HUD features.
                new AbilityTimersTab(),
                new AbilityCooldownTab(),
                new QuiverDisplayTab(),
                new SpringBootsTab(),
                new CustomScoreboardTab(),
                new LagDisplayTab(),
                new PositionTab()
                // Trail moved to the Cosmetics category (2026-10-08).
        ));
    }
}

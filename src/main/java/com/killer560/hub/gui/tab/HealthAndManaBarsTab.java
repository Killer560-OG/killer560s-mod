package com.killer560.hub.gui.tab;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.gui.components.AbstractWidget;

import java.util.ArrayList;
import java.util.List;

/** Health and Mana Bars - the home for the mod's own player-status bars.
 *  <p>
 *  Created 2026-09-30 from killer560's request: "move the player display hide to another section called
 *  something like fancy bars or something where we will reskin the health hearts into a custom health bar
 *  and make a mana bar and whatnot." So far it holds only the vanilla-bar hides that used to sit under
 *  Object Hider's "Player Display: Hide" header - turning the vanilla hearts/armour/hunger off is the first
 *  half of replacing them, so they belong next to the replacements rather than in the generic hide pack.
 *  <p>
 *  This is a presentation move only: every toggle here still reads and writes the SAME
 *  {@link ObjectHiderConfig} fields under the same JSON keys it always did, so an existing config keeps
 *  whatever was set. The custom health bar and mana bar themselves are not built yet - they get their own
 *  section header under the hides when they land. */
public class HealthAndManaBarsTab extends BaseTab {

    public HealthAndManaBarsTab() {
        super("Health and Mana Bars");
    }

    @Override
    public List<AbstractWidget> buildWidgets(int contentX, int contentY, int contentWidth, Runnable requestRebuild) {
        List<AbstractWidget> widgets = new ArrayList<>();
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        int[] y = {contentY};

        ObjectHiderTab.header(widgets, contentX, contentWidth, y, "Hide Vanilla Bars");

        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Health", cfg::getHideHealthBarRaw, cfg::setHideHealthBar);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Absorption", cfg::getHideAbsorptionHeartsRaw, cfg::setHideAbsorptionHearts);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Mount Health", cfg::getHideMountHealthBarRaw, cfg::setHideMountHealthBar);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Regeneration Bounce", cfg::getHideRegenBounceRaw, cfg::setHideRegenBounce);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Armour", cfg::getHideArmorBarRaw, cfg::setHideArmorBar);
        ObjectHiderTab.toggle(widgets, contentX, contentWidth, y, cfg, requestRebuild,
                "Hunger", cfg::getHideHungerBarRaw, cfg::setHideHungerBar);

        return widgets;
    }
}

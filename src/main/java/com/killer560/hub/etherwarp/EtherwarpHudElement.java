package com.killer560.hub.etherwarp;

import com.killer560.hub.hud.HudElement;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draggable HUD list of etherwarp/secret-spot bookmarks saved for the room you're currently standing in -
 *  name + room-order number. Posmsg used to have a matching list; it was replaced with real in-world rings
 *  (2026-09-16, see {@code PosmsgRenderer}), so this one could get the same treatment via
 *  {@code WorldRenderUtils}. */
public final class EtherwarpHudElement implements HudElement {

    private static final int COLOR = 0xFFCC6600;

    @Override
    public String id() {
        return "etherwarp_waypoints";
    }

    @Override
    public String displayName() {
        return "Etherwarp Waypoints";
    }

    @Override
    public int defaultX() {
        return 10;
    }

    @Override
    public int defaultY() {
        return 220;
    }

    @Override
    public int width() {
        return 160;
    }

    @Override
    public int height() {
        return 12 * Math.max(1, EtherwarpFeature.waypointsHere().size());
    }

    @Override
    public boolean isEnabledInSettings() {
        // Deliberately false, not isEnabled(): the HUD list option was removed (killer560, 2026-09-21,
        // "remove the hud list option to etherwarp waypoints") and isVisible() below always returns false,
        // so this element can never appear in game. Reporting it as "on" would leave the HUD editor
        // permanently showing it as "on but never seen", which is the opposite of informative. It stays
        // registered only so old saved layouts still load.
        return false;
    }

    /** In-game gate used by {@code GuiOverlays}: any real menu hides the list, chat does not (killer560:
     *  "dont make it hide the gui if i open chat"), and the HUD editor draws this element itself. */
    public boolean isVisible() {
        // The HUD list is gone (killer560, 2026-09-21: "remove the hud list option to etherwarp waypoints") - the
        // box on the block is the feature. Kept registered so saved HUD layouts don't break; it never draws.
        return false;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (!EtherwarpWaypointsConfig.getInstance().isEnabled()) {
            return;
        }
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        int lineY = y;
        // Room-relative storage (2026-09-27) means a waypoint only has a real position while you're
        // standing in the room it belongs to - see EtherwarpFeature.waypointsHere() - so this preview (the
        // HUD editor is the only thing that still calls render() for this element; see isVisible() above)
        // shows the room-order number instead of a live distance.
        for (EtherwarpWaypoint waypoint : EtherwarpFeature.waypointsHere()) {
            graphics.fill(x, lineY + 1, x + 8, lineY + 9, COLOR);
            graphics.text(client.font, "#" + waypoint.order + " " + waypoint.name, x + 12, lineY, 0xFFFFFFFF, false);
            lineY += 12;
        }
    }
}

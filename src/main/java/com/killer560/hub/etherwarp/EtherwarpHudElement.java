package com.killer560.hub.etherwarp;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudVisibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Draggable HUD list of this run's etherwarp/secret-spot bookmarks - name + live distance. Posmsg used
 *  to have a matching list; it was replaced with real in-world rings (2026-09-16, see
 *  {@code PosmsgRenderer}), so this one could get the same treatment via {@code WorldRenderUtils}. */
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
        return 12 * Math.max(1, EtherwarpFeature.waypoints().size());
    }

    @Override
    public boolean isRelevantNow() {
        return EtherwarpWaypointsConfig.getInstance().isEnabled();
    }

    /** In-game gate used by {@code EtherwarpGuiMixin}: any real menu hides the list, chat does not (killer560:
     *  "dont make it hide the gui if i open chat"), and the HUD editor draws this element itself. */
    public boolean isVisible() {
        return !HudVisibility.menuOpen();
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (!EtherwarpWaypointsConfig.getInstance().isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        int lineY = y;
        for (EtherwarpWaypoint waypoint : EtherwarpFeature.waypoints()) {
            graphics.fill(x, lineY + 1, x + 8, lineY + 9, COLOR);
            String distanceText = player == null ? "" : String.format(Locale.US, " (%.0fm)",
                    player.position().distanceTo(new Vec3(waypoint.x, waypoint.y, waypoint.z)));
            graphics.text(client.font, waypoint.name + distanceText, x + 12, lineY, 0xFFFFFFFF, false);
            lineY += 12;
        }
    }
}

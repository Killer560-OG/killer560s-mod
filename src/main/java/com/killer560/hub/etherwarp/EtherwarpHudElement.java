package com.killer560.hub.etherwarp;

import com.killer560.hub.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/** Draggable HUD list of this run's etherwarp/secret-spot bookmarks - name + live distance, same visual
 *  shape as {@link com.killer560.hub.posmsg.PosmsgHudElement} (a plain text list, no in-world 3D marker -
 *  see that class's own note on why this codebase has no verified hook for real 3D world rendering yet). */
public final class EtherwarpHudElement implements HudElement {

    private static final int COLOR = 0xFF60A5FA;

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

    public boolean isVisible() {
        return Minecraft.getInstance().screen == null;
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

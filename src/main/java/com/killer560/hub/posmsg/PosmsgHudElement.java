package com.killer560.hub.posmsg;

import com.killer560.hub.hud.HudElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Draggable HUD list of every currently-active Posmsg waypoint (this player's own configured
 *  presets, plus anything received from a party member's chat this run) - name, a color swatch
 *  matching {@link PosmsgEntry#colorHex}/the received-marker default, and live distance. Each line
 *  respects its "show display" toggle and, if set, the "only inside radius" toggle - a line that's
 *  hidden by radius simply isn't drawn rather than being greyed out, keeping the list itself the
 *  main declutter mechanism killer560 asked for rather than a text-only marker in-world (the literal
 *  ground-circle ESP visual he described is real 3D world rendering this codebase has no existing,
 *  verified hook for yet - see the Posmsg tab's own note on this). */
public final class PosmsgHudElement implements HudElement {

    private static final int RECEIVED_DEFAULT_COLOR = 0xFFCC6600;

    @Override
    public String id() {
        return "posmsg_waypoints";
    }

    @Override
    public String displayName() {
        return "Posmsg Waypoints";
    }

    @Override
    public int defaultX() {
        return 10;
    }

    @Override
    public int defaultY() {
        return 120;
    }

    @Override
    public int width() {
        return 160;
    }

    @Override
    public int height() {
        return 12 * Math.max(1, activeCount());
    }

    /** Only during normal gameplay - matches every other always-on overlay in this mod (no point
     *  drawing a waypoint list over the inventory or a mod screen). */
    public boolean isVisible() {
        return Minecraft.getInstance().screen == null;
    }

    @Override
    public void render(GuiGraphicsExtractor graphics, int x, int y) {
        if (!PosmsgConfig.getInstance().isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        int lineY = y;
        for (Line line : activeLines(player)) {
            graphics.fill(x, lineY + 1, x + 8, lineY + 9, line.color);
            String text = line.distanceText == null
                    ? line.name
                    : String.format(Locale.US, "%s (%s)", line.name, line.distanceText);
            graphics.text(client.font, text, x + 12, lineY, 0xFFFFFFFF, false);
            lineY += 12;
        }
    }

    private int activeCount() {
        return Math.max(1, activeLines(Minecraft.getInstance().player).size());
    }

    private List<Line> activeLines(Player player) {
        List<Line> lines = new ArrayList<>();
        for (PosmsgEntry e : PosmsgConfig.getInstance().entries()) {
            if (!e.enabled || !e.configured || !e.showDisplay) {
                continue;
            }
            Double distance = distanceTo(player, e.x, e.y, e.z);
            if (e.showOnlyInsideRadius && (distance == null || distance > e.radius)) {
                continue;
            }
            lines.add(new Line(e.name, e.color(), formatDistance(distance)));
        }
        for (PosmsgFeature.ReceivedMarker m : PosmsgFeature.receivedMarkers().values()) {
            Double distance = distanceTo(player, m.x(), m.y(), m.z());
            lines.add(new Line(m.name(), RECEIVED_DEFAULT_COLOR, formatDistance(distance)));
        }
        return lines;
    }

    private static Double distanceTo(Player player, double x, double y, double z) {
        if (player == null) {
            return null;
        }
        return player.position().distanceTo(new net.minecraft.world.phys.Vec3(x, y, z));
    }

    private static String formatDistance(Double distance) {
        return distance == null ? null : String.format(Locale.US, "%.0fm", distance);
    }

    private record Line(String name, int color, String distanceText) {
    }
}

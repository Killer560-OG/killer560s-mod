package com.killer560.hub.terminalaura;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Finding P3's "Inactive Terminal" marker entities, shared by {@link TerminalAuraFeature} (anything in
 *  range) and {@code TerminalTriggerbotFeature} (only what the crosshair is actually on).
 *  <p>
 *  These armour stands are what a real right-click interacts with, but they don't reliably show up in
 *  {@code Minecraft#hitResult} - a marker stand has no pickable hitbox - so the triggerbot ray-traces
 *  against their bounding boxes here rather than reading the client's own crosshair target. */
public final class TerminalStands {

    /** Hypixel's nametag for a terminal that hasn't been opened yet. */
    public static final String INACTIVE_TERMINAL = "Inactive Terminal";
    /** How much the stands' boxes are grown before testing, matching QUOI's own interact inflation. */
    public static final double INFLATE = 0.1;

    private TerminalStands() {
    }

    public static boolean isInactiveTerminal(ArmorStand stand) {
        if (stand == null || stand.isRemoved() || !stand.isAlive() || stand.getDisplayName() == null) {
            return false;
        }
        return stand.getDisplayName().getString().contains(INACTIVE_TERMINAL);
    }

    public static List<ArmorStand> near(ClientLevel level, Player player, double range) {
        return level.getEntitiesOfClass(ArmorStand.class, player.getBoundingBox().inflate(range),
                TerminalStands::isInactiveTerminal);
    }

    /** The centre of a stand's box - what both features aim their interact at. */
    public static Vec3 center(ArmorStand stand) {
        return stand.position().add(0, stand.getBbHeight() / 2.0, 0);
    }

    /**
     * The nearest inactive terminal the player is actually looking at.
     *
     * @return the stand and the exact point on it the look vector meets, or null if none is in the way
     */
    public static Hit raycast(ClientLevel level, Player player, double range, float partialTick) {
        Vec3 eyes = player.getEyePosition(partialTick);
        Vec3 end = eyes.add(player.getViewVector(partialTick).scale(range));
        Hit best = null;
        for (ArmorStand stand : near(level, player, range)) {
            AABB box = stand.getBoundingBox().inflate(INFLATE);
            Vec3 hit = box.clip(eyes, end).orElse(null);
            if (hit == null) {
                continue;
            }
            double distance = eyes.distanceToSqr(hit);
            if (best == null || distance < best.distanceSqr()) {
                best = new Hit(stand, hit, distance);
            }
        }
        return best;
    }

    public record Hit(ArmorStand stand, Vec3 point, double distanceSqr) {
    }
}

package com.killer560.hub.autoclear;

import com.killer560.hub.livemap.autoclear.TeleportUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * What each Auto Clear weapon really reaches, and where to stand / aim so it hits a mob - including a mob inside a wall
 * or a block, since both abilities are EXPLOSIONS measured from where they go off, not from a line of sight to the mob.
 *
 * <h2>The numbers (hypixelskyblock.minecraft.wiki, fetched 2026-10-06)</h2>
 * <ul>
 *   <li><b>Hyperion, Wither Impact</b>: "Teleports 10 blocks ahead of you dealing 10,000 Damage damage to nearby
 *       enemies within a 6 block radius." (page "Wither Impact"; the Implosion scroll line reads "-4 Wither Impact
 *       implosion radius (from 10 to 6)"). So: a 10-block dash along the look that stops at what is in the way, then a
 *       6-block blast where he LANDED. Aimed straight down the dash goes nowhere and the blast is where he stands.</li>
 *   <li><b>Spirit Sceptre, Guided Bat</b>: "Upon hitting a mob or block, the bat creates an explosion with a radius of
 *       6 blocks". The wiki gives NO range or speed for the bat; the only number in this repo is the sim's
 *       {@code SimSpiritSceptre} (1.02 blocks/tick for 29 ticks, ~29.6 blocks), tuned by killer560's feel, not
 *       measured on Hypixel - so planning stays well inside it ({@link #SCEPTRE_PLAN_RANGE}).</li>
 * </ul>
 * Distances are measured from the blast centre to the mob's BOX (its nearest point), the way the sim's blasts measure
 * them, and every plan keeps {@link #MARGIN} inside the radius.
 */
public final class WeaponReach {

    /** Wither Impact's dash length (wiki: "Teleports 10 blocks ahead of you"). */
    public static final double HYPERION_TELEPORT = 10.0;
    /** Wither Impact's blast radius (wiki: "within a 6 block radius"). */
    public static final double HYPERION_RADIUS = 6.0;
    /** Guided Bat's blast radius (wiki: "an explosion with a radius of 6 blocks"). */
    public static final double SCEPTRE_RADIUS = 6.0;
    /** How far a bat is trusted to fly. The sim flies ~29.6 (unmeasured on Hypixel); 24 leaves room for error. */
    public static final double SCEPTRE_PLAN_RANGE = 24.0;
    /** Planned inside each radius by this much: lag, a mob shifting, the box/centre the server measures from. */
    public static final double MARGIN = 1.0;
    /** The blast centre above his feet: half a standing player (the sim measures from there). */
    private static final double BLAST_ABOVE_FEET = 0.9;

    private WeaponReach() {
    }

    /** A body aim (yaw on any wrapping, pitch within +-90) and what it is expected to do. */
    public record Aim(float yaw, float pitch, Vec3 expectFeet, String what) {
    }

    // ------------------------------------------------------------------------------------------- Hyperion

    /** Whether a Wither Impact going off with his feet at {@code feet} reaches the mob's box. */
    public static boolean hyperionHits(Vec3 feet, AABB mob) {
        double r = HYPERION_RADIUS - MARGIN;
        return mob.distanceToSqr(feet.add(0, BLAST_ABOVE_FEET, 0)) <= r * r;
    }

    /** How far past the planned radius the mob still is from {@code feet} (<= 0 means it hits). */
    static double hyperionShortfall(Vec3 feet, AABB mob) {
        return Math.sqrt(mob.distanceToSqr(feet.add(0, BLAST_ABOVE_FEET, 0))) - (HYPERION_RADIUS - MARGIN);
    }

    /** Straight down: the dash goes nowhere and the blast is where he stands. */
    public static Aim hyperionInPlace(Player player, Vec3 feet) {
        return new Aim(player.getYRot(), 90f, feet, "Wither Impact in place");
    }

    /**
     * One Hyperion hop toward the mob: candidate looks around the direction to it, each landing predicted by the same
     * dash model the sim's server uses ({@code SimAbilities.dashTarget}, measured against 52 Hypixel teleports), and the
     * one landing closest to the mob kept. Null when no candidate gets him at least 2 blocks closer.
     */
    public static Aim hyperionHop(Level level, Player player, Vec3 feet, AABB mob) {
        Vec3 eye = feet.add(0, player.getEyeHeight(), 0);
        Vec3 centre = mob.getCenter();
        TeleportUtils.Rotation direct = TeleportUtils.getDirection(eye, centre);
        double now = hyperionShortfall(feet, mob);
        Aim best = null;
        double bestShort = now - 2.0;
        float[] yawOffsets = {0f, -12f, 12f, -25f, 25f, -40f, 40f};
        float[] pitches = {direct.pitch(), 0f, -8f, 8f, 18f, -18f};
        for (float dy : yawOffsets) {
            for (float p : pitches) {
                float yaw = direct.yaw() + dy;
                float pitch = Mth.clamp(p, -89f, 89f);
                Vec3 look = TeleportUtils.getLook(yaw, pitch);
                Vec3 land = com.killer560.hub.roomsim.SimAbilities.dashTarget(level, player, feet, look, HYPERION_TELEPORT);
                if (land == null || land.distanceToSqr(feet) < 1.0) {
                    continue;
                }
                double s = hyperionShortfall(land, mob);
                if (s < bestShort) {
                    bestShort = s;
                    best = new Aim(yaw, pitch, land, s <= 0 ? "Wither Impact hop onto the mob" : "Wither Impact hop toward it");
                }
                if (s <= 0 && dy == 0f) {
                    return best;   // the straight line lands in range: no need to look further
                }
            }
        }
        return best;
    }

    /** Hops a greedy chain of {@link #hyperionHop}s needs to get the mob in range, or -1 past {@code maxHops}. */
    public static int hyperionHopsNeeded(Level level, Player player, Vec3 feet, AABB mob, int maxHops) {
        Vec3 at = feet;
        for (int hops = 0; hops <= maxHops; hops++) {
            if (hyperionHits(at, mob)) {
                return hops;
            }
            Aim next = hyperionHop(level, player, at, mob);
            if (next == null || next.expectFeet() == null) {
                return -1;
            }
            at = next.expectFeet();
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------- Spirit Sceptre

    /**
     * Where a bat fired from {@code eye} along {@code dir} explodes: the first block (collision shape) or living mob it
     * meets, else in the air at {@link #SCEPTRE_PLAN_RANGE}. Armour stands (the star tags) are markers and are skipped.
     */
    public static Vec3 batImpact(Level level, Player player, Vec3 eye, Vec3 dir) {
        Vec3 end = eye.add(dir.scale(SCEPTRE_PLAN_RANGE));
        BlockHitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player));
        Vec3 stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        Vec3 impact = stop;
        double best = eye.distanceToSqr(stop);
        for (Entity e : level.getEntities(player, new AABB(eye, stop).inflate(1.0),
                e -> e instanceof LivingEntity && !(e instanceof ArmorStand) && e.isAlive())) {
            java.util.Optional<Vec3> hit = e.getBoundingBox().clip(eye, stop);
            if (hit.isPresent() && eye.distanceToSqr(hit.get()) < best) {
                best = eye.distanceToSqr(hit.get());
                impact = hit.get();
            }
        }
        return impact;
    }

    static boolean sceptreHits(Vec3 impact, AABB mob) {
        double r = SCEPTRE_RADIUS - MARGIN;
        return mob.distanceToSqr(impact) <= r * r;
    }

    /**
     * An aim from where he stands whose bat goes off within reach of the mob: straight at it first, then at points of
     * its box, then a fan of looks around it (which is what finds a wall face next to a mob standing inside the wall).
     * Null when nothing from here reaches it.
     */
    public static Aim sceptreAim(Level level, Player player, Vec3 eye, AABB mob) {
        List<Vec3> points = new ArrayList<>();
        Vec3 c = mob.getCenter();
        points.add(c);
        points.add(new Vec3(c.x, mob.maxY - 0.2, c.z));
        points.add(new Vec3(c.x, mob.minY + 0.2, c.z));
        for (Vec3 p : points) {
            if (eye.distanceTo(p) > SCEPTRE_PLAN_RANGE) {
                continue;
            }
            TeleportUtils.Rotation r = TeleportUtils.getDirection(eye, p);
            Vec3 impact = batImpact(level, player, eye, TeleportUtils.getLook(r.yaw(), r.pitch()));
            if (sceptreHits(impact, mob)) {
                return new Aim(r.yaw(), Mth.clamp(r.pitch(), -90f, 90f), null, "Guided Bat at the mob");
            }
        }
        if (eye.distanceTo(c) > SCEPTRE_PLAN_RANGE + SCEPTRE_RADIUS) {
            return null;
        }
        TeleportUtils.Rotation direct = TeleportUtils.getDirection(eye, c);
        Aim best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dy = -40; dy <= 40; dy += 5) {
            for (int dp = -45; dp <= 45; dp += 5) {
                float yaw = direct.yaw() + dy;
                float pitch = Mth.clamp(direct.pitch() + dp, -90f, 90f);
                Vec3 impact = batImpact(level, player, eye, TeleportUtils.getLook(yaw, pitch));
                if (!sceptreHits(impact, mob)) {
                    continue;
                }
                double d = mob.distanceToSqr(impact);
                if (d < bestDist) {
                    bestDist = d;
                    best = new Aim(yaw, pitch, null, "Guided Bat into the block beside the mob");
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------- standing spots

    /**
     * A block to etherwarp ONTO (solid, two air above - what {@code ClearExecutor.etherPath} wants) from which the
     * weapon reaches the mob: for the Hyperion the blast where he lands, for the sceptre a bat fired straight down at his
     * own feet. Nearest to the mob first, then nearest to him. Null when none within 5 blocks of it.
     */
    public static BlockPos standSpot(Level level, Player player, AABB mob, AutoClearConfig.Weapon weapon) {
        BlockPos base = BlockPos.containing(mob.getCenter());
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -5; dy <= 3; dy++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    Vec3 feet = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
                    boolean hits = weapon == AutoClearConfig.Weapon.HYPERION ? hyperionHits(feet, mob)
                            : sceptreHits(feet, mob);
                    if (!hits || !TeleportUtils.etherwarpable(pos)) {
                        continue;
                    }
                    double score = mob.distanceToSqr(feet.add(0, 1.0, 0)) * 4.0 + feet.distanceToSqr(player.position()) * 0.01;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }
}

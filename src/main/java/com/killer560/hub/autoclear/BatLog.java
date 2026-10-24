package com.killer560.hub.autoclear;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.livemap.autoclear.ClearExecutor;
import com.killer560.hub.livemap.autoclear.TeleportUtils;
import com.killer560.hub.mobesp.MobEspFeature;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.ViewFreeze;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every Spirit Sceptre bat's life, one log line per event (killer560, 2026-10-20: "many loggers" for each bat), logger
 * {@code killer560smod-bats}, every line starting {@code [Bat] #N} so one shot greps out whole:
 * <ul>
 *   <li>{@code [Bat] #N use} - the use packet as it leaves: the client tick, his feet and eye, the look the packet carried,
 *       the camera's view, and the target (entity id, type, name tag, distance, velocity a tick, health) with how far the
 *       look is off it in degrees (total, yaw and pitch).</li>
 *   <li>{@code [Bat] #N seen} - the bat entity that showed up for that use (id, ticks after the use, where).</li>
 *   <li>{@code [Bat] #N fly} - its position every tick: the step, its distance off the launch look and off the look the
 *       server has now, and its distance to the target.</li>
 *   <li>{@code [Bat] #N hit} - the tick it went (removed, or no bat at all), where, flight ticks and blocks, speed.</li>
 *   <li>{@code [Bat] #N result} - {@link #AFTER_TICKS} later: the target's health and name tag before and after (the
 *       damage), whether it died, and whether the bat flew along the look or bent toward the target.</li>
 * </ul>
 * Passive: it reads the use packets ({@code RightClickSendMixin}) and the client's entities, and sends nothing. The target
 * is the one a caller named ({@link #expectTarget}: Auto Clear's mob, /test's mob), else the living mob nearest the look.
 */
public final class BatLog {

    static final Logger LOGGER = ModLog.get("killer560smod-bats");

    /** A bat counts as this use's only if it shows up within this many ticks of it... */
    static final int APPEAR_TICKS = 12;
    /** ...within this distance of the eye at the use, plus a bat's flight a tick for every tick since. */
    static final double APPEAR_RADIUS = 3.0;
    /** A bat still flying after this long is logged as still flying and let go. */
    static final int MAX_FLIGHT_TICKS = 100;
    /** Ticks after the bat goes before the target's health is read again (the damage reaches the client later). */
    static final int AFTER_TICKS = 3;
    /** A target hint older than this when the use goes out is not this use's. */
    static final int HINT_TICKS = 6;
    /** Without a hint: the mob nearest the look within this angle and distance is taken as the target. */
    static final double TARGET_CONE_DEG = 25.0;
    static final double TARGET_RANGE = 40.0;
    /** A flight that stays within this (blocks) of the line it flew is straight; a centre passed this much nearer than a
     *  straight bat along the look would pass it means it bent toward the target. */
    static final double STRAIGHT_BLOCKS = 0.75;
    /** A straight flight whose line is within this many degrees of the launch look flew along the look. */
    static final double ALONG_DEG = 5.0;

    /** A position the server sent for a bat, and the client tick it was first seen. */
    record Sample(long tick, Vec3 pos) {
    }

    /** One shot: from its use packet to its result. */
    public static final class Shot {
        public final int no;
        public final long useTick;
        public final Vec3 feet;
        public final Vec3 eye;
        public final float yaw;
        public final float pitch;
        public final Vec3 look;
        public final int targetId;
        public final String targetName;
        public final double targetDist;
        public final Vec3 targetCentre;
        public final double lookOffDeg;
        public final double yawOffDeg;
        public final double pitchOffDeg;
        public final float healthBefore;
        public final String tagBefore;
        /** Closest the straight launch look passes the target centre (blocks): where a straight bat would go by it. */
        public final double rayMissBlocks;

        int batId = -1;
        long seenTick = -1;
        /** Each new position the server sent for the bat, with the tick it was seen. */
        final List<Sample> samples = new ArrayList<>();
        /** Degrees between the launch look and the line the bat flew (first seen to last seen); NaN under two points. */
        public double flightOffDeg = Double.NaN;
        /** The most any seen position lies off that line (blocks): how far the flight bowed. */
        public double bowBlocks = Double.NaN;
        Vec3 last;
        /** The last position the server sent for the bat. */
        Vec3 lastServer;
        Vec3 batStart;
        double flown;
        double maxOffLaunch;
        double endOffLaunch;
        double endOffNow;
        double closestToTarget = Double.MAX_VALUE;
        long endTick = -1;
        Vec3 end;
        String how;
        boolean done;
        public float healthAfter = Float.NaN;
        public String tagAfter = "";
        public boolean targetGone;
        public String curve = "";

        Shot(int no, long useTick, Vec3 feet, Vec3 eye, float yaw, float pitch, Entity target, Minecraft client) {
            this.no = no;
            this.useTick = useTick;
            this.feet = feet;
            this.eye = eye;
            this.yaw = yaw;
            this.pitch = pitch;
            this.look = TeleportUtils.getLook(yaw, pitch);
            this.targetId = target == null ? -1 : target.getId();
            this.targetName = target == null ? "none" : target.getName().getString();
            this.targetCentre = target == null ? null : target.getBoundingBox().getCenter();
            this.targetDist = target == null ? -1 : eye.distanceTo(targetCentre);
            if (target != null) {
                TeleportUtils.Rotation direct = TeleportUtils.getDirection(eye, targetCentre);
                this.yawOffDeg = Mth.wrapDegrees(yaw - direct.yaw());
                this.pitchOffDeg = pitch - direct.pitch();
                Vec3 to = targetCentre.subtract(eye).normalize();
                this.lookOffDeg = Math.toDegrees(Math.acos(Mth.clamp(look.dot(to), -1.0, 1.0)));
                this.rayMissBlocks = offRay(eye, look, targetCentre);
            } else {
                this.yawOffDeg = Double.NaN;
                this.pitchOffDeg = Double.NaN;
                this.lookOffDeg = Double.NaN;
                this.rayMissBlocks = Double.NaN;
            }
            this.healthBefore = target instanceof LivingEntity l ? l.getHealth() : Float.NaN;
            this.tagBefore = target == null ? "" : nameTag(client, target);
        }

        public boolean done() {
            return done;
        }

        public boolean batSeen() {
            return batId >= 0;
        }

        /** Ticks from the use to the bat going (-1: no bat). */
        public int flightTicks() {
            return batId < 0 || endTick < 0 ? -1 : (int) (endTick - useTick);
        }

        /**
         * Blocks a tick while the client saw it fly, without the round trip: a least-squares line through each position
         * update the server sent (distance along the flight's line against the tick it arrived). A server sends an
         * entity's position every few ticks, not every tick, so summing tick-to-tick steps reads the bursts, not the
         * speed. -1 with fewer than two updates.
         */
        public double trackedSpeed() {
            if (samples.size() < 2) {
                return -1;
            }
            Vec3 first = samples.get(0).pos();
            Vec3 dir = samples.get(samples.size() - 1).pos().subtract(first);
            if (dir.lengthSqr() < 1e-6) {
                return 0;
            }
            dir = dir.normalize();
            double tm = 0;
            double am = 0;
            for (Sample p : samples) {
                tm += p.tick();
                am += p.pos().subtract(first).dot(dir);
            }
            tm /= samples.size();
            am /= samples.size();
            double num = 0;
            double den = 0;
            for (Sample p : samples) {
                double dt = p.tick() - tm;
                num += dt * (p.pos().subtract(first).dot(dir) - am);
                den += dt * dt;
            }
            return den <= 0 ? -1 : num / den;
        }

        /** Position updates the server sent while it flew (distinct positions, with the tick each was seen). */
        public int updates() {
            return samples.size();
        }

        /** Straight-line blocks from the eye at the use to where the bat went. */
        public double reach() {
            return end == null ? -1 : eye.distanceTo(end);
        }

        public String how() {
            return how;
        }

        public double maxOffLaunch() {
            return maxOffLaunch;
        }

        public double closestToTarget() {
            return closestToTarget;
        }
    }

    private static final List<Shot> LIVE = new ArrayList<>();
    /** Every shot by number, the last 40 kept (for /test's summary). */
    private static final Map<Integer, Shot> BY_NO = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Shot> eldest) {
            return size() > 40;
        }
    };
    private static final Set<Integer> CLAIMED = new HashSet<>();
    private static int shots = 0;
    private static int hintTarget = -1;
    private static long hintTick = Long.MIN_VALUE;
    private static Object lastLevel;

    private BatLog() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("BatLog.tick", BatLog::tick));
    }

    /** The next sceptre use in the next few ticks is aimed at this entity (Auto Clear's mob, /test's mob). */
    public static void expectTarget(int entityId) {
        hintTarget = entityId;
        hintTick = ClearExecutor.clientTick();
    }

    /** Shots logged ever (only goes up); the last one's number. */
    public static int shots() {
        return shots;
    }

    public static Shot shot(int no) {
        return BY_NO.get(no);
    }

    // ------------------------------------------------------------------------------------------- the use packet

    /** From {@code RightClickSendMixin}, client thread, as a use packet leaves. Logs a shot when the hand holds a sceptre. */
    public static void onUseSent(LocalPlayer player, ServerboundUseItemPacket p) {
        if (player == null || !ItemIdentity.matches(player.getItemInHand(p.getHand()),
                AutoClearConfig.Weapon.SPIRIT_SCEPTRE.itemId())) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        long tick = ClearExecutor.clientTick();
        Vec3 eye = player.getEyePosition();
        Vec3 look = TeleportUtils.getLook(p.getYRot(), p.getXRot());
        Entity target = null;
        String chosen;
        if (hintTarget >= 0 && tick - hintTick <= HINT_TICKS) {
            target = client.level.getEntity(hintTarget);
            chosen = target == null ? "named by the caller, not loaded" : "named by the caller";
        } else {
            target = nearestToLook(client, player, eye, look);
            chosen = target == null ? "no mob within " + (int) TARGET_CONE_DEG + " deg of the look" : "nearest the look";
        }
        hintTarget = -1;
        Shot s = new Shot(++shots, tick, player.position(), eye, p.getYRot(), p.getXRot(), target, client);
        LIVE.add(s);
        BY_NO.put(s.no, s);
        float viewYaw = ViewFreeze.viewYaw();
        float viewPitch = ViewFreeze.viewPitch();
        Vec3 vel = target == null ? Vec3.ZERO : target.position().subtract(target.xo, target.yo, target.zo);
        LOGGER.info(String.format(Locale.US, "[Bat] #%d use tick %d seq %d | feet %s eye %s | packet look %.2f/%.2f |"
                        + " camera %s | target %s (%s): id %d %s tag '%s' dist %.2f vel %s (%.2f b/t) hp %s | look off %.2f deg"
                        + " (yaw %+.2f, pitch %+.2f), a straight bat passes it %.2f blocks away",
                s.no, tick, p.getSequence(), fmt(s.feet), fmt(eye), p.getYRot(), p.getXRot(),
                Float.isNaN(viewYaw) ? "not held (= look)" : String.format(Locale.US, "%.2f/%.2f", viewYaw, viewPitch),
                s.targetName, chosen, s.targetId, target == null ? "-" : target.getType().toShortString(), s.tagBefore,
                s.targetDist, fmt(vel), vel.length(), hp(target), s.lookOffDeg, s.yawOffDeg, s.pitchOffDeg, s.rayMissBlocks));
    }

    /** The living, non-marker mob nearest the look within {@link #TARGET_CONE_DEG}, starred ones first. */
    private static Entity nearestToLook(Minecraft client, LocalPlayer player, Vec3 eye, Vec3 look) {
        Entity best = null;
        double bestAngle = TARGET_CONE_DEG;
        List<Entity> starred = MobEspFeature.starredMobs(client);
        for (int pass = 0; pass < 2 && best == null; pass++) {
            Iterable<Entity> pool = pass == 0 ? starred
                    : client.level.getEntities(player, new AABB(eye, eye).inflate(TARGET_RANGE),
                    e -> e instanceof LivingEntity && !(e instanceof ArmorStand) && !(e instanceof Bat) && e.isAlive());
            for (Entity e : pool) {
                Vec3 c = e.getBoundingBox().getCenter();
                if (c.distanceTo(eye) > TARGET_RANGE) {
                    continue;
                }
                double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(c.subtract(eye).normalize()), -1.0, 1.0)));
                if (angle < bestAngle) {
                    bestAngle = angle;
                    best = e;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------- every tick

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            for (Shot s : LIVE) {
                LOGGER.info("[Bat] #{} dropped: the world changed", s.no);
            }
            LIVE.clear();
            CLAIMED.clear();
        }
        if (LIVE.isEmpty() || client.level == null || client.player == null) {
            return;
        }
        long now = ClearExecutor.clientTick();
        for (var it = LIVE.iterator(); it.hasNext(); ) {
            Shot s = it.next();
            try {
                if (step(client, s, now)) {
                    it.remove();
                    if (s.batId >= 0) {
                        CLAIMED.remove(s.batId);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.warn("[Bat] #" + s.no + " logging failed", e);
                it.remove();
            }
        }
    }

    /** @return true once the shot is finished (result logged) */
    private static boolean step(Minecraft client, Shot s, long now) {
        long since = now - s.useTick;
        if (s.endTick >= 0) {
            if (now - s.endTick < AFTER_TICKS) {
                return false;
            }
            result(client, s);
            return true;
        }
        if (s.batId < 0) {
            Bat bat = findBat(client, s, since);
            if (bat == null) {
                if (since >= APPEAR_TICKS) {
                    end(s, now, s.eye, "no bat seen within " + APPEAR_TICKS + " ticks of the use");
                }
                return false;
            }
            Vec3 at = serverPos(bat);
            s.batId = bat.getId();
            s.seenTick = now;
            s.batStart = at;
            s.last = at;
            CLAIMED.add(bat.getId());
            LOGGER.info(String.format(Locale.US, "[Bat] #%d seen tick %d (+%d): bat id %d at %s, %.2f from the eye, %.2f off"
                    + " the look", s.no, now, since, bat.getId(), fmt(at), at.distanceTo(s.eye), offRay(s.eye, s.look, at)));
            track(client, s, bat, now, since);
            return false;
        }
        Entity e = client.level.getEntity(s.batId);
        if (e == null || e.isRemoved() || !e.isAlive()) {
            end(s, now, s.last, "removed (it went off)");
            return false;
        }
        if (since > MAX_FLIGHT_TICKS) {
            end(s, now, serverPos(s, e), "still flying after " + MAX_FLIGHT_TICKS + " ticks - let go");
            return false;
        }
        track(client, s, e, now, since);
        return false;
    }

    private static Bat findBat(Minecraft client, Shot s, long since) {
        double radius = APPEAR_RADIUS + Math.max(0, since) * WeaponReach.BAT_BLOCKS_PER_TICK * 1.5;
        Bat best = null;
        double bestD = Double.MAX_VALUE;
        for (Bat bat : client.level.getEntitiesOfClass(Bat.class, new AABB(s.eye, s.eye).inflate(radius),
                b -> b.isAlive() && !CLAIMED.contains(b.getId()))) {
            if (bat.tickCount > since + 2) {
                continue;   // was there before this use (a secret bat, another shot's)
            }
            Vec3 at = serverPos(bat);
            double d = offRay(s.eye, s.look, at) + at.distanceTo(s.eye) * 0.1;
            if (d < bestD) {
                bestD = d;
                best = bat;
            }
        }
        return best;
    }

    private static void track(Minecraft client, Shot s, Entity bat, long now, long since) {
        // The position the server last sent (the interpolation's target), not the drawn one a few ticks behind it.
        Vec3 at = serverPos(s, bat);
        Vec3 drawn = bat.position();
        double step = s.last == null ? 0 : at.distanceTo(s.last);
        s.flown += step;
        s.last = at;
        boolean update = s.samples.isEmpty() || s.samples.get(s.samples.size() - 1).pos().distanceTo(at) > 0.01;
        if (update) {
            s.samples.add(new Sample(now, at));
        }
        double offLaunch = offRay(s.eye, s.look, at);
        s.maxOffLaunch = Math.max(s.maxOffLaunch, offLaunch);
        s.endOffLaunch = offLaunch;
        LocalPlayer player = client.player;
        Vec3 lookNow = TeleportUtils.getLook(player.getYRot(), player.getXRot());
        double offNow = offRay(player.getEyePosition(), lookNow, at);
        s.endOffNow = offNow;
        Entity target = s.targetId < 0 ? null : client.level.getEntity(s.targetId);
        // To the target's CENTRE, as rayMissBlocks is: the two are compared to tell a bat bending toward it.
        double toTarget = target == null ? -1 : at.distanceTo(target.getBoundingBox().getCenter());
        if (toTarget >= 0) {
            s.closestToTarget = Math.min(s.closestToTarget, toTarget);
        }
        LOGGER.info(String.format(Locale.US, "[Bat] #%d fly tick %d (+%d) at %s%s (drawn %s) step %.2f flown %.2f | off"
                        + " launch look %.2f | off look now %.2f (%.1f/%.1f) | to target center %s", s.no, now, since, fmt(at),
                update ? " update" : " (no update)", fmt(drawn), step, s.flown, offLaunch, offNow, player.getYRot(),
                player.getXRot(),
                toTarget < 0 ? "-" : String.format(Locale.US, "%.2f", toTarget)));
    }

    /** Where the server last put an entity: its interpolation's target while it is drawn toward one, else where it is. */
    static Vec3 serverPos(Entity e) {
        var interp = e.getInterpolation();
        return interp != null && interp.hasActiveInterpolation() ? interp.position() : e.position();
    }

    /**
     * The same for a tracked bat, but between two updates the last one the server sent - not where the client has moved
     * it since on its own (a vanilla bat flaps on the client, so its drawn height drifts between updates;
     * {@code InterpolationHandler.position()} hands back that drawn position once the steps run out, javap 26.1.2).
     */
    private static Vec3 serverPos(Shot s, Entity e) {
        var interp = e.getInterpolation();
        if (interp != null && interp.hasActiveInterpolation()) {
            s.lastServer = interp.position();
        } else if (s.lastServer == null) {
            s.lastServer = e.position();
        }
        return s.lastServer;
    }

    private static void end(Shot s, long now, Vec3 at, String how) {
        s.endTick = now;
        s.end = at;
        s.how = how;
        int ticks = (int) (now - s.useTick);
        double reach = at == null ? 0 : at.distanceTo(s.eye);
        LOGGER.info(String.format(Locale.US, "[Bat] #%d hit tick %d (+%d after the use%s): %s at %s | %.2f from the eye, flown"
                        + " %.2f, %.2f blocks/tick | to target %s", s.no, now, ticks,
                s.seenTick >= 0 ? ", seen +" + (s.seenTick - s.useTick) : "", how, fmt(at), reach, s.flown,
                ticks > 0 && s.batId >= 0 ? reach / ticks : 0.0,
                s.closestToTarget == Double.MAX_VALUE ? "-" : String.format(Locale.US, "closest %.2f", s.closestToTarget)));
    }

    private static void result(Minecraft client, Shot s) {
        Entity target = s.targetId < 0 ? null : client.level.getEntity(s.targetId);
        s.targetGone = s.targetId >= 0 && (target == null || target.isRemoved() || !target.isAlive());
        s.healthAfter = target instanceof LivingEntity l && !s.targetGone ? l.getHealth() : (s.targetGone ? 0f : Float.NaN);
        s.tagAfter = target == null ? "" : nameTag(client, target);
        s.curve = curve(s);
        s.done = true;
        String damage = Float.isNaN(s.healthBefore) ? "-"
                : String.format(Locale.US, "%.1f -> %s (%s)", s.healthBefore,
                Float.isNaN(s.healthAfter) ? "?" : String.format(Locale.US, "%.1f", s.healthAfter),
                s.targetGone ? "dead or gone" : Float.isNaN(s.healthAfter) ? "?"
                        : String.format(Locale.US, "%+.1f", s.healthAfter - s.healthBefore));
        LOGGER.info(String.format(Locale.US, "[Bat] #%d result: %s | flight %d ticks, %.2f blocks | seen +%d, %s b/t while"
                        + " seen (fitted over %d position updates) | target %s hp %s | tag '%s' -> '%s' | look off %.2f deg"
                        + " (yaw %+.2f, pitch %+.2f) - %s | curve: %s | end off launch look %.2f, off look now %.2f", s.no,
                s.how, s.flightTicks(), s.reach(), s.seenTick < 0 ? -1 : s.seenTick - s.useTick,
                s.trackedSpeed() < 0 ? "-" : String.format(Locale.US, "%.2f", s.trackedSpeed()), s.updates(),
                s.targetName, damage,
                s.tagBefore,
                s.tagAfter, s.lookOffDeg, s.yawOffDeg, s.pitchOffDeg,
                Double.isNaN(s.lookOffDeg) ? "no target" : s.lookOffDeg < 3.0 ? "on the target" : "off the target",
                s.curve, s.endOffLaunch, s.endOffNow));
    }

    /**
     * How the bat flew, from its seen positions: the line from the first to the last ({@link Shot#flightOffDeg} off the
     * launch look) and how far the flight bowed off that line ({@link Shot#bowBlocks}). A flight within
     * {@link #STRAIGHT_BLOCKS} of its line is straight - along the look when the line is within {@link #ALONG_DEG} of it;
     * otherwise it curved, toward the target when it passed the target's centre nearer than a straight bat along the look
     * would have.
     */
    private static String curve(Shot s) {
        if (s.batId < 0) {
            return "no bat";
        }
        if (s.samples.size() < 2) {
            return "one position update - no line";
        }
        Vec3 first = s.samples.get(0).pos();
        Vec3 last = s.samples.get(s.samples.size() - 1).pos();
        Vec3 chord = last.subtract(first);
        if (chord.length() < 0.5) {
            return "hardly moved while seen";
        }
        Vec3 dir = chord.normalize();
        s.flightOffDeg = Math.toDegrees(Math.acos(Mth.clamp(dir.dot(s.look), -1.0, 1.0)));
        double bow = 0;
        for (Sample p : s.samples) {
            bow = Math.max(bow, offRay(first, dir, p.pos()));
        }
        s.bowBlocks = bow;
        boolean toward = s.targetId >= 0 && s.closestToTarget < s.rayMissBlocks - STRAIGHT_BLOCKS;
        String towardText = toward ? String.format(Locale.US, ", toward the target (passed its center at %.2f, a straight"
                + " bat along the look %.2f)", s.closestToTarget, s.rayMissBlocks) : "";
        if (bow > STRAIGHT_BLOCKS) {
            return String.format(Locale.US, "curved (bowed %.2f blocks off its line)%s", bow, towardText);
        }
        if (s.flightOffDeg <= ALONG_DEG) {
            return String.format(Locale.US, "straight along the look (%.1f deg off it, bow %.2f)%s", s.flightOffDeg, bow,
                    towardText);
        }
        return String.format(Locale.US, "straight but %.1f deg off the look (bow %.2f)%s", s.flightOffDeg, bow, towardText);
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** Perpendicular distance of {@code p} from the ray {@code from + t * dir} (t >= 0; behind the start: the start). */
    static double offRay(Vec3 from, Vec3 dir, Vec3 p) {
        Vec3 rel = p.subtract(from);
        double along = Math.max(0.0, rel.dot(dir));
        return rel.subtract(dir.scale(along)).length();
    }

    /** The name tag stand over a mob (Hypixel's "star name health" line), or "". */
    static String nameTag(Minecraft client, Entity mob) {
        AABB above = mob.getBoundingBox().expandTowards(0, 3.0, 0).inflate(0.6);
        String best = "";
        for (ArmorStand stand : client.level.getEntitiesOfClass(ArmorStand.class, above, ArmorStand::hasCustomName)) {
            String name = stand.getName().getString();
            if (best.isEmpty() || name.contains("❤") && !best.contains("❤")) {
                best = name;
            }
        }
        if (best.isEmpty() && mob.hasCustomName()) {
            best = mob.getName().getString();
        }
        return best;
    }

    static String hp(Entity e) {
        return e instanceof LivingEntity l ? String.format(Locale.US, "%.1f/%.1f", l.getHealth(), l.getMaxHealth()) : "-";
    }

    static String fmt(Vec3 v) {
        return v == null ? "-" : String.format(Locale.US, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }
}

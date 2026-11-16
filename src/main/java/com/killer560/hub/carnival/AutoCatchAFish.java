package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.islands.Islands;
import net.minecraft.world.item.FishingRodItem;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.PerTickServerClock;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;

/**
 * Auto Catch a Fish (cheat build only): while you play Catch a Fish holding the Carnival Rod, turns your view through
 * Auto Routes' eased rotation to the Catch a Fish Helper's pick (the nearest Yellow Fish, else the nearest fish),
 * following it as it swims, and taps Use to cast once it is within a few degrees. When your hook has a fish on it, it
 * holds your Use key down - the wiki's "hold Right-Click to reel it until its progress bar fills" - until the fish is
 * off the hook, then picks the next fish. It keeps one fish as its target until that fish is gone or caught (the fish
 * just caught is never the next target), casts along the path that CROSSES that fish while the hook still flies or
 * glides ({@link Lead#castPlan}: the 1.8 hook's flight, the fish's circle, the round trip, never through the bank), and
 * casts only once the look has stayed on that point for a few rolled ticks with no bobber out (killer560 11-14: "after it
 * finishes catching one it insta throws the hook in the wrong direction"). A fish no clear cast can meet is passed over
 * for two seconds. While the hook is out it watches the bobber; a bobber that has come to rest (it catches nothing then:
 * every catch in his 11-14 games came in flight or glide) is reeled back after a rolled pause and cast again. Reactions
 * and the cast's length are rolled each time. Casts are at least Cast Delay apart. Free Camera keeps your screen still; Only Escape or the Emergency Cancel Key stops it
 * for the rest of that game; moving the mouse never does. You are held still by the game, and it never moves you. Taps and the hold go through
 * {@link ActionGate} at the start of the tick; a server correction is reported and it carries on. Automation: can get
 * you banned; keep it off on the main server. Off by default.
 *
 * <p>Unverified in game: that a hooked fish shows as the bobber's hooked entity, that the cast lands where you look
 * (it aims straight at the fish), and how the Carnival Rod reels.
 */
public final class AutoCatchAFish {

    static final String NAME = "Auto Catch a Fish";

    private enum State { AIM, WAIT_HOOK, REEL }

    private static State state = State.AIM;
    private static boolean aiming;
    private static boolean holding;
    private static long stateSince;
    private static long nextCastTick;
    private static long lastHookedTick;
    private static int casts;
    private static int reels;
    /** Walking to the pond's edge on keys (Sneak + Forward), the sneak kept while fishing so it cannot step in. */
    private static boolean walking;
    private static boolean sneaking;
    private static int walkTicks;
    static final int WALK_MAX_TICKS = 160;
    /** No cast waits longer than this. */
    static final int MAX_WAIT_TICKS = 600;
    private static int walks;
    /** The fish it is going for (kept until gone), the one just caught (skipped for a while), and ticks on aim. */
    private static int targetId = -1;
    private static int caughtId = -1;
    private static long caughtTick;
    private static int onAim;
    private static boolean judged;
    /** Ticks on aim before a cast, the cast's extra length (blocks), both rolled per cast; the tick a resting bobber is
     *  reeled back (-1: not yet), where and since when it has lain still, and the tick a hooked fish is first reeled. */
    private static int castSettle = 2;
    private static double castExtra;
    private static long reelBackTick = -1;
    private static Vec3 restAt;
    /** SERVER ticks the bobber has held {@link #restAt}: a slowed server sends no update for several client ticks, and
     *  counting client ticks then read a gliding hook as at rest (3823 under load: range error -1.11). */
    private static int restTicks;
    private static final PerTickServerClock.Step REST_STEP = new PerTickServerClock.Step();
    /** The client runs its own modern hook between the server's position updates (every 5 ticks) and stops it at the
     *  splash, while the server's 1.8 hook glides on 3-4 blocks: only a spot held past an update is a rest (3823 learned
     *  a -1.36 range error, and a gliding hook was reeled back, from the client's still velocity). */
    static final int REST_TICKS = 7;
    static final double REST_MOVE = 0.15;
    private static long reactTick;
    /** Fish no clear cast can meet, and when that was found (passed over {@link #UNREACH_TICKS}). */
    private static final java.util.Map<Integer, Long> UNREACH = new java.util.HashMap<>();
    static final int UNREACH_TICKS = 40;
    static final int CAUGHT_SKIP_TICKS = 60;
    /** A bobber that appears this soon after a reel, with no cast of ours, is the held Use crossing the catch. */
    static final int STRAY_TICKS = 12;
    /** Off the hook this long (it stays held while hooked) and the reel lets go. */
    static final int UNHOOKED_TICKS = 4;
    private static long reelEndTick = -100;
    private static long strayTapTick = -100;
    private static int castsThisGame;
    private static int strays;
    private static long aimSince;

    private AutoCatchAFish() {
    }

    public static void register() {
        CarnivalInput.register();
        CarnivalInput.stopRule(NAME, () -> AutoCatchAFishConfig.getInstance().isEnabled() && CatchAFish.active());
        CatchAFish.addWant(() -> AutoCatchAFishConfig.getInstance().isEnabledRaw());
        CarnivalState.onGameStart(g -> {
            walkTicks = 0;
            castsThisGame = 0;
            UNREACH.clear();
        });
        int bit = FeatureBits.register(NAME, () -> AutoCatchAFishConfig.getInstance().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoCatchAFish.tick", bit, AutoCatchAFish::tick));
    }

    private static void release(Minecraft mc) {
        if (walking || sneaking) {
            walking = false;
            sneaking = false;
            // Hand the keys back as they really are.
            mc.options.keyUp.setDown(Islands.physical(mc, mc.options.keyUp));
            mc.options.keyShift.setDown(Islands.physical(mc, mc.options.keyShift));
        }
        if (holding) {
            holding = false;
            // Hand the key back as it really is: down only if he is holding the button himself.
            mc.options.keyUse.setDown(Islands.physical(mc, mc.options.keyUse));
        }
    }

    private static void idle(Minecraft mc) {
        release(mc);
        if (aiming) {
            aiming = false;
            CarnivalInput.release(NAME);
        }
        state = State.AIM;
    }

    private static void to(State s, long now) {
        state = s;
        stateSince = now;
        reelBackTick = -1;
        restAt = null;
        if (s == State.REEL) {
            reactTick = now + Human.ticks(2, 1, 5); // a hand sees the bite, then holds
        }
    }

    private static void tick(Minecraft mc) {
        AutoCatchAFishConfig cfg = AutoCatchAFishConfig.getInstance();
        LocalPlayer player = mc.player;
        if (!cfg.isEnabled() || player == null || McCompat.screen(mc) != null || !CatchAFish.active() || CarnivalInput.stopped(NAME)
                || !(player.getMainHandItem().getItem() instanceof FishingRodItem)) {
            idle(mc);
            return;
        }
        CarnivalInput.watchCorrections(NAME);
        long now = ActionGate.currentTick();
        FishingHook hook = player.fishing;
        boolean hooked = hook != null && hook.getHookedIn() != null;
        if (hooked) {
            lastHookedTick = now;
        }
        if (hooked) {
            caughtId = hook.getHookedIn().getId();
        }
        CatchAFish.Fish target = target(now);
        switch (state) {
            case AIM -> {
                if (hook != null && !hooked && now - reelEndTick <= STRAY_TICKS) {
                    // The reel's held Use crossed the catch and cast a new bobber the old way (his 11-14 game: two
                    // such casts each cost about 32 ticks waiting to settle). Reel it straight back, once per round trip.
                    if (now - strayTapTick > 6 + 2L * Lead.pingTicks() && CarnivalInput.tap(mc.options.keyUse)) {
                        strayTapTick = now;
                        strays++;
                        ModChat.log(NAME, ModChat.text("Reeling back a stray cast."));
                    }
                    return;
                }
                if (hook != null) {
                    to(hooked ? State.REEL : State.WAIT_HOOK, now);
                    return;
                }
                // Only to start a game: walking mid-game let go of the aim every time a fish swam out of sight.
                if (castsThisGame == 0 && walkToEdge(mc, player, cfg)) {
                    return;
                }
                if (target == null) {
                    if (aiming) {
                        aiming = false;
                        CarnivalInput.release(NAME);
                    }
                    return;
                }
                Vec3 eye = player.getEyePosition();
                Vec3 center = target.box().getCenter();
                double surface = Lead.surface(mc.level, center);
                Vec3 at = Lead.castPlan(mc.level, target.stand().getId(), center, target.box(), eye, surface, castExtra);
                if (at == null) {
                    // No clear cast meets it (behind the bank's edge, or out of reach): pass it over for a while.
                    UNREACH.put(target.stand().getId(), now);
                    targetId = -1;
                    onAim = 0;
                    return;
                }
                if (!aiming) {
                    aimSince = now;
                }
                aiming = true;
                CarnivalInput.aim(NAME, player, at, true, cfg.isFreeCamera());
                onAim = CarnivalInput.error(player, at) <= castWindow(eye, center, now - aimSince) ? onAim + 1 : 0;
                if (now >= nextCastTick && onAim >= castSettle && CarnivalInput.tap(mc.options.keyUse)) {
                    casts++;
                    castsThisGame++;
                    nextCastTick = now + ActionGate.delayTicks(cfg.getCastDelayMs());
                    castSettle = Human.ticks(2, 1, 6);
                    castExtra = Human.spread(0.12, 0.25);
                    Lead.noteCast(player.getXRot(), eye, surface);
                    aimSince = now;
                    judged = false;
                    to(State.WAIT_HOOK, now);
                    ModChat.log(NAME, ModChat.text("Cast at a " + (target.yellow() ? "Yellow Fish" : "fish") + "."));
                }
            }
            case WAIT_HOOK -> {
                if (hooked) {
                    to(State.REEL, now);
                    return;
                }
                if (hook == null) {
                    if (now - stateSince > 10) {
                        to(State.AIM, now); // the cast never came out, or the bobber is gone: aim again
                    }
                    return;
                }
                // Eyes on the bobber while it flies, as a player watches his float.
                CarnivalInput.aim(NAME, player, hook.position(), true, cfg.isFreeCamera());
                // At rest once its glide stops (it still bobs up and down): teaches the range error of a cast.
                Vec3 hp = hook.position();
                int served = REST_STEP.take();
                if (restAt == null || Math.abs(hp.x - restAt.x) + Math.abs(hp.z - restAt.z) > REST_MOVE) {
                    restAt = hp;
                    restTicks = 0;
                } else {
                    restTicks += served;
                }
                boolean still = now - stateSince >= 5 && restTicks >= REST_TICKS;
                if (!judged && still) {
                    judged = true;
                    Lead.castSettled(hp);
                }
                // A resting hook catches nothing (the 1.8 hook hooks a fish only when its own step crosses the fish's box:
                // all 8 of his pincarn6 catches came 7-17 ticks after the cast): reel back after a rolled pause, cast again.
                if (reelBackTick < 0 && still) {
                    reelBackTick = now + Human.ticks(3, 1, 10);
                }
                if ((reelBackTick >= 0 && now >= reelBackTick || now - stateSince > MAX_WAIT_TICKS)
                        && CarnivalInput.tap(mc.options.keyUse)) {
                    ModChat.log(NAME, ModChat.text("The hook came to rest; reeling back."));
                    to(State.AIM, now);
                }
            }
            case REEL -> {
                if (!holding) {
                    if (now < reactTick && hook != null) {
                        return;
                    }
                    if (!ActionGate.tryAct(ActionGate.Actor.CARNIVAL_WORLD)) {
                        return;
                    }
                    holding = true;
                    reels++;
                    ModChat.log(NAME, ModChat.text("Fish on; reeling."));
                }
                mc.options.keyUse.setDown(true);
                // Off the hook for half a second (caught or lost), the bobber back, or ten seconds: let go.
                if (hook == null || now - lastHookedTick > UNHOOKED_TICKS || now - stateSince > 200) {
                    release(mc);
                    reelEndTick = now;
                    nextCastTick = now + ActionGate.delayTicks(cfg.getCastDelayMs());
                    // A fresh target and a fresh aim: the next cast waits until the look has settled on it.
                    caughtTick = now;
                    targetId = -1;
                    onAim = 0;
                    if (aiming) {
                        aiming = false;
                        CarnivalInput.release(NAME);
                    }
                    to(State.AIM, now);
                }
            }
        }
    }

    /** The fish it goes for: the one it already had while it is still a fish here, else the helper's pick, else the
     *  nearest other fish; never the one just caught. */
    private static CatchAFish.Fish target(long now) {
        java.util.List<CatchAFish.Fish> all = CatchAFish.fish();
        boolean skipCaught = now - caughtTick < CAUGHT_SKIP_TICKS;
        UNREACH.values().removeIf(t -> now - t > UNREACH_TICKS);
        for (CatchAFish.Fish f : all) {
            if (f.stand().getId() == targetId && !(skipCaught && targetId == caughtId)) {
                return f;
            }
        }
        CatchAFish.Fish pick = CatchAFish.best();
        if (pick != null && (skipCaught && pick.stand().getId() == caughtId || UNREACH.containsKey(pick.stand().getId()))) {
            pick = null;
            for (CatchAFish.Fish f : all) {
                int id = f.stand().getId();
                if (!(skipCaught && id == caughtId) && !UNREACH.containsKey(id) && (pick == null || f.yellow() && !pick.yellow())) {
                    pick = f;
                }
            }
        }
        if ((pick == null ? -1 : pick.stand().getId()) != targetId) {
            onAim = 0;
        }
        targetId = pick == null ? -1 : pick.stand().getId();
        return pick;
    }

    /** How far off the cast look may be, degrees: what moves the rest point about 0.4 blocks at that range (0.5-2), a
     *  little wider for each tick it has waited past one second, so a jittery aim never holds the cast for long (his
     *  11-14 game sat 288-447 ticks without casting). */
    static float castWindow(Vec3 eye, Vec3 point, long waited) {
        double range = Math.max(1, Math.hypot(point.x - eye.x, point.z - eye.z));
        double base = Math.max(0.5, Math.min(2, Math.toDegrees(Math.atan(0.4 / range))));
        return (float) (base * (1 + Math.min(1, Math.max(0, waited - 20) / 40.0)));
    }

    /** Harness: stray casts reeled straight back. */
    public static int strays() {
        return strays;
    }

    /** Harness: the fish it is going for (stand id), or -1. */
    public static int targetId() {
        return targetId;
    }

    /** Harness: {cx, cz, r, w} of the circle fitted to the target's swim, or null while none fits. */
    public static double[] targetCircle() {
        return targetId < 0 ? null : Lead.circle(targetId);
    }

    /** Harness: the fish last on the hook (stand id), or -1. */
    public static int caughtId() {
        return caughtId;
    }

    /** The middle of the fish in view (the pond), or null with none. */
    static Vec3 pond(java.util.List<CatchAFish.Fish> fish) {
        if (fish.isEmpty()) {
            return null;
        }
        double x = 0;
        double z = 0;
        for (CatchAFish.Fish f : fish) {
            x += f.stand().getX();
            z += f.stand().getZ();
        }
        return new Vec3(x / fish.size(), fish.get(0).stand().getY(), z / fish.size());
    }

    /** Whether the floor one step ahead of {@code player} toward {@code dir} (horizontal) is missing or water: the edge. */
    static boolean edgeAhead(net.minecraft.client.multiplayer.ClientLevel level, LocalPlayer player, Vec3 dir) {
        double len = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (len < 1e-3) {
            return true;
        }
        net.minecraft.core.BlockPos ahead = net.minecraft.core.BlockPos.containing(player.getX() + dir.x / len * 0.8,
                player.getY() - 0.5, player.getZ() + dir.z / len * 0.8);
        return level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty();
    }

    /**
     * Walks to the pond's edge crouched, on key presses only (Sneak held, Forward held while the view faces the pond's
     * middle): while a fish in the pond is out of sight, until all are in view, the floor ahead ends (the edge) or the
     * pond is under 2.5 blocks away; at most {@link #WALK_MAX_TICKS} ticks a game, so it never freezes. The sneak stays
     * held afterward. True while it is walking (the caller does not cast then).
     */
    private static boolean walkToEdge(Minecraft mc, LocalPlayer player, AutoCatchAFishConfig cfg) {
        Vec3 pond = pond(CatchAFish.fish());
        boolean seesAll = true;
        for (CatchAFish.Fish f : CatchAFish.fish()) {
            seesAll &= CatchAFish.inSight(player, f);
        }
        Vec3 dir = pond == null ? Vec3.ZERO : pond.subtract(player.position());
        boolean done = pond == null || seesAll || walkTicks >= WALK_MAX_TICKS || dir.horizontalDistanceSqr() < 6.25
                || edgeAhead(mc.level, player, dir);
        if (done) {
            if (sneaking) {
                mc.options.keyShift.setDown(true);
            }
            if (walking) {
                walking = false;
                mc.options.keyUp.setDown(Islands.physical(mc, mc.options.keyUp));
                CarnivalInput.release(NAME);
                aiming = false;
                ModChat.log(NAME, ModChat.text("At the pond's edge."));
            }
            return false;
        }
        if (!walking) {
            walking = true;
            walks++;
            ModChat.log(NAME, ModChat.text("Walking to the pond's edge, crouched."));
        }
        sneaking = true;
        walkTicks++;
        aiming = true;
        CarnivalInput.aim(NAME, player, new Vec3(pond.x, player.getEyeY(), pond.z), false, cfg.isFreeCamera());
        mc.options.keyShift.setDown(true);
        mc.options.keyUp.setDown(true);
        return true;
    }

    /** Harness: walks to the edge started. */
    public static int walks() {
        return walks;
    }

    /** Harness: whether it is walking now. */
    public static boolean walking() {
        return walking;
    }

    /** Harness: casts made. */
    public static int casts() {
        return casts;
    }

    /** Harness: fish reeled (holds started). */
    public static int reels() {
        return reels;
    }

    /** Harness: whether it is holding Use now. */
    public static boolean holding() {
        return holding;
    }
}

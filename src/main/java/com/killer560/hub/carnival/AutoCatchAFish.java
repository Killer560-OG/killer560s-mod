package com.killer560.hub.carnival;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.islands.Islands;
import net.minecraft.world.item.FishingRodItem;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
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
 * off the hook, then aims at the next. A cast that hooks nothing within Hook Wait is reeled back with one tap and cast
 * again. Casts are at least Cast Delay apart. Free Camera keeps your screen still; Only Escape or the Emergency Cancel Key stops it
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
    private static int walks;

    private AutoCatchAFish() {
    }

    public static void register() {
        CarnivalInput.register();
        CarnivalInput.stopRule(NAME, () -> AutoCatchAFishConfig.getInstance().isEnabled() && CatchAFish.active());
        CatchAFish.addWant(() -> AutoCatchAFishConfig.getInstance().isEnabledRaw());
        CarnivalState.onGameStart(g -> {
            walkTicks = 0;
            CatchAFish.forgetFlung();
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
        CatchAFish.Fish target = CatchAFish.best();
        switch (state) {
            case AIM -> {
                if (hook != null) {
                    to(hooked ? State.REEL : State.WAIT_HOOK, now);
                    return;
                }
                if (walkToEdge(mc, player, cfg)) {
                    return;
                }
                if (target == null) {
                    if (aiming) {
                        aiming = false;
                        CarnivalInput.release(NAME);
                    }
                    return;
                }
                Vec3 at = target.box().getCenter();
                aiming = true;
                CarnivalInput.aim(NAME, player, at, true, cfg.isFreeCamera());
                if (now >= nextCastTick && CarnivalInput.error(player, at) <= 3f && CarnivalInput.tap(mc.options.keyUse)) {
                    casts++;
                    nextCastTick = now + ActionGate.delayTicks(cfg.getCastDelayMs());
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
                if (target != null) {
                    CarnivalInput.aim(NAME, player, target.box().getCenter(), true, cfg.isFreeCamera());
                }
                if (now - stateSince >= cfg.getHookWaitS() * 20L && CarnivalInput.tap(mc.options.keyUse)) {
                    ModChat.log(NAME, ModChat.text("No fish on the hook; reeling back."));
                    to(State.AIM, now);
                }
            }
            case REEL -> {
                if (!holding) {
                    if (!ActionGate.tryAct(ActionGate.Actor.CARNIVAL_WORLD)) {
                        return;
                    }
                    holding = true;
                    reels++;
                    ModChat.log(NAME, ModChat.text("Fish on; reeling."));
                }
                mc.options.keyUse.setDown(true);
                // Off the hook for half a second (caught or lost), the bobber back, or ten seconds: let go.
                if (hook == null || now - lastHookedTick > 10 || now - stateSince > 200) {
                    release(mc);
                    nextCastTick = now + ActionGate.delayTicks(cfg.getCastDelayMs());
                    to(State.AIM, now);
                }
            }
        }
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

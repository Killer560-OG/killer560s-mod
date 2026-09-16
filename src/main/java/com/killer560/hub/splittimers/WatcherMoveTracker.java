package com.killer560.hub.splittimers;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * "Watcher Move" - how long after the blood door opens the Watcher actually gets off his perch and starts walking,
 * ported from Devonian {@code features/dungeons/clear/WatcherSplits.kt} ({@code Stages.WatcherMove}).
 * <p>
 * Devonian's logic, and what each piece became here:
 * <ul>
 * <li>{@code WatcherMove.start()} the moment {@code WatcherDialog} starts, i.e. on the blood-door-open /
 * Watcher-greeting line -&gt; {@link #onBloodDoorOpened()}, called from {@link SplitTimersFeature}'s chat handler
 * with that feature's own existing {@code BLOOD_CLEAR} pattern (Odin {@code SplitsManager.BLOOD_OPEN_REGEX}),
 * so there is no second regex and no second chat subscription.
 * <li>{@code dialogTicks} = the tick {@code WatcherDialog} finished, i.e. the
 * "[BOSS] The Watcher: Let's see how you can handle this." line -&gt; {@link #onDialogueEnd()}.
 * <li>{@code if (EventBus.serverTicks() - dialogTicks < 45) return} - Devonian waits 45 <b>server ticks</b> after
 * the dialogue ends before it believes any movement. This mod has no server-tick counter (see
 * {@code SplitTimersFeature}'s class doc: "Odin counts server ticks from ping packets, which this mod has no hook
 * for"), so the wait here is 45 * 50 = {@value #DIALOGUE_SETTLE_MS} ms of wall clock. On a lagging server that is
 * shorter than 45 real ticks - flagged as the one approximation in this port.
 * <li>{@code val entity = minecraft.level?.getEntity(entityId - 1)} where {@code entityId} came from a name-change
 * event on a nametag containing " The Watcher " -&gt; the nametag armor stand is found by scanning the rendered
 * entities for {@value #WATCHER_NAME} in its name, and the Watcher himself is the entity one id below it. That
 * {@code id - 1} nametag-to-mob convention is the same one this repo already relies on in
 * {@code mobesp.MobEspFeature.resolveMob} (NoammAddons {@code StarMobESP.checkStarMob} / QUOI
 * {@code DungeonESP.handleStand}).
 * <li>{@code entity.xo == entity.x && ...} - previous-tick position equals current position means "not moving".
 * {@code xo}/{@code yo}/{@code zo} are the real 26.1.2 Mojang-mapped fields (javap-verified on the merged jar).
 * </ul>
 * <b>Not verified against a real run:</b> nothing here can be exercised offline - the Watcher's nametag text, the
 * {@code id - 1} offset for him specifically, and the 45-tick settle window all need one live blood room. The row
 * is off by default and simply never appears if the Watcher is never resolved.
 * <p>
 * {@code bloodcamp.BloodCampFeature} already identifies the Watcher properly (a {@code Zombie} wearing one of the
 * real watcher skull textures, from {@code ClientboundSetEquipmentPacket}), but its {@code watcherEntityId} is
 * private, it is gated behind Blood Camp's own toggle and F7/M7 only, and {@code bloodcamp/} is not this task's to
 * edit - so the nametag route is used instead. If Blood Camp ever exposes a getter, switch to it: it is the more
 * reliable of the two.
 */
final class WatcherMoveTracker {

    /** Devonian: {@code event.name.contains(" The Watcher ")}. Matched on the formatting-stripped nametag. */
    private static final String WATCHER_NAME = "The Watcher";
    private static final long DIALOGUE_SETTLE_MS = 45L * 50L;

    private static long doorOpenMs = 0L;
    private static long dialogueEndMs = 0L;
    private static long moveMs = 0L;
    private static int watcherId = -1;

    private WatcherMoveTracker() {
    }

    /** Blood door opened / Watcher greeting - starts the Watcher Move clock. */
    static void onBloodDoorOpened() {
        if (doorOpenMs == 0L) {
            doorOpenMs = System.currentTimeMillis();
        }
    }

    /** "[BOSS] The Watcher: Let's see how you can handle this." - the dialogue is over, movement can be believed. */
    static void onDialogueEnd() {
        if (dialogueEndMs == 0L) {
            dialogueEndMs = System.currentTimeMillis();
        }
    }

    static void reset() {
        doorOpenMs = 0L;
        dialogueEndMs = 0L;
        moveMs = 0L;
        watcherId = -1;
    }

    /** Milliseconds from the blood door opening until the Watcher first moved, or 0 if not measured (yet). */
    static long getMoveMs() {
        return moveMs > 0L && doorOpenMs > 0L ? moveMs - doorOpenMs : 0L;
    }

    static void tick() {
        if (moveMs != 0L || doorOpenMs == 0L || dialogueEndMs == 0L
                || !SplitTimersConfig.getInstance().isWatcherMoveSplit()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - dialogueEndMs < DIALOGUE_SETTLE_MS) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        Entity watcher = watcherId == -1 ? null : client.level.getEntity(watcherId);
        if (watcher == null) {
            watcher = findWatcher(client);
            if (watcher == null) {
                return;
            }
            watcherId = watcher.getId();
            SplitTimersFeature.LOGGER.info("[SplitTimers] Watcher resolved for Watcher Move: id={} type={}",
                    watcherId, watcher.getType().toShortString());
        }
        if (watcher.xo == watcher.getX() && watcher.yo == watcher.getY() && watcher.zo == watcher.getZ()) {
            return;
        }
        moveMs = now;
        SplitTimersFeature.LOGGER.info("[SplitTimers] Watcher Move: {}ms after the blood door opened", getMoveMs());
    }

    /** The entity one id below the "The Watcher" nametag stand - see this class's doc. */
    private static Entity findWatcher(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (!(entity instanceof ArmorStand stand)) {
                continue;
            }
            String name = ChatFormatting.stripFormatting(stand.getName().getString());
            if (name == null || !name.contains(WATCHER_NAME)) {
                continue;
            }
            Entity below = client.level.getEntity(stand.getId() - 1);
            if (below != null && !(below instanceof ArmorStand)) {
                return below;
            }
        }
        return null;
    }
}

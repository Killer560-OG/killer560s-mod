package com.killer560.hub.autoroutes;

import com.killer560.hub.util.ModLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Kill Mimic (killer560, 2026-10-05: "if it detects it just clicked a trapped chest it should hype straight down once
 * or use spirit sceptre straight down until it detects the mimic is killed").
 * <p>
 * Triggered by OUR click on a trapped chest ({@link AwaitEvents#onLocalBlockClick} - by hand or by a route node), with
 * Auto Routes on and in a dungeon. It waits for the route to be between nodes (or only waiting on an await), then
 * swaps to the item, aims straight down (pitch 90: a camera turn in legit mode, in obvious mode the body
 * with the camera held - {@link RouteExecutor#useHeldItem}), and uses it: a wither blade once, a Spirit Sceptre again every interact
 * delay until the mimic is seen dying ({@link AwaitEvents#mimicKilledSince}), the Mimic Timeout at most, or until he
 * presses a movement key. A Spirit Sceptre's first {@link RouteExecutor#BAT_DOWN_USES} uses go straight down; the rest
 * go AT the mimic while its bat can reach it (killer560, 2026-10-17, for every room), and straight down again when it
 * cannot. Then the slot he held goes back. While it runs a route starts no node and moves nothing
 * ({@link RouteExecutor} holds on {@link #isBusy}).
 */
public final class MimicKiller {

    private static final Logger LOGGER = ModLog.get("killer560smod-autoroutes");

    /** Longest it waits for a running node to finish before giving up on the mimic. */
    private static final int PENDING_TIMEOUT = 100;
    private static final int AIM_TIMEOUT = 20;
    /**
     * Every straight-down Kill Mimic use goes out at exactly this pitch, with either item, in either mode (killer560,
     * 2026-10-11: "for mimic it should also always be straight down"; since 2026-10-17 a Spirit Sceptre's uses after its
     * first two aim at the mimic instead). Legit mode turns the camera there smoothly and waits for it to
     * read exactly 90.0 (RouteRotation lands its last hundredth of a degree exactly); obvious mode sends it in the packet.
     * Before that it fired a Hyperion at whatever the camera read once its aim timed out - 46.7 to 62.3 with Secret Aura
     * on (testkit 96-ar-651) - which is a Wither Impact along the look, at the mimic.
     */
    static final float STRAIGHT_DOWN = 90f;
    /** Legit Hyperion: how long it keeps aiming down before giving up WITHOUT using it. */
    private static final int AIM_GIVE_UP = 60;

    private enum State { IDLE, PENDING, AIM, USING }

    private static State state = State.IDLE;
    private static AutoRoutesConfig.KillMimic mode;
    private static int itemSlot;
    private static int slotBefore;
    private static int armedAt;
    private static int stateTicks;
    private static int lastUseTick;
    private static int uses;
    private static boolean[] keysAtStart;
    /** The trapped chest this run is for (its center). */
    private static Vec3 chestAt;
    /**
     * Past this from the chest, nothing it sends can reach the mimic (the mimic stays within AwaitEvents' 8 of its chest,
     * a straight-down sceptre blast reaches 6): he has left it. stall-1020: Secret Aura took Dueces' trapped chest under
     * Auto Clear, Auto Secret then warped him to Staircase, and 51 sceptre uses went straight down there for the whole
     * Mimic Timeout - Staircase's route stood 4.3 s behind it.
     */
    private static final double LEFT_CHEST = 16.0;
    /** ChestWindows.count() when the chest was clicked; the client tick its window came (-1 none yet); lag mark then. */
    private static int windowMark;
    private static int windowAt = -1;
    private static long windowLag;
    /** Ticks after the chest's window with no mimic by it before it counts as a plain chest (the sim's mimic is out with
     *  the window; Hypixel spawns it as the chest opens). Plus any server stall since the window. */
    private static final int NO_MIMIC_TICKS = 4;

    private MimicKiller() {
    }

    public static boolean isBusy() {
        return state != State.IDLE;
    }

    /** Whether it holds the camera (aiming down or using) - Secret Aura turns nothing meanwhile. */
    public static boolean holdsCamera() {
        return state == State.AIM || state == State.USING;
    }

    /** Lets go now (his left click skipped the node it held): the slot he held goes back, as when it finishes. */
    static void cancel(Minecraft client, String why) {
        if (state != State.IDLE) {
            finish(client, client.player, why);
        }
    }

    /** From {@link AwaitEvents}: we just clicked a trapped chest. */
    static void onTrappedChestClicked(Minecraft client, BlockPos chest) {
        AutoRoutesConfig cfg = AutoRoutesConfig.getInstance();
        AutoRoutesConfig.KillMimic m = cfg.getKillMimic();
        LocalPlayer player = client.player;
        if (m == AutoRoutesConfig.KillMimic.OFF || state != State.IDLE || player == null || !cfg.isEnabled()
                || !com.killer560.hub.secrets.DungeonState.isInDungeon()) {
            LOGGER.info("[Mimic] trapped chest at {} not acted on: mode={} state={} player null={} enabled={} inDungeon={}",
                    chest.toShortString(), m, state, player == null, cfg.isEnabled(),
                    com.killer560.hub.secrets.DungeonState.isInDungeon());
            return;
        }
        int slot = ItemIdentity.findHotbarSlot(player, m.itemId());
        if (slot < 0) {
            LOGGER.info("[Mimic] trapped chest at {} not acted on: no {} in the hotbar", chest.toShortString(), m.label());
            AutoRoutesFeature.chatBad("Kill Mimic: no " + (m == AutoRoutesConfig.KillMimic.HYPERION ? "wither blade"
                    : "Spirit Sceptre") + " in the hotbar.");
            return;
        }
        mode = m;
        chestAt = Vec3.atCenterOf(chest);
        windowMark = com.killer560.hub.util.ChestWindows.count();
        windowAt = -1;
        itemSlot = slot;
        slotBefore = player.getInventory().getSelectedSlot();
        armedAt = AwaitEvents.now();
        uses = 0;
        aimedAt = null;
        state = State.PENDING;
        stateTicks = 0;
        LOGGER.info("[AutoRoutes] Kill Mimic: trapped chest at {} - {} from slot {}", chest.toShortString(), m.label(),
                slot + 1);
    }

    /** Every client tick, before the route's own tick. */
    static void tick(Minecraft client) {
        if (state == State.IDLE) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null || client.level == null || client.gameMode == null) {
            finish(client, null, "world change");
            return;
        }
        stateTicks++;
        if (AwaitEvents.mimicKilledSince(armedAt)) {
            finish(client, player, "mimic killed after " + uses + " use(s)");
            return;
        }
        // Not every trapped chest is the mimic: one that opens as a plain chest (its window came) with no new baby zombie
        // by it a few ticks later has none, and the sceptre has nothing to kill (stall-1020: Dueces' trapped chest, 51
        // uses and the whole Mimic Timeout for nothing, with Auto Secret holding for it).
        if (windowAt < 0 && com.killer560.hub.util.ChestWindows.since(windowMark)) {
            windowAt = AwaitEvents.now();
            windowLag = com.killer560.hub.util.ServerLag.mark();
        }
        if (windowAt >= 0 && AwaitEvents.liveMimic(client) == null
                && AwaitEvents.now() - windowAt > NO_MIMIC_TICKS + com.killer560.hub.util.ServerLag.ticksSince(windowLag)) {
            finish(client, player, "the chest opened as a plain chest - no mimic came out in "
                    + (AwaitEvents.now() - windowAt) + " tick(s)");
            return;
        }
        if (chestAt != null && player.position().distanceTo(chestAt) > LEFT_CHEST) {
            finish(client, player, String.format(java.util.Locale.ROOT,
                    "he left the trapped chest (%.1f blocks from it) - nothing from here reaches its mimic",
                    player.position().distanceTo(chestAt)));
            return;
        }
        boolean legit = AutoRoutesConfig.getInstance().isLegitMode();
        switch (state) {
            case PENDING -> {
                // Also wait out an aura's body turn (Secret Aura clicking the chest turns him to it and gives the view
                // back a tick later): that hand-back writes the rotation, and landing on top of the aim it undid it.
                if (!RouteExecutor.mayYieldToMimic() || com.killer560.hub.util.TurnFirst.anyTurnOut()) {
                    boolean nodeBusy = !RouteExecutor.mayYieldToMimic();
                    logWait(AwaitEvents.now(), nodeBusy ? "pending-node" : "pending-turn", "pending " + stateTicks + "/"
                            + PENDING_TIMEOUT + " tick(s): " + (nodeBusy ? "a route node is acting"
                            : "an aura's body turn is still out"));
                    if (stateTicks > PENDING_TIMEOUT) {
                        finish(client, player, "the route's node never finished");
                    }
                    return;
                }
                LOGGER.info("[Mimic] start: selecting slot {} ({}) from slot {}, {} aim, waited {} tick(s), tick={}",
                        itemSlot + 1, mode.label(), slotBefore + 1, legit ? "camera" : "packet", stateTicks,
                        AwaitEvents.now());
                RouteExecutor.select(client, player, itemSlot);
                keysAtStart = movementKeys(client);
                if (legit) {
                    RouteRotation.beginApproach(player.getYRot(), STRAIGHT_DOWN, false, 0f, 0f);
                }
                state = State.AIM;
                stateTicks = 0;
            }
            case AIM -> {
                if (tookControl(client)) {
                    finish(client, player, "you moved");
                    return;
                }
                // The route lets go of sneak while this holds it; the use goes out once the server has shift up.
                boolean sneakDown = player.getLastSentInput().shift() && stateTicks <= AIM_TIMEOUT;
                boolean turning = legit && !RouteRotation.settled(1.0f) && stateTicks <= AIM_TIMEOUT;
                if (legit && player.getXRot() != STRAIGHT_DOWN) {
                    // NEVER at any other pitch: keep aiming (re-issued if something else let go of or moved the
                    // approach - his yaw, unclamped), and past AIM_GIVE_UP give up with nothing used.
                    if (stateTicks > AIM_GIVE_UP) {
                        AutoRoutesFeature.chatBad("Kill Mimic: could not aim straight down - nothing used.");
                        finish(client, player, String.format(java.util.Locale.ROOT,
                                "gave up: pitch %s after %d ticks, %s not used", Float.toString(player.getXRot()),
                                stateTicks, mode.label()));
                        return;
                    }
                    logWait(AwaitEvents.now(), "aim-pitch", "aiming down: camera pitch " + player.getXRot() + " after "
                            + stateTicks + " tick(s) (gives up at " + AIM_GIVE_UP + ")");
                    if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                        RouteRotation.beginApproach(player.getYRot(), STRAIGHT_DOWN, false, 0f, 0f);
                    }
                    return;
                }
                if (sneakDown || turning) {
                    logWait(AwaitEvents.now(), sneakDown ? "aim-sneak" : "aim-turn", sneakDown
                            ? "use held: the server still has sneak down (tick " + stateTicks + " of " + AIM_TIMEOUT + ")"
                            : "use held: camera still turning (tick " + stateTicks + " of " + AIM_TIMEOUT + ")");
                    return;
                }
                // The trapped chest click that armed this is unpaired on the server, and the server's look still points at
                // the chest: it hears the straight-down look first, or the use is taken as the click's end (util/RightClick).
                if (RouteExecutor.turnFirst(player, player.getYRot(), STRAIGHT_DOWN, "[AutoRoutes] Kill Mimic")) {
                    return;
                }
                use(client, player);
                if (mode == AutoRoutesConfig.KillMimic.HYPERION) {
                    // Once: Wither Impact straight down implodes where he stands.
                    finish(client, player, "wither impact used");
                    return;
                }
                state = State.USING;
                stateTicks = 0;
            }
            case USING -> {
                if (tookControl(client)) {
                    finish(client, player, "you moved");
                    return;
                }
                // Spirit Sceptre: the longest it keeps using for one mimic, the Mimic Timeout setting.
                int useTimeout = AutoRoutesConfig.getInstance().getMimicTimeoutTicks();
                if (stateTicks > useTimeout) {
                    AutoRoutesFeature.chatBad(String.format(java.util.Locale.US,
                            "Kill Mimic: the mimic did not die in %.1f s.", useTimeout / 20.0));
                    finish(client, player, "timed out");
                    return;
                }
                int delay = Math.max(1, AutoRoutesConfig.getInstance().getInteractDelayTicks());
                // Past the first two uses straight down, the rest go AT the mimic while its bat can reach it (killer560,
                // 2026-10-17: "if it did the two bats down then the rest aimed at it then Chains would work" - Chains'
                // trapped chest is six blocks over him, out of a floor blast's six, and 51 uses down never touched it).
                if (uses >= RouteExecutor.BAT_DOWN_USES) {
                    net.minecraft.world.entity.monster.zombie.Zombie mimic = AwaitEvents.liveMimic(client);
                    if (mimic != null && RouteExecutor.sceptreReaches(client, player, mimic)) {
                        tickAimedUse(client, player, mimic, legit, delay);
                        return;
                    }
                    if (aimedAt != null) {
                        aimedAt = null;
                        LOGGER.info("[AutoRoutes] Kill Mimic: {} - back to straight down", mimic == null
                                ? "no live mimic seen by the chest" : "the mimic is out of the sceptre's reach");
                    }
                }
                // Each further Spirit Sceptre use too: only while the camera reads exactly 90.0 in legit mode.
                if (legit && player.getXRot() != STRAIGHT_DOWN) {
                    if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                        RouteRotation.beginApproach(player.getYRot(), STRAIGHT_DOWN, false, 0f, 0f);
                    }
                    return;
                }
                if (AwaitEvents.now() - lastUseTick >= delay
                        && !RouteExecutor.turnFirst(player, player.getYRot(), STRAIGHT_DOWN, "[AutoRoutes] Kill Mimic")) {
                    use(client, player);
                }
            }
            default -> {
            }
        }
    }

    /** The mimic the aimed uses are going at, or null while they go straight down (for the once-per-change log lines). */
    private static Integer aimedAt;
    /** How near the look the server last heard must be to the mimic's for an obvious-mode use to go at it. */
    private static final float TOLD_DEGREES = 2f;
    /** Legit mode: how near the camera must be to the mimic's look before a use goes (the bat node's tolerance). */
    private static final float LEGIT_AIM_TOLERANCE = 6f;

    /**
     * One tick of a Spirit Sceptre use AT the mimic's center. The look is from his eye, yaw as a delta on his running
     * value (never wrapped), pitch within +-90. Obvious mode turns the body (camera held) and uses only once the server
     * has heard that look in a movement packet - a use whose rotation no movement packet reported is GrimAC BadPacketsJ,
     * and Hypixel's 1.8 server aims the bats along the look it last heard; the first aimed use costs that one tick,
     * later ones none while the mimic stands. Legit mode eases the camera and uses at the camera's rotation once it is
     * within {@link #LEGIT_AIM_TOLERANCE}. Uses stay an interact delay apart, as the straight-down ones.
     */
    private static void tickAimedUse(Minecraft client, LocalPlayer player,
                                     net.minecraft.world.entity.monster.zombie.Zombie mimic, boolean legit, int delay) {
        net.minecraft.world.phys.Vec3 at = mimic.getBoundingBox().getCenter();
        var r = com.killer560.hub.livemap.autoclear.TeleportUtils.getDirection(player.getEyePosition(), at);
        float yaw = player.getYRot() + net.minecraft.util.Mth.wrapDegrees(r.yaw() - player.getYRot());
        float pitch = net.minecraft.util.Mth.clamp(r.pitch(), -90f, 90f);
        if (aimedAt == null || aimedAt != mimic.getId()) {
            aimedAt = mimic.getId();
            LOGGER.info("[AutoRoutes] Kill Mimic: {} uses went down - now aiming the Spirit Sceptre at the mimic at {} "
                            + "({} blocks)", uses, String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", at.x, at.y, at.z),
                    String.format(java.util.Locale.ROOT, "%.1f", player.getEyePosition().distanceTo(at)));
        }
        boolean due = AwaitEvents.now() - lastUseTick >= delay;
        if (legit) {
            if (!RouteRotation.isActive() || RouteRotation.settled(1.0f)) {
                RouteRotation.beginApproach(yaw, pitch, false, 0f, 0f);
            }
            float error = (float) Math.hypot(net.minecraft.util.Mth.wrapDegrees(yaw - player.getYRot()),
                    pitch - player.getXRot());
            if (!due || error > LEGIT_AIM_TOLERANCE
                    || RouteExecutor.turnFirst(player, player.getYRot(), player.getXRot(), "[AutoRoutes] Kill Mimic")) {
                return;
            }
            aimedUse(client, player, player.getYRot(), player.getXRot(), at);
            return;
        }
        float[] heard = com.killer560.hub.util.RightClick.heardLook();
        boolean told = heard != null && Math.abs(net.minecraft.util.Mth.wrapDegrees(heard[0] - yaw)) <= TOLD_DEGREES
                && Math.abs(heard[1] - pitch) <= TOLD_DEGREES;
        if (!told) {
            RouteExecutor.aimBody(player, yaw, pitch); // this tick's movement packet tells the server
            return;
        }
        // The look exactly as the server heard it, expressed on his running yaw.
        float useYaw = player.getYRot() + net.minecraft.util.Mth.wrapDegrees(heard[0] - player.getYRot());
        if (!due || RouteExecutor.turnFirst(player, useYaw, heard[1], "[AutoRoutes] Kill Mimic")) {
            return;
        }
        aimedUse(client, player, useYaw, heard[1], at);
    }

    private static void aimedUse(Minecraft client, LocalPlayer player, float yaw, float pitch,
                                 net.minecraft.world.phys.Vec3 at) {
        RouteExecutor.useHeldItem(client, player, yaw, pitch, false);
        uses++;
        lastUseTick = AwaitEvents.now();
        LOGGER.info("[AutoRoutes] Kill Mimic: {} used at the mimic (use {}, yaw {} pitch {}, {} blocks)", mode.label(),
                uses, Float.toString(yaw), Float.toString(pitch),
                String.format(java.util.Locale.ROOT, "%.1f", player.getEyePosition().distanceTo(at)));
    }

    private static void use(Minecraft client, LocalPlayer player) {
        RouteExecutor.useHeldItem(client, player, player.getYRot(), STRAIGHT_DOWN, false);
        uses++;
        lastUseTick = AwaitEvents.now();
        // The pitch the use went out at in legit mode is the camera's (obvious mode sends 90 in the packet).
        LOGGER.info("[AutoRoutes] Kill Mimic: {} used straight down (use {}, {} pitch {})", mode.label(), uses,
                AutoRoutesConfig.getInstance().isLegitMode() ? "camera" : "packet",
                AutoRoutesConfig.getInstance().isLegitMode()
                        ? Float.toString(player.getXRot()) : "90.0");
    }

    private static String lastWaitKey;
    private static long lastWaitTick;

    /** A per-tick wait, once per reason per 20 ticks. */
    private static void logWait(long now, String key, String detail) {
        if (key.equals(lastWaitKey) && now - lastWaitTick < 20) {
            return;
        }
        lastWaitKey = key;
        lastWaitTick = now;
        LOGGER.info("[Mimic] held: {} (tick {})", detail, now);
    }

    private static void finish(Minecraft client, LocalPlayer player, String why) {
        LOGGER.info("[AutoRoutes] Kill Mimic done: {}", why);
        LOGGER.info("[Mimic] finished in state {} after {} tick(s) since the chest click, {} use(s), restoring slot {}: {}",
                state, AwaitEvents.now() - armedAt, uses,
                player != null && state != State.PENDING && slotBefore >= 0 && slotBefore <= 8 ? slotBefore + 1 : "none",
                why);
        lastWaitKey = null;
        if (player != null && state != State.PENDING && slotBefore >= 0 && slotBefore <= 8) {
            RouteExecutor.select(client, player, slotBefore);
        }
        if (state == State.AIM || state == State.USING) {
            if (AutoRoutesConfig.getInstance().isLegitMode()) {
                // The camera is the route's again: its next node aims where it needs.
                RouteRotation.clear();
            }
        }
        state = State.IDLE;
    }

    private static boolean[] movementKeys(Minecraft client) {
        var o = client.options;
        return new boolean[]{o.keyUp.isDown(), o.keyDown.isDown(), o.keyLeft.isDown(), o.keyRight.isDown(),
                o.keyJump.isDown()};
    }

    /** A movement key pressed since it started (a key already held then - the walk to the chest - is not one). */
    private static boolean tookControl(Minecraft client) {
        if (com.killer560.hub.compat.McCompat.screen(client) != null || keysAtStart == null) {
            return false;
        }
        boolean[] now = movementKeys(client);
        for (int i = 0; i < now.length; i++) {
            if (now[i] && !keysAtStart[i]) {
                return true;
            }
            if (!now[i]) {
                keysAtStart[i] = false;
            }
        }
        return false;
    }
}

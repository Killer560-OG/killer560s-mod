package com.killer560.hub.lightsdevice;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.leveraura.P3SectionTracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.BlockHits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.compat.McCompat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lights Device - the F7/M7 P3 Section 2 Lights device (six levers, all lit = done), as its own cheat-only feature
 * (killer560, 2026-10-15: "Make lights have its own aura trigger and own triggerbot stuff it should not be impacted by
 * other auras. It should be its own cheat."). Until then it was part of In Boss (the old Lever Aura), and before that
 * killer560's original spec: "in s2 if I have lever aura on it'll do each corner lever and the two in the middle if s2
 * isn't open yet. Then once s1 finishes if I go into s2 it should flick any lever prioritizing levers that would
 * complete the dev then if not click another lever to activate the dev. Don't keep flicking, only flick it once."
 * <p>
 * The six levers sit on the wall at z=142 - corners (58/62, 133/136) and middle (60, 134/135). Flicking a lit lever does
 * not turn its light off ("Levers don't turn off lights only on", hypixel.net/threads/5607893). Coordinates: Skyblocker
 * {@code skyblock/dungeon/device/LightsOn.java}, identical to QUOI {@code SecretAura.kt}'s device lever list.
 * <p>
 * Two halves, each with its own switch, its own settings and its own {@link ActionGate} actor ({@code LIGHTS_TRIGGER},
 * {@code LIGHTS_AURA}), so neither Secret Aura nor In Boss shares a switch, a setting or a click budget with them, and
 * both of those leave the six levers alone ({@link #isLightsLever}). The gate's one-interaction-per-tick rule still holds
 * for everything in the mod: two actors wanting the same tick means one goes on the next, never both at once.
 * <ul>
 * <li><b>Aura</b> - every lever in range, nearest first: before S2 is open (Pre-Flick), every UNLIT lever once; once S2 is
 * open (Finish, S1 objectives "(n/n)" + "The gate has been destroyed!", {@link P3SectionTracker}): an unlit lever (the last
 * one completes it); all six already lit, exactly ONE flick to make the now-active device register. Paced by its CPS (a
 * lever must be in range for one gap before its first flick; gaps 1000 / CPS ms +-10%, scheduled from the last flick's
 * due tick). Surface hit clipped from the eye, box range checked again at the send, and a lever the reported look does
 * not reach gets the body (never the camera) turned to it a tick first (util/TurnFirst).
 * <li><b>Triggerbot</b> - only the lever under the crosshair ({@code Minecraft#hitResult}, a real ray within vanilla
 * reach, so no turn is ever needed), by the same device rules, after its Delay (whole ticks, rounded up) and at least its
 * Cooldown after its last flick; dropped if the crosshair leaves the lever before the delay ends.
 * </ul>
 * Both share one per-run record of which levers were flicked, so the two halves never flick the same lever twice, and
 * nothing is flicked once the device is done or past S2. Everything resets on a new run (Goldor's line, a world change).
 * Never with a container open (the Triggerbot: never with any screen open, the crosshair is stale then).
 */
public final class LightsDeviceFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-lightsdevice");
    private static final String CHAT_TAG = "Lights Device";

    /** Skyblocker LightsOn.java / QUOI SecretAura.kt. */
    private static final List<BlockPos> LIGHTS_LEVERS = List.of(
            new BlockPos(62, 136, 142), new BlockPos(58, 136, 142),   // top corners
            new BlockPos(60, 135, 142), new BlockPos(60, 134, 142),   // middle
            new BlockPos(62, 133, 142), new BlockPos(58, 133, 142));  // bottom corners
    private static final Set<BlockPos> LIGHTS_SET = Set.copyOf(LIGHTS_LEVERS);
    private static final String ACTIVATION_REASON = "all lit, one activation flick";
    private static final int HISTORY = 32;

    // ---- per-run bookkeeping, shared by both halves ----
    /** Levers flicked before S2 opened (per run). */
    private static final Set<Long> clickedEarly = new HashSet<>();
    /** Levers flicked after S2 opened (per run). */
    private static final Set<Long> clickedS2 = new HashSet<>();
    private static boolean activationClicked = false;
    /** "aura 62, 136, 142" / "trigger ..." for every flick this session, newest last (the tab and testkit read it). */
    private static final List<String> recent = new ArrayList<>();
    private static int lastGeneration = Integer.MIN_VALUE;
    private static int lastSection = 1;

    // ---- Aura ----
    private static final Map<Long, Long> inRangeSince = new HashMap<>();
    private static final Map<Long, Long> requiredDwell = new HashMap<>();
    /** ActionGate tick the next aura flick is due on, fractional so a CPS between whole ticks keeps its average. */
    private static double nextAuraDueTick = 0.0;
    /** The tick a due flick first went to turning the body (TurnFirst), or -1: the flick is scheduled from there. */
    private static long turnStartTick = -1L;
    /** The body turn for a lever the reported look does not reach (see util/TurnFirst); the camera never moves. */
    private static final com.killer560.hub.util.TurnFirst TURN = new com.killer560.hub.util.TurnFirst();

    // ---- Triggerbot ----
    private static BlockPos pendingPos = null;
    private static long pendingDueTick = 0L;
    private static long triggerReadyTick = 0L;

    private LightsDeviceFeature() {
    }

    public static void register() {
        if (!com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        P3SectionTracker.register();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("LightsDeviceFeature.tick", LightsDeviceFeature::tick));
        LOGGER.info("[LightsDevice] Registered (both halves default OFF)");
    }

    /** One of the device's six levers. Secret Aura and its Triggerbot leave these alone in F7/M7 ({@link #isLightsLever}). */
    public static boolean isLightsLever(BlockPos pos) {
        return LIGHTS_SET.contains(pos);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        // First, always: a body turned for a flick is held for it and given back the tick after.
        TURN.tick(client.player);
        P3SectionTracker.poll(client);
        if (P3SectionTracker.generation() != lastGeneration) {
            lastGeneration = P3SectionTracker.generation();
            resetRun();
        }
        int section = P3SectionTracker.section();
        if (section != lastSection) {
            lastSection = section;
            inRangeSince.clear();
            requiredDwell.clear();
            pendingPos = null;
        }
        LightsDeviceConfig cfg = LightsDeviceConfig.getInstance();
        boolean aura = cfg.isAuraEnabled();
        boolean trigger = cfg.isTriggerEnabled();
        if (!trigger) {
            pendingPos = null;
        }
        if (!aura && !trigger) {
            return;
        }
        if (client.level == null || client.player == null || client.gameMode == null
                || !CheatUtils.isOnDungeonServer(client)
                || !DungeonState.isF7OrM7() || !Floor7Tracker.inF7Boss()
                || Floor7Tracker.getPhaseAt() != Floor7Tracker.Phase.P3
                || section > 2
                || ActionGate.containerScreenOpen(client)) {
            pendingPos = null;
            return;
        }
        boolean s2Open = section == 2;
        if (s2Open && P3SectionTracker.lightsCompleted()) {
            pendingPos = null;
            return; // done (by anyone): nothing left to flick
        }
        // The Triggerbot first: it is his own aim, the Aura is everything else in range. One flick a tick between them.
        if (trigger && triggerTick(client, cfg, s2Open)) {
            return;
        }
        if (aura) {
            auraTick(client, cfg, s2Open);
        }
    }

    /** The device's state: how many of the six are unlit, or -1 when any of the six is not a lever (not loaded / not the device). */
    private static int unlitCount(Minecraft client) {
        int unlit = 0;
        for (BlockPos pos : LIGHTS_LEVERS) {
            BlockState st = client.level.getBlockState(pos);
            if (st.getBlock() != Blocks.LEVER) {
                return -1;
            }
            if (!st.getValue(LeverBlock.POWERED)) {
                unlit++;
            }
        }
        return unlit;
    }

    /** Why the device rules would flick {@code pos} now, or null. */
    private static String wantedReason(Minecraft client, BlockPos pos, boolean s2Open, int unlitTotal) {
        if ((s2Open ? clickedS2 : clickedEarly).contains(pos.asLong())) {
            return null;
        }
        boolean lit = client.level.getBlockState(pos).getValue(LeverBlock.POWERED);
        if (!lit) {
            return s2Open
                    ? (unlitTotal == 1 ? "last unlit lever (completes device)" : "unlit lever (" + unlitTotal + " unlit)")
                    : "pre-flick before S2 (" + unlitTotal + " unlit)";
        }
        if (s2Open && unlitTotal == 0 && clickedS2.isEmpty() && !activationClicked) {
            return ACTIVATION_REASON;
        }
        return null;
    }

    // ---- Aura ----------------------------------------------------------------------------------------------------

    private static void auraTick(Minecraft client, LightsDeviceConfig cfg, boolean s2Open) {
        boolean wanted = s2Open ? cfg.isFinish() : cfg.isPreFlick();
        int unlitTotal = wanted ? unlitCount(client) : -1;
        if (unlitTotal < 0) {
            inRangeSince.clear();
            requiredDwell.clear();
            turnStartTick = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getRange() * cfg.getRange();
        BlockPos nearestUnlit = null;
        double nearestUnlitSq = Double.MAX_VALUE;
        BlockPos nearestAny = null;
        double nearestAnySq = Double.MAX_VALUE;
        String unlitReason = null;
        String anyReason = null;
        Set<Long> inRangeNow = new HashSet<>();
        for (BlockPos pos : LIGHTS_LEVERS) {
            // Nearest point of the block, not its centre. See BlockHits.boxDistanceSq.
            double d = BlockHits.boxDistanceSq(eye, pos);
            if (d > rangeSq) {
                continue;
            }
            long key = pos.asLong();
            inRangeNow.add(key);
            if (!dwellPassed(cfg, key, now)) {
                continue;
            }
            String reason = wantedReason(client, pos, s2Open, unlitTotal);
            if (reason == null) {
                continue;
            }
            boolean lit = client.level.getBlockState(pos).getValue(LeverBlock.POWERED);
            if (!lit && d < nearestUnlitSq) {
                nearestUnlitSq = d;
                nearestUnlit = pos;
                unlitReason = reason;
            } else if (lit && d < nearestAnySq) {
                nearestAnySq = d;
                nearestAny = pos;
                anyReason = reason;
            }
        }
        inRangeSince.keySet().retainAll(inRangeNow);
        requiredDwell.keySet().retainAll(inRangeNow);
        BlockPos target = nearestUnlit != null ? nearestUnlit : nearestAny;
        String reason = nearestUnlit != null ? unlitReason : anyReason;

        long tick = ActionGate.currentTick();
        if (target == null) {
            turnStartTick = -1L;
            return;
        }
        if (tick < nextAuraDueTick) {
            return;
        }
        // Its own actor: a refusal (another feature owns this tick) costs nothing - the same lever is re-picked next tick.
        if (!ActionGate.tryAct(ActionGate.Actor.LIGHTS_AURA)) {
            return;
        }
        if (!auraClick(client, cfg, target, reason, s2Open, rangeSq)) {
            // The body was turned to it this tick (or no hit yet): the flick goes next tick.
            if (turnStartTick < 0) {
                turnStartTick = tick;
            }
            return;
        }
        // A flick that needed a turn first counts from the tick the turn took, so a turned flick does not add a tick to
        // the gap (10 CPS measured about 8 before, testkit 846).
        long base = turnStartTick >= 0 ? turnStartTick : tick;
        turnStartTick = -1L;
        nextAuraDueTick = Math.max(nextAuraDueTick, base - 1) + gapMs(cfg) / 50.0;
    }

    /** False when nothing went out this tick (no hit yet, out of reach at the send, or the body was turned first). */
    private static boolean auraClick(Minecraft client, LightsDeviceConfig cfg, BlockPos pos, String reason, boolean s2Open,
                                     double rangeSq) {
        Vec3 eye = client.player.getEyePosition();
        // The reach is measured HERE, at the send, to the block's box - a flick that waited a tick for its turn is not
        // sent from where it was picked.
        if (BlockHits.boxDistanceSq(eye, pos) > rangeSq) {
            return false;
        }
        // A surface hit from the eye rather than the block's centre: a point inside the block is what GrimAC flagged on
        // Secret Aura (2026-09-28, PositionPlace at a distance the server itself accepted). See BlockHits.
        BlockHitResult hit = BlockHits.surface(client.level, pos, eye);
        if (hit == null) {
            return false; // no ray could produce a hit from here; try again next tick
        }
        // A lever the look the server was told about does not reach gets the BODY turned to it first, the camera held:
        // a click the look misses draws GrimAC RotationPlace (testkit 419).
        if (!TURN.readyBlock(client.player, client.level, pos, hit.getLocation(), eye.distanceTo(hit.getLocation()) + 1.0)) {
            return false;
        }
        send(client, pos, hit, reason, s2Open, cfg.isAuraSwingHand(), "aura");
        if (cfg.isChatFeedback()) {
            ModChat.send(CHAT_TAG, ModChat.text("Flicked "), ModChat.value(pos.toShortString()), ModChat.dim(" (" + reason + ")"));
        }
        return true;
    }

    /** A lever must have been in range for one CPS gap (rolled when it came into range) before its first flick. */
    private static boolean dwellPassed(LightsDeviceConfig cfg, long key, long now) {
        long since = inRangeSince.computeIfAbsent(key, k -> now);
        long need = requiredDwell.computeIfAbsent(key, k -> Math.round(gapMs(cfg)));
        return now - since >= need;
    }

    /** One gap between flicks: 1000 / CPS ms, +-10%. */
    private static double gapMs(LightsDeviceConfig cfg) {
        return 1000.0 / cfg.getCps() * (0.9 + ThreadLocalRandom.current().nextDouble() * 0.2);
    }

    // ---- Triggerbot ----------------------------------------------------------------------------------------------

    /** True when the Triggerbot flicked this tick. */
    private static boolean triggerTick(Minecraft client, LightsDeviceConfig cfg, boolean s2Open) {
        if (McCompat.screen(client) != null) {
            // Any screen: with a menu up the mouse is not steering the crosshair, so "looking at" is stale.
            pendingPos = null;
            return false;
        }
        BlockHitResult hit = client.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
        BlockPos pos = hit == null ? null : hit.getBlockPos();
        int unlitTotal = pos != null && isLightsLever(pos) ? unlitCount(client) : -1;
        String reason = unlitTotal < 0 ? null : wantedReason(client, pos, s2Open, unlitTotal);
        if (reason == null) {
            pendingPos = null;
            return false;
        }
        long tick = ActionGate.currentTick();
        if (!pos.equals(pendingPos)) {
            // The crosshair just landed on it: the delay counts from here (0 ms flicks this same tick).
            pendingPos = pos.immutable();
            pendingDueTick = tick + ActionGate.delayTicks(cfg.getTriggerDelayMs());
        }
        if (tick < pendingDueTick || tick < triggerReadyTick) {
            return false;
        }
        // Reach at the send, to the box: the crosshair ray is vanilla's block reach, never more than the measured cap.
        double cap = com.killer560.hub.cheatutils.CheatUtilsConfig.MEASURED_MAX_REACH;
        if (BlockHits.boxDistanceSq(client.player.getEyePosition(), pos) > cap * cap) {
            return false;
        }
        if (!ActionGate.tryAct(ActionGate.Actor.LIGHTS_TRIGGER)) {
            return false;
        }
        // The crosshair's own hit: the look the server was told about is the one that produced it, so no turn.
        send(client, pos, hit, reason, s2Open, cfg.isTriggerSwingHand(), "trigger");
        pendingPos = null;
        triggerReadyTick = tick + ActionGate.delayTicks(cfg.getTriggerCooldownMs());
        return true;
    }

    // ---- shared --------------------------------------------------------------------------------------------------

    private static void send(Minecraft client, BlockPos pos, BlockHitResult hit, String reason, boolean s2Open, boolean swing,
                             String who) {
        (s2Open ? clickedS2 : clickedEarly).add(pos.asLong());
        if (ACTIVATION_REASON.equals(reason)) {
            activationClicked = true;
        }
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (swing) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        recent.add(who + " " + pos.toShortString());
        while (recent.size() > HISTORY) {
            recent.remove(0);
        }
    }

    private static void resetRun() {
        clickedEarly.clear();
        clickedS2.clear();
        activationClicked = false;
        inRangeSince.clear();
        requiredDwell.clear();
        nextAuraDueTick = 0.0;
        turnStartTick = -1L;
        pendingPos = null;
        triggerReadyTick = 0L;
        lastSection = P3SectionTracker.section();
    }

    /** Every flick this session, "aura x, y, z" or "trigger x, y, z", oldest first (at most the last 32). */
    public static List<String> recentFlicks() {
        return List.copyOf(recent);
    }

    /** For the tab's status line. */
    public static Component statusText() {
        int section = P3SectionTracker.section();
        String s = section >= 5 ? "Core open" : section > 2 ? "Past S2" : section == 2 ? "S2 open" : "S2 not open";
        return Component.literal("§7Tracked: §f" + s + (P3SectionTracker.lightsCompleted() ? " §7| §aLights done" : ""));
    }
}

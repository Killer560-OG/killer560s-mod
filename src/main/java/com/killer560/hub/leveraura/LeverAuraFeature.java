package com.killer560.hub.leveraura;

import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * In Boss's own lever clicking (the Secret Aura tab's "In Boss", once the separate Lever Aura) - F7/M7 P3 Section 2's
 * two section levers, cheat build only. The Lights device it used to flick as well is its own feature since 2026-10-15
 * ({@code lightsdevice.LightsDeviceFeature}; killer560: "Make lights have its own aura trigger and own triggerbot stuff
 * it should not be impacted by other auras"), so nothing here touches the six Lights levers any more.
 * <p>
 * S2's two section levers (27,124,127) and (23,132,138) are QUOI's (SecretAuraFeature#BOSS_LEVERS, the two inside
 * Floor7Tracker's S2 box x 19..111, z 121..145). Each is flicked while its "Not Activated" stand is above it - once after
 * S2 opens, and optionally once more beforehand ("early"). The stand check stops any re-click after they activated.
 * Section progress is {@link P3SectionTracker}'s, shared with the Lights Device; everything resets on a new run, and
 * nothing happens past S2.
 * <p>
 * Click is {@code SecretAuraFeature}'s: {@code gameMode.useItemOn} with a surface hit clipped from the eye, box range,
 * no line-of-sight check, never with a container open; a lever the reported look does not reach gets the body (never the
 * camera) turned to it a tick first (util/TurnFirst). Pacing is a CPS setting (1-20): a lever must be in range for one
 * gap before its first click, and clicks are spaced by 1000 / CPS ms with +-10% jitter, scheduled from the previous
 * click's due tick, at most one a tick.
 */
public final class LeverAuraFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-leveraura");
    private static final String CHAT_TAG = "In Boss";

    /** QUOI SecretAura.kt boss levers inside Section 2. */
    private static final List<BlockPos> S2_SECTION_LEVERS = List.of(
            new BlockPos(27, 124, 127), new BlockPos(23, 132, 138));

    // ---- click bookkeeping ----
    /** Levers clicked before S2 opened (per run). */
    private static final Set<Long> clickedEarly = new HashSet<>();
    /** Levers clicked after S2 opened (per run). */
    private static final Set<Long> clickedS2 = new HashSet<>();
    private static final Map<Long, Long> inRangeSince = new HashMap<>();
    private static final Map<Long, Long> requiredDwell = new HashMap<>();
    /** ActionGate tick the next flick is due on, fractional so a CPS between whole ticks keeps its average. */
    private static double nextClickDueTick = 0.0;
    /** The tick a due flick first went to turning the body (TurnFirst), or -1: the flick is scheduled from there. */
    private static long turnStartTick = -1L;
    private static int lastGeneration = Integer.MIN_VALUE;
    private static int lastSection = 1;

    private LeverAuraFeature() {
    }

    // ---- logging only ----
    private static String lastGateLogged = "init";
    /** Per lever, the last state code logged (logged again only on change). */
    private static final Map<Long, String> leverStateLogged = new HashMap<>();
    private static String lastHeldKey;
    private static long lastHeldTick;

    private static String reasonFor(boolean s2Open) {
        return s2Open ? "S2 section lever" : "S2 section lever (early)";
    }

    private static void logLever(BlockPos pos, String code, String detail, double distSq) {
        if (code.equals(leverStateLogged.get(pos.asLong()))) {
            return;
        }
        leverStateLogged.put(pos.asLong(), code);
        LOGGER.info("[LeverAura] lever {}: {} distToBox={} tick={}", pos.toShortString(), detail,
                distSq < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distSq)), ActionGate.currentTick());
    }

    /** A chosen lever held back, once per key per 20 ticks (BloodCampFeature.logRefusal model). */
    private static void logHeld(String key, String detail) {
        long now = ActionGate.currentTick();
        if (key.equals(lastHeldKey) && now - lastHeldTick < 20) {
            return;
        }
        lastHeldKey = key;
        lastHeldTick = now;
        LOGGER.info("[LeverAura] flick held: {} tick={}", detail, now);
    }

    public static void register() {
        P3SectionTracker.register();
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("LeverAuraFeature.tick", LeverAuraFeature::tick));
        LOGGER.debug("[LeverAura] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------------------------

    /** The body turn for a lever the reported look does not reach (see util/TurnFirst); the camera never moves. */
    private static final com.killer560.hub.util.TurnFirst TURN = new com.killer560.hub.util.TurnFirst();

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
            LOGGER.info("[LeverAura] section {} -> {}: dwell timers cleared", lastSection, section);
            lastSection = section;
            inRangeSince.clear();
            requiredDwell.clear();
        }
        LeverAuraConfig cfg = LeverAuraConfig.getInstance();
        String gate = null;
        if (!cfg.isEnabled()) {
            gate = "disabled";
        } else if (client.level == null || client.player == null || client.gameMode == null) {
            gate = "no player";
        } else if (!CheatUtils.isOnDungeonServer(client)) {
            gate = "not on hypixel/p3sim";
        } else if (!DungeonState.isF7OrM7() || !Floor7Tracker.inF7Boss()) {
            gate = "not in F7/M7 boss";
        } else if (Floor7Tracker.getPhaseAt() != Floor7Tracker.Phase.P3) {
            gate = "not in P3 area";
        } else if (section > 2) {
            gate = "past S2 (section " + section + ")";
        } else if (com.killer560.hub.util.ActionGate.containerScreenOpen(client)) {
            gate = "a container screen is open";
        }
        if (!java.util.Objects.equals(gate, lastGateLogged)) {
            LOGGER.info("[LeverAura] gate: {} -> {} (section {}) tick={}", lastGateLogged == null ? "running" : lastGateLogged,
                    gate == null ? "running" : gate, section, ActionGate.currentTick());
            lastGateLogged = gate;
        }
        if (gate != null) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean s2Open = section == 2;
        Vec3 eye = client.player.getEyePosition();
        double rangeSq = cfg.getRange() * cfg.getRange();

        BlockPos target = null;
        String reason = null;
        Set<Long> inRangeNow = new HashSet<>();

        // ---- S2 section levers ----
        boolean sectionWanted = s2Open ? cfg.isSectionLevers() : cfg.isSectionLeversEarly();
        if (sectionWanted) {
            double best = Double.MAX_VALUE;
            for (BlockPos pos : S2_SECTION_LEVERS) {
                BlockState st = client.level.getBlockState(pos);
                if (st.getBlock() != Blocks.LEVER) {
                    logLever(pos, "notLever", "no lever block there (chunk not loaded or replaced)", -1);
                    continue;
                }
                // Nearest point of the block, not its centre. See BlockHits.boxDistanceSq.
                double d = com.killer560.hub.util.BlockHits.boxDistanceSq(eye, pos);
                if (d > rangeSq) {
                    logLever(pos, "outOfRange", "out of range " + String.format(java.util.Locale.ROOT, "%.2f", cfg.getRange()), d);
                    continue;
                }
                long key = pos.asLong();
                inRangeNow.add(key);
                if (!dwellPassed(cfg, key, now) || (s2Open ? clickedS2 : clickedEarly).contains(key)
                        || !hasNotActivatedStand(client, pos)) {
                    // Read back what dwellPassed just stored (no second call: it rolls the dwell on first sight).
                    Long since = inRangeSince.get(key);
                    Long need = requiredDwell.get(key);
                    if (since != null && need != null && now - since < need) {
                        logLever(pos, "dwell", "in range, waiting the dwell (" + need + " ms)", d);
                    } else if ((s2Open ? clickedS2 : clickedEarly).contains(key)) {
                        logLever(pos, "clicked", s2Open ? "already flicked since S2 opened" : "already flicked early", d);
                    } else {
                        logLever(pos, "activated", "no Not Activated stand above (already activated)", d);
                    }
                    continue;
                }
                logLever(pos, "eligible", reasonFor(s2Open), d);
                if (d < best) {
                    best = d;
                    target = pos;
                    reason = s2Open ? "S2 section lever" : "S2 section lever (early)";
                }
            }
        }

        inRangeSince.keySet().retainAll(inRangeNow);
        requiredDwell.keySet().retainAll(inRangeNow);

        long tick = ActionGate.currentTick();
        if (target == null) {
            turnStartTick = -1L;
            return;
        }
        if (tick < nextClickDueTick) {
            logHeld("cps", "CPS gap: next flick due at tick " + String.format(java.util.Locale.ROOT, "%.2f", nextClickDueTick)
                    + " (now " + tick + ") lever " + target.toShortString());
            return;
        }
        // killer560 (2026-09-20): "make sure that stuff like breaker aura, and flicking levers aren't gonna go off in
        // the same packet". Last check before anything is mutated - a refusal must cost nothing, the same lever is
        // simply re-picked next tick. Target selection above is already nearest-first, one lever per tick.
        if (!ActionGate.tryAct(ActionGate.Actor.LEVER_AURA)) {
            logHeld("gate", "ActionGate refused LEVER_AURA, lever " + target.toShortString());
            return;
        }
        if (!click(client, cfg, target, reason, s2Open)) {
            // The body was turned to it this tick (or no hit yet): the flick goes next tick.
            if (turnStartTick < 0) {
                turnStartTick = tick;
            }
            return;
        }
        // A flick that needed a turn first counts from the tick the turn took, not the tick after: otherwise every
        // turned flick added its turn tick to the gap and 10 CPS measured about 8.
        long base = turnStartTick >= 0 ? turnStartTick : tick;
        turnStartTick = -1L;
        // CPS: each gap is 1000 / CPS ms with +-10% jitter (never a metronome), scheduled from the previous click's due
        // tick, not this one, so the average is the CPS set even when the gap is a fraction of a tick. One flick a tick
        // at most, so 20 is the ceiling; a flick the gate held back carries at most a tick of backlog.
        nextClickDueTick = Math.max(nextClickDueTick, base - 1) + gapMs(cfg) / 50.0;
    }

    /** False when nothing went out this tick (no hit yet, or the body was turned to the lever first). */
    private static boolean click(Minecraft client, LeverAuraConfig cfg, BlockPos pos, String reason, boolean s2Open) {
        // A surface hit from the eye rather than the block's centre: a point INSIDE the block is what GrimAC flagged on
        // Secret Aura (2026-09-28, PositionPlace at a distance the server itself accepted). See BlockHits.
        BlockHitResult hit = com.killer560.hub.util.BlockHits.surface(client.level, pos,
                client.player.getEyePosition());
        if (hit == null) {
            // Skip the tick rather than send a hit no ray could produce; the lever is not marked done, so the next
            // tick tries again once the geometry allows a real hit.
            logHeld("noHit", "no surface hit on lever " + pos.toShortString() + " from here");
            return false;
        }
        // A lever the look the server was told about does not reach (behind him) gets the BODY turned to it first,
        // the camera held: Secret Aura's identical click drew GrimAC RotationPlace from behind (testkit 419).
        if (!TURN.readyBlock(client.player, client.level, pos, hit.getLocation(),
                client.player.getEyePosition().distanceTo(hit.getLocation()) + 1.0)) {
            LOGGER.info("[LeverAura] turning body to lever {} first (reported look does not reach it) tick={}",
                    pos.toShortString(), ActionGate.currentTick());
            return false;
        }
        (s2Open ? clickedS2 : clickedEarly).add(pos.asLong());
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        LOGGER.info("[LeverAura] flick sent: lever {} ({}) face={} dist={} powered={} swing={} tick={}", pos.toShortString(),
                reason, hit.getDirection(), String.format(java.util.Locale.ROOT, "%.2f",
                        client.player.getEyePosition().distanceTo(hit.getLocation())),
                client.level.getBlockState(pos).getBlock() == Blocks.LEVER
                        ? client.level.getBlockState(pos).getValue(net.minecraft.world.level.block.LeverBlock.POWERED) : null,
                cfg.isSwingHand(), ActionGate.currentTick());
        if (cfg.isSwingHand()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        if (cfg.isChatFeedback()) {
            ModChat.send(CHAT_TAG, ModChat.text("Flicked "), ModChat.value(pos.toShortString()), ModChat.dim(" (" + reason + ")"));
        }
        return true;
    }

    /** A lever must have been in range for one CPS gap (rolled when it came into range) before its first click. */
    private static boolean dwellPassed(LeverAuraConfig cfg, long key, long now) {
        long since = inRangeSince.computeIfAbsent(key, k -> now);
        long need = requiredDwell.computeIfAbsent(key, k -> Math.round(gapMs(cfg)));
        return now - since >= need;
    }

    /** One gap between flicks: 1000 / CPS ms, +-10%. */
    private static double gapMs(LeverAuraConfig cfg) {
        return 1000.0 / cfg.getCps() * (0.9 + ThreadLocalRandom.current().nextDouble() * 0.2);
    }

    /** QUOI isBossBlock / SecretAuraFeature#isBossLeverClickable: a "Not Activated" armor stand just above. */
    private static boolean hasNotActivatedStand(Minecraft client, BlockPos pos) {
        Vec3 above = Vec3.atCenterOf(pos.above());
        AABB box = new AABB(above, above).inflate(1.5);
        return !client.level.getEntitiesOfClass(ArmorStand.class, box,
                s -> s.getDisplayName() != null && "Not Activated".equals(s.getDisplayName().getString())).isEmpty();
    }

    private static void resetRun() {
        LOGGER.info("[LeverAura] new run (generation {}): clickedEarly={} clickedS2={} cleared", P3SectionTracker.generation(),
                clickedEarly.size(), clickedS2.size());
        leverStateLogged.clear();
        lastHeldKey = null;
        clickedEarly.clear();
        clickedS2.clear();
        inRangeSince.clear();
        requiredDwell.clear();
        nextClickDueTick = 0.0;
        turnStartTick = -1L;
        lastSection = P3SectionTracker.section();
    }

    /** True while In Boss is on and owns {@code pos} (its click-once rules): S2's two section levers while Section
     *  Levers is on. {@code SecretAuraFeature} skips these so the two can't both click the same lever inside the same
     *  second. The Lights levers are the Lights Device's, which Secret Aura leaves alone on its own rule. */
    public static boolean owns(BlockPos pos) {
        LeverAuraConfig cfg = LeverAuraConfig.getInstance();
        return cfg.isSectionLevers() && S2_SECTION_LEVERS.contains(pos);
    }

    /** For the tab's status line. */
    public static Component statusText() {
        int section = P3SectionTracker.section();
        String s = section >= 5 ? "Core open" : section > 2 ? "Past S2" : section == 2 ? "S2 open" : "S2 not open";
        return Component.literal("§7Tracked: §f" + s);
    }
}

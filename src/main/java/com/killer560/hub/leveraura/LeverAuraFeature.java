package com.killer560.hub.leveraura;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lever Aura - F7/M7 P3 (Goldor) Section 2 lever automation, cheat build only. killer560's spec: "in s2 if I have
 * lever aura on it'll do each corner lever and the two in the middle if s2 isn't open yet. Then once s1 finishes if I
 * go into s2 it should flick any lever prioritizing levers that would complete the dev then if not click another lever
 * to activate the dev. Don't keep flicking, only flick it once."
 * <p>
 * "Each corner lever and the two in the middle" is the S2 Lights device: 6 levers on the wall at z=142 - corners
 * (58/62, 133/136) and middle (60, 134/135). All six powered = all lamps lit = device done; flicking a lit lever does
 * not turn its light off ("Levers don't turn off lights only on", hypixel.net/threads/5607893).
 * Coordinates: Skyblocker {@code skyblock/dungeon/device/LightsOn.java} (TOP_LEFT 62,136,142 ... BOTTOM_RIGHT
 * 58,133,142), identical to QUOI {@code SecretAura.kt}'s device lever list already in
 * {@code cheatutils.SecretAuraFeature#DEVICE_LEVERS}. S2's two section levers (27,124,127) and (23,132,138) are also
 * QUOI's (SecretAuraFeature#BOSS_LEVERS, the two inside Floor7Tracker's S2 box x 19..111, z 121..145).
 * <p>
 * Behaviour:
 * <ul>
 * <li><b>Before S2 is open</b> (S1 not cleared yet): every UNLIT lights lever in range is flicked once (skip lit ones).
 * <li><b>S2 open</b> (S1 objectives "(n/n)" + "The gate has been destroyed!", tracked here from chat, so it also works on
 * p3sim.net where Goldor's opening line can be missing): if exactly one lever is unlit, that one is flicked (it
 * completes the device). If more are unlit, the nearest unlit one is flicked (each once - the last completes it). If
 * all six are already lit (pre-flicked), exactly ONE lever is flicked to make the now-active device register.
 * Once any S2-phase click was made, no "activation" click follows, and every S2 lever is clicked at most once.
 * The device is done on a "... completed a device! (x/y)" line while S2 is the active section.
 * <li>Optional: S2's two section levers, while their "Not Activated" stand is above them - once after S2 opens, and
 * optionally once more beforehand ("early"). The stand check stops any re-click after they activated.
 * <li>Everything resets on world change and on Goldor's "Who dares trespass" line; nothing happens past S2.
 * </ul>
 * Click is {@code SecretAuraFeature}'s: no rotation, {@code gameMode.useItemOn} with a synthetic BlockHitResult, eye to
 * block-center range, no line-of-sight check (SecretAura has none either), never with a screen open. Human-ish pacing: a
 * lever must be in range for a random min..max delay before its first click, and clicks are spaced by a fresh random
 * min..max delay.
 */
public final class LeverAuraFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-leveraura");
    private static final String CHAT_TAG = "Lever Aura";

    /** Skyblocker LightsOn.java / QUOI SecretAura.kt. */
    private static final List<BlockPos> LIGHTS_LEVERS = List.of(
            new BlockPos(62, 136, 142), new BlockPos(58, 136, 142),   // top corners
            new BlockPos(60, 135, 142), new BlockPos(60, 134, 142),   // middle
            new BlockPos(62, 133, 142), new BlockPos(58, 133, 142));  // bottom corners
    /** QUOI SecretAura.kt boss levers inside Section 2. */
    private static final List<BlockPos> S2_SECTION_LEVERS = List.of(
            new BlockPos(27, 124, 127), new BlockPos(23, 132, 138));

    // Same completion regex as Floor7Tracker / QUOI REGEX_TERM_COMPLETED (optional trailing suffix from other mods).
    private static final Pattern TERM_COMPLETED =
            Pattern.compile("^(.{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");
    private static final String GOLDOR_START = "[BOSS] Goldor: Who dares trespass into my domain?";
    private static final String GATE_DESTROYED = "The gate has been destroyed!";
    private static final String CORE_OPENING = "The Core entrance is opening!";
    private static final String ACTIVATION_REASON = "lights: all lit, one activation flick";

    // ---- P3 section tracking (own, chat-only; see class doc) ----
    private static int section = 1;
    private static int sectionCurrent = 0;
    private static int sectionTotal = 0;
    private static boolean sectionGate = false;
    private static boolean lightsCompleted = false;

    // ---- click bookkeeping ----
    /** Levers clicked before S2 opened (per run). */
    private static final Set<Long> clickedEarly = new HashSet<>();
    /** Levers clicked after S2 opened (per run). */
    private static final Set<Long> clickedS2 = new HashSet<>();
    private static boolean lightsActivationClicked = false;
    private static final Map<Long, Long> inRangeSince = new HashMap<>();
    private static final Map<Long, Long> requiredDwell = new HashMap<>();
    private static long nextClickAllowedMs = 0L;
    private static Object lastLevel = null;
    private static String lastGateLog = null;
    /** Whether Goldor's opening line was seen this run - p3sim.net can skip it (see {@link #onChat}). */
    private static boolean sawGoldorStart = false;

    private LeverAuraFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(LeverAuraFeature::tick);
        ChatObserver.subscribe(message -> onChat(ChatObserver.strip(message)));
        LOGGER.info("[LeverAura] Registered (cheatBuild={})", com.killer560.hub.BuildVariant.CHEAT_FEATURES_ENABLED);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Chat: section progress + lights completion
    // ------------------------------------------------------------------------------------------------------------

    static void onChat(String plain) {
        if (plain == null || !DungeonState.isF7OrM7()) {
            return;
        }
        plain = plain.trim();
        if (GOLDOR_START.equals(plain)) {
            resetRun("Goldor start");
            sawGoldorStart = true;
            return;
        }
        if (CORE_OPENING.equals(plain)) {
            setSection(5, "core opening");
            return;
        }
        if (GATE_DESTROYED.equals(plain)) {
            sectionGate = true;
            checkSectionAdvance("gate destroyed");
            return;
        }
        Matcher m = TERM_COMPLETED.matcher(plain);
        if (!m.matches()) {
            return;
        }
        int cur = parse(m.group(4));
        int tot = parse(m.group(5));
        // p3sim.net (and a mid-run join) can skip Goldor's opening line: with no start line seen this world, the
        // first objective line tells us P3 is running, so take the section from where the player is actually
        // standing (Floor7Tracker's own S1..S4 boxes) instead of assuming S1 - otherwise the S2 "finish the
        // device" behaviour never runs there. Same rule Floor7Tracker uses for its own phase inference.
        if (!sawGoldorStart && section == 1 && sectionTotal == 0) {
            Floor7Tracker.Stage at = Floor7Tracker.getStageAt();
            if (at.number >= 2 && at.number <= 4) {
                setSection(at.number, "inferred from position (no Goldor line seen)");
            }
        }
        // Missed gate line fallback: this section was already complete and a line from a different count arrives.
        if (sectionTotal > 0 && sectionCurrent == sectionTotal && (tot != sectionTotal || cur < sectionCurrent)) {
            setSection(section + 1, "new section's objective line (" + cur + "/" + tot + ")");
        }
        sectionCurrent = cur;
        sectionTotal = tot;
        if (section == 2 && "device".equals(m.group(3)) && !lightsCompleted) {
            // S2 has exactly one device - the Lights device.
            lightsCompleted = true;
            LOGGER.info("[LeverAura] S2 device (Lights) completed by {}", m.group(1));
        }
        checkSectionAdvance("objective " + cur + "/" + tot);
    }

    private static void checkSectionAdvance(String why) {
        if (section <= 3 && sectionGate && sectionTotal > 0 && sectionCurrent == sectionTotal) {
            setSection(section + 1, why);
        }
    }

    private static void setSection(int s, String why) {
        if (s == section) {
            return;
        }
        LOGGER.info("[LeverAura] P3 section {} -> {} ({})", section, s, why);
        section = s;
        sectionCurrent = 0;
        sectionTotal = 0;
        sectionGate = false;
        inRangeSince.clear();
        requiredDwell.clear();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------------------------------------------------

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            resetRun("world change");
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
        } else if (client.screen != null) {
            gate = "screen open";
        }
        logGate(gate == null ? (section == 1 ? "active (S2 not open)" : "active (S2 open)") : gate);
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

        // ---- Lights device ----
        boolean lightsWanted = s2Open ? cfg.isLightsFinish() && !lightsCompleted : cfg.isLightsPreFlick();
        if (lightsWanted) {
            int unlitTotal = 0;
            BlockPos nearestUnlit = null;
            double nearestUnlitSq = Double.MAX_VALUE;
            BlockPos nearestAny = null;
            double nearestAnySq = Double.MAX_VALUE;
            boolean allLevers = true;
            for (BlockPos pos : LIGHTS_LEVERS) {
                BlockState st = client.level.getBlockState(pos);
                if (st.getBlock() != Blocks.LEVER) {
                    allLevers = false; // chunk not loaded / not the device
                    continue;
                }
                boolean lit = st.getValue(LeverBlock.POWERED);
                if (!lit) {
                    unlitTotal++;
                }
                double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
                if (d > rangeSq) {
                    continue;
                }
                long key = pos.asLong();
                inRangeNow.add(key);
                if (!dwellPassed(cfg, key, now)) {
                    continue;
                }
                Set<Long> clicked = s2Open ? clickedS2 : clickedEarly;
                if (clicked.contains(key)) {
                    continue;
                }
                if (!lit && d < nearestUnlitSq) {
                    nearestUnlitSq = d;
                    nearestUnlit = pos;
                }
                if (d < nearestAnySq) {
                    nearestAnySq = d;
                    nearestAny = pos;
                }
            }
            if (allLevers) {
                if (nearestUnlit != null) {
                    target = nearestUnlit;
                    reason = s2Open
                            ? (unlitTotal == 1 ? "lights: last unlit lever (completes device)" : "lights: unlit lever (" + unlitTotal + " unlit)")
                            : "lights: pre-flick before S2 (" + unlitTotal + " unlit)";
                } else if (s2Open && unlitTotal == 0 && clickedS2.isEmpty() && !lightsActivationClicked && nearestAny != null) {
                    target = nearestAny;
                    reason = ACTIVATION_REASON;
                }
            }
        }

        // ---- S2 section levers ----
        boolean sectionWanted = s2Open ? cfg.isSectionLevers() : cfg.isSectionLeversEarly();
        if (target == null && sectionWanted) {
            double best = Double.MAX_VALUE;
            for (BlockPos pos : S2_SECTION_LEVERS) {
                BlockState st = client.level.getBlockState(pos);
                if (st.getBlock() != Blocks.LEVER) {
                    continue;
                }
                double d = eye.distanceToSqr(Vec3.atCenterOf(pos));
                if (d > rangeSq) {
                    continue;
                }
                long key = pos.asLong();
                inRangeNow.add(key);
                if (!dwellPassed(cfg, key, now) || (s2Open ? clickedS2 : clickedEarly).contains(key)
                        || !hasNotActivatedStand(client, pos)) {
                    continue;
                }
                if (d < best) {
                    best = d;
                    target = pos;
                    reason = s2Open ? "S2 section lever" : "S2 section lever (early)";
                }
            }
        }

        inRangeSince.keySet().retainAll(inRangeNow);
        requiredDwell.keySet().retainAll(inRangeNow);

        if (target == null || now < nextClickAllowedMs) {
            return;
        }
        // killer560 (2026-09-20): "make sure that stuff like breaker aura, and flicking levers aren't gonna go off in
        // the same packet". Last check before anything is mutated - a refusal must cost nothing, the same lever is
        // simply re-picked next tick. Target selection above is already nearest-first, one lever per tick.
        if (!ActionGate.tryAct(ActionGate.Actor.LEVER_AURA)) {
            return;
        }
        if (ACTIVATION_REASON.equals(reason)) {
            lightsActivationClicked = true;
        }
        click(client, cfg, target, reason, s2Open);
        nextClickAllowedMs = now + randomDelay(cfg);
    }

    private static void click(Minecraft client, LeverAuraConfig cfg, BlockPos pos, String reason, boolean s2Open) {
        (s2Open ? clickedS2 : clickedEarly).add(pos.asLong());
        BlockState st = client.level.getBlockState(pos);
        Direction face = Direction.UP;
        if (st.hasProperty(LeverBlock.FACE) && st.hasProperty(LeverBlock.FACING)) {
            AttachFace attach = st.getValue(LeverBlock.FACE);
            face = attach == AttachFace.WALL ? st.getValue(LeverBlock.FACING)
                    : attach == AttachFace.CEILING ? Direction.DOWN : Direction.UP;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
        client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
        if (cfg.isSwingHand()) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
        double dist = Math.sqrt(client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)));
        LOGGER.info("[LeverAura] Clicked {} ({}, dist={}, section={}, wasPowered={})", pos.toShortString(), reason,
                String.format(Locale.US, "%.2f", dist), section, st.hasProperty(LeverBlock.POWERED) && st.getValue(LeverBlock.POWERED));
        if (cfg.isChatFeedback()) {
            ModChat.send(CHAT_TAG, ModChat.text("Flicked "), ModChat.value(pos.toShortString()), ModChat.dim(" (" + reason + ")"));
        }
    }

    /** A lever must have been in range for a random min..max delay (rolled when it came into range) before a click. */
    private static boolean dwellPassed(LeverAuraConfig cfg, long key, long now) {
        long since = inRangeSince.computeIfAbsent(key, k -> now);
        long need = requiredDwell.computeIfAbsent(key, k -> randomDelay(cfg));
        return now - since >= need;
    }

    private static long randomDelay(LeverAuraConfig cfg) {
        int min = cfg.getMinDelayMs();
        int max = Math.max(min, cfg.getMaxDelayMs());
        return min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1L);
    }

    /** QUOI isBossBlock / SecretAuraFeature#isBossLeverClickable: a "Not Activated" armor stand just above. */
    private static boolean hasNotActivatedStand(Minecraft client, BlockPos pos) {
        Vec3 above = Vec3.atCenterOf(pos.above());
        AABB box = new AABB(above, above).inflate(1.5);
        return !client.level.getEntitiesOfClass(ArmorStand.class, box,
                s -> s.getDisplayName() != null && "Not Activated".equals(s.getDisplayName().getString())).isEmpty();
    }

    private static void resetRun(String why) {
        if (!clickedEarly.isEmpty() || !clickedS2.isEmpty() || section != 1) {
            LOGGER.info("[LeverAura] Reset ({}): section was {}, {} early clicks, {} S2 clicks, lightsCompleted={}",
                    why, section, clickedEarly.size(), clickedS2.size(), lightsCompleted);
        }
        section = 1;
        sectionCurrent = 0;
        sectionTotal = 0;
        sectionGate = false;
        sawGoldorStart = false;
        lightsCompleted = false;
        lightsActivationClicked = false;
        clickedEarly.clear();
        clickedS2.clear();
        inRangeSince.clear();
        requiredDwell.clear();
        nextClickAllowedMs = 0L;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void logGate(String gate) {
        if (!gate.equals(lastGateLog)) {
            lastGateLog = gate;
            LOGGER.info("[LeverAura] State: {}", gate);
        }
    }

    /** True while Lever Aura is on and owns {@code pos} (its click-once rules): the S2 Lights device levers always,
     *  and S2's two section levers while Section Levers is on. {@code SecretAuraFeature} skips these so the two auras
     *  can't both click the same lever inside the same second. */
    public static boolean owns(BlockPos pos) {
        LeverAuraConfig cfg = LeverAuraConfig.getInstance();
        if (!cfg.isEnabled()) {
            return false;
        }
        return LIGHTS_LEVERS.contains(pos) || (cfg.isSectionLevers() && S2_SECTION_LEVERS.contains(pos));
    }

    /** For the tab's status line. */
    public static Component statusText() {
        String s = section >= 5 ? "Core open" : section > 2 ? "Past S2" : section == 2 ? "S2 open" : "S2 not open";
        return Component.literal("§7Tracked: §f" + s + (lightsCompleted ? " §7| §aLights done" : ""));
    }
}

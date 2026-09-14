package com.killer560.hub.simonsays;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.WorldRenderUtils;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F7/M7 boss-fight Simon Says solver.
 * <p>
 * The real device layout and detection logic below is ported from Odin's and QUOI's own confirmed,
 * compiling implementations for this exact Minecraft version (both pin {@code minecraft_version=26.1.2}
 * in their {@code gradle.properties}) - not guessed. The button grid is a 4x4 wall at x=110 (stone
 * buttons) with a matching sea-lantern wall at x=111, y in [120,123], z in [92,95], with the device's
 * start button at (110, 121, 91). The real "P1 starts" auto-start trigger and its two settings are
 * ported from NoammAddons' own {@code SimonSays.kt} (cloned reference, 2026-09-14) - see
 * {@link #AUTO_START_TRIGGER_PATTERN}'s doc comment.
 * <p>
 * This mod has no {@code BlockUpdateEvent}-style hook (Odin/QUOI each define their own via a Mixin this
 * codebase hasn't added, deliberately - see this mod's standing "no unconfirmed Mixin target" rule), so
 * detection here is done by polling {@link net.minecraft.world.level.Level#getBlockState} once a client
 * tick and diffing against the previous tick's snapshot, same safe mixin-free technique the original
 * diagnostic-only version of this class used. 20Hz polling is far finer than a human's real click cadence.
 */
public final class SimonSaysFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-simonsays");

    private static final BlockPos START_BUTTON = new BlockPos(110, 121, 91);
    private static final double ACTIVE_RANGE_SQ = 30.0 * 30.0;

    // "SS 3/5" - deliberately the same wire format Odin's own announceProgress uses (its real chat call
    // is literally `pc SS ${clickInOrder.size}/5`), so this mod's party progress tracker also understands
    // teammates running Odin or QUOI, not just other killer560s-mod users.
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(?:^|[:>\\]]\\s*)(\\S+?)\\s*:\\s*SS (\\d+)/(\\d+)");
    private static final Pattern PROGRESS_PATTERN_SIMPLE = Pattern.compile("SS (\\d+)/(\\d+)");

    // Real chat line (ported from NoammAddons' own SimonSays.kt `startRegex`, confirmed against this
    // exact Minecraft version's F7/M7 boss fight) marking the moment the SS device's boss phase actually
    // begins - killer560's own "the second p1 starts" request. Matched on plain text with formatting
    // stripped first, same established convention DungeonState's own BOSS_START_PATTERN uses.
    private static final Pattern AUTO_START_TRIGGER_PATTERN =
            Pattern.compile("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$");

    private static final List<BlockPos> GRID_LANTERNS = buildGrid(111);
    private static final List<BlockPos> GRID_BUTTONS = buildGrid(110);

    private static List<BlockPos> buildGrid(int x) {
        List<BlockPos> list = new ArrayList<>();
        for (int y = 120; y <= 123; y++) {
            for (int z = 92; z <= 95; z++) {
                list.add(new BlockPos(x, y, z));
            }
        }
        return list;
    }

    // --- solve state (Odin's real, confirmed sequence-detection logic, ported) ---
    private static final List<BlockPos> clickInOrder = new ArrayList<>();
    private static int clickNeeded = 0;
    private static boolean firstPhase = true;
    // Real bug found and fixed (2026-09-14): this mod's port of the firstPhase reveal-order quirk
    // (size 2 -> reverse, size 3 -> drop the middle) only ever cleared firstPhase after a FULL
    // successful click-through - Odin's own real code instead clears it on a real TIMEOUT once the
    // reveal flash settles (see below), independent of whether you've clicked anything yet. Without
    // that timeout, every subsequent lantern reveal past the 3rd kept re-triggering the same
    // size-3-drops-the-middle correction forever (add a 4th -> size 3 again -> drop again -> stays at
    // 2), which is exactly the real symptom killer560 reported: "only keeping 2 highlighted" and
    // highlights "moving off early." lastLanternChangeTick counts ticks since the last real lantern
    // step was recorded; once 10 ticks pass with the grid back to mostly real buttons (not still mid-
    // flash), firstPhase clears for the rest of this device's reveal, matching Odin's own real logic.
    private static int lastLanternChangeTick = -1;
    private static BlockState lastStartButtonState = null;
    private static final Map<BlockPos, BlockState> lastGridStates = new HashMap<>();
    private static boolean wasActive = false;
    private static boolean wasGridReset = false;
    private static long solveStartedAtMs = 0L;

    // --- whole-device completion timer (2026-09-14, killer560's own request): "send a message client
    // side about how long it took from the first ss start click to the last click to finish dev." Unlike
    // solveStartedAtMs above (which resets every round - it only ever measured ONE round, despite the
    // old "Device completed" log name being misleading about that), these two track the REAL whole
    // device: first click of round 1 to the final click of round 5, regardless of whether a person,
    // Trigger Bot, or Auto Solve is doing the clicking. Reset only at firstPhase's own two real trigger
    // points (fresh device encounter, real start-button press), never on the routine per-round reset -
    // same treatment as the auto-solve pacing fields below, for the same reason.
    private static long deviceStartedAtMs = 0L;
    private static int totalClicksThisAttempt = 0;

    // --- announce keybind (renamed from "reset key" 2026-09-14 - see SimonSaysConfig's own doc comment:
    // it no longer resets any solve state itself, only sends the announce chat line on demand) ---
    private static boolean announceKeyWasDown = false;

    // --- real start-button CLICK ATTEMPT timing logger (2026-09-14, killer560's own explicit request):
    // "add a quick logger to see how I manually start it so we can get a good guess as to what time
    // spacing for clicks actually gets the skip." Hooked into the actual useItemOn call via
    // SimonSaysMisclickMixin/onRealBlockInteractAttempt rather than polling the start button's own block
    // state - a real stone button stays POWERED for about a second after being clicked, so clicking it
    // again while still powered doesn't emit a new state transition, meaning polling could only ever see
    // the FIRST click of a rapid burst (confirmed against a real log: killer560 said "it should be 3 or 4
    // clicks really close together" but the block-state version only ever showed multi-second gaps).
    // Always-on (not gated behind Diagnostic Logging) - this is the active investigation right now.
    private static long lastStartButtonPressAtMs = 0L;
    // Set true for the duration of this mod's OWN synthetic useItemOn calls (Auto Solve/Auto Start/
    // Trigger Bot) so onRealBlockInteractAttempt can tell a real player click apart from the bot's own -
    // both funnel through the exact same real method, with no other distinguishing signal available.
    private static boolean syntheticClickInProgress = false;

    // --- auto-start pacing (real trigger + settings ported from NoammAddons) ---
    private static boolean autoStartRunning = false;
    private static int autoStartClicksSent = 0;
    private static int autoStartTicksUntilNextClick = 0;

    // --- auto-solve pacing (Target ± Variance overall for the WHOLE device attempt - moved here from
    // Auto Start's old model 2026-09-14, see SimonSaysConfig's own doc comment) ---
    private static final int TOTAL_REAL_CLICKS_PER_DEVICE = 1 + 2 + 3 + 4 + 5; // 15 - confirmed real:
    // exactly 5 rounds, round N has N steps (see this class's own doc comment).
    private static long lastAutoClickAtMs = 0L;
    private static BlockPos lastAutoClickedPos = null;
    private static long autoSolveDeadlineMs = 0L;
    private static long autoSolveNextClickAtMs = 0L;
    private static boolean autoSolveArmed = false;
    private static int autoSolveClicksDoneThisAttempt = 0;
    // Wall-clock time of the last tick the reveal-delay accounting above ran - lets it compute exactly
    // how much real time passed since the last check, so it can extend the deadline by that much while
    // blocked. Reset alongside autoSolveArmed so a stale value from a previous attempt never leaks in.
    private static long autoSolveLastTickAtMs = 0L;
    // Running total of real time spent blocked (reveal-settle windows + waiting between rounds) across
    // the WHOLE attempt (2026-09-14, killer560's own request: "figure out how long it takes to actually
    // go through that transition phase because that needs to be factored into the overall time it
    // takes"). A single observed real run measured this at ~1.4s/1.9s/2.2s/2.6s per round transition
    // (growing with round size, ~8.1s total across all 4 transitions in a full 5-round solve) - reported
    // per-attempt here instead of hardcoding that one-run number, since it will vary run to run.
    private static long autoSolveBlockedMsThisAttempt = 0L;

    // --- trigger bot debounce ---
    private static BlockPos lastTriggerBotTarget = null;

    // --- party progress tracker: sender name -> progress ---
    private static final Map<String, PartyProgress> partyProgress = new HashMap<>();

    private SimonSaysFeature() {
    }

    public static void register() {
        ClientReceiveMessageEvents.CHAT.register(
                (message, signedMessage, sender, params, receptionTimestamp) -> onChatMessage(message));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onChatMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(SimonSaysFeature::onWorldRender);
    }

    private static boolean isDeviceInRange(Minecraft client) {
        return DungeonState.isF7OrM7() && client.player != null
                && client.player.distanceToSqr(Vec3.atCenterOf(START_BUTTON)) <= ACTIVE_RANGE_SQ;
    }

    /** For {@code SimonSaysMisclickMixin} - "Prevent Misclicks", killer560's own request, same real
     *  behavior as Odin's own "Block Wrong Clicks" toggle: only blocks a real click on one of the 16
     *  main grid buttons that ISN'T the one you actually need next, and shift always overrides it (so a
     *  manual "click it anyway" is never fully locked out). Never blocks the start button, and never
     *  blocks anything once the device isn't actively being tracked - this is a safety net during a
     *  real attempt, not a general block-interaction filter. */
    public static boolean shouldBlockClick(BlockPos pos, boolean shiftDown) {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isPreventMisclicksEnabled() || !wasActive || shiftDown) {
            return false;
        }
        if (!GRID_BUTTONS.contains(pos)) {
            return false;
        }
        if (clickNeeded >= clickInOrder.size()) {
            return true;
        }
        BlockPos correctButton = clickInOrder.get(clickNeeded).west();
        return !pos.equals(correctButton);
    }

    /** For {@code SimonSaysMisclickMixin} - called for every real {@code useItemOn} attempt, regardless
     *  of block. Logs the real ms gap between consecutive REAL attempts specifically on the start button
     *  (see {@link #lastStartButtonPressAtMs}'s own doc comment for why this has to hook the actual click
     *  event rather than poll block state). Ignores this mod's own synthetic clicks via
     *  {@link #syntheticClickInProgress}, and anything that isn't the start button. */
    public static void onRealBlockInteractAttempt(BlockPos pos) {
        if (syntheticClickInProgress || !pos.equals(START_BUTTON)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (lastStartButtonPressAtMs > 0) {
            LOGGER.info("[SimonSays] Real start-button click attempt - {}ms since previous.", now - lastStartButtonPressAtMs);
        } else {
            LOGGER.info("[SimonSays] Real start-button click attempt (first this attempt).");
        }
        lastStartButtonPressAtMs = now;
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private static void onChatMessage(Component message) {
        String raw = message.getString();
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();

        if (cfg.isDiagnosticLoggingEnabled() && raw.toLowerCase(Locale.ROOT).contains("simon says")) {
            LOGGER.info("[SimonSays] Chat line mentioning Simon Says: \"{}\"", raw);
        }

        // Real auto-start trigger (ported from NoammAddons) - fires the moment Goldor's real boss line
        // appears, regardless of whether the player is near the device yet (tickAutoStart below only
        // actually clicks once in range, so this just arms the sequence to begin as soon as possible).
        if (cfg.isAutoStartEnabled() && DungeonState.isF7OrM7()) {
            String plain = ChatFormatting.stripFormatting(raw);
            if (plain != null && AUTO_START_TRIGGER_PATTERN.matcher(plain).find()) {
                beginAutoStart(cfg);
            }
        }

        if (!cfg.isPartyProgressTrackerEnabled()) {
            return;
        }
        Matcher m = PROGRESS_PATTERN.matcher(raw);
        String sender;
        int progress;
        int total;
        if (m.find()) {
            sender = m.group(1);
            progress = Integer.parseInt(m.group(2));
            total = Integer.parseInt(m.group(3));
        } else {
            Matcher simple = PROGRESS_PATTERN_SIMPLE.matcher(raw);
            if (!simple.find()) {
                return;
            }
            sender = raw.length() > 20 ? raw.substring(0, 20).trim() : raw.trim();
            progress = Integer.parseInt(simple.group(1));
            total = Integer.parseInt(simple.group(2));
        }
        long now = System.currentTimeMillis();
        PartyProgress p = partyProgress.computeIfAbsent(sender, s -> new PartyProgress());
        if (progress <= 1 || p.total != total) {
            p.startedAtMs = now;
        }
        p.progress = progress;
        p.total = total;
        p.lastUpdateMs = now;
    }

    // ------------------------------------------------------------------
    // Tick: detection, auto-start pacing, auto-solve pacing, trigger bot, announce key
    // ------------------------------------------------------------------

    private static void tick() {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        boolean active = cfg.isEnabled() && client.player != null && client.level != null && isDeviceInRange(client);

        tickAnnounceKeybind(client, cfg);

        if (!active) {
            if (wasActive) {
                LOGGER.info("[SimonSays] Left device range/floor - clearing solve state.");
                resetSolveState();
                lastStartButtonState = null;
                lastStartButtonPressAtMs = 0L;
            }
            wasActive = false;
            return;
        }
        if (!wasActive) {
            // Missing before 2026-09-14 - only the "left" transition was logged, so a real test log
            // could never distinguish "never got in range" from "was in range the whole time but
            // detected nothing" (a real question that came up investigating a p3sim.net report).
            LOGGER.info("[SimonSays] Entered device range on F7/M7 at distance {} - now watching for grid changes.",
                    String.format(Locale.US, "%.1f", Math.sqrt(client.player.distanceToSqr(Vec3.atCenterOf(START_BUTTON)))));
            // A fresh encounter with the device - one of firstPhase's two real trigger points (see
            // resetSolveState's doc comment), matching Odin's own LevelEvent.Load reset. Also re-arms
            // Auto Solve's own once-per-attempt pacing window (see its field doc comment).
            firstPhase = true;
            autoSolveArmed = false;
            autoSolveClicksDoneThisAttempt = 0;
            autoSolveLastTickAtMs = 0L;
            autoSolveBlockedMsThisAttempt = 0L;
            deviceStartedAtMs = 0L;
            totalClicksThisAttempt = 0;
        }
        wasActive = true;

        tickStartButton(client, cfg);

        detectGridChanges(client, cfg);
        tickAutoStart(client, cfg);
        tickAutoSolveAndTriggerBot(client, cfg);
    }

    private static void tickAnnounceKeybind(Minecraft client, SimonSaysConfig cfg) {
        if (cfg.getAnnounceKeyCode() < 0 || client.getWindow() == null) {
            announceKeyWasDown = false;
            return;
        }
        boolean down = InputConstants.isKeyDown(client.getWindow(), cfg.getAnnounceKeyCode());
        if (down && !announceKeyWasDown) {
            LOGGER.info("[SimonSays] Manual announce via keybind.");
            if (client.player != null) {
                client.player.connection.sendCommand("pc " + cfg.getResetMessageText());
            }
        }
        announceKeyWasDown = down;
    }

    /** Sends the real announce/reset party-chat line, if "Auto Message" is on - wired into BOTH real
     *  automatic reset-detection paths (a real start-button press, and a grid-reset transition), not
     *  just the manual "Announce Reset Key" press. Killer560's explicit request (2026-09-14): "make sure
     *  that works if i ever press the reset button after the first time or if you sense it reset via
     *  removing all buttons and no new highlights." */
    private static void maybeAutoAnnounceReset(Minecraft client, SimonSaysConfig cfg) {
        if (cfg.isAutoSendResetMessage() && client.player != null) {
            client.player.connection.sendCommand("pc " + cfg.getResetMessageText());
        }
    }

    private static void resetSolveState() {
        clickInOrder.clear();
        clickNeeded = 0;
        // Deliberately does NOT touch firstPhase - matching Odin's own real resetSolution(), which
        // never does either. Real bug found and fixed (2026-09-14): this used to force firstPhase=true
        // right here, unconditionally - but this method is also called on the routine "grid reset
        // detected" between EVERY round of a real multi-round device (the sequence genuinely grows and
        // replays from the top each round, like classic Simon Says), not just once per device. That
        // silently re-armed the reveal-order quirk (reverse at size 2, drop-the-middle at size 3) for
        // round 2 onward, scrambling a sequence that should never have gotten that correction again -
        // exactly killer560's report ("first one should always be the first one", seeing only the last
        // two entries highlighted). firstPhase is now set true ONLY at its own two real trigger points:
        // entering device range fresh, and a real start-button press (see below) - both matching Odin's
        // own explicit reset sites, never the general per-round reset.
        lastLanternChangeTick = -1;
        lastGridStates.clear();
        autoStartRunning = false;
        autoStartClicksSent = 0;
        autoStartTicksUntilNextClick = 0;
        // Deliberately does NOT touch autoSolveArmed/autoSolveClicksDoneThisAttempt - this method also
        // runs on the routine per-round reset (same reasoning as firstPhase above), and the whole point
        // of this pacing model is ONE Target ± Variance window across all 5 rounds of a single attempt,
        // not a fresh one every round (that was the real bug killer560 found - "extremely slow" because
        // the full target duration was being spent on each round's handful of clicks alone). Those two
        // fields are only reset at firstPhase's own two real trigger points (fresh device encounter,
        // real start-button press) - see below and tick()'s "entered device range" branch.
        lastAutoClickedPos = null;
        lastTriggerBotTarget = null;
        solveStartedAtMs = 0L;
    }

    /** Real start-button-press detection, ported from Odin's own {@code BlockUpdateEvent} check for
     *  {@code pos == startButton}. This is firstPhase's OTHER real trigger point besides entering
     *  device range fresh (see resetSolveState's doc comment) - a real press means the attempt is
     *  restarting from scratch (e.g. after a failure), so the reveal-order quirk needs to apply again
     *  for the new reveal that follows. Also fires "Auto Message" (2026-09-14) - a real press of this
     *  button is exactly "the reset button" killer560 meant, and this fires on every real press, not
     *  just the first. */
    private static void tickStartButton(Minecraft client, SimonSaysConfig cfg) {
        BlockState now = client.level.getBlockState(START_BUTTON);
        BlockState old = lastStartButtonState;
        lastStartButtonState = now;
        if (old == null) {
            return;
        }
        boolean nowPowered = now.is(Blocks.STONE_BUTTON) && now.getValue(BlockStateProperties.POWERED);
        boolean oldPowered = old.is(Blocks.STONE_BUTTON) && old.getValue(BlockStateProperties.POWERED);
        if (nowPowered && !oldPowered) {
            // Real click-timing logging moved to onRealBlockInteractAttempt (2026-09-14) - a real stone
            // button stays POWERED for about a second after being clicked, and clicking it again WHILE
            // still powered doesn't emit a new block-state transition (POWERED was already true), so this
            // block-state-based edge can only ever catch the FIRST click of a rapid burst, never the
            // rapid repeats killer560 specifically wanted timing on ("it should be 3 or 4 clicks really
            // close together" - his real log only ever showed multi-SECOND gaps). See that method's own
            // doc comment for the real fix: hooking the actual click event instead of polling state.
            resetSolveState();
            firstPhase = true;
            autoSolveArmed = false;
            autoSolveClicksDoneThisAttempt = 0;
            autoSolveLastTickAtMs = 0L;
            autoSolveBlockedMsThisAttempt = 0L;
            deviceStartedAtMs = 0L;
            totalClicksThisAttempt = 0;
            maybeAutoAnnounceReset(client, cfg);
        }
    }

    /** Ported from Odin's real {@code BlockUpdateEvent} handler, adapted to tick-polling: a lantern
     *  going from lit (sea lantern) to dark (obsidian) records that position as the next step in the
     *  real sequence order; a button (x=110) transitioning to powered advances progress. The
     *  first-activation reveal quirk (size 2 -&gt; reverse, size 3 -&gt; reverse again + drop the
     *  middle one) is Hypixel's own real behavior, confirmed by Odin against a live run - not a guess. */
    private static void detectGridChanges(Minecraft client, SimonSaysConfig cfg) {
        for (BlockPos pos : GRID_LANTERNS) {
            BlockState now = client.level.getBlockState(pos);
            BlockState old = lastGridStates.put(pos, now);
            if (old == null) {
                continue;
            }
            if (now.is(Blocks.OBSIDIAN) && old.is(Blocks.SEA_LANTERN) && !clickInOrder.contains(pos)) {
                clickInOrder.add(pos.immutable());
                lastLanternChangeTick = 0;
                if (clickInOrder.size() == 1) {
                    solveStartedAtMs = System.currentTimeMillis();
                }
                if (firstPhase) {
                    if (clickInOrder.size() == 2) {
                        java.util.Collections.reverse(clickInOrder);
                    } else if (clickInOrder.size() == 3) {
                        clickInOrder.remove(clickInOrder.size() - 2);
                    }
                }
                if (cfg.isDiagnosticLoggingEnabled()) {
                    LOGGER.info("[SimonSays] Recorded step {} at {}", clickInOrder.size(), pos);
                }
            }
        }

        int airCount = 0;
        int stoneButtonCount = 0;
        for (BlockPos pos : GRID_BUTTONS) {
            BlockState now = client.level.getBlockState(pos);
            BlockState old = lastGridStates.put(pos, now);
            if (now.isAir()) {
                airCount++;
            }
            if (now.is(Blocks.STONE_BUTTON)) {
                stoneButtonCount++;
            }
            if (old == null) {
                continue;
            }
            boolean nowPowered = now.is(Blocks.STONE_BUTTON) && now.getValue(BlockStateProperties.POWERED);
            boolean oldPowered = old.is(Blocks.STONE_BUTTON) && old.getValue(BlockStateProperties.POWERED);
            if (nowPowered && !oldPowered) {
                onButtonPressed(pos, cfg, client);
            }
        }
        // Real timeout ported from Odin's own TickEvent.Server check (see this class's firstPhase field
        // doc comment) - the reveal-order quirk only ever applies during the initial flash; 10 ticks
        // after the last real lantern change, once the grid has settled back to mostly real buttons,
        // firstPhase clears for the rest of THIS device so later reveals stop getting truncated.
        // Real bug found and fixed (2026-09-14): this increment used to be gated behind "if (firstPhase
        // && ...)", so it only ever ran during round 1 - meaning lastLanternChangeTick never advanced
        // past 0 for rounds 2-5, and isStillRevealing() (used to gate Auto Solve) would have stayed true
        // forever after any later-round reveal. Killer560's own explicit report: "it is forgetting that
        // there is a delay time in between when it shows a pattern... After finishing a set it has to
        // show the new pattern, then it can click" - every round has this same reveal-settle window, not
        // just the first, so the increment now always runs; only the firstPhase-clearing ACTION below
        // stays scoped to round 1 (that quirk-correction is real round-1-only behavior, unrelated to
        // whether the grid has visually settled).
        if (lastLanternChangeTick >= 0) {
            lastLanternChangeTick++;
        }
        if (firstPhase && lastLanternChangeTick > 10 && stoneButtonCount > 8) {
            firstPhase = false;
            if (cfg.isDiagnosticLoggingEnabled()) {
                LOGGER.info("[SimonSays] Reveal flash settled - firstPhase quirk correction now off for this device.");
            }
        }
        boolean gridReset = airCount > 8;
        // THE real bug behind "Simon Says does nothing on p3sim.net" (2026-09-14) - not just noisy
        // logging. This used to call resetSolveState() every single tick for as long as the grid
        // looked reset (16 air blocks), not just on the transition into that state. resetSolveState()
        // clears lastGridStates - so on almost every tick (since normally only a few of 16 slots are
        // lit at once, the other 8+ read as air and trip this), the "old" state needed to compare
        // against next tick got wiped before a real transition could ever be caught. A real p3sim.net
        // log proved it: a real button/lantern change was independently confirmed at the exact same
        // coordinates on the exact same tick this logged "16 air blocks" and cleared the map - so the
        // transition was always one tick too late to compare against. Edge-triggered now, same pattern
        // as the enter/leave device-range logging above - lastGridStates only gets wiped once, on the
        // real transition into a reset state. Also fires "Auto Message" (2026-09-14) - this IS the real
        // "removing all buttons and no new highlights" reset killer560 described.
        if (gridReset && !wasGridReset) {
            if (cfg.isDiagnosticLoggingEnabled()) {
                LOGGER.info("[SimonSays] Grid reset detected ({} air blocks).", airCount);
            }
            resetSolveState();
            maybeAutoAnnounceReset(client, cfg);
        }
        wasGridReset = gridReset;
    }

    /** True while within 10 ticks of the last real lantern reveal for the CURRENT round - i.e. the round's
     *  pattern is still actively flashing, or has only just finished. Generalizes firstPhase's own
     *  settle-timeout (see {@link #lastLanternChangeTick}'s doc comment) to every round, not just round 1
     *  - killer560's own explicit report (2026-09-14): "After finishing a set it has to show the new
     *  pattern, then it can click." Used to gate Auto Solve so it doesn't click mid-reveal on any round. */
    private static boolean isStillRevealing() {
        return lastLanternChangeTick >= 0 && lastLanternChangeTick <= 10;
    }

    private static void onButtonPressed(BlockPos buttonPos, SimonSaysConfig cfg, Minecraft client) {
        BlockPos lanternPos = buttonPos.east();
        int index = clickInOrder.indexOf(lanternPos);
        if (index < 0) {
            return;
        }
        clickNeeded = index + 1;
        totalClicksThisAttempt++;
        if (deviceStartedAtMs == 0L) {
            // The very first real click of the whole device attempt (round 1, click 1) - killer560's own
            // "from the first ss start click" anchor point.
            deviceStartedAtMs = System.currentTimeMillis();
        }
        // Real format ported from Odin's own announceProgress ("pc SS ${clickInOrder.size}/5") - only
        // sent on the LAST click of the current round (real Hypixel Simon Says is always exactly 5
        // rounds, round N has N steps, so clickInOrder.size() at round-completion IS the round number).
        if (cfg.isAnnounceProgress() && client.player != null && clickNeeded >= clickInOrder.size()) {
            client.player.connection.sendCommand("pc SS " + clickInOrder.size() + "/5");
        }
        if (clickNeeded >= clickInOrder.size()) {
            long tookMs = solveStartedAtMs > 0 ? System.currentTimeMillis() - solveStartedAtMs : 0;
            LOGGER.info("[SimonSays] Round completed in {} ms.", tookMs);
            // Real whole-device completion (2026-09-14, killer560's own request) - TOTAL_REAL_CLICKS_
            // PER_DEVICE (15) is the confirmed real total across all 5 rounds (1+2+3+4+5), so hitting it
            // here IS the real "last click of the whole device" regardless of who did the clicking.
            if (totalClicksThisAttempt >= TOTAL_REAL_CLICKS_PER_DEVICE && client.player != null) {
                long deviceTookMs = deviceStartedAtMs > 0 ? System.currentTimeMillis() - deviceStartedAtMs : 0;
                LOGGER.info("[SimonSays] Whole device completed in {} ms ({} ms of that was real reveal/transition delay).",
                        deviceTookMs, autoSolveBlockedMsThisAttempt);
                // Client-side only (sendSystemMessage, same technique this mod's other features already
                // use for a local-only notice) - killer560 asked for a message to himself, not a real
                // party announcement. Breaks out the real reveal/transition delay (2026-09-14, killer560's
                // own request: "figure out how long it takes to actually go through that transition phase
                // because that needs to be factored into the overall time it takes") whenever Auto Solve's
                // Target/Variance mode measured any - only that mode tracks it, so a manual/Trigger Bot/
                // Fixed-Delay solve just gets the plain total.
                if (autoSolveBlockedMsThisAttempt > 0) {
                    client.player.sendSystemMessage(Component.literal(String.format(Locale.US,
                            "§6[Simon Says] §fWhole device solved in §e%.2fs §7(§e%.2fs§7 reveal delay)",
                            deviceTookMs / 1000.0, autoSolveBlockedMsThisAttempt / 1000.0)));
                } else {
                    client.player.sendSystemMessage(Component.literal(String.format(Locale.US,
                            "§6[Simon Says] §fWhole device solved in §e%.2fs", deviceTookMs / 1000.0)));
                }
            }
            resetSolveState();
            firstPhase = false;
        }
    }

    // ------------------------------------------------------------------
    // Auto-start - real trigger + settings ported from NoammAddons' own SimonSays.kt
    // ------------------------------------------------------------------

    private static void tickAutoStart(Minecraft client, SimonSaysConfig cfg) {
        if (!cfg.isAutoStartEnabled() || !autoStartRunning) {
            return;
        }
        if (autoStartClicksSent >= cfg.getAutoStartClicks()) {
            LOGGER.info("[SimonSays] Auto-start finished ({} of {} clicks sent).", autoStartClicksSent, cfg.getAutoStartClicks());
            autoStartRunning = false;
            return;
        }
        if (autoStartTicksUntilNextClick > 0) {
            autoStartTicksUntilNextClick--;
            return;
        }
        sendNoRotateInteract(client, START_BUTTON);
        autoStartClicksSent++;
        autoStartTicksUntilNextClick = cfg.getAutoStartClickDelayTicks();
        if (cfg.isDiagnosticLoggingEnabled()) {
            LOGGER.info("[SimonSays] Auto-start click {}/{} sent.", autoStartClicksSent, cfg.getAutoStartClicks());
        }
    }

    /** Real trigger ported from NoammAddons' own SimonSays.kt: the moment Goldor's real "Who dares
     *  trespass into my domain?" boss line appears (see {@link #AUTO_START_TRIGGER_PATTERN}), begin
     *  auto-clicking the real start button. Clicks/delay are both directly configurable, matching
     *  NoammAddons' own two settings exactly ("Start Clicks" 1-10, "Start Click Delay" 1-25 ticks)
     *  instead of this mod's old guessed six-preset "skip mode" system (removed 2026-09-14 - killer560's
     *  own call, since this session never had confirmed real per-mode click counts). Only actually
     *  clicks once {@link #tickAutoStart} sees the device in range - see this method's own call site. */
    private static void beginAutoStart(SimonSaysConfig cfg) {
        autoStartClicksSent = 0;
        autoStartTicksUntilNextClick = 0;
        autoStartRunning = true;
        LOGGER.info("[SimonSays] Auto-start triggered by real Goldor phase-start line: {} clicks, {} ticks apart.",
                cfg.getAutoStartClicks(), cfg.getAutoStartClickDelayTicks());
    }

    // ------------------------------------------------------------------
    // Auto-solve (no-rotate) + trigger bot
    // ------------------------------------------------------------------

    private static void tickAutoSolveAndTriggerBot(Minecraft client, SimonSaysConfig cfg) {
        long now = System.currentTimeMillis();
        boolean noStepsPending = clickNeeded >= clickInOrder.size();
        // Generalized (2026-09-14) beyond just firstPhase - see isStillRevealing()'s own doc comment:
        // every round has a reveal-settle window, not just round 1.
        boolean blockedByReveal = firstPhase || isStillRevealing();

        // Extend the Target/Variance deadline by any real time spent blocked - waiting for the next
        // round's pattern to finish revealing (or between rounds entirely, before it starts revealing at
        // all) - so uncontrollable real reveal/flash time doesn't eat into the deliberate click-pacing
        // budget. Killer560's own explicit report (2026-09-14): "it is forgetting that there is a delay
        // time in between when it shows a pattern that it has to account for... That needs to be
        // factored into the 12s timer." Fixed-delay mode doesn't use a budget, so it's skipped here.
        if (cfg.isAutoSolveEnabled() && !cfg.isAutoSolveFixedDelayMode() && autoSolveArmed) {
            if (autoSolveLastTickAtMs > 0 && (noStepsPending || blockedByReveal)) {
                long blockedDelta = now - autoSolveLastTickAtMs;
                autoSolveDeadlineMs += blockedDelta;
                autoSolveNextClickAtMs += blockedDelta;
                autoSolveBlockedMsThisAttempt += blockedDelta;
            }
            autoSolveLastTickAtMs = now;
        }

        if (noStepsPending) {
            return;
        }
        BlockPos nextLantern = clickInOrder.get(clickNeeded);
        BlockPos nextButton = nextLantern.west();

        if (cfg.isAutoSolveEnabled()) {
            // Killer560's explicit request (2026-09-14): don't click at all while the lights are still
            // being shown - this now applies to every round, not just round 1's firstPhase window (see
            // isStillRevealing()'s own doc comment) - clicking mid-flash could fire on a position that's
            // about to be reinterpreted (round 1) or simply isn't fully shown yet (any round).
            if (blockedByReveal) {
                return;
            }
            // Flat "ms between clicks" pacing (2026-09-14, killer560's own request after seeing real log
            // data show the Target/Variance model landing at a consistent but slow-feeling ~850ms/click) -
            // a direct, immediately-understandable alternative to the overall-duration target below.
            if (cfg.isAutoSolveFixedDelayMode()) {
                boolean sameTargetFixed = nextButton.equals(lastAutoClickedPos);
                long minDelayFixed = Math.max(cfg.getAutoSolveFixedDelayMs(), sameTargetFixed ? 300 : 0);
                if (now - lastAutoClickAtMs >= minDelayFixed) {
                    long sincePreviousMs = lastAutoClickAtMs > 0 ? now - lastAutoClickAtMs : 0;
                    sendNoRotateInteract(client, nextButton);
                    lastAutoClickAtMs = now;
                    lastAutoClickedPos = nextButton;
                    autoSolveClicksDoneThisAttempt++;
                    LOGGER.info("[SimonSays] Auto-solve click {}/{} sent ({}ms since previous click, fixed {}ms delay).",
                            autoSolveClicksDoneThisAttempt, TOTAL_REAL_CLICKS_PER_DEVICE, sincePreviousMs,
                            cfg.getAutoSolveFixedDelayMs());
                }
                return;
            }
            if (!autoSolveArmed) {
                // Arm the "Target ± Variance overall" pacing window ONCE per full device attempt (not
                // once per round - see this field's own doc comment for the real "extremely slow" bug
                // this fixes), moved here from Auto Start's old model (2026-09-14, see SimonSaysConfig's
                // own doc comment). Re-arms automatically on the next real fresh-attempt trigger - no
                // manual "restart" toggle needed, matching killer560's own "unless it can be done
                // automatically".
                int variance = cfg.getClickTimerVarianceMs();
                long jitter = variance <= 0 ? 0 : (long) ((Math.random() * 2 - 1) * variance);
                autoSolveDeadlineMs = now + cfg.getClickTimerTargetMs() + jitter;
                autoSolveNextClickAtMs = now;
                autoSolveArmed = true;
            }
            int remaining = Math.max(1, TOTAL_REAL_CLICKS_PER_DEVICE - autoSolveClicksDoneThisAttempt);
            boolean sameTarget = nextButton.equals(lastAutoClickedPos);
            long minDelay = sameTarget ? 300 : 0;
            if (now >= autoSolveNextClickAtMs && now - lastAutoClickAtMs >= minDelay) {
                // "No Rotate"/"Rotate" mode (cfg.isAutoSolveRotate()) - see SimonSaysConfig's own doc
                // comment: "Rotate" is a placeholder for a future real-click-learning feature and is not
                // wired to different behavior yet, so both modes click the same way for now.
                long sincePreviousMs = lastAutoClickAtMs > 0 ? now - lastAutoClickAtMs : 0;
                sendNoRotateInteract(client, nextButton);
                lastAutoClickAtMs = now;
                lastAutoClickedPos = nextButton;
                autoSolveClicksDoneThisAttempt++;
                int remainingAfter = Math.max(1, TOTAL_REAL_CLICKS_PER_DEVICE - autoSolveClicksDoneThisAttempt);
                long windowLeftMs = autoSolveDeadlineMs - now;
                autoSolveNextClickAtMs = now + Math.max(50, windowLeftMs / remainingAfter);
                // Always-on (not gated behind Diagnostic Logging) while killer560's "still very delayed"
                // report is unresolved (2026-09-14) - this is the exact data needed to see whether the
                // delay is really coming from this pacing math or from something else entirely (e.g. real
                // per-round reveal wait time, which this can't control).
                LOGGER.info("[SimonSays] Auto-solve click {}/{} sent ({}ms since previous click, next in ~{}ms).",
                        autoSolveClicksDoneThisAttempt, TOTAL_REAL_CLICKS_PER_DEVICE, sincePreviousMs,
                        autoSolveNextClickAtMs - now);
            }
            return;
        }

        if (cfg.isTriggerBotEnabled() && client.hitResult instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(nextButton) && !nextButton.equals(lastTriggerBotTarget)) {
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, blockHit);
            client.player.swing(InteractionHand.MAIN_HAND);
            lastTriggerBotTarget = nextButton;
        } else if (!(client.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(nextButton)) {
            lastTriggerBotTarget = null;
        }
    }

    /** Interacts with a block without needing the player's crosshair on it - the "no rotate" click
     *  killer560 asked for ("just like QUOI"), which sends the interact packet directly via a synthetic
     *  {@link BlockHitResult} instead of first turning the camera to aim. Real automation - callers must
     *  already be behind a {@code BuildVariant.CHEAT_FEATURES_ENABLED} check. */
    private static void sendNoRotateInteract(Minecraft client, BlockPos pos) {
        if (client.player == null || client.gameMode == null) {
            return;
        }
        Vec3 hitVec = Vec3.atCenterOf(pos);
        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.EAST, pos, false);
        // Flagged so onRealBlockInteractAttempt (called from the same useItemOn this goes through) knows
        // to ignore this as one of the mod's own clicks rather than a real one.
        syntheticClickInProgress = true;
        try {
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hitResult);
        } finally {
            syntheticClickInProgress = false;
        }
        client.player.swing(InteractionHand.MAIN_HAND);
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    private static void onWorldRender(LevelRenderContext context) {
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isSolverEnabled() || clickInOrder.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        for (int index = clickNeeded; index < clickInOrder.size(); index++) {
            BlockPos lanternPos = clickInOrder.get(index);
            double x = lanternPos.getX();
            double y = lanternPos.getY();
            double z = lanternPos.getZ();
            AABB box = new AABB(x + 0.05, y + 0.37, z + 0.3, x - 0.15, y + 0.63, z + 0.7);

            int colorArgb = switch (index - clickNeeded) {
                case 0 -> cfg.getFirstColor();
                case 1 -> cfg.getSecondColor();
                default -> cfg.getThirdColor();
            };
            float[] rgba = WorldRenderUtils.argbToFloats(colorArgb);

            switch (cfg.getStyle()) {
                case FILLED -> WorldRenderUtils.renderFilledBox(context, box, rgba[0], rgba[1], rgba[2], rgba[3]);
                case OUTLINE -> WorldRenderUtils.renderOutlineBox(context, box, rgba[0], rgba[1], rgba[2], 1f, 2f);
                case FILLED_OUTLINE -> {
                    WorldRenderUtils.renderFilledBox(context, box, rgba[0], rgba[1], rgba[2], rgba[3] * 0.5f);
                    WorldRenderUtils.renderOutlineBox(context, box, rgba[0], rgba[1], rgba[2], 1f, 2f);
                }
            }

            if (cfg.isNumberOverlay()) {
                renderNumber(context, box.getCenter().x, box.getCenter().y, box.getCenter().z,
                        index - clickNeeded + 1, cfg.getNumberScale());
            }
        }
    }

    /** Billboard text - the same "translate to the world position, rotate to face the camera, draw
     *  through the font" technique vanilla itself uses for entity name tags. */
    private static void renderNumber(LevelRenderContext context, double worldX, double worldY, double worldZ,
                                      int number, float scaleMultiplier) {
        var bufferSource = context.bufferSource();
        if (bufferSource == null) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        Font font = client.font;
        var mainCamera = client.gameRenderer.getMainCamera();
        Vec3 cam = mainCamera.position();
        String text = String.valueOf(number);
        float scale = 0.02f * scaleMultiplier;

        PoseStack poseStack = context.poseStack();
        poseStack.pushPose();
        poseStack.translate(worldX - cam.x, worldY - cam.y, worldZ - cam.z);
        poseStack.mulPose(mainCamera.rotation());
        poseStack.scale(-scale, -scale, scale);

        float width = font.width(text);
        int background = (int) (0.4f * 255f) << 24;
        // Real bug found and fixed (2026-09-14): this used Font.DisplayMode.NORMAL (depth-tested), but
        // the highlight box itself sits right at the button/lantern face boundary (see the AABB above) -
        // almost exactly where the button's own rendered geometry is, so the number was very likely
        // being depth-occluded by the button model itself ("rendered inside the button", killer560's own
        // guess). SEE_THROUGH ignores depth test - confirmed the correct real fix by checking how
        // NoammAddons and Odin render their own equivalent Simon Says numbers: both explicitly pass a
        // "through walls"/"phase" flag to their text renderer for exactly this reason.
        font.drawInBatch(text, -width / 2f, 0f, 0xFFFFFFFF, false, poseStack.last().pose(),
                bufferSource, Font.DisplayMode.SEE_THROUGH, background, 0xF000F0);

        poseStack.popPose();
    }

    // ------------------------------------------------------------------
    // Party progress tracker HUD
    // ------------------------------------------------------------------

    private static final class PartyProgress {
        int progress;
        int total = 5;
        long startedAtMs;
        long lastUpdateMs;
    }

    public static final class PartyProgressHudElement implements HudElement {
        @Override
        public String id() {
            return "simonsays_party_progress";
        }

        @Override
        public String displayName() {
            return "Simon Says Party Progress";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 260;
        }

        @Override
        public int width() {
            return 180;
        }

        @Override
        public int height() {
            return 12 * Math.max(1, visibleEntries().size());
        }

        private List<Map.Entry<String, PartyProgress>> visibleEntries() {
            long now = System.currentTimeMillis();
            List<Map.Entry<String, PartyProgress>> entries = new ArrayList<>();
            for (Map.Entry<String, PartyProgress> e : partyProgress.entrySet()) {
                if (now - e.getValue().lastUpdateMs < 30_000 && e.getValue().progress < e.getValue().total) {
                    entries.add(e);
                }
            }
            return entries;
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            if (!SimonSaysConfig.getInstance().isPartyProgressTrackerEnabled() || Minecraft.getInstance().screen != null) {
                return;
            }
            long now = System.currentTimeMillis();
            int lineY = y;
            for (Map.Entry<String, PartyProgress> e : visibleEntries()) {
                PartyProgress p = e.getValue();
                String eta = "";
                long elapsed = now - p.startedAtMs;
                if (p.progress > 0 && elapsed > 0) {
                    double msPerStep = elapsed / (double) p.progress;
                    long remainingMs = (long) (msPerStep * (p.total - p.progress));
                    eta = String.format(Locale.US, " (~%.1fs)", remainingMs / 1000.0);
                }
                graphics.text(Minecraft.getInstance().font,
                        String.format(Locale.US, "%s: %d/%d%s", e.getKey(), p.progress, p.total, eta),
                        x, lineY, 0xFFFFFFFF, false);
                lineY += 12;
            }
        }
    }
}

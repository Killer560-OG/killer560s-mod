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
import net.minecraft.util.Mth;
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
    // Real bug found and fixed (2026-09-14, killer560's own request: "after finishing the skip portion
    // it should more or less look toward the middle of the screen to see where all the buttons are
    // coming out cause that is what a normal human does"): the geometric center of the 4x4 button grid
    // (y 120-123, z 92-95, each button's own real face center sitting roughly a half-block in) - used as
    // idle's look target for the gap between Auto Start's burst finishing and the first light actually
    // revealing, when there's no real button yet to look at and no reason left to still be staring at the
    // start button.
    private static final Vec3 GRID_CENTER_LOOK = new Vec3(109.95, 122.0, 94.0);

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
    // (size 2 -> reverse, size 3 -> drop the first - see detectGridChanges for the real size-3 rule,
    // corrected same day from an earlier "drop the middle" guess) only ever cleared firstPhase after a
    // FULL successful click-through - Odin's own real code instead clears it on a real TIMEOUT once the
    // reveal flash settles (see below), independent of whether you've clicked anything yet. Without
    // that timeout, every subsequent lantern reveal past the 3rd kept re-triggering the same size-3
    // correction forever (add a 4th -> size 3 again -> correct again -> stays at 2), which is exactly
    // the real symptom killer560 reported: "only keeping 2 highlighted" and highlights "moving off
    // early." lastLanternChangeTick counts ticks since the last real lantern step was recorded; once 10
    // ticks pass with the grid back to mostly real buttons (not still mid-flash), firstPhase clears for
    // the rest of this device's reveal, matching Odin's own real logic.
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
    // Diagnostic-only (2026-09-14, "for ss it is now starting weird again... add loggers") - lets the
    // interact-range gate log only on real state transitions, not every tick it's blocking.
    private static boolean lastAutoStartTooFarLogged = false;

    // --- auto-solve pacing (Target ± Variance overall for the WHOLE device attempt - moved here from
    // Auto Start's old model 2026-09-14, see SimonSaysConfig's own doc comment) ---
    private static final int TOTAL_REAL_CLICKS_PER_DEVICE = 1 + 2 + 3 + 4 + 5; // 15 - confirmed real:
    // exactly 5 rounds, round N has N steps (see this class's own doc comment).
    // Measured directly from real full-device runs (2026-09-14): the real reveal/transition overhead at
    // each of the 4 round boundaries (round1->2, 2->3, 3->4, 4->5) consistently grows with the size of the
    // round being revealed - roughly 1.4s, 1.9s, 2.2s, 2.6s (~8.1s total for a full 5-round solve). Real
    // bug found and fixed TWICE the same day: subtracting the 8.1s LUMP SUM from the target once at arm
    // time (first fix) double-counted it, since real reveal time ALSO keeps eating the (now-shrunk) fixed
    // deadline as it actually happens - killer560's own real data caught this immediately (target 12s,
    // actual 9.05s, nowhere close to 12). The real fix has to be dynamic: at every click, only RESERVE the
    // overhead for TRANSITIONS STILL AHEAD (indexed by the current round number) from whatever real time
    // is left before the deadline, never touching the deadline itself. That way already-elapsed reveal
    // time (which already shows up naturally in "time left before deadline") is never subtracted twice.
    private static final long[] TRANSITION_OVERHEAD_MS = {1400L, 1900L, 2200L, 2600L};
    private static int currentRoundNumber = 1;
    // The real total click count for the REST of this attempt - normally 15 (1+2+3+4+5), but a real "SS
    // skip" can start the attempt after round 1 (killer560's own report: "it starts on 2/5 and never
    // 1/5"), making the real total for that attempt smaller (e.g. 2+3+4+5=14 for a 1-round skip). Set
    // once per attempt, the first time the real starting round is detected (see
    // tickAutoSolveAndTriggerBot's own currentRoundNumber detection) - defaults to the normal full total
    // until then.
    private static int expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;

    /** Sum of {@link #TRANSITION_OVERHEAD_MS} for every round transition still ahead of the CURRENT
     *  round - e.g. round 1 (nothing completed yet) has all 4 ahead (~8.1s); round 4 (working on the
     *  4th round) has only the round4->5 transition ahead (~2.6s); round 5 has none left (0). */
    private static long estimatedRemainingRevealMs() {
        long total = 0L;
        for (int i = currentRoundNumber - 1; i < TRANSITION_OVERHEAD_MS.length; i++) {
            if (i >= 0) {
                total += TRANSITION_OVERHEAD_MS[i];
            }
        }
        return total;
    }

    /** Real total clicks from {@code startRound} through round 5 inclusive (round N always has exactly N
     *  steps) - e.g. {@code totalClicksFrom(2)} = 2+3+4+5 = 14, the real total for an attempt that skipped
     *  round 1 entirely. */
    private static int totalClicksFrom(int startRound) {
        int total = 0;
        for (int r = startRound; r <= 5; r++) {
            total += r;
        }
        return total;
    }
    private static long lastAutoClickAtMs = 0L;
    private static BlockPos lastAutoClickedPos = null;
    private static long autoSolveDeadlineMs = 0L;
    private static long autoSolveNextClickAtMs = 0L;
    private static boolean autoSolveArmed = false;
    private static int autoSolveClicksDoneThisAttempt = 0;
    // --- Skip-vs-normal-flash distinguisher (2026-09-14, killer560's own real-attempt report) - true
    // once either Auto Start OR a real player has actually sent 2+ real clicks on the start button THIS
    // phase (see tickAutoStart and onRealBlockInteractAttempt), reset at firstPhase's own two real
    // trigger points (fresh device encounter, real start-button press). Needed because "3 real lights
    // before any click" means two different real things depending on whether a skip was actually
    // attempted: a landed skip (drop the first, real remaining sequence is the last two) if a real skip
    // attempt happened, or Hypixel's own normal round-1 reveal-flash quirk (drop the middle instead,
    // Odin's original confirmed behavior) if it didn't. Real bug found and fixed (2026-09-14, "make sure
    // that the solver works if it resets even for the skip detection"): this used to only ever get set
    // by Auto Start's OWN synthetic clicks - Auto Start only ever fires once per real Goldor line, so a
    // real MANUAL retry after a mid-attempt reset (a real player rapid-clicking start themselves,
    // without Auto Start's help) would never set it, meaning a genuinely-landed skip on a retry attempt
    // would still get the wrong (normal-flash) correction. Now also set by 2+ real (non-synthetic) start-
    // button presses this phase, tracked independently of who's doing the clicking.
    // Deliberately its own dedicated flag rather than reusing any click-COUNT field (clickNeeded/
    // totalClicksThisAttempt) - killer560's own explicit caution ("make sure it doesn't count the button
    // clicks to actually start it as well"): those two already only ever count real GRID button clicks
    // (see onButtonPressed, which is only ever called for GRID_BUTTONS - the START button's own presses
    // run through the completely separate tickStartButton/onRealBlockInteractAttempt paths and never
    // touch either counter), so this flag can't be confused with them.
    private static boolean autoStartClickedThisPhase = false;
    private static int realStartButtonPressCountThisPhase = 0;

    // --- Rotate Mode (killer560's own request, 2026-09-14) - real behavior for the "Rotate" toggle that
    // previously did nothing (see SimonSaysConfig#isAutoSolveRotate's own doc comment: originally planned
    // as record-and-replay of killer560's own manual solves, not built yet). This is a simpler real
    // version: instead of a no-rotate synthetic click, actually turns the camera toward the target
    // button's real center over several ticks (same real "bounded delta, never wrap/clamp the running
    // yaw/pitch" technique already proven in BloodCampFeature's own aura mode) and only fires once the
    // real crosshair raycast confirms genuine aim - works off the button's true, unmodified hitbox (no
    // Full Block dependency), matching killer560's own "except not using fullblock" requirement.
    // Revised same day per killer560's own live-test feedback ("really slow and kind of choppy... far
    // away buttons... overshoot just a hair too far... some slightly curved movement... dont make it
    // freeze"): base turn speed raised, the old one-shot overshoot (which snapped back to the true target
    // the instant the next tick ran) replaced with a smoothly-decaying offset, an optional decaying
    // perpendicular-axis "curve" added for occasional arced paths, and an idle sway added for whenever
    // it's genuinely just looking at something and not mid-approach.
    // Revised AGAIN same day, second round of live-test feedback ("still really choppy... update far
    // more often"): the real cause was updating rotation only once per real game TICK (20Hz) - real
    // mouse look updates every RENDER FRAME, often 60-240Hz, so a 20Hz update looks stepped by
    // comparison no matter how the easing math itself is tuned. The actual rotation-applying logic
    // (tickRotateFrame and friends) now runs off LevelRenderEvents (every frame) instead of the tick
    // loop; the tick-based callers (tickAutoSolveAndTriggerBot/tickAutoStart) still decide WHEN a click
    // is due and WHAT the target is (unchanged), they just poll rotateClickFiredFor once per tick to
    // learn whether the frame-driven approach has actually landed the click yet. Also per that same
    // report ("it can be a hair off of pressing buttons... make it go a bit more central"): a click now
    // only fires once the remaining yaw/pitch delta is small (genuinely settled near center), not just
    // the instant the raycast first crosses onto the right block's face somewhere. ---
    private static BlockPos rotateInProgressTarget = null;
    private static float rotateSmoothingThisApproach = 0.30f;
    private static float rotateOvershootYawRemaining = 0f;
    private static float rotateOvershootPitchRemaining = 0f;
    private static boolean rotateCurveOnThisApproach = false;
    private static float rotateCurveSign = 1f;
    private static float rotateApproachElapsedTicks = 0f;
    private static long rotateLastFrameAtNanos = 0L;
    // Set by the frame-driven approach the instant a click actually fires; polled and cleared by the
    // tick-based caller that owns that target, so click-bookkeeping/pacing still only ever runs once
    // per real click, from the same tick-based code as every other click mode.
    private static BlockPos rotateClickFiredFor = null;
    // Real bug found and fixed (2026-09-14, killer560's own report: "the tick delay was off... its still
    // set to 2 but i can tell its not going off every 2 ticks"): unlike rotateInProgressTarget (nulled
    // the instant a click fires), this persists across a fire - lets tickRotateClick tell "repeat-clicking
    // the SAME button we just clicked" (Auto Start's own rapid burst on the start button, which never
    // moves) apart from "a genuinely new target". See tickRotateClick's own doc comment for why that
    // distinction matters for real click timing.
    private static BlockPos rotateLastFiredTarget = null;
    // Whenever there's no real click actively due (killer560's own request), the camera should be
    // looking at the first real grid button ("the 1/5 button... or the first one from 2/5" - not the
    // literal start/reset button) with a slight idle sway, not frozen. Only while actually near the
    // device (within 3 blocks of x=108,y=120,z=94 - killer560's own real coordinates, tighter than the
    // general 30-block ACTIVE_RANGE_SQ detection radius) and only after the real Goldor phase-start line
    // has been seen this phase.
    // Real bug found and fixed (2026-09-14, "it is back to not looking at the first button at all"):
    // this used to be a one-shot "arm it, a real approach starting anywhere consumes it" flag
    // (rotateIdleAtStart) - but Auto Start's own very first click fires with ZERO initial delay, so its
    // approach toward the start button began in the exact same tick as the "just entered range" trigger
    // that armed idle, before idle ever got even one visible frame. INVERTED to the opposite default
    // (2026-09-14): idle now runs any time nothing is actively being approached (checked in
    // tickRotateFrame's own dispatch, not tracked here) UNLESS explicitly suppressed - the only real
    // reason to suppress it is right after a whole-device completion (killer560's own "after it finishes
    // dont have it go back to the start button"), until the next genuine phase-start clears it again.
    // Real bug found and fixed AGAIN (2026-09-14, "I tried start only without the solver on and it is
    // doing some sort of weird movement while hovering that button... make sure the movement for auto
    // start only happens from auto solve and that it always stays on the start button during that
    // time"): idle resuming in the gaps between Auto Start's OWN scheduled clicks (the thing the comment
    // above used to call out as a real benefit) was actually wrong - a real player who's already aiming
    // at a button and about to click it again in a fraction of a second wouldn't randomly sway their aim
    // in between, and that drift also meant each next click's approach had to re-converge from a
    // slightly-off starting point instead of staying locked, throwing off the real configured pacing
    // ("3 ticks with 3 presses isnt working"). Idle is now ALSO suppressed for the entire real duration
    // of an Auto Start run (autoStartRunning), not just after a completion - the camera just holds
    // perfectly still wherever the last click left it (already centered on the start button) until Auto
    // Start either finishes or Auto Solve needs the camera for a real grid button next.
    private static boolean idleSuppressedAfterCompletion = false;
    private static boolean goldorLineSeenThisPhase = false;
    private static final Vec3 IDLE_LOOK_ANCHOR = new Vec3(108.0, 120.0, 94.0);
    private static final double IDLE_LOOK_RANGE_SQ = 3.0 * 3.0;
    // Real bug found and fixed (2026-09-14, "it likes to hold this tiny drift up and to the left alot.
    // Instead of that it should kind of have these twitches. Think microscopic movements then going back
    // to middle of the button"): a continuous sine-wave sway spends roughly half of every ~9-12 second
    // cycle sitting near one extreme before slowly crossing back, which reads as "always drifting that
    // way" even though it's technically centered over a full period. Replaced with discrete micro-twitches
    // instead - most of the time this sits at dead center, and every so often (see applyIdleSwayFrame)
    // kicks a small random offset that decays fast back to zero, closer to how a resting human hand
    // actually moves than a slow, sustained wander.
    private static float idleTwitchYaw = 0f;
    private static float idleTwitchPitch = 0f;
    private static long idleNextTwitchAtMs = 0L;
    // Real bug found and fixed (2026-09-14, "For the going back to the first button to help remember
    // that once it detects the proper first button that one will be the same unless ss is reset for
    // that. For all stages"): clickInOrder gets cleared on every real per-ROUND transition (not just a
    // full device reset - see resetSolveState's own doc comment on this), so idle's old fallback
    // (clickInOrder.isEmpty() ? START_BUTTON : ...) briefly reverted to the start button at literally
    // EVERY round boundary while waiting for the next round's own first light to reveal, causing the
    // exact same kind of mid-flight retarget/bent-path symptom Round 109 already fixed for the
    // whole-device-completion case specifically - just recurring at every ordinary round transition too.
    // Remembers whichever real button was most recently confirmed as "the current round's first one" and
    // keeps using THAT as the fallback instead of reverting to the start button, for every stage - only
    // cleared back to null at a genuine full SS reset (the same two real trigger points firstPhase itself
    // resets at), never on an ordinary round-to-round transition.
    private static BlockPos rememberedFirstButton = null;
    // Real bug found and fixed (2026-09-14, "the clicks are no longer separated by the right amount of
    // time... does the first one then waits a second before doing more") - see tickAutoStart's own doc
    // comment for the full mechanism. Deliberately larger than IDLE_LOOK_RANGE_SQ (measured from a
    // different, nearby real anchor point) so idle-look's own tighter gate opens no later than this one
    // during a normal walk-up, giving it a real window to pre-aim the camera before this gate does.
    private static final double REAL_INTERACT_RANGE_SQ = 8.0 * 8.0;
    // Wall-clock time of the last tick the reveal-delay accounting below ran - lets it compute exactly
    // how much real time passed since the last check. Renamed from autoSolveLastTickAtMs (2026-09-14) -
    // this tracking is unconditional now (see tickAutoSolveAndTriggerBot's own doc comment), not specific
    // to Auto Solve. Reset alongside the other per-attempt fields so a stale value never leaks in.
    private static long lastBlockedTrackAtMs = 0L;
    // Running total of real time spent blocked (reveal-settle windows + waiting between rounds) across
    // the WHOLE attempt (2026-09-14, killer560's own request: "figure out how long it takes to actually
    // go through that transition phase because that needs to be factored into the overall time it
    // takes"). A single observed real run measured this at ~1.4s/1.9s/2.2s/2.6s per round transition
    // (growing with round size, ~8.1s total across all 4 transitions in a full 5-round solve) - reported
    // per-attempt here instead of hardcoding that one-run number, since it will vary run to run.
    private static long autoSolveBlockedMsThisAttempt = 0L;
    // For the per-transition log (2026-09-14) - wasBlockedByReveal is the previous tick's blocked state
    // (to edge-detect the exact moment a round becomes clickable), lastRoundCompletedAtMs is when the
    // PREVIOUS round's last click landed (see onButtonPressed). Both unconditional, same as above.
    private static boolean wasBlockedByReveal = false;
    private static long lastRoundCompletedAtMs = 0L;

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
        // Rotate Mode's own real rotation-applying step - deliberately every FRAME, not every tick, so
        // it's as smooth as real mouse look (see this class's own "Rotate Mode" field-group doc comment
        // for the full real reasoning).
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context -> tickRotateFrame());
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
        // A single accidental press doesn't mean a skip was being attempted - a real skip needs multiple
        // rapid presses in a row, whether that's Auto Start's own clicking or a real player manually
        // rapid-clicking after a mid-attempt reset (see this flag's own field doc comment above).
        realStartButtonPressCountThisPhase++;
        if (realStartButtonPressCountThisPhase >= 2) {
            autoStartClickedThisPhase = true;
        }
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
        // goldorLineSeenThisPhase is tracked independently of isAutoStartEnabled() - killer560's own
        // gate for the idle-look-at-first-button behavior ("only after the line...") applies to Rotate
        // Mode generally, not just when Auto Start specifically is turned on.
        if (DungeonState.isF7OrM7()) {
            String plain = ChatFormatting.stripFormatting(raw);
            if (plain != null && AUTO_START_TRIGGER_PATTERN.matcher(plain).find()) {
                goldorLineSeenThisPhase = true;
                if (cfg.isAutoStartEnabled()) {
                    beginAutoStart(cfg);
                }
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
                goldorLineSeenThisPhase = false;
                idleSuppressedAfterCompletion = false;
                rotateInProgressTarget = null;
                rotateLastFiredTarget = null;
                autoStartClickedThisPhase = false;
                realStartButtonPressCountThisPhase = 0;
                rememberedFirstButton = null;
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
            lastBlockedTrackAtMs = 0L;
            autoSolveBlockedMsThisAttempt = 0L;
            wasBlockedByReveal = false;
            lastRoundCompletedAtMs = 0L;
            currentRoundNumber = 1;
            expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;
            deviceStartedAtMs = 0L;
            totalClicksThisAttempt = 0;
            rotateInProgressTarget = null;
            rotateLastFiredTarget = null;
            autoStartClickedThisPhase = false;
            realStartButtonPressCountThisPhase = 0;
            idleSuppressedAfterCompletion = false;
            rememberedFirstButton = null;
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
            // Real bug found and fixed (2026-09-14): while Auto Start is actively clicking, its OWN
            // first click powers this exact button - which used to trip this same "real press" branch
            // below and call resetSolveState(), which unconditionally sets autoStartRunning=false. That
            // silently killed the rest of Auto Start's own click sequence after just ONE click, every
            // single time - confirmed directly from a real log: every "Auto-start triggered... N clicks"
            // line was followed by exactly one "click 1/N sent" line and never a 2nd or 3rd, no matter
            // what N or the tick delay was set to. This is (almost certainly) never a genuine "the device
            // is restarting from scratch" press while our own sequence is actively mid-click, so skip the
            // reset entirely in that case and let Auto Start finish what it started.
            if (autoStartRunning) {
                return;
            }
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
            lastBlockedTrackAtMs = 0L;
            autoSolveBlockedMsThisAttempt = 0L;
            wasBlockedByReveal = false;
            lastRoundCompletedAtMs = 0L;
            currentRoundNumber = 1;
            expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;
            deviceStartedAtMs = 0L;
            totalClicksThisAttempt = 0;
            rotateInProgressTarget = null;
            rotateLastFiredTarget = null;
            // Real bug found and fixed (2026-09-14, "make sure that the solver works if it resets even
            // for the skip detection"): deliberately does NOT reset autoStartClickedThisPhase/
            // realStartButtonPressCountThisPhase here, unlike the OTHER two real reset points (fresh
            // device encounter, left device range). This specific reset fires off a real BLOCK-STATE
            // edge, which (per this method's own doc comment above) can only ever catch the FIRST click
            // of a rapid burst - the exact real click that's ABOUT to be, or was just, a genuine skip
            // attempt. Clearing the flag/counter here would wipe out that very click's own contribution
            // to the skip-detection count, before the burst's remaining clicks even land. Letting it
            // carry over means a real manual retry's own rapid clicks keep counting toward "was a skip
            // genuinely attempted", uninterrupted by this same reset.
            idleSuppressedAfterCompletion = false;
            rememberedFirstButton = null;
            maybeAutoAnnounceReset(client, cfg);
        }
    }

    /** Ported from Odin's real {@code BlockUpdateEvent} handler, adapted to tick-polling: a lantern
     *  going from lit (sea lantern) to dark (obsidian) records that position as the next step in the
     *  real sequence order; a button (x=110) transitioning to powered advances progress. The
     *  first-activation reveal quirk (size 2 -&gt; reverse) is Hypixel's own real behavior, confirmed by
     *  Odin against a live run. The size-3 case was corrected 2026-09-14 from an earlier "drop the
     *  middle" guess to "drop the first" instead, per killer560's own direct real-attempt observation:
     *  seeing 3 real lights light up before he'd clicked anything means a real Auto Start skip landed
     *  successfully, and the real remaining sequence to click is only the LAST two of those three - the
     *  first is stale history from the round the skip already satisfied, not something to click again. */
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
                        // Real bug found and fixed (2026-09-14, killer560's own report: "it kept the first
                        // one green removed the second and made the third orange" on a landed skip):
                        // reversing here unconditionally corrupted the list order BEFORE a skip's own 3rd
                        // light even arrived. The size==3 branch below assumes clickInOrder is still in
                        // true chronological arrival order when it does remove(0) to drop "the first" -
                        // but after this reverse ran on [A, B], the list was actually [B, A], so the later
                        // remove(0) dropped B (the real 2nd light) instead of A (the real 1st), leaving
                        // [A, C] instead of the intended [B, C]. The reverse is real Hypixel behavior only
                        // for the ordinary NON-skip case (Odin's own confirmed reveal-order quirk) - a
                        // skip attempt needs the list left in real arrival order so the size==3 drop below
                        // can correctly remove the true first element.
                        if (autoStartClickedThisPhase) {
                            // Real bug found and fixed (2026-09-14, killer560's own request: "whatever it
                            // senses as the second button is the one it should go towards to hold until it
                            // is able to click"): the just-added SECOND light is the one that survives a
                            // correct "drop the first" once a real 3rd light confirms it - track it now,
                            // tentatively, rather than the first light (which might get dropped) or waiting
                            // in silence until the drop actually happens.
                            updateRememberedFirstButton(clickInOrder.get(clickInOrder.size() - 1));
                        } else {
                            java.util.Collections.reverse(clickInOrder);
                            // Post-reverse, index 0 IS the real first button for this ordinary round - same
                            // "track the second light shown" idea, since reversing [X, Y] makes Y (the
                            // second real light) the new first.
                            updateRememberedFirstButton(clickInOrder.get(0));
                        }
                    } else if (clickInOrder.size() == 3) {
                        // Real bug found and fixed (2026-09-14, "now it's bugged every time instead of
                        // just sometimes"): dropping the first unconditionally broke the ordinary,
                        // non-skip case - Odin's own confirmed real behavior for THAT case is drop the
                        // MIDDLE. The two cases look identical (firstPhase true, size reaches 3, before
                        // any click) unless something else distinguishes them: whether Auto Start
                        // actually sent a real click this phase. Only when it did is a landed skip the
                        // real explanation, and only then should the first (not the middle) be dropped.
                        if (autoStartClickedThisPhase) {
                            clickInOrder.remove(0);
                        } else {
                            clickInOrder.remove(clickInOrder.size() - 2);
                        }
                        // Real bug found and fixed (2026-09-14, killer560's own request: "if it ends up not
                        // being the first then flick to the first like normal but 99% of the time that will
                        // be the right button"): the tentative 2nd-light guess above is usually already
                        // correct, but this is the definitive, always-correct answer once the real 3rd
                        // light has resolved which one actually survives - updates again in case they
                        // differ (a real, expected, occasional correction, not a bug).
                        updateRememberedFirstButton(clickInOrder.get(0));
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
            // Real bug found and fixed (2026-09-14, killer560's own request: "whatever it senses as the
            // second button is the one it should go towards to hold until it is able to click"): the
            // size==2/size==3 branches above only ever update rememberedFirstButton once a REAL second
            // light arrives - but a real round 1 with only a single total step (the single most common
            // non-skip case) never reaches size 2 at all, so it would otherwise never get tracked. Once
            // the reveal has genuinely settled with nothing more coming, if nothing's been tracked yet
            // this is exactly that case - the lone light already recorded IS the real (and only) first
            // button, safe to show now.
            if (rememberedFirstButton == null && !clickInOrder.isEmpty()) {
                updateRememberedFirstButton(clickInOrder.get(0));
            }
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
        // real transition into a reset state.
        // Real bug found and fixed (2026-09-14): this used to also fire "Auto Message" here, on the
        // theory that this WAS the real "removing all buttons and no new highlights" reset killer560
        // described - but a real device clears its whole grid to blank between EVERY round, not just on
        // a genuine failure, so Auto Message was announcing "Resetting Simon Says" after every normal
        // round advance (killer560's own report: it fired right after the "4/5", "3/5" progress
        // messages). The real per-button-press reset in tickStartButton is the correct, much rarer
        // signal for a genuine reset/restart - this transition is expected and silent now.
        if (gridReset && !wasGridReset) {
            if (cfg.isDiagnosticLoggingEnabled()) {
                LOGGER.info("[SimonSays] Grid reset detected ({} air blocks).", airCount);
            }
            resetSolveState();
            // Real bug found and fixed (2026-09-14, killer560's own report: "it kept all 3 after i reset
            // it"): the whole-device-completion trigger point (onButtonPressed) only re-arms firstPhase
            // when a device attempt cleanly finishes all 5 rounds - it never fires for an attempt that
            // gets abandoned or retried after a failed/confusing skip before any click of it ever lands
            // (the real log showed exactly that: a 2nd device attempt's reveal got corrupted by the
            // reverse-vs-drop bug above, never produced a "Round completed", and was followed straight by
            // another Grid reset starting a 3rd attempt with firstPhase still stuck false from the 2nd's
            // own 10-tick reveal-settle timeout). totalClicksThisAttempt is only ever incremented by a
            // REAL landed click of the current attempt (see onButtonPressed) and is otherwise untouched by
            // the routine per-round reset above - so it staying at 0 here means no click of THIS attempt
            // has ever landed, i.e. this grid-reset is a genuine fresh-attempt boundary (first-ever
            // encounter, after a clean completion, or after an abandoned retry) rather than an ordinary
            // between-round transition within an attempt already in progress (where it's already >0).
            if (totalClicksThisAttempt == 0) {
                firstPhase = true;
                // Real bug found and fixed (2026-09-14, killer560's own report: "dont make it go back to
                // middle after doing 5/5. If it ever gets 5/5 it can stop all things"): idleSuppressed-
                // AfterCompletion used to also clear right here, at this same grid-reset boundary - but
                // that transition fires almost immediately after "Whole device completed" (well before any
                // new light actually reveals), so un-suppressing this early meant idle immediately started
                // moving again (toward the grid center, or a stale remembered button) with nothing real to
                // look at yet. Un-suppressing now happens inside updateRememberedFirstButton instead - the
                // single point where a real button actually becomes known for the new attempt - so nothing
                // moves at all between a whole-device completion and the next attempt's own first real
                // light, matching "stop all things" literally. rememberedFirstButton is still cleared here
                // so nothing stale carries over into that eventual first real update.
                rememberedFirstButton = null;
            }
        }
        wasGridReset = gridReset;
    }

    /** Updates idle's own remembered "first button" target and, the first time it actually gets a real
     *  value for a new attempt, lifts {@link #idleSuppressedAfterCompletion} - see that field's own doc
     *  comment and this round's fix for why suppression now lifts HERE specifically (the single point a
     *  real button becomes known) rather than at the earlier, still-nothing-to-look-at grid-reset point. */
    private static void updateRememberedFirstButton(BlockPos lanternPos) {
        BlockPos button = lanternPos.west();
        if (!button.equals(rememberedFirstButton)) {
            LOGGER.info("[SimonSays][RotateFrame] rememberedFirstButton updated: {} -> {}", rememberedFirstButton, button);
            rememberedFirstButton = button;
        }
        idleSuppressedAfterCompletion = false;
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
            // Anchor for the per-transition log in tickAutoSolveAndTriggerBot - marks the exact moment
            // this round's last click landed, so the NEXT round becoming clickable can report the real
            // gap between them.
            lastRoundCompletedAtMs = System.currentTimeMillis();
            // Real whole-device completion. Real bug found and fixed (2026-09-14): this used to check
            // totalClicksThisAttempt >= TOTAL_REAL_CLICKS_PER_DEVICE (15, i.e. 1+2+3+4+5) - correct for a
            // normal solve starting at round 1, but a real "SS skip" starts the attempt AFTER round 1
            // (killer560's own report: "it starts on 2/5 and never 1/5"), so the real total for that
            // attempt is only 2+3+4+5=14, which the hardcoded 15 check would never reach - the whole-
            // device message simply never fired after a skip. clickInOrder.size() at THIS exact
            // completion point already IS the real round number (round N always has exactly N steps,
            // same fact the "SS N/5" announce message above already relies on) - checking for round 5
            // specifically is skip-proof, since round 5 is always the last regardless of which round the
            // attempt started on.
            boolean wholeDeviceCompleted = clickInOrder.size() >= 5;
            if (wholeDeviceCompleted && client.player != null) {
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
                // Real bug found and fixed (2026-09-14, real boot-test log evidence: idleSuppressedAfter-
                // Completion flipped true right after round 1's own "Round completed in 900 ms." - at that
                // exact moment clickInOrder.size() was only 1, not >=5 - and then never reverted for the
                // rest of the whole 5-round attempt, so idle-look never engaged again after round 1. This
                // flag must only suppress idle after the WHOLE device (round 5) finishes, matching the
                // original intent below ("after it finishes dont have it go back to the start button" -
                // "it finishes" meant the whole device, not each individual round) - moved inside this
                // round-5-only block instead of running unconditionally on every round completion.
                idleSuppressedAfterCompletion = true;
            }
            resetSolveState();
            firstPhase = false;
            if (wholeDeviceCompleted) {
                // Real bug found and fixed (2026-09-14, killer560's own report: "the solver is messing
                // up. It is keeping all 3 lighted buttons as options when it should discard the first.
                // Make sure it resets the discard first one every time ss is reset"): a real boot-test log
                // proved firstPhase (which gates the size==3 "drop the first" skip-detection correction in
                // detectGridChanges) was only ever getting re-armed at its two documented trigger points
                // (fresh device encounter, real start-button press mid-attempt) - NEITHER of which fires
                // when one device attempt finishes and Hypixel moves straight into a brand new one while
                // the player never left range and never manually pressed start. The log showed exactly
                // that: firstPhase went false after device 1's round 1 and simply never came back, so
                // devices 2 and 3 both kept all 3 lanterns from a landed skip's reveal instead of dropping
                // the first. Whole-device completion is functionally identical to those other two trigger
                // points - a guaranteed brand new attempt is about to begin - so it now resets the same
                // per-attempt bookkeeping fresh device encounter does (see tick()'s own "entered device
                // range" branch), not just firstPhase alone, so nothing about the next attempt starts
                // stale. Runs AFTER the unconditional firstPhase=false right above (which would otherwise
                // immediately undo this same-event firstPhase=true). Deliberately excludes
                // idleSuppressedAfterCompletion and rememberedFirstButton - those two are idle-look's own
                // state, not solve state, and must survive until the next attempt's actual reveal begins
                // (see their own doc comments).
                firstPhase = true;
                autoSolveArmed = false;
                autoSolveClicksDoneThisAttempt = 0;
                lastBlockedTrackAtMs = 0L;
                autoSolveBlockedMsThisAttempt = 0L;
                wasBlockedByReveal = false;
                lastRoundCompletedAtMs = 0L;
                currentRoundNumber = 1;
                expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;
                deviceStartedAtMs = 0L;
                totalClicksThisAttempt = 0;
                autoStartClickedThisPhase = false;
                realStartButtonPressCountThisPhase = 0;
            }
            // Real bug found and fixed (2026-09-14, "it goes down a bit or up a bit then over, make it
            // much more straight and direct... after it finishes dont have it go back to the start
            // button"): re-arming idle-look here used to retarget the camera toward the start button
            // right as resetSolveState() cleared clickInOrder to empty - but if a fresh round-1 reveal
            // then began WHILE that idle ease was still mid-flight, applyIdleSwayFrame's own target
            // (clickInOrder.isEmpty() ? START_BUTTON : clickInOrder.get(0)) would suddenly switch
            // mid-ease from the start button to the new first lantern, splicing two separate straight
            // eases into one visibly bent path. Killer560's own explicit fix: don't look back at the
            // start button after a real completion at all - idle-look is suppressed right here (only on
            // whole-device completion, see above), and only un-suppressed again at a real phase START
            // (fresh device encounter, real start-button press), never automatically just because a fresh
            // reveal happens to begin.
            rotateInProgressTarget = null;
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
            // Real bug found and fixed (2026-09-14, killer560's own report: "it is still staying on the
            // start button after getting skip instead of looking at the middle of the obsidian... once
            // the 2nd click comes out look at that button"): Round 123 moved idleSuppressedAfterCompletion's
            // clear entirely into updateRememberedFirstButton (the 2nd-light/settle-timeout point) to stop
            // idle jumping to a stale button the INSTANT the previous device finished - but that meant idle
            // now stayed frozen on the start button all the way through the NEXT device's own skip burst
            // too, with nothing to show the grid-center fallback until a real light existed. The real
            // desired sequence is: freeze right after 5/5 (unchanged - still doesn't clear at grid-reset),
            // then wake up specifically once THIS device's own skip burst finishes (here) - showing the
            // grid center per the original request - then track the real 2nd light once it exists (already
            // handled by updateRememberedFirstButton). Harmless no-op if it wasn't suppressed to begin with.
            idleSuppressedAfterCompletion = false;
            return;
        }
        // Real bug found and fixed (2026-09-14, "the clicks are no longer separated by the right amount
        // of time... does the first one then waits a second before doing more"): the schedule countdown
        // used to start ticking down the moment the player entered the general 30-block ACTIVE_RANGE_SQ
        // zone (a loose check meant for passive state tracking, not "close enough to actually interact")
        // - meaning it could reach 0 and start the real Rotate Mode approach while the player was still
        // many blocks away and not yet facing anywhere near the button, forcing a large one-time turn
        // that ate up to a real second before the first click could actually fire. Every click after
        // that landed on schedule because the camera was already sitting right at the button - only the
        // FIRST one paid this cost. Real fix: don't even start the countdown until genuinely close enough
        // to interact (same real ballpark as vanilla's own reach distance) - this also gives idle-look a
        // real window to pre-aim the camera at the first button WHILE still walking up, so by the time
        // this schedule does start, the camera's usually already close and the first click lands on time
        // too.
        if (cfg.isAutoSolveRotate()) {
            double distSqToStart = client.player.distanceToSqr(Vec3.atCenterOf(START_BUTTON));
            boolean tooFar = distSqToStart > REAL_INTERACT_RANGE_SQ;
            if (tooFar != lastAutoStartTooFarLogged) {
                LOGGER.info("[SimonSays][AutoStart] Interact-range gate {} (distSq={}, need<={}).",
                        tooFar ? "BLOCKING (too far)" : "PASSED (close enough)", String.format(Locale.US, "%.1f", distSqToStart),
                        REAL_INTERACT_RANGE_SQ);
                lastAutoStartTooFarLogged = tooFar;
            }
            if (tooFar) {
                return;
            }
        }
        if (autoStartTicksUntilNextClick > 0) {
            autoStartTicksUntilNextClick--;
            return;
        }
        // "Look Only" mode (2026-09-14, killer560's own request, partly to test his own theory about why
        // Auto Start "isn't working") - only actually clicks once the REAL crosshair raycast confirms the
        // player is genuinely looking at the button (waits here, doesn't burn through the click schedule,
        // until that's true). Real bug found and fixed the same day, "bug once-over" pass: this used to
        // click straight through the raw real hitResult, which - with Full Block hitbox expansion on -
        // could land anywhere inside the artificially enlarged hitbox. Killer560's own explicit rule
        // (applies everywhere except Auto Solve's own already-centered synthetic clicking): "make sure it
        // goes to center." The real raycast is still what CONFIRMS real aim; the actual click always lands
        // on the button's true center now, via the same sendNoRotateInteract Aura mode already uses.
        // Rotate Mode (killer560's own request: "whenever the phase starts it should look at the start
        // button if not already doing it") takes priority over both existing Auto Start aim modes when
        // on - the camera actually turns toward the real start button over several ticks instead of
        // either clicking instantly (aura) or waiting on the PLAYER's own real aim (Look Only).
        if (cfg.isAutoSolveRotate()) {
            if (!tickRotateClick(client, START_BUTTON)) {
                return;
            }
        } else if (cfg.isAutoStartLookOnlyMode()) {
            if (!(client.hitResult instanceof BlockHitResult lookHit) || !lookHit.getBlockPos().equals(START_BUTTON)) {
                return;
            }
            sendNoRotateInteract(client, START_BUTTON);
        } else {
            sendNoRotateInteract(client, START_BUTTON);
        }
        autoStartClicksSent++;
        autoStartClickedThisPhase = true;
        autoStartTicksUntilNextClick = cfg.getAutoStartClickDelayTicks();
        // Always-on (not gated behind Diagnostic Logging) while killer560's "isn't working" report is
        // unresolved (2026-09-14) - includes the button's own POWERED state at send-time to directly
        // answer his own question ("is it still clicking the start button while it is already pressed
        // still?"): this code never skips a scheduled click based on that state, so if the log shows 3
        // clicks land exactly on schedule regardless of powered state, the click-sending itself isn't
        // the problem - something server-side is.
        BlockState startButtonState = client.level.getBlockState(START_BUTTON);
        boolean startButtonPowered = startButtonState.is(Blocks.STONE_BUTTON)
                && startButtonState.getValue(BlockStateProperties.POWERED);
        LOGGER.info("[SimonSays] Auto-start click {}/{} sent ({} mode, button currently powered={}).",
                autoStartClicksSent, cfg.getAutoStartClicks(),
                cfg.isAutoSolveRotate() ? "rotate" : cfg.isAutoStartLookOnlyMode() ? "look-only" : "aura",
                startButtonPowered);
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
        // Real bug found and fixed (2026-09-14, "make sure the auto start isnt starting it itself and is
        // instead doing the auto start clicks only... it is clicking once then the auto start fires"):
        // this used to be 0, so the very first click fired the INSTANT the player came in range - with
        // no delay at all, unlike every other click in the sequence. Since "in range" also happens to be
        // the exact same tick firstPhase/idle-look reset, that zero-delay first click looked like a
        // separate, disconnected event from the real evenly-paced sequence that followed it. Now uses
        // the same real configured delay as every other click, so the whole sequence (including the
        // first click) reads as one consistent, evenly-spaced Auto Start run.
        autoStartTicksUntilNextClick = cfg.getAutoStartClickDelayTicks();
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

        // Track real time spent blocked - waiting for the next round's pattern to finish revealing (or
        // between rounds entirely, before it starts revealing at all) - for REPORTING (the "Whole device
        // solved in X.XXs (Y.YYs reveal delay)" message) AND for the per-transition log below. Real bug
        // found and fixed (2026-09-14): this used to only track while Auto Solve's Target/Variance mode
        // was active, so killer560's own planned self-logging session (Full Block + Trigger Bot + Auto
        // Start, no Auto Solve) would have gotten zero reveal-delay data - now unconditional, so it works
        // regardless of which assist feature (if any) is doing the clicking.
        if (lastBlockedTrackAtMs > 0 && (noStepsPending || blockedByReveal)) {
            autoSolveBlockedMsThisAttempt += now - lastBlockedTrackAtMs;
        }
        lastBlockedTrackAtMs = now;
        // Real per-transition log (2026-09-14) - independent of any automation, so killer560's planned
        // self-logging test (which won't use Auto Solve) still gets clean, structured per-transition
        // timing data instead of only the lump-sum total in the completion message.
        if (wasBlockedByReveal && !(noStepsPending || blockedByReveal) && lastRoundCompletedAtMs > 0) {
            long transitionMs = now - lastRoundCompletedAtMs;
            LOGGER.info("[SimonSays] Round transition took {} ms (now on round {}).", transitionMs, clickInOrder.size());
            lastRoundCompletedAtMs = 0L;
        }
        wasBlockedByReveal = noStepsPending || blockedByReveal;

        if (noStepsPending) {
            return;
        }
        BlockPos nextLantern = clickInOrder.get(clickNeeded);
        BlockPos nextButton = nextLantern.west();

        // Real bug found and fixed (2026-09-14): killer560 confirmed a real "SS skip" starts the attempt
        // AFTER round 1 ("it starts on 2/5 and never 1/5"), but currentRoundNumber always started at 1 and
        // only ever incremented by 1 per round completed - so after a skip, it was permanently off by
        // however many rounds got skipped. Auto-detects the REAL round number the same way the "SS N/5"
        // announce message already does: clickInOrder.size() IS the round number once its reveal has
        // fully settled (round N always has exactly N steps), regardless of which round the attempt
        // actually started on. Moved OUT of the Auto-Solve-only branch (2026-09-14) - this needs to stay
        // accurate for Trigger Bot too, since killer560's planned self-logging test uses that, not Auto
        // Solve. Only recomputes expectedTotalClicksThisAttempt once, on the very first round seen this
        // attempt (detected via totalClicksThisAttempt, which counts ANY real completed click regardless
        // of source - autoSolveClicksDoneThisAttempt would never move at all during a Trigger-Bot-only
        // attempt) - it's the fixed total for the WHOLE remaining attempt, not a per-round value.
        if (clickNeeded == 0) {
            currentRoundNumber = clickInOrder.size();
            if (totalClicksThisAttempt == 0) {
                expectedTotalClicksThisAttempt = totalClicksFrom(currentRoundNumber);
            }
        }
        // Real bug found and fixed (2026-09-14, "bug once-over" pass): Trigger Bot never had this same
        // gate, so a real click during round 1's reveal-order-quirk window (firstPhase) could fire on a
        // position clickInOrder.get(clickNeeded) that's about to be reinterpreted (reverse/drop-middle) -
        // same real risk Auto Solve's own gate already protects against, just never applied here too.
        if (blockedByReveal) {
            return;
        }
        // Real bug found and fixed (2026-09-14, "it undergoes this crazy rotation then basically snaps
        // back to the button"): this method and tickAutoStart both independently drive the SAME shared
        // rotateInProgressTarget/rotateClickFiredFor state via tickRotateClick, with no mutual exclusion
        // between them. If Auto Start's own burst (aimed at START_BUTTON) was still resolving its last
        // click in the exact tick window this method started trying to click a freshly-revealed grid
        // button, each call would stomp the other's target - whipping the camera toward whichever one ran
        // last that tick, back and forth, until Auto Start's burst finally finished and stopped competing.
        // Auto Solve/Trigger Bot now waits for Auto Start to fully finish before touching the camera at
        // all - the same principle idle's own existing !autoStartRunning gate already uses.
        if (autoStartRunning) {
            return;
        }

        if (cfg.isAutoSolveEnabled()) {
            // Flat "ms between clicks" pacing (2026-09-14, killer560's own request after seeing real log
            // data show the Target/Variance model landing at a consistent but slow-feeling ~850ms/click) -
            // a direct, immediately-understandable alternative to the overall-duration target below.
            if (cfg.isAutoSolveFixedDelayMode()) {
                boolean sameTargetFixed = nextButton.equals(lastAutoClickedPos);
                long minDelayFixed = Math.max(cfg.getAutoSolveFixedDelayMs(), sameTargetFixed ? 300 : 0);
                if (now - lastAutoClickAtMs >= minDelayFixed) {
                    long sincePreviousMs = lastAutoClickAtMs > 0 ? now - lastAutoClickAtMs : 0;
                    boolean clicked = cfg.isAutoSolveRotate()
                            ? tickRotateClick(client, nextButton)
                            : fireInstantClick(client, nextButton);
                    if (clicked) {
                        lastAutoClickAtMs = now;
                        lastAutoClickedPos = nextButton;
                        autoSolveClicksDoneThisAttempt++;
                        LOGGER.info("[SimonSays] Auto-solve click {}/{} sent ({}ms since previous click, fixed {}ms delay{}).",
                                autoSolveClicksDoneThisAttempt, expectedTotalClicksThisAttempt, sincePreviousMs,
                                cfg.getAutoSolveFixedDelayMs(), cfg.isAutoSolveRotate() ? ", rotate mode" : "");
                    }
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
                // Deadline is the RAW target - no upfront subtraction (see TRANSITION_OVERHEAD_MS's own
                // doc comment for why an upfront lump-sum subtraction double-counted reveal time). Future
                // reveal overhead is reserved dynamically per click instead, below.
                autoSolveDeadlineMs = now + cfg.getClickTimerTargetMs() + jitter;
                autoSolveNextClickAtMs = now;
                autoSolveArmed = true;
            }
            int remaining = Math.max(1, expectedTotalClicksThisAttempt - autoSolveClicksDoneThisAttempt);
            boolean sameTarget = nextButton.equals(lastAutoClickedPos);
            long minDelay = sameTarget ? 300 : 0;
            if (now >= autoSolveNextClickAtMs && now - lastAutoClickAtMs >= minDelay) {
                // "No Rotate"/"Rotate" mode (cfg.isAutoSolveRotate()) - Rotate now has real behavior (see
                // tickRotateClick's own doc comment): turns the camera toward the target over several
                // ticks and only fires once real aim is confirmed, instead of an instant synthetic click.
                // tickRotateClick returns false on ticks it's still mid-turn - this whole block (and its
                // click-bookkeeping/pacing-timer update below) simply doesn't run again until it returns
                // true, so a multi-tick approach never double-counts or reschedules early.
                long sincePreviousMs = lastAutoClickAtMs > 0 ? now - lastAutoClickAtMs : 0;
                boolean clicked = cfg.isAutoSolveRotate()
                        ? tickRotateClick(client, nextButton)
                        : fireInstantClick(client, nextButton);
                if (!clicked) {
                    return;
                }
                lastAutoClickAtMs = now;
                lastAutoClickedPos = nextButton;
                autoSolveClicksDoneThisAttempt++;
                int remainingAfter = Math.max(1, expectedTotalClicksThisAttempt - autoSolveClicksDoneThisAttempt);
                long windowLeftMs = autoSolveDeadlineMs - now;
                // Reserve only the overhead for transitions STILL AHEAD (never touching the deadline
                // itself) - already-elapsed reveal time is already reflected in windowLeftMs shrinking
                // naturally, so reserving it again here would double-count it (the exact bug in the first
                // fix attempt).
                long activeWindowLeftMs = Math.max(0L, windowLeftMs - estimatedRemainingRevealMs());
                autoSolveNextClickAtMs = now + Math.max(50, activeWindowLeftMs / remainingAfter);
                // Always-on (not gated behind Diagnostic Logging) while killer560's "still very delayed"
                // report is unresolved (2026-09-14) - this is the exact data needed to see whether the
                // delay is really coming from this pacing math or from something else entirely (e.g. real
                // per-round reveal wait time, which this can't control).
                LOGGER.info("[SimonSays] Auto-solve click {}/{} sent ({}ms since previous click, next in ~{}ms{}).",
                        autoSolveClicksDoneThisAttempt, expectedTotalClicksThisAttempt, sincePreviousMs,
                        autoSolveNextClickAtMs - now, cfg.isAutoSolveRotate() ? ", rotate mode" : "");
            }
            return;
        }

        if (cfg.isTriggerBotEnabled() && client.hitResult instanceof BlockHitResult blockHit
                && blockHit.getBlockPos().equals(nextButton) && !nextButton.equals(lastTriggerBotTarget)) {
            // Real bug found and fixed (2026-09-14, killer560's own explicit rule): this used to click
            // through the REAL hitResult directly, which - with "Full Block" hitbox expansion on, which
            // killer560 said he'll be testing with - could land anywhere inside the artificially enlarged
            // hitbox, not necessarily anywhere near the button's real visual center. Killer560's own rule,
            // to apply everywhere except Auto Solve's own already-centered synthetic clicking: "make sure
            // it goes to center." Confirming real aim is on the button is still done via the real
            // hitResult above; the actual click now always lands on the block's true center regardless.
            sendNoRotateInteract(client, nextButton);
            lastTriggerBotTarget = nextButton;
            LOGGER.info("[SimonSays] Trigger Bot click sent (round {}, total clicks {} so far this attempt).",
                    currentRoundNumber, totalClicksThisAttempt);
        } else if (!(client.hitResult instanceof BlockHitResult bh) || !bh.getBlockPos().equals(nextButton)) {
            lastTriggerBotTarget = null;
        }
    }

    /** Uniform wrapper around the instant no-rotate click so both click mechanisms (No Rotate, Rotate)
     *  share the same "did a click actually fire this tick" boolean return the caller's pacing/bookkeeping
     *  code relies on. */
    private static boolean fireInstantClick(Minecraft client, BlockPos pos) {
        sendNoRotateInteract(client, pos);
        return true;
    }

    /** Rotate Mode's tick-side half - called once per tick while a click is due. Ownership split
     *  (2026-09-14, "still choppy... update far more often" fix): the actual rotation-applying step now
     *  runs every FRAME via {@link #tickRotateFrame()}, not every tick, so it's as smooth as real mouse
     *  look. This method just (a) tells the frame-driven approach what to aim at, by setting
     *  {@link #rotateInProgressTarget} if it isn't already this exact target, and (b) polls
     *  {@link #rotateClickFiredFor}, which the frame-driven side sets the instant it actually fires a
     *  real click - returning true exactly once, consuming the flag, so the caller's own click-
     *  bookkeeping/pacing still only ever runs once per real click. */
    private static boolean tickRotateClick(Minecraft client, BlockPos buttonPos) {
        if (client.player == null) {
            return false;
        }
        if (buttonPos.equals(rotateClickFiredFor)) {
            rotateClickFiredFor = null;
            return true;
        }
        if (!buttonPos.equals(rotateInProgressTarget)) {
            if (buttonPos.equals(rotateLastFiredTarget)) {
                // Real bug found and fixed (2026-09-14, "the tick delay was off... its still set to 2 but
                // i can tell its not going off every 2 ticks"): beginRotateApproach rolls a FRESH humanized
                // approach every time - new overshoot chance, new curve chance, elapsed-ticks back to 0 -
                // meant for genuinely turning toward a new, different target. Auto Start's own rapid burst
                // re-clicks this exact same button several times in a row without it ever moving, so the
                // camera is already sitting right on it - re-rolling a fresh approach anyway (random
                // overshoot up to 2.5-degrees, random curve) added variable extra settle time on top of
                // the configured tick delay every single repeat click, so a "3 clicks, 2 ticks apart"
                // burst never actually landed evenly 2 ticks apart. Just keep aiming at the same spot
                // instead - the delta is already ~0, so the very next frame settles and fires immediately.
                rotateInProgressTarget = buttonPos;
            } else {
                beginRotateApproach(buttonPos);
            }
        }
        return false;
    }

    /** Rolls a fresh approach's humanization so every turn looks like a slightly different real human
     *  flick rather than an identical robotic ease every time - see this class's own "Rotate Mode"
     *  field-group doc comment for the full real reasoning behind each piece. */
    private static void beginRotateApproach(BlockPos buttonPos) {
        LOGGER.info("[SimonSays][RotateFrame] Beginning approach to {} (was {}).", buttonPos, rotateInProgressTarget);
        rotateInProgressTarget = buttonPos;
        rotateApproachElapsedTicks = 0f;
        rotateSmoothingThisApproach = 0.30f + (float) (Math.random() * 0.15);
        // Real bug found and fixed (2026-09-14, "it is also kind of doing this really weird flick towards
        // the buttons... it should more or less be on track to the button at all times just make it not
        // be a perfectly straight line"): 2.5/2.0-degree overshoot plus a 3-degree curve, each rolling
        // 40% of the time, could stack to 5+ degrees of deviation from the direct path - a huge detour for
        // a target that might only be 10-20 degrees away, reading as a dramatic flick rather than a subtle
        // human imperfection. Both cut down and made rarer so an approach mostly tracks straight at the
        // target with only an occasional slight waver, never a real swing off to the side.
        if (Math.random() < 0.2) {
            rotateOvershootYawRemaining = (float) ((Math.random() * 2 - 1) * 1.0);
            rotateOvershootPitchRemaining = (float) ((Math.random() * 2 - 1) * 0.8);
        } else {
            rotateOvershootYawRemaining = 0f;
            rotateOvershootPitchRemaining = 0f;
        }
        rotateCurveOnThisApproach = Math.random() < 0.2;
        rotateCurveSign = Math.random() < 0.5 ? 1f : -1f;
        // Deliberately does NOT touch idleSuppressedAfterCompletion - see that field's own doc comment
        // for why this used to unconditionally disable idle here, and why that was the real cause of
        // idle never getting a visible chance to run at all.
    }

    /** Real per-FRAME rotation step, registered on {@code LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES}
     *  (2026-09-14, "still choppy... update far more often" fix) - runs at full render framerate instead
     *  of the fixed 20Hz tick rate, which is the real reason it looked stepped no matter how the easing
     *  math itself was tuned: real mouse look updates every frame too. Uses real elapsed wall-clock time
     *  since the last frame (in tick-equivalents, 1 tick = 50ms) to scale every per-tick-tuned constant
     *  below so the overall convergence SPEED stays the same regardless of framerate - only the
     *  smoothness of the steps in between changes. */
    // Diagnostic-only (2026-09-14, killer560's own explicit request: "Add some loggers to see what
    // exactly it is doing so you can fix it" - after repeated live reports that idle-look and Auto
    // Start's own timing still aren't behaving as expected despite several real, understood fixes).
    // Always-on while this investigation is unresolved, same convention this class already uses
    // elsewhere (real click-timing logger, per-transition logger, etc.) - logs every real STATE
    // TRANSITION (not every frame, which would spam thousands of lines/second) so a real log capture
    // can show exactly which gate is blocking idle, or what target an approach is actually chasing, at
    // the exact moment something looks wrong.
    private static String lastLoggedRotateFrameState = "";

    private static void tickRotateFrame() {
        Minecraft client = Minecraft.getInstance();
        SimonSaysConfig cfg = SimonSaysConfig.getInstance();
        if (client.player == null || !cfg.isEnabled() || !cfg.isAutoSolveRotate()) {
            rotateLastFrameAtNanos = 0L;
            return;
        }
        long now = System.nanoTime();
        double dtTicks = rotateLastFrameAtNanos == 0L ? 1.0 : (now - rotateLastFrameAtNanos) / 50_000_000.0;
        rotateLastFrameAtNanos = now;
        dtTicks = Mth.clamp(dtTicks, 0.0, 3.0); // guard against a lag spike/alt-tab producing one huge jump

        // Real bug found and fixed (2026-09-14, "it still doesnt move back to the first button... it
        // needs to remember it" / later refined per "whatever it senses as the second button..."):
        // rememberedFirstButton's own update used to live HERE, running every frame regardless of
        // firstPhase - but that meant it always just mirrored clickInOrder.get(0) live, unable to tell
        // "the first light, which might still get dropped/reordered" apart from "the real resolved first
        // button". That update now lives entirely in detectGridChanges instead, at the exact moments
        // (the size==2 and size==3 firstPhase branches, plus the reveal-settle timeout for a genuine
        // single-step round) where the real answer is actually known - see updateRememberedFirstButton's
        // own doc comment. Rounds 2-5 never need a fresh update at all: the growing-sequence mechanic
        // replays the SAME physical first button every round, so whatever round 1 already resolved stays
        // correct for the rest of the device untouched.

        // Real bug found and fixed (2026-09-14, killer560's own report: "it just did that large weird
        // flick again... it is always right after a button press and it moves a ton then right back"):
        // rotateInProgressTarget is nulled the instant a click fires (see applyRotateApproachFrame) and
        // only gets set to the NEXT real target on the next TICK (tickAutoSolveAndTriggerBot/tickAutoStart
        // are tick-based) - but this frame loop runs far more often than ticks, so there was a real gap of
        // several frames after every single click where rotateInProgressTarget was null. Every OTHER idle
        // gate (autoStartRunning false, near the anchor, Goldor line seen) is already satisfied throughout
        // an entire active solve, so idle would win that gap and start easing the camera toward
        // rememberedFirstButton (button 1) - then the very next tick immediately yanked it back to the
        // real next target. That's the "flick": a real jump toward button 1 and back, every single click.
        // solveStepsPending means Auto Solve/Trigger Bot still has a real click due in the CURRENT round -
        // i.e. another approach is about to begin on the very next tick regardless - so idle must not
        // touch the camera during that gap at all; holding still (falling into BLOCKED, which doesn't move
        // the camera) until that next approach begins is exactly what a real person's aim would do.
        boolean solveStepsPending = !clickInOrder.isEmpty() && clickNeeded < clickInOrder.size();

        String state;
        if (rotateInProgressTarget != null) {
            state = "APPROACH target=" + rotateInProgressTarget;
            applyRotateApproachFrame(client, rotateInProgressTarget, dtTicks);
        } else {
            boolean nearAnchor = isNearIdleLookAnchor(client);
            if (!autoStartRunning && !idleSuppressedAfterCompletion && goldorLineSeenThisPhase && nearAnchor
                    && !solveStepsPending) {
                state = "IDLE target=" + (rememberedFirstButton != null ? rememberedFirstButton : START_BUTTON);
                applyIdleSwayFrame(client, dtTicks);
            } else {
                double distSq = client.player.position().distanceToSqr(IDLE_LOOK_ANCHOR);
                state = String.format(Locale.US,
                        "BLOCKED autoStartRunning=%b idleSuppressedAfterCompletion=%b goldorLineSeen=%b nearAnchor=%b (distSq=%.1f, need<=%.1f) solveStepsPending=%b rememberedFirstButton=%s clickInOrder.size=%d clickNeeded=%d",
                        autoStartRunning, idleSuppressedAfterCompletion, goldorLineSeenThisPhase, nearAnchor,
                        distSq, IDLE_LOOK_RANGE_SQ, solveStepsPending, rememberedFirstButton, clickInOrder.size(), clickNeeded);
            }
        }
        if (!state.equals(lastLoggedRotateFrameState)) {
            LOGGER.info("[SimonSays][RotateFrame] {}", state);
            lastLoggedRotateFrameState = state;
        }
    }

    private static boolean isNearIdleLookAnchor(Minecraft client) {
        return client.player != null && client.player.position().distanceToSqr(IDLE_LOOK_ANCHOR) <= IDLE_LOOK_RANGE_SQ;
    }

    private static void applyRotateApproachFrame(Minecraft client, BlockPos buttonPos, double dtTicks) {
        var player = client.player;
        rotateApproachElapsedTicks += (float) dtTicks;

        Vec3 eyePos = player.getEyePosition();
        Vec3 target = realBlockCenter(client, buttonPos);
        Vec3 diff = target.subtract(eyePos);
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float rawTargetYaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float rawTargetPitch = (float) -(Mth.atan2(diff.y, horizontalDist) * (180.0 / Math.PI));

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        if (rotateCurveOnThisApproach) {
            boolean yawDominant = Math.abs(Mth.wrapDegrees(rawTargetYaw - currentYaw))
                    >= Math.abs(Mth.wrapDegrees(rawTargetPitch - currentPitch));
            // Simple rise-then-fade envelope in continuous elapsed-tick-equivalents (not a discrete tick
            // counter) so it stays frame-rate-independent: ramps up over the first 3, decays ~20%/tick
            // after - doesn't need to know the approach's total real length in advance to look smooth.
            // Real bug found and fixed (2026-09-14, "it should more or less be on track to the button at
            // all times just make it not be a perfectly straight line"): a 3-degree peak read as a real
            // detour off the direct path, not a subtle waver - cut to 1 degree.
            float t = rotateApproachElapsedTicks;
            float curveMagnitude = t <= 3f ? (t / 3f) * 1.0f : (float) (1.0 * Math.pow(0.8, t - 3f));
            float curveOffset = curveMagnitude * rotateCurveSign;
            if (yawDominant) {
                rawTargetPitch += curveOffset;
            } else {
                rawTargetYaw += curveOffset;
            }
        }

        rawTargetYaw += rotateOvershootYawRemaining;
        rawTargetPitch += rotateOvershootPitchRemaining;
        // Real bug found and fixed (2026-09-14): decaying by a FIXED per-tick fraction every FRAME (as
        // this used to, before the frame-rate-independence pass) decayed several times faster at high
        // framerate than intended - scaled by dtTicks via a real exponential-decay identity instead, so
        // the real decay SPEED (in wall-clock time) stays constant regardless of framerate.
        double overshootDecay = Math.pow(0.6, dtTicks);
        rotateOvershootYawRemaining *= (float) overshootDecay;
        rotateOvershootPitchRemaining *= (float) overshootDecay;
        if (Math.abs(rotateOvershootYawRemaining) < 0.05f) {
            rotateOvershootYawRemaining = 0f;
        }
        if (Math.abs(rotateOvershootPitchRemaining) < 0.05f) {
            rotateOvershootPitchRemaining = 0f;
        }

        float yawDelta = Mth.wrapDegrees(rawTargetYaw - currentYaw);
        float pitchDelta = Mth.wrapDegrees(rawTargetPitch - currentPitch);
        // Diagnostic-only (2026-09-14, killer560's own report: "it undergoes this crazy rotation then
        // basically snaps back to the button... hopefully your sensors will show you that"): a large raw
        // delta on the very FIRST frame of a fresh approach is normal (the target can genuinely be far
        // from wherever the camera was looking before). A large raw delta appearing well INTO an already-
        // in-progress approach (elapsed ticks > 2, i.e. it should already be most of the way there) is not
        // normal and means the effective target moved out from under this approach somehow - logs every
        // real piece of state that could explain it, so a repeat of this report has hard evidence instead
        // of another guess.
        if (rotateApproachElapsedTicks > 2f && (Math.abs(yawDelta) > 20f || Math.abs(pitchDelta) > 20f)) {
            LOGGER.warn("[SimonSays][RotateFrame] Large mid-approach jump - target={} elapsedTicks={} "
                            + "rawTargetYaw={} rawTargetPitch={} currentYaw={} currentPitch={} yawDelta={} "
                            + "pitchDelta={} overshootYaw={} overshootPitch={} curveOn={} dtTicks={} "
                            + "autoStartRunning={}.",
                    buttonPos, rotateApproachElapsedTicks, rawTargetYaw, rawTargetPitch, currentYaw,
                    currentPitch, yawDelta, pitchDelta, rotateOvershootYawRemaining, rotateOvershootPitchRemaining,
                    rotateCurveOnThisApproach, dtTicks, autoStartRunning);
        }
        // Same real exponential-decay identity as the overshoot above, applied to the main ease-toward-
        // target smoothing factor - keeps the overall turn SPEED matching what rotateSmoothingThisApproach
        // was tuned for per-tick, regardless of how many frames actually render per tick.
        float frameSmoothing = 1f - (float) Math.pow(1.0 - rotateSmoothingThisApproach, dtTicks);
        player.setYRot(currentYaw + yawDelta * frameSmoothing);
        player.setXRot(currentPitch + pitchDelta * frameSmoothing);

        // Real bug found and fixed (2026-09-14, "it can be a hair off of pressing buttons... make it go
        // a bit more central"): only fires once BOTH the real raycast confirms the right block AND the
        // remaining delta is small enough that the aim has actually settled near center - previously
        // fired the instant the raycast first crossed onto the right block's face at all, which could be
        // right at an edge rather than the middle.
        boolean settledNearCenter = Math.abs(yawDelta) < 1.5f && Math.abs(pitchDelta) < 1.5f
                && rotateOvershootYawRemaining == 0f && rotateOvershootPitchRemaining == 0f;
        if (settledNearCenter && client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(buttonPos)) {
            // Real aim confirmed - the actual click still always lands on the button's true center
            // (killer560's own standing rule), same real click-sender every other mode already uses.
            sendNoRotateInteract(client, buttonPos);
            rotateClickFiredFor = buttonPos;
            rotateLastFiredTarget = buttonPos;
            rotateInProgressTarget = null;
            rotateApproachElapsedTicks = 0f;
        }
    }

    /** Idle look at the real first grid button of the current sequence ("the 1/5 button essentially, or
     *  the first one from 2/5" - killer560's own clarification, NOT the literal start/reset button) with
     *  a slight resting sway, rather than a hard freeze. Sits at dead center most of the time, with
     *  brief random micro-twitches on a random timer that decay fast back to center (see idleTwitchYaw's
     *  own field doc comment for why this replaced a continuous sine-wave sway). Falls back to
     *  whichever real button was most recently remembered as "the current round's first one" (see
     *  rememberedFirstButton's own doc comment - persists across ordinary round transitions, only clears
     *  on a genuine full reset). If nothing's been revealed yet this attempt (including right after a
     *  genuine reset - 2026-09-14, killer560's own request: "make sure that it will go back to looking at
     *  the middle if the dev is reset"): looks at the grid's own geometric center instead - a real person
     *  watching for a reveal looks at the grid, not fixates on the start button (2026-09-14, "after
     *  finishing the skip portion it should more or less look toward the middle of the screen to see
     *  where all the buttons are coming out cause that is what a normal human does"). */
    private static void applyIdleSwayFrame(Minecraft client, double dtTicks) {
        var player = client.player;
        Vec3 eyePos = player.getEyePosition();
        Vec3 target = rememberedFirstButton != null ? realBlockCenter(client, rememberedFirstButton) : GRID_CENTER_LOOK;
        Vec3 diff = target.subtract(eyePos);
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float rawTargetYaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float rawTargetPitch = (float) -(Mth.atan2(diff.y, horizontalDist) * (180.0 / Math.PI));

        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();

        // Real bug found and fixed (2026-09-14, "it is going back to 1 but it is slow now. Make it more
        // snappy like the rest of the movement, but a human that sits on the button for awhile will have
        // small mouse movements so imitate that as well as possible"): the initial snap-back to the first
        // button and the small resting sway once already looking at it used to share ONE slow smoothing
        // constant (0.08), tuned only for the sway's own tiny amplitude - so the snap-back inherited that
        // same sluggish pace. Measures distance to the RAW target (no sway added) to tell the two apart:
        // while still far away (just switched here from an approach/BLOCKED state, or the remembered
        // button just changed), eases in fast like a real approach; once genuinely close, drops back to
        // the slow sway-chasing smoothing so the small idle wobble still reads as a human resting on the
        // button, not a snappy flick.
        float rawYawDeltaNoSway = Mth.wrapDegrees(rawTargetYaw - currentYaw);
        float rawPitchDeltaNoSway = Mth.wrapDegrees(rawTargetPitch - currentPitch);
        boolean stillCatchingUp = Math.abs(rawYawDeltaNoSway) > 3.0f || Math.abs(rawPitchDeltaNoSway) > 3.0f;

        // Real bug found and fixed (2026-09-14, "it likes to hold this tiny drift up and to the left
        // alot... it should kind of have these twitches. Think microscopic movements then going back to
        // middle of the button"): replaced the old continuous sine-wave sway (see idleTwitchYaw's own
        // field doc comment for why that read as sustained drift) with brief random twitches on a random
        // timer, each decaying back toward dead center fast rather than lingering.
        long nowMs = System.currentTimeMillis();
        if (nowMs >= idleNextTwitchAtMs) {
            idleTwitchYaw = (float) ((Math.random() * 2 - 1) * 0.12);
            idleTwitchPitch = (float) ((Math.random() * 2 - 1) * 0.08);
            idleNextTwitchAtMs = nowMs + 500L + (long) (Math.random() * 1500.0);
        }
        double twitchDecay = Math.pow(0.15, dtTicks);
        idleTwitchYaw *= (float) twitchDecay;
        idleTwitchPitch *= (float) twitchDecay;
        float swayYaw = idleTwitchYaw;
        float swayPitch = idleTwitchPitch;

        float yawDelta = Mth.wrapDegrees(rawTargetYaw + swayYaw - currentYaw);
        float pitchDelta = Mth.wrapDegrees(rawTargetPitch + swayPitch - currentPitch);
        float perTickSmoothing = stillCatchingUp ? 0.35f : 0.08f;
        float frameSmoothing = 1f - (float) Math.pow(1.0 - perTickSmoothing, dtTicks);
        player.setYRot(currentYaw + yawDelta * frameSmoothing);
        player.setXRot(currentPitch + pitchDelta * frameSmoothing);
    }

    /** Real bug found and fixed (2026-09-14, "it is aiming to the left of the start button... aiming to
     *  the left of normal buttons again as well"): a real vanilla {@code stone_button} isn't a full
     *  block - it's a thin box mounted flush against whichever real face it's attached to, so its own
     *  true visual/clickable center sits well off to one side of the FULL BLOCK's center, not in the
     *  middle. {@link Vec3#atCenterOf} (the full-block center) was never the right aim point for a real
     *  button; this reads the block's own real shape instead - same real technique already proven in
     *  {@code EtherwarpOverlayFeature#realBoxFor} - so the aim point matches wherever this specific
     *  button's real model actually sits, regardless of its real facing/attach-face. */
    private static Vec3 realBlockCenter(Minecraft client, BlockPos pos) {
        var shape = client.level.getBlockState(pos).getShape(client.level, pos);
        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        return box.getCenter();
    }

    /** Interacts with a block without needing the player's crosshair on it - the "no rotate" click
     *  killer560 asked for ("just like QUOI"), which sends the interact packet directly via a synthetic
     *  {@link BlockHitResult} instead of first turning the camera to aim. Real automation - callers must
     *  already be behind a {@code BuildVariant.CHEAT_FEATURES_ENABLED} check. Also Rotate Mode's own
     *  final click-fire step (see {@link #tickRotateClick}) once its own real aim is confirmed. */
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

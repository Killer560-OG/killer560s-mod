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
    // Re-measured 2026-09-14 evening from two real skip runs (2->3: 1851/2148ms, 3->4: 2199/2398ms, 4->5:
    // 2599/2798ms) - the old values ran ~150-250ms short, which matters more now that the Timer Target
    // counts from the start-button click and leaves much less click budget to absorb the error. 1->2 has
    // no newer real sample (every recent run was a skip), kept as-is.
    private static final long[] TRANSITION_OVERHEAD_MS = {1400L, 2000L, 2300L, 2700L};
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
    // Real bug found and fixed (2026-09-14, killer560's own report: "It is now still not going the right
    // speed. I set it to 11.2 and it got like 15s"): now that tickRotateClick actually reports success
    // (see its own doc comment), the pacing schedule itself is real - but it only ever reserved budget
    // for real REVEAL overhead (estimatedRemainingRevealMs), never for the real TIME Rotate Mode's own
    // humanized camera turn takes between the schedule saying "click now" and the approach actually
    // settling and firing. A real log confirmed it: "since previous click" gaps were routinely 2-3x
    // longer than what was scheduled for them, purely from real turn time never budgeted for at all -
    // across 14 real clicks that adds up to exactly the kind of overage reported. Tracks a rolling
    // estimate of that real per-click overhead (actual gap minus what was scheduled for it) and reserves
    // it for all remaining clicks too, the same way reveal overhead already is - self-correcting within a
    // single device's own run as real data comes in, same "calculate as it goes" approach the distance
    // weighting already uses.
    // Seeded at a realistic ~100ms per click rather than 0 (2026-09-14, verified by simulating the pacing
    // math against killer560's real log timings): starting at 0 over-funded the first few clicks, which
    // only mattered when the Timer Target was close to the fastest physically possible time.
    private static final long APPROACH_OVERHEAD_EMA_SEED_MS = 100L;
    private static long autoApproachOverheadEmaMs = APPROACH_OVERHEAD_EMA_SEED_MS;
    private static long lastScheduledDelayMs = 0L;
    // Diagnostic-only (2026-09-14, killer560's own report: "it is still no where near the propper time" -
    // a real log capture for that report showed Auto Solve's Target/Variance path log exactly ONE click
    // for a whole device that otherwise completed all 5 rounds, meaning something else finished the
    // remaining 14 real clicks without it ever being confirmed WHY Auto Solve's own scheduling stopped
    // firing). Tracks how long a click has been "due" (now >= autoSolveNextClickAtMs) for the SAME target
    // without tickRotateClick ever actually firing it - logs a WARN with full state once that exceeds 2
    // real seconds, so a repeat of this has hard evidence instead of another guess.
    private static BlockPos autoSolveStallTarget = null;
    private static long autoSolveStallSinceMs = 0L;
    private static boolean autoSolveStallLogged = false;
    // Diagnostic-only (2026-09-14) - state-transition logger for tickAutoSolveAndTriggerBot's own gating
    // decisions (NO_STEPS_PENDING / BLOCKED_BY_REVEAL / BLOCKED_BY_AUTOSTART / DISPATCH), same
    // only-log-on-change convention as [SimonSays][RotateFrame] - see logAutoSolveState.
    private static String lastLoggedAutoSolveState = "";
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
    // Real feature added (2026-09-14, killer560's own request: "keep a similar rotation speed but have it
    // miss a button, go towards the next one, then go back after missing" - after several rounds of
    // chasing exactly why settling before a click felt like sitting and waiting): rather than continuing
    // to tune settle thresholds, gives Rotate Mode a real humanized "feint" - reaches partway toward
    // wherever the real NEXT button is before correcting back to properly click the CURRENT one, like a
    // person whose eyes/aim jump ahead before finishing what's in front of them. Rolled once per approach
    // in beginRotateApproach (as a yaw/pitch OFFSET - the real angular difference between "look at the
    // current target" and "look at the next one", scaled to a believable near-complete reach, not
    // literally the next button's own position). Stores the PEAK magnitude only - applyRotateApproachFrame
    // computes the real applied contribution fresh each frame as a continuous rise-then-fade envelope
    // (killer560's own "be careful to not have it snap" - see that method's own doc comment), never
    // decayed/mutated in place like overshoot is.
    private static float rotateFeintYawOffset = 0f;
    private static float rotateFeintPitchOffset = 0f;
    // Diagnostic-only (2026-09-14, killer560's own report: "it is still waiting while looking at the
    // button for too long" - Round 138's overshoot fix and this round's curve-decay speedup were both
    // reasoned guesses at what was blocking settledNearCenter, not confirmed by a log). Logs the exact
    // gating values once an approach has been visually sitting on a target for a real suspicious length
    // of time without firing, so a repeat of this report has hard evidence of exactly what's still
    // blocking it instead of a fourth guess.
    private static boolean rotateApproachStallLogged = false;
    // Set by the frame-driven approach the instant a click actually fires; polled and cleared by the
    // tick-based caller that owns that target, so click-bookkeeping/pacing still only ever runs once
    // per real click, from the same tick-based code as every other click mode.
    private static BlockPos rotateClickFiredFor = null;
    // Wall-clock time the frame-driven side actually fired rotateClickFiredFor - so Auto Solve's pacing
    // books the click at its REAL fire moment, not whenever the next tick happened to get around to it.
    private static long rotateClickFiredAtMs = 0L;
    // Real bug found and fixed (2026-09-14, killer560's own real log + request: "please fix the auto spacing.
    // I need 150 to be perfectly optimal"): Auto Start's "2t" really spaced clicks 198-200ms (4 ticks) apart -
    // the tick-side countdown was off by one (N ticks set -> N+1 ticks waited), and in Rotate mode each click
    // also had to wait for the NEXT tick to consume the previous fire before re-aiming, adding up to another
    // tick. Spacing is now wall-clock exact: when a start-button click fires with more of the burst left, the
    // frame-driven side keeps aiming at the start button and arms this "not before" time = that fire +
    // Delay x 50ms, and fires on the first frame at or past it. 0 = no hold (every other click).
    private static long rotateFireNotBeforeMs = 0L;
    // Real bug found and fixed (2026-09-14, killer560's own report: "it is drifting off of the button
    // again for the one it should be going back to"): between rounds Hypixel removes all 16 grid buttons
    // (the "Grid reset detected (16 air blocks)" log line), and that's exactly the window idle spends
    // looking back at rememberedFirstButton while the next pattern plays. realBlockCenter used to fall
    // back to the FULL block's center for an air block - ~0.44 blocks closer to the player than the real
    // button face, which is ~5 degrees of pitch off for a top/bottom-row button at idle's real standing
    // distance - so idle visibly drifted up/down off the button for the whole reveal, then snapped back
    // once the buttons reappeared. Remembers each grid button's real box from whenever it last existed
    // (see aimBoxFor), with vanilla's own wall-button geometry as the fallback if it was never seen.
    private static final Map<BlockPos, AABB> lastSeenGridButtonBoxes = new HashMap<>();
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
    // Real bug history (2026-09-14) on idle's resting sway, for context on the current design in
    // applyIdleSwayFrame: started as one continuous slow sine wave (read as "always drifting one way"
    // since it spends half of every ~9-12s cycle near one extreme) -> discrete random micro-twitches on a
    // timer (read as "only goes one way" over a short real observation window, since only whichever couple
    // of random directions happened to roll were ever visible) -> now two continuous sine waves at
    // different, fairly fast frequencies (a real Lissajous-style path, always changing direction, never
    // holding one offset) - hard-clamped every frame to a real fraction of the button's own actual angular
    // size as seen from the player right now, so the exact raw amplitude barely matters anymore; the clamp
    // is what determines the real visible motion.
    // Real bug found and fixed (2026-09-14, "make the movement happen sometimes on the waiting first
    // button time and others itll more or less be exactly still"): toggles the sway on/off on a random
    // timer so idle sometimes visibly fidgets and sometimes just holds still for a while, like a real
    // resting person - see applyIdleSwayFrame for where this is consumed.
    private static boolean idleSwayActive = true;
    private static long idleSwayPhaseEndsAtMs = 0L;
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

    // --- Start-click anchor (2026-09-14, killer560's own report after a real run: Hypixel's own "completed a
    // device (14.436s)" and a p3sim recording both time Simon Says from the FIRST START-BUTTON CLICK to the
    // end - but this mod's Timer Target and "Whole device solved" message both started at the first GRID
    // click, silently leaving out the Auto Start burst + first reveal (~2.4s in that run: 12.00s here vs
    // Hypixel's 14.44s). "Sub 12 is tick time so it needs to be sub 4 death ticks" - the target has to mean
    // the same thing Hypixel measures.) Set by the first start-button click of an attempt, real or Auto
    // Start (see noteStartButtonClick); cleared at the same fresh-attempt points as the rest of the attempt
    // state EXCEPT tickStartButton's press-edge reset, which is triggered by that very click.
    private static long startClickAnchorMs = 0L;
    private static long lastStartButtonClickAtMs = 0L;
    // Clicks of one skip burst are at most ~1.2s apart even at the slider's max delay; anything later than
    // this is a separate, fresh start.
    private static final long START_BURST_MAX_GAP_MS = 1500L;
    // A first grid click this long after the anchor can't belong to the same start (the first reveal plays
    // right after the start click) - fall back to anchoring on the grid click itself.
    private static final long START_ANCHOR_MAX_AGE_MS = 10_000L;

    private static void noteStartButtonClick(long atMs, String source) {
        boolean freshStart = startClickAnchorMs == 0L || totalClicksThisAttempt > 0
                || atMs - lastStartButtonClickAtMs > START_BURST_MAX_GAP_MS;
        if (freshStart) {
            startClickAnchorMs = atMs;
            LOGGER.info("[SimonSays] Timer anchored on first start-button click ({}).", source);
        }
        lastStartButtonClickAtMs = atMs;
    }

    // --- Live-run verification diagnostics (2026-09-14, pre-M7 test pass) - LOGGING ONLY: nothing below
    // is ever read by any pacing/aim/click decision. Per-attempt ones reset via resetDeviceDiagnostics() at
    // the same real fresh-attempt points deviceStartedAtMs itself resets at.
    private static long diagArmedAtMs = 0L;
    private static long diagArmedJitterMs = 0L;
    private static final List<Long> diagTransitionMs = new ArrayList<>();
    private static int diagReClicks = 0;
    private static int diagIgnoredClicks = 0;
    private static int diagConfirmCount = 0;
    private static long diagConfirmLatencySumMs = 0L;
    private static long diagConfirmLatencyMaxMs = 0L;
    // The most recent synthetic grid click still awaiting server confirmation (see onButtonPressed).
    private static BlockPos diagPendingConfirmButton = null;
    private static long diagPendingConfirmFiredAtMs = 0L;
    private static long diagPendingConfirmFirstFiredAtMs = 0L;
    private static int diagPendingConfirmFires = 0;
    private static long diagLastIdleLogAtMs = 0L;
    private static long diagLastDispatchTimingLogAtMs = 0L;
    private static long diagApproachBeganAtMs = 0L;
    private static boolean diagApproachReaim = false;
    private static boolean diagApproachOvershootRolled = false;
    private static boolean diagApproachCurveRolled = false;
    private static boolean diagApproachFeintRolled = false;
    private static long diagLastAutoStartFireAtMs = 0L;

    private static void resetDeviceDiagnostics() {
        diagArmedAtMs = 0L;
        diagArmedJitterMs = 0L;
        diagTransitionMs.clear();
        diagReClicks = 0;
        diagIgnoredClicks = 0;
        diagConfirmCount = 0;
        diagConfirmLatencySumMs = 0L;
        diagConfirmLatencyMaxMs = 0L;
    }

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
        boolean block = clickNeeded >= clickInOrder.size() || !pos.equals(clickInOrder.get(clickNeeded).west());
        // Diagnostic-only (2026-09-14): the mixin returns FAIL silently, so a blocked BOT click would
        // otherwise look exactly like a sent one that the server just never confirmed.
        if (block && syntheticClickInProgress) {
            LOGGER.warn("[SimonSays] Prevent Misclicks BLOCKED this mod's own synthetic click on {} "
                            + "(clickNeeded={} clickInOrder.size={}).", pos, clickNeeded, clickInOrder.size());
        }
        return block;
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
        noteStartButtonClick(now, "real click");
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
            rotateClickFiredFor = null;
            startClickAnchorMs = 0L;
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
            autoApproachOverheadEmaMs = APPROACH_OVERHEAD_EMA_SEED_MS;
            lastScheduledDelayMs = 0L;
            wasBlockedByReveal = false;
            lastRoundCompletedAtMs = 0L;
            currentRoundNumber = 1;
            expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;
            deviceStartedAtMs = 0L;
            totalClicksThisAttempt = 0;
            resetDeviceDiagnostics();
            startClickAnchorMs = 0L;
            rotateInProgressTarget = null;
            rotateLastFiredTarget = null;
            autoStartClickedThisPhase = false;
            realStartButtonPressCountThisPhase = 0;
            idleSuppressedAfterCompletion = false;
            rememberedFirstButton = null;
        }
        wasActive = true;

        // Deliberately FIRST, before detectGridChanges - see consumeFiredRotateClick's own doc comment.
        consumeFiredRotateClick(cfg);
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
        // Found in the 2026-09-14 review pass: an approach still in progress when the grid/round resets
        // (e.g. a failed attempt) used to keep aiming at - and eventually click - its now-stale button,
        // possibly mid-way through the next reveal. Nothing left in progress belongs to the new state.
        rotateInProgressTarget = null;
        rotateFireNotBeforeMs = 0L;
        autoSolveStallTarget = null;
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
            autoApproachOverheadEmaMs = APPROACH_OVERHEAD_EMA_SEED_MS;
            lastScheduledDelayMs = 0L;
            wasBlockedByReveal = false;
            lastRoundCompletedAtMs = 0L;
            currentRoundNumber = 1;
            expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;
            deviceStartedAtMs = 0L;
            totalClicksThisAttempt = 0;
            resetDeviceDiagnostics();
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
                // Real bug found and fixed (2026-09-14, killer560's own report: "it needs to take into
                // account the set time as well. If i set it to 15 then it needs to be getting 15 wwith the
                // variance accounted for. It is currently almost always the same speed"): this general
                // fresh-attempt boundary only ever reset firstPhase/rememberedFirstButton - it never reset
                // Auto Solve's own Target/Variance pacing state (autoSolveArmed, autoSolveDeadlineMs is
                // derived from it, autoSolveClicksDoneThisAttempt, etc.), unlike the "entering range" and
                // "real start-button press" trigger points, which already reset all of these. That's fine
                // for an attempt that cleanly completes (the whole-device-completion branch in
                // onButtonPressed already resets all of it there) - but an attempt that gets ABANDONED or
                // RETRIED before completing (exactly what heavy skip-testing produces - see this same
                // block's own earlier doc comment) never reaches that branch, so the retry inherited the
                // FAILED attempt's still-armed, likely-already-expired autoSolveDeadlineMs. With the
                // deadline already in the past, activeWindowLeftMs collapsed to 0 for every remaining
                // click of the retry, regardless of the configured Timer Target - exactly "almost always
                // the same [fast] speed" no matter what's set. Now resets the same full set of per-attempt
                // pacing fields the other two trigger points already do, so a retried attempt re-arms its
                // own fresh deadline from the real configured target instead of inheriting a dead one.
                autoSolveArmed = false;
                autoSolveClicksDoneThisAttempt = 0;
                lastBlockedTrackAtMs = 0L;
                autoSolveBlockedMsThisAttempt = 0L;
                autoApproachOverheadEmaMs = APPROACH_OVERHEAD_EMA_SEED_MS;
                lastScheduledDelayMs = 0L;
                wasBlockedByReveal = false;
                lastRoundCompletedAtMs = 0L;
                currentRoundNumber = 1;
                expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;
                deviceStartedAtMs = 0L;
                resetDeviceDiagnostics();
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
        // Diagnostic-only (2026-09-14): server-confirmation latency for this mod's own synthetic click -
        // fire (render frame) to this tick-polled POWERED edge, so it includes up to ~50ms of poll delay.
        if (buttonPos.equals(diagPendingConfirmButton)) {
            long confirmedAtMs = System.currentTimeMillis();
            long latencyMs = confirmedAtMs - diagPendingConfirmFiredAtMs;
            diagConfirmCount++;
            diagConfirmLatencySumMs += latencyMs;
            diagConfirmLatencyMaxMs = Math.max(diagConfirmLatencyMaxMs, latencyMs);
            LOGGER.info("[SimonSays] Click on {} server-confirmed {}ms after fire ({} fire(s), {}ms since first fire).",
                    buttonPos, latencyMs, diagPendingConfirmFires, confirmedAtMs - diagPendingConfirmFirstFiredAtMs);
            diagPendingConfirmButton = null;
        }
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
                long completedAtMs = System.currentTimeMillis();
                long deviceTookMs = deviceStartedAtMs > 0 ? completedAtMs - deviceStartedAtMs : 0;
                // Headline number counts from the first start-button click, same as Hypixel's own device
                // timer (see startClickAnchorMs's own doc comment); falls back to the first grid click if no
                // start click was seen this attempt (e.g. entered range mid-device).
                long fromStartMs = startClickAnchorMs > 0 && startClickAnchorMs <= completedAtMs
                        ? completedAtMs - startClickAnchorMs : -1L;
                LOGGER.info("[SimonSays] Whole device completed in {} ms from first start-button click, {} ms from first grid click ({} ms of that was real reveal/transition delay).",
                        fromStartMs, deviceTookMs, autoSolveBlockedMsThisAttempt);
                logDeviceSummary(cfg, deviceTookMs);
                // Client-side only (sendSystemMessage, same technique this mod's other features already
                // use for a local-only notice) - killer560 asked for a message to himself, not a real
                // party announcement. Breaks out the real reveal/transition delay (2026-09-14, killer560's
                // own request: "figure out how long it takes to actually go through that transition phase
                // because that needs to be factored into the overall time it takes") whenever Auto Solve's
                // Target/Variance mode measured any - only that mode tracks it, so a manual/Trigger Bot/
                // Fixed-Delay solve just gets the plain total.
                if (fromStartMs >= 0) {
                    client.player.sendSystemMessage(Component.literal(String.format(Locale.US,
                            "§6[Simon Says] §fWhole device solved in §e%.2fs §7from start click (§e%.2fs§7 from first grid click, §e%.2fs§7 reveal delay)",
                            fromStartMs / 1000.0, deviceTookMs / 1000.0, autoSolveBlockedMsThisAttempt / 1000.0)));
                } else if (autoSolveBlockedMsThisAttempt > 0) {
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
                autoApproachOverheadEmaMs = APPROACH_OVERHEAD_EMA_SEED_MS;
                lastScheduledDelayMs = 0L;
                wasBlockedByReveal = false;
                lastRoundCompletedAtMs = 0L;
                currentRoundNumber = 1;
                expectedTotalClicksThisAttempt = TOTAL_REAL_CLICKS_PER_DEVICE;
                deviceStartedAtMs = 0L;
                totalClicksThisAttempt = 0;
                resetDeviceDiagnostics();
                startClickAnchorMs = 0L;
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

    /** Diagnostic-only (2026-09-14, pre-M7 verification) - one line per whole-device completion answering
     *  "did the booked-at-real-fire-time pacing land on the Timer Target": the jitter-adjusted deadline vs
     *  the real last-click FIRE time (the same clock the deadline is armed on), plus every input the pacing
     *  math used. Called before the completion branch resets any of it. */
    private static void logDeviceSummary(SimonSaysConfig cfg, long deviceTookMs) {
        String pacing;
        if (!cfg.isAutoSolveEnabled()) {
            pacing = "autoSolve=off";
        } else if (cfg.isAutoSolveFixedDelayMode()) {
            pacing = "fixedDelay=" + cfg.getAutoSolveFixedDelayMs() + "ms";
        } else if (!autoSolveArmed || diagArmedAtMs == 0L) {
            pacing = "target=" + cfg.getClickTimerTargetMs() + "ms (pacing window never armed)";
        } else {
            long offsetMs = lastAutoClickAtMs - autoSolveDeadlineMs;
            pacing = String.format(Locale.US,
                    "target=%dms variance=%dms jitter=%+dms -> deadline at +%dms from pacing anchor; last click fired at +%dms = %dms %s",
                    cfg.getClickTimerTargetMs(), cfg.getClickTimerVarianceMs(), diagArmedJitterMs,
                    autoSolveDeadlineMs - diagArmedAtMs, lastAutoClickAtMs - diagArmedAtMs, Math.abs(offsetMs),
                    offsetMs <= 0 ? "EARLY" : "LATE");
        }
        long transitionSumMs = 0L;
        for (long t : diagTransitionMs) {
            transitionSumMs += t;
        }
        LOGGER.info("[SimonSays][DeviceSummary] {} | confirm-to-confirm {}ms | transitions {}ms (sum {}ms, "
                        + "reserved estimate {}ms) | clicks booked {}/{} confirmed {} | re-clicks {} ignored {} | "
                        + "confirm latency avg {}ms max {}ms | approachOverheadEma={}ms | revealBlocked={}ms",
                pacing, deviceTookMs, diagTransitionMs, transitionSumMs,
                java.util.Arrays.toString(TRANSITION_OVERHEAD_MS), autoSolveClicksDoneThisAttempt,
                expectedTotalClicksThisAttempt, totalClicksThisAttempt, diagReClicks, diagIgnoredClicks,
                diagConfirmCount > 0 ? diagConfirmLatencySumMs / diagConfirmCount : -1, diagConfirmLatencyMaxMs,
                autoApproachOverheadEmaMs, autoSolveBlockedMsThisAttempt);
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
        // Click mode follows Auto Solve's Mode (2026-09-14, killer560's own call - replaces the separate
        // Aura/Look Only setting): Rotate = look only - the camera turns to the real start button and the
        // click only fires once the real crosshair raycast confirms it's on the button (see
        // applyRotateApproachFrame); No Rotate = aura, the instant synthetic click that works regardless of
        // where the player is looking. Either way the click itself lands on the button's true center.
        if (cfg.isAutoSolveRotate()) {
            if (!tickRotateClick(client, START_BUTTON, null)) {
                return;
            }
        } else {
            sendNoRotateInteract(client, START_BUTTON);
        }
        // Diagnostic-only (2026-09-14): real fire time of this click (the frame it actually fired in, for
        // rotate) - so the burst's real click spacing is visible, not just the tick it got consumed on.
        long autoStartConsumedAtMs = System.currentTimeMillis();
        long autoStartFiredAtMs = cfg.isAutoSolveRotate() ? rotateClickFiredAtMs : autoStartConsumedAtMs;
        long autoStartSincePrevFireMs = diagLastAutoStartFireAtMs > 0 ? autoStartFiredAtMs - diagLastAutoStartFireAtMs : -1;
        diagLastAutoStartFireAtMs = autoStartFiredAtMs;
        noteStartButtonClick(autoStartFiredAtMs, "Auto Start");
        autoStartClicksSent++;
        autoStartClickedThisPhase = true;
        // Rotate: the frame-driven side already holds the next click for exactly Delay x 50ms from this
        // fire (see rotateFireNotBeforeMs) - no extra tick countdown on top. Aura fires from this tick loop,
        // so wait exactly Delay ticks: the countdown below decrements-and-returns once per tick and fires on
        // the tick after it reaches 0, so Delay - 1 here is Delay real ticks (was Delay + 1 before).
        autoStartTicksUntilNextClick = cfg.isAutoSolveRotate() ? 0 : cfg.getAutoStartClickDelayTicks() - 1;
        // Always-on (not gated behind Diagnostic Logging) while killer560's "isn't working" report is
        // unresolved (2026-09-14) - includes the button's own POWERED state at send-time to directly
        // answer his own question ("is it still clicking the start button while it is already pressed
        // still?"): this code never skips a scheduled click based on that state, so if the log shows 3
        // clicks land exactly on schedule regardless of powered state, the click-sending itself isn't
        // the problem - something server-side is.
        BlockState startButtonState = client.level.getBlockState(START_BUTTON);
        boolean startButtonPowered = startButtonState.is(Blocks.STONE_BUTTON)
                && startButtonState.getValue(BlockStateProperties.POWERED);
        LOGGER.info("[SimonSays] Auto-start click {}/{} sent ({} mode, button currently powered={}, "
                        + "{}ms since previous auto-start fire, consumed {}ms after fire).",
                autoStartClicksSent, cfg.getAutoStartClicks(),
                cfg.isAutoSolveRotate() ? "look-only (rotate)" : "aura",
                startButtonPowered, autoStartSincePrevFireMs, autoStartConsumedAtMs - autoStartFiredAtMs);
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
        diagLastAutoStartFireAtMs = 0L;
        LOGGER.info("[SimonSays] Auto-start triggered by real Goldor phase-start line: {} clicks, {} ticks apart.",
                cfg.getAutoStartClicks(), cfg.getAutoStartClickDelayTicks());
    }

    // ------------------------------------------------------------------
    // Auto-solve (no-rotate) + trigger bot
    // ------------------------------------------------------------------

    /** Diagnostic-only - logs {@code state} only when it actually changes, same convention as
     *  {@code tickRotateFrame}'s own [RotateFrame] logger. See {@link #lastLoggedAutoSolveState}. */
    private static void logAutoSolveState(String state) {
        if (!state.equals(lastLoggedAutoSolveState)) {
            LOGGER.info("[SimonSays][AutoSolve] {}", state);
            lastLoggedAutoSolveState = state;
        }
    }

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
        // Real bug found and fixed AGAIN (2026-09-14, killer560's own report: "something is off with the
        // reveal delay too... Whole device solved in 10.90s (27.05s reveal delay)" - the reported reveal
        // delay was bigger than the whole device time it's supposed to be a sub-component of): this
        // accumulates starting the moment the player is simply in range with nothing pending (which
        // includes the real Goldor-dialogue wait AND Auto Start's own burst, both BEFORE the device's
        // first real click even lands), but deviceTookMs (used for the total in that same message) is
        // only measured starting from deviceStartedAtMs - the first real click. Two different starting
        // points measuring two different windows, so the "sub-component" was routinely bigger than the
        // whole. Now only accumulates once the device attempt has actually started (deviceStartedAtMs
        // set), so it measures blocked time within the SAME window deviceTookMs does, and can never
        // exceed it again.
        if (lastBlockedTrackAtMs > 0 && deviceStartedAtMs > 0 && (noStepsPending || blockedByReveal)) {
            autoSolveBlockedMsThisAttempt += now - lastBlockedTrackAtMs;
        }
        lastBlockedTrackAtMs = now;
        // Real per-transition log (2026-09-14) - independent of any automation, so killer560's planned
        // self-logging test (which won't use Auto Solve) still gets clean, structured per-transition
        // timing data instead of only the lump-sum total in the completion message.
        if (wasBlockedByReveal && !(noStepsPending || blockedByReveal) && lastRoundCompletedAtMs > 0) {
            long transitionMs = now - lastRoundCompletedAtMs;
            LOGGER.info("[SimonSays] Round transition took {} ms (now on round {}).", transitionMs, clickInOrder.size());
            diagTransitionMs.add(transitionMs);
            lastRoundCompletedAtMs = 0L;
        }
        wasBlockedByReveal = noStepsPending || blockedByReveal;

        if (noStepsPending) {
            logAutoSolveState("NO_STEPS_PENDING clickNeeded=" + clickNeeded + " clickInOrder.size=" + clickInOrder.size());
            return;
        }
        BlockPos nextLantern = clickInOrder.get(clickNeeded);
        BlockPos nextButton = nextLantern.west();
        // Real feature added (2026-09-14, killer560's own request: "keep a similar rotation speed but
        // have it miss a button, go towards the next one, then go back after missing" - after several
        // rounds of chasing exactly why settling before a click felt like sitting and waiting): rather
        // than continuing to tune settle thresholds, gives Rotate Mode a real humanized "feint" - reaches
        // partway toward wherever the NEXT real button actually is before correcting back to click the
        // CURRENT one, like a person whose eyes/aim jump ahead before finishing the click in front of
        // them. Peeking clickNeeded+1 is the same safe lookahead the distance-weighting fix already uses -
        // null once no more real positions are known this round (the upcoming click belongs to a
        // not-yet-revealed future round), in which case no feint is possible.
        // Real bug found and fixed (2026-09-14, killer560's own request: "For the miss it should only be
        // able to happen on the 2,3,4 button no others"): restricts feint eligibility to the round's own
        // 2nd/3rd/4th click (clickNeeded, 0-indexed, in [1,3]) - never the round's first click (a person
        // starting a fresh sequence is deliberate, not already anticipating ahead) or its last (nothing
        // real to reach toward yet within THIS round regardless of whether a next round's own position
        // happens to be known).
        // Real bug found and fixed (2026-09-14, killer560's own request: "make it so it can only have the
        // missclick if the delay is over 12s. Anything below that it shouldnt be able to have the
        // missclick"): the feint adds real extra settle time to whichever click rolls it - only worth
        // spending when the configured Timer Target actually has slack for it. Below 12 real seconds,
        // every bit of the budget matters more, so no feint is ever eligible there regardless of click
        // position.
        int rotateFeintPeekIndex = clickNeeded + 1;
        boolean rotateFeintEligiblePosition = clickNeeded >= 1 && clickNeeded <= 3
                && cfg.getClickTimerTargetMs() > 12000;
        BlockPos rotateNextHint = rotateFeintEligiblePosition && rotateFeintPeekIndex < clickInOrder.size()
                ? clickInOrder.get(rotateFeintPeekIndex).west() : null;

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
            logAutoSolveState("BLOCKED_BY_REVEAL target=" + nextButton + " firstPhase=" + firstPhase
                    + " isStillRevealing=" + isStillRevealing());
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
            logAutoSolveState("BLOCKED_BY_AUTOSTART target=" + nextButton);
            return;
        }
        // Diagnostic-only (2026-09-14, killer560's own report: "it is still no where near the propper
        // time" - confirmed via a real log capture that NEITHER an "Auto-solve click" NOR a "Trigger Bot
        // click" line appeared even once for a whole device that still fully completed, despite killer560
        // confirming Auto Solve was toggled on, Trigger Bot off, and no manual clicking - meaning
        // something is preventing this method from ever reaching its own click-dispatch code at all, not
        // just failing to fire once there). Logs the exact config state and gating values reaching this
        // point, right before dispatch - the single most direct way to see WHY neither branch below ever
        // fires, if that happens again.
        // De-spammed (2026-09-14): the change key used to embed the two ms values below, which change every
        // tick, so this "state change" logger fired every single tick while dispatching. Flags/target only
        // now; the ms values go on their own line, throttled to at most once a second.
        logAutoSolveState(String.format(Locale.US,
                "DISPATCH target=%s autoSolveEnabled=%b autoSolveRotate=%b autoSolveFixedDelay=%b "
                        + "triggerBotEnabled=%b autoSolveArmed=%b",
                nextButton, cfg.isAutoSolveEnabled(), cfg.isAutoSolveRotate(), cfg.isAutoSolveFixedDelayMode(),
                cfg.isTriggerBotEnabled(), autoSolveArmed));
        if (now - diagLastDispatchTimingLogAtMs >= 1000L) {
            diagLastDispatchTimingLogAtMs = now;
            LOGGER.info("[SimonSays][AutoSolve] DISPATCH timing target={} now-autoSolveNextClickAtMs={}ms "
                            + "now-lastAutoClickAtMs={}ms deadline in {}ms",
                    nextButton, now - autoSolveNextClickAtMs, now - lastAutoClickAtMs,
                    autoSolveArmed ? autoSolveDeadlineMs - now : -1);
        }

        if (cfg.isAutoSolveEnabled()) {
            // Real bug found and fixed (2026-09-14, killer560's own report: "Whole device solved in 11.55s
            // (6.65s reveal delay)... it is set to 11.2" - a real log showed every click after the first
            // scheduled with "base 0ms" yet still landing 350-400ms apart): this block used to also do the
            // click BOOKKEEPING, the tick after tickRotateClick reported the frame-driven click had fired -
            // but by then detectGridChanges had usually already advanced clickNeeded (see
            // consumeFiredRotateClick's own doc comment), so the click was recorded against the NEXT button
            // (lastAutoClickedPos = a button not clicked yet). That tripped the 300ms same-button re-click
            // guard below on literally every click - the real log shows DISPATCH sitting exactly 300ms
            // before every "Beginning approach". A round's LAST click was also only booked once the NEXT
            // round finished revealing, feeding ~2s reveal gaps into the approach-overhead estimate, which
            // is what drove "base" to 0. Bookkeeping now lives in bookAutoSolveClick, called with the real
            // clicked button and real fire time; this block only decides WHEN to start the next click.
            //
            // Flat "ms between clicks" pacing (2026-09-14, killer560's own request after seeing real log
            // data show the Target/Variance model landing at a consistent but slow-feeling ~850ms/click) -
            // a direct, immediately-understandable alternative to the overall-duration target below.
            boolean sameTarget = nextButton.equals(lastAutoClickedPos);
            boolean dueNow;
            if (cfg.isAutoSolveFixedDelayMode()) {
                long minDelayFixed = Math.max(cfg.getAutoSolveFixedDelayMs(), sameTarget ? 300 : 0);
                dueNow = now - lastAutoClickAtMs >= minDelayFixed;
            } else {
                // The Target ± Variance window arms on the attempt's first real click (see
                // bookAutoSolveClick) - until then the first click is simply due immediately.
                // sameTarget's 300ms guard only ever matters now in its intended case: the click just
                // fired but the server hasn't confirmed it yet (clickNeeded still points at it), so don't
                // re-click the same button until either it confirms or 300ms passes (a lost click).
                long minDelay = sameTarget ? 300 : 0;
                dueNow = (!autoSolveArmed || now >= autoSolveNextClickAtMs) && now - lastAutoClickAtMs >= minDelay;
            }
            if (!dueNow) {
                return;
            }
            if (!cfg.isAutoSolveRotate()) {
                fireInstantClick(client, nextButton);
                bookAutoSolveClick(cfg, nextButton, now);
                return;
            }
            // "Rotate" mode (see tickRotateClick's own doc comment): starts/continues the humanized turn
            // toward this button. The frame-driven side fires the real click once aim is confirmed, and the
            // NEXT tick's consumeFiredRotateClick books it - nothing to do here once it's requested.
            tickRotateClick(client, nextButton, rotateNextHint);
            // Diagnostic-only stall detector - see autoSolveStallTarget's own field doc comment. Cleared by
            // bookAutoSolveClick the moment a click actually lands.
            if (!nextButton.equals(autoSolveStallTarget)) {
                autoSolveStallTarget = nextButton;
                autoSolveStallSinceMs = now;
                autoSolveStallLogged = false;
            } else if (!autoSolveStallLogged && now - autoSolveStallSinceMs > 2000L) {
                LOGGER.warn("[SimonSays] Auto-solve click stalled - due for {}ms on target {} but "
                                + "no rotate click has fired. rotateInProgressTarget={} "
                                + "rotateClickFiredFor={} rotateLastFiredTarget={} autoStartRunning={} "
                                + "blockedByReveal={} rotateEnabled={}.",
                        now - autoSolveStallSinceMs, nextButton, rotateInProgressTarget, rotateClickFiredFor,
                        rotateLastFiredTarget, autoStartRunning, blockedByReveal, cfg.isAutoSolveRotate());
                autoSolveStallLogged = true;
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

    /** Books a pending Rotate Mode click - called at the very START of every tick, before tickStartButton/
     *  detectGridChanges. Real bug found and fixed (2026-09-14, killer560's own report: "Whole device
     *  solved in 11.55s... it is set to 11.2"): the old "any pending rotateClickFiredFor counts as whatever
     *  the caller is working on now" rule (see tickRotateClick) got the success signal delivered, but
     *  delivered it to tickAutoSolveAndTriggerBot AFTER detectGridChanges had usually already processed the
     *  server's confirmation of that click - so it was booked against the next button (or, for a round's
     *  last click, not until the following round finished revealing). Consuming it here instead is
     *  deterministic: the click fires during a render frame, and this is the first code of the very next
     *  tick, so clickInOrder/clickNeeded still describe the round the click actually belonged to, and a
     *  round/whole-device reset triggered by that same click's confirmation can only happen AFTER it's
     *  been booked (never booked into the next attempt by mistake). Auto Start's own start-button clicks
     *  are still consumed by tickAutoStart itself, by exact position (see tickRotateClick). */
    private static void consumeFiredRotateClick(SimonSaysConfig cfg) {
        BlockPos fired = rotateClickFiredFor;
        if (fired == null) {
            return;
        }
        if (fired.equals(START_BUTTON)) {
            // Left for tickAutoStart - unless its run was cancelled mid-burst (resetSolveState), in which
            // case nothing will ever consume it, so drop it instead of letting it linger.
            if (!autoStartRunning) {
                rotateClickFiredFor = null;
            }
            return;
        }
        rotateClickFiredFor = null;
        if (cfg.isAutoSolveEnabled()) {
            bookAutoSolveClick(cfg, fired, rotateClickFiredAtMs);
        }
    }

    /** All of Auto Solve's per-click bookkeeping and pacing (both Fixed Delay and Target ± Variance), for
     *  a click that really landed on {@code clickedButton} at {@code clickedAtMs} - see the doc comment in
     *  tickAutoSolveAndTriggerBot's Auto Solve branch for why this is keyed on the real clicked button and
     *  real fire time rather than on whatever clickNeeded happens to point at by the time it's booked. */
    private static void bookAutoSolveClick(SimonSaysConfig cfg, BlockPos clickedButton, long clickedAtMs) {
        long sincePreviousMs = lastAutoClickAtMs > 0 ? clickedAtMs - lastAutoClickAtMs : 0;
        autoSolveStallTarget = null;
        String modeSuffix = cfg.isAutoSolveRotate() ? ", rotate mode" : "";
        if (clickedButton.equals(lastAutoClickedPos)) {
            // The 300ms same-button guard expired before the server confirmed the previous click, so this
            // was a retry of the SAME step (lastAutoClickedPos is cleared at every round reset, and a round
            // never repeats a button, so this can't be a genuinely new step). Restart the guard's timer but
            // don't count it as another step or reschedule/re-estimate anything off it.
            lastAutoClickAtMs = clickedAtMs;
            diagReClicks++;
            LOGGER.info("[SimonSays] Auto-solve re-click of {} ({}ms since previous click, server hadn't confirmed it yet{}).",
                    clickedButton, sincePreviousMs, modeSuffix);
            return;
        }
        int clickedIndex = clickInOrder.indexOf(clickedButton.east());
        if (clickedIndex < 0) {
            // Not a step of the round currently being tracked (a stale click that landed across a reset) -
            // never let it count toward, or arm the deadline of, whatever attempt is tracked now.
            diagIgnoredClicks++;
            LOGGER.info("[SimonSays] Auto-solve click on {} ignored for pacing - not a step of the current round.", clickedButton);
            return;
        }
        // The real button after this one IN THIS ROUND, if known - null for a round's last click (the next
        // click belongs to a not-yet-revealed round).
        BlockPos followingButton = clickedIndex + 1 < clickInOrder.size()
                ? clickInOrder.get(clickedIndex + 1).west() : null;
        lastAutoClickAtMs = clickedAtMs;
        lastAutoClickedPos = clickedButton;
        autoSolveClicksDoneThisAttempt++;

        if (cfg.isAutoSolveFixedDelayMode()) {
            LOGGER.info("[SimonSays] Auto-solve click {}/{} sent ({}ms since previous click, fixed {}ms delay{}).",
                    autoSolveClicksDoneThisAttempt, expectedTotalClicksThisAttempt, sincePreviousMs,
                    cfg.getAutoSolveFixedDelayMs(), modeSuffix);
            return;
        }

        if (!autoSolveArmed) {
            // Arm the "Target ± Variance overall" pacing window ONCE per full device attempt (not once per
            // round - see autoSolveArmed's own history), re-armed automatically at the next real
            // fresh-attempt trigger. Real bug found and fixed (2026-09-14, "set to 11.2"): this used to arm
            // the moment round 1 first became clickable - but the completion message killer560 compares
            // against measures from the FIRST CLICK, so every approach/confirmation delay before that first
            // click was silently eaten out of the target. Anchored on the first real click itself now, the
            // same starting point the completion message uses. Deadline is still the RAW target - no upfront
            // subtraction (see TRANSITION_OVERHEAD_MS's own doc comment); future reveal overhead is
            // reserved dynamically below.
            int variance = cfg.getClickTimerVarianceMs();
            long jitter = variance <= 0 ? 0 : (long) ((Math.random() * 2 - 1) * variance);
            // Anchored on the attempt's first START-BUTTON click when one was seen (2026-09-14 - see
            // startClickAnchorMs's own doc comment: that's what Hypixel's own device timer measures), so
            // the Auto Start burst and first reveal that already happened count against the target too.
            // windowLeftMs below is simply smaller by however long that took.
            boolean anchoredOnStart = startClickAnchorMs > 0 && startClickAnchorMs <= clickedAtMs
                    && clickedAtMs - startClickAnchorMs <= START_ANCHOR_MAX_AGE_MS;
            long anchorMs = anchoredOnStart ? startClickAnchorMs : clickedAtMs;
            autoSolveDeadlineMs = anchorMs + cfg.getClickTimerTargetMs() + jitter;
            autoSolveArmed = true;
            diagArmedAtMs = anchorMs;
            diagArmedJitterMs = jitter;
            LOGGER.info("[SimonSays] Auto-solve pacing armed on first grid click of {}: anchored on {} ({}ms before this click), target {}ms, jitter {}ms -> deadline {}ms from now.",
                    clickedButton, anchoredOnStart ? "first start-button click" : "this click (no recent start click seen)",
                    clickedAtMs - anchorMs, cfg.getClickTimerTargetMs(), jitter, autoSolveDeadlineMs - clickedAtMs);
        } else if (lastScheduledDelayMs > 0 && clickedIndex > 0) {
            // Real per-click approach overhead (see autoApproachOverheadEmaMs's own doc comment) - only
            // sampled between two clicks of the SAME round (clickedIndex > 0). The gap before a round's
            // first click spans that round's whole reveal, which estimatedRemainingRevealMs already
            // reserves separately - sampling it here too was exactly what inflated this estimate to
            // 900-1150ms in killer560's 11.55s log and forced every delay to 0.
            long observedOverheadMs = Math.max(0L, sincePreviousMs - lastScheduledDelayMs);
            autoApproachOverheadEmaMs = (autoApproachOverheadEmaMs * 3 + observedOverheadMs) / 4;
        }

        int remainingAfter = Math.max(1, expectedTotalClicksThisAttempt - autoSolveClicksDoneThisAttempt);
        long windowLeftMs = autoSolveDeadlineMs - clickedAtMs;
        // Reserve only the overhead for transitions STILL AHEAD (never touching the deadline itself) -
        // already-elapsed reveal time is already reflected in windowLeftMs shrinking naturally, so
        // reserving it again here would double-count it. Also reserves the real per-click approach
        // overhead estimated above for every remaining click, same reasoning.
        long activeWindowLeftMs = Math.max(0L, windowLeftMs - estimatedRemainingRevealMs()
                - autoApproachOverheadEmaMs * remainingAfter);
        long baseDelayMs = activeWindowLeftMs / remainingAfter;
        // Distance weighting (2026-09-14, killer560's own request: "Close one should be faster and ones
        // further away should be longer") - closer pairs get proportionally less time, farther pairs more,
        // against a typical ~2.5-block hop. baseDelayMs is recomputed from the real time left at every
        // click, so one click taking more just means less remains for the rest. Real bug found and fixed
        // (2026-09-14, "set to 11.2"): with only ONE click left there's nothing left to rebalance against,
        // so a far final hop (weight up to 1.8x) scheduled that last click up to 80% past the deadline
        // itself - now never weighted when it's the last click (with 2+ left, 1.8x of an even share can't
        // exceed the remaining window).
        long delayMs = baseDelayMs;
        if (followingButton != null && remainingAfter > 1) {
            double distance = Math.sqrt(clickedButton.distSqr(followingButton));
            double weight = Mth.clamp(distance / 2.5, 0.5, 1.8);
            delayMs = (long) (baseDelayMs * weight);
        }
        autoSolveNextClickAtMs = clickedAtMs + Math.max(50, delayMs);
        lastScheduledDelayMs = autoSolveNextClickAtMs - clickedAtMs;
        // Always-on (not gated behind Diagnostic Logging) - the exact data needed to see whether real delay
        // is coming from this pacing math or from something it can't control (reveal, turn time).
        LOGGER.info("[SimonSays] Auto-solve click {}/{} sent ({}ms since previous click, next in ~{}ms "
                        + "[base {}ms, distance-weighted{}, approachOverheadEma={}ms, deadline in {}ms]{}).",
                autoSolveClicksDoneThisAttempt, expectedTotalClicksThisAttempt, sincePreviousMs,
                lastScheduledDelayMs, baseDelayMs, followingButton != null && remainingAfter > 1 ? "" : "=n/a",
                autoApproachOverheadEmaMs, windowLeftMs, modeSuffix);
    }

    /** No Rotate mode's instant click - booked by the caller via bookAutoSolveClick in the same tick. */
    private static void fireInstantClick(Minecraft client, BlockPos pos) {
        sendNoRotateInteract(client, pos);
    }

    /** Rotate Mode's tick-side half - called once per tick while a click is due. Ownership split
     *  (2026-09-14, "still choppy... update far more often" fix): the actual rotation-applying step now
     *  runs every FRAME via {@link #tickRotateFrame()}, not every tick, so it's as smooth as real mouse
     *  look. This method just (a) tells the frame-driven approach what to aim at, by setting
     *  {@link #rotateInProgressTarget} if it isn't already this exact target, and (b) for Auto Start's
     *  start-button clicks only, polls {@link #rotateClickFiredFor} by exact position - returning true
     *  exactly once per real click. Auto Solve's grid clicks never report back through here; they're
     *  booked by {@link #consumeFiredRotateClick} at the start of the next tick instead. */
    private static boolean tickRotateClick(Minecraft client, BlockPos buttonPos, BlockPos nextHint) {
        if (client.player == null) {
            return false;
        }
        // Exact-position match only. History (2026-09-14): an exact match used to orphan every Auto Solve
        // grid click, because detectGridChanges advances clickNeeded before Auto Solve asks again - that
        // was first "fixed" by accepting ANY pending click here, which reported success but booked each
        // click against the wrong (next) button - the real cause of the 300ms-per-click slowdown in
        // killer560's "11.55s when set to 11.2" log. Grid clicks are now booked by
        // consumeFiredRotateClick at the start of the tick (before detectGridChanges), so the only thing
        // still consumed here is Auto Start's start-button click, whose position never changes.
        if (buttonPos.equals(rotateClickFiredFor)) {
            rotateClickFiredFor = null;
            return true;
        }
        if (!buttonPos.equals(rotateInProgressTarget)) {
            rotateFireNotBeforeMs = 0L;
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
                // No fresh roll means no fresh humanization either - clear anything left over from the
                // approach that fired, so a leftover feint/curve can't swing the camera off this button
                // again while re-aiming (found in the 2026-09-14 review pass).
                rotateFeintYawOffset = 0f;
                rotateFeintPitchOffset = 0f;
                rotateCurveOnThisApproach = false;
                rotateOvershootYawRemaining = 0f;
                rotateOvershootPitchRemaining = 0f;
                // Diagnostic-only - begin marker for the "Approach fired" log (re-aim = no fresh roll).
                diagApproachBeganAtMs = System.currentTimeMillis();
                diagApproachReaim = true;
                diagApproachOvershootRolled = false;
                diagApproachCurveRolled = false;
                diagApproachFeintRolled = false;
            } else {
                beginRotateApproach(buttonPos, nextHint);
            }
        }
        return false;
    }

    /** Rolls a fresh approach's humanization so every turn looks like a slightly different real human
     *  flick rather than an identical robotic ease every time - see this class's own "Rotate Mode"
     *  field-group doc comment for the full real reasoning behind each piece. */
    private static void beginRotateApproach(BlockPos buttonPos, BlockPos nextHint) {
        LOGGER.info("[SimonSays][RotateFrame] Beginning approach to {} (was {}).", buttonPos, rotateInProgressTarget);
        rotateInProgressTarget = buttonPos;
        rotateApproachElapsedTicks = 0f;
        rotateApproachStallLogged = false;
        // Real bug found and fixed (2026-09-14, killer560's own report: "it still just got 14.7 when set
        // to 11.2" - a real per-click log confirmed the real physical floor: 14 clicks' worth of camera-
        // turn time plus real reveal waiting added up to more than the configured target could ever allow,
        // regardless of how the pacing schedule was tuned - no amount of scheduling math can make a turn
        // finish faster than the turn itself takes): sped up the base turn rate (was 0.30-0.45 per-tick
        // smoothing) so Rotate Mode can physically reach lower configured targets.
        rotateSmoothingThisApproach = 0.45f + (float) (Math.random() * 0.20);
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
        // See rotateFeintYawOffset's own field doc comment for the full real reasoning. 30% chance,
        // and only when a real next button is actually known this round.
        // Real bug found and fixed (2026-09-14, killer560's own request: "have it go all the way to the
        // next button essentially then back"): was only reaching 50% of the real angular difference -
        // raised to 85% ("essentially" all the way, deliberately just short of exactly overlapping the
        // next button's own real aim point).
        rotateFeintYawOffset = 0f;
        rotateFeintPitchOffset = 0f;
        if (nextHint != null && Math.random() < 0.3) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                Vec3 eyePos = mc.player.getEyePosition();
                float[] toTarget = yawPitchTo(eyePos, realBlockCenter(mc, buttonPos));
                float[] toNext = yawPitchTo(eyePos, realBlockCenter(mc, nextHint));
                rotateFeintYawOffset = Mth.wrapDegrees(toNext[0] - toTarget[0]) * 0.85f;
                rotateFeintPitchOffset = Mth.wrapDegrees(toNext[1] - toTarget[1]) * 0.85f;
            }
        }
        // Diagnostic-only - captured at roll time, since overshoot/feint decay to 0 before the fire.
        diagApproachBeganAtMs = System.currentTimeMillis();
        diagApproachReaim = false;
        diagApproachOvershootRolled = rotateOvershootYawRemaining != 0f || rotateOvershootPitchRemaining != 0f;
        diagApproachCurveRolled = rotateCurveOnThisApproach;
        diagApproachFeintRolled = rotateFeintYawOffset != 0f || rotateFeintPitchOffset != 0f;
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
        // Real bug found and fixed (2026-09-14, killer560's own report: "it should go from looking at
        // middle to looking at that button as soon as it appears... not look after all 3 go out"): this
        // used to just check "is there an unclicked step in clickInOrder", which is true for the ENTIRE
        // reveal too, not just the actual clicking phase - clickNeeded never advances while lights are
        // still popping up, since nothing is being clicked yet. That blocked idle from tracking a freshly
        // revealed button the whole time it was still revealing, only letting go once the round had
        // already been solved and reset. Now also requires the reveal to have actually finished
        // (mirrors tickAutoSolveAndTriggerBot's own blockedByReveal gate) - a pending step only means
        // "don't touch the camera, an approach is imminent" once clicking can genuinely start.
        boolean revealStillBlocking = firstPhase || isStillRevealing();
        boolean solveStepsPending = !revealStillBlocking && !clickInOrder.isEmpty() && clickNeeded < clickInOrder.size();

        String state;
        if (rotateInProgressTarget != null) {
            state = "APPROACH target=" + rotateInProgressTarget;
            applyRotateApproachFrame(client, rotateInProgressTarget, dtTicks);
        } else {
            boolean nearAnchor = isNearIdleLookAnchor(client);
            // Real bug found and fixed (2026-09-14, killer560's own request: "dont have it full stop on
            // buttons it should keep moving the whole time towards the next one"): once a click fired,
            // the camera used to just freeze (falling into BLOCKED below) for however long the pacing
            // schedule made it wait before the NEXT click was due - a real, visible dead stop. Whenever a
            // real click is due in the current round but not yet permitted to fire (solveStepsPending),
            // continuously eases the camera toward that button the whole time instead - by the time the
            // schedule actually permits the click, the camera is usually already there (or very close),
            // so the real committed approach that follows (see applyRotateApproachFrame) settles almost
            // immediately instead of starting a fresh turn from a dead stop. Guarded by !autoStartRunning
            // for the same reason every other grid-facing state already is - the camera must stay locked
            // on the start button for the whole real duration of an Auto Start run.
            if (solveStepsPending && !autoStartRunning) {
                BlockPos preDriftTarget = clickInOrder.get(clickNeeded).west();
                state = "PRE_DRIFT target=" + preDriftTarget;
                applyPreDriftFrame(client, preDriftTarget, dtTicks);
            } else if (!autoStartRunning && !idleSuppressedAfterCompletion && goldorLineSeenThisPhase && nearAnchor
                    && !solveStepsPending) {
                state = "IDLE target=" + (rememberedFirstButton != null ? rememberedFirstButton : START_BUTTON);
                applyIdleSwayFrame(client, dtTicks);
                if (rememberedFirstButton != null) {
                    logIdleAimThrottled(client);
                }
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

    /** Diagnostic-only (2026-09-14, verifying the aimBoxFor air-button fix) - at most once a second while
     *  IDLE on rememberedFirstButton: angular offset from the camera to the aim point, whether that button
     *  is currently air (between rounds) and which box aimBoxFor is using, and whether the real crosshair
     *  raycast is on it (necessarily false while it's air - realHit then shows what's behind it). withinFace
     *  compares the offset against the box's own angular half-extents, so drift off the face is numeric. */
    private static void logIdleAimThrottled(Minecraft client) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - diagLastIdleLogAtMs < 1000L || client.level == null) {
            return;
        }
        diagLastIdleLogAtMs = nowMs;
        var player = client.player;
        BlockPos target = rememberedFirstButton;
        BlockState state = client.level.getBlockState(target);
        String boxSource = !state.getShape(client.level, target).isEmpty() ? "live"
                : lastSeenGridButtonBoxes.containsKey(target) ? "lastSeen" : "vanillaFallback";
        Vec3 eyePos = player.getEyePosition();
        Vec3 aim = realBlockCenter(client, target);
        float[] toAim = yawPitchTo(eyePos, aim);
        float dYaw = Mth.wrapDegrees(toAim[0] - player.getYRot());
        float dPitch = Mth.wrapDegrees(toAim[1] - player.getXRot());
        float[] half = realButtonAngularHalfExtents(client, target, eyePos);
        boolean withinFace = Math.abs(dYaw) <= half[0] && Math.abs(dPitch) <= half[1];
        boolean raycastOnTarget = client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(target);
        LOGGER.info("[SimonSays][Idle] target={} air={} box={} aim=({}) dYaw={} dPitch={} halfExtent=({}) "
                        + "withinFace={} raycastOnTarget={} realHit={} swayActive={}",
                target, state.isAir(), boxSource,
                String.format(Locale.US, "%.3f, %.3f, %.3f", aim.x, aim.y, aim.z),
                String.format(Locale.US, "%.2f", dYaw), String.format(Locale.US, "%.2f", dPitch),
                String.format(Locale.US, "%.2f, %.2f", half[0], half[1]), withinFace, raycastOnTarget,
                client.hitResult instanceof BlockHitResult hit2 ? hit2.getBlockPos() : "none", idleSwayActive);
    }

    private static boolean isNearIdleLookAnchor(Minecraft client) {
        return client.player != null && client.player.position().distanceToSqr(IDLE_LOOK_ANCHOR) <= IDLE_LOOK_RANGE_SQ;
    }

    /** Continuously eases the camera toward wherever the next real click is going to land, without
     *  clicking or rolling any of the full approach's own humanization (overshoot/curve/feint) - see the
     *  PRE_DRIFT dispatch branch's own doc comment in {@link #tickRotateFrame} for the full real
     *  reasoning. Deliberately simple/stateless (no "begin" event, no rolled randomness) since it runs
     *  continuously every frame the schedule is making the caller wait, not once per discrete approach. */
    private static void applyPreDriftFrame(Minecraft client, BlockPos target, double dtTicks) {
        var player = client.player;
        Vec3 eyePos = player.getEyePosition();
        float[] toTarget = yawPitchTo(eyePos, realBlockCenter(client, target));
        float currentYaw = player.getYRot();
        float currentPitch = player.getXRot();
        float yawDelta = Mth.wrapDegrees(toTarget[0] - currentYaw);
        float pitchDelta = Mth.wrapDegrees(toTarget[1] - currentPitch);
        float frameSmoothing = 1f - (float) Math.pow(1.0 - 0.5, dtTicks);
        player.setYRot(currentYaw + yawDelta * frameSmoothing);
        player.setXRot(currentPitch + pitchDelta * frameSmoothing);
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
            // counter) so it stays frame-rate-independent: ramps up over the first 3, decays after -
            // doesn't need to know the approach's total real length in advance to look smooth.
            // Real bug found and fixed (2026-09-14, "it should more or less be on track to the button at
            // all times just make it not be a perfectly straight line"): a 3-degree peak read as a real
            // detour off the direct path, not a subtle waver - cut to 1 degree.
            // Real bug found and fixed AGAIN (2026-09-14, killer560's own report: "it is still waiting
            // while looking at the button for too long" - Round 138 fixed overshoot's own exact-zero
            // requirement, but curve's decay was never sped up to match): at the old ~20%-remaining/tick
            // decay, curve's own contribution could still be a meaningful fraction of a degree for the
            // better part of a second - not enough to block settling by itself (it never exceeded the
            // 1.5-degree threshold alone), but combined with the small residual gap the main easing always
            // leaves, it could keep tipping the total over 1.5 degrees for far longer than the visible
            // motion actually lasted. Sped up to match overshoot's own decay rate.
            float t = rotateApproachElapsedTicks;
            float curveMagnitude = t <= 3f ? (t / 3f) * 1.0f : (float) (1.0 * Math.pow(0.35, t - 3f));
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
        // Real bug found and fixed (2026-09-14, killer560's own report: "it sits waiting to click buttons
        // for a bit"): a click can't fire until overshoot fully decays below the 0.05-degree threshold
        // (see below) - the camera can look like it's already arrived while still silently waiting on
        // this decay tail. Sped up from 0.6 (~60% remaining per tick) to 0.35 (~35% remaining per tick) so
        // that tail resolves faster without removing the overshoot effect itself.
        double overshootDecay = Math.pow(0.35, dtTicks);
        rotateOvershootYawRemaining *= (float) overshootDecay;
        rotateOvershootPitchRemaining *= (float) overshootDecay;
        if (Math.abs(rotateOvershootYawRemaining) < 0.05f) {
            rotateOvershootYawRemaining = 0f;
        }
        if (Math.abs(rotateOvershootPitchRemaining) < 0.05f) {
            rotateOvershootPitchRemaining = 0f;
        }

        // Real feature added (2026-09-14, killer560's own request: "keep a similar rotation speed but
        // have it miss a button, go towards the next one, then go back after missing"): pulls rawTarget
        // toward roughly where the next button is for a brief real moment, then fades back out, letting
        // the approach correct and settle on the true current target normally.
        // Real bug found and fixed (2026-09-14, killer560's own report: "Be careful to not have it snap"):
        // rotateFeintYawOffset/PitchOffset used to be applied at their FULL rolled magnitude on the very
        // first frame, then multiplicatively decayed - meaning the "reach toward next" itself was an
        // instant jump, not a real motion, before fading out. Now stores the PEAK magnitude only and
        // computes the actual contribution fresh each frame as a continuous rise-then-fade envelope (the
        // same real technique curve already uses just above) - ramps up smoothly over the first few
        // ticks, holds near peak, then fades back out over a longer stretch, so both the reach AND the
        // return read as real, gradual motion rather than a snap in either direction. Slower fade than
        // curve/overshoot (0.7 vs 0.35) since "essentially all the way" (now 85% of the real angular
        // difference, see beginRotateApproach) needs more real time to read as deliberate, not abrupt.
        if (rotateFeintYawOffset != 0f || rotateFeintPitchOffset != 0f) {
            float ft = rotateApproachElapsedTicks;
            float feintEnvelope = ft <= 4f ? (ft / 4f) : (float) Math.pow(0.7, ft - 4f);
            rawTargetYaw += rotateFeintYawOffset * feintEnvelope;
            rawTargetPitch += rotateFeintPitchOffset * feintEnvelope;
            if (feintEnvelope < 0.03f) {
                rotateFeintYawOffset = 0f;
                rotateFeintPitchOffset = 0f;
            }
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
        if (rotateApproachElapsedTicks > 2f && (Math.abs(yawDelta) > 20f || Math.abs(pitchDelta) > 20f)
                && rotateFeintYawOffset == 0f && rotateFeintPitchOffset == 0f) {
            // Excludes an active feint (see rotateFeintYawOffset's own doc comment) - that deliberately
            // creates a large mid-approach delta on purpose, which would otherwise look identical to the
            // real anomaly this diagnostic exists to catch.
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
        // Real bug found and fixed AGAIN (2026-09-14, killer560's own report: "it is waiting on buttons
        // really long before going to the next one" - confirmed as "camera sits frozen on the button, not
        // clicking"): requiring overshoot to be EXACTLY zero meant a tiny, visually-invisible residual
        // (anything down to the 0.05-degree hard-zero snap below) fully blocked firing even though the
        // camera already looked perfectly settled - exponential decay's long tail means crossing that
        // exact threshold can take noticeably longer than the motion stays visible. Loosened to "close
        // enough to gone" (0.3 degrees, still imperceptible) instead of demanding a hard zero.
        boolean settledNearCenter = Math.abs(yawDelta) < 1.5f && Math.abs(pitchDelta) < 1.5f
                && Math.abs(rotateOvershootYawRemaining) < 0.3f && Math.abs(rotateOvershootPitchRemaining) < 0.3f;
        // rotateClickFiredFor must be consumed first so two fires can never collapse into one booked click,
        // and a held Auto Start click waits for its exact spacing time (see rotateFireNotBeforeMs).
        boolean fireAllowed = rotateClickFiredFor == null && System.currentTimeMillis() >= rotateFireNotBeforeMs;
        if (settledNearCenter && fireAllowed
                && client.hitResult instanceof BlockHitResult hit && hit.getBlockPos().equals(buttonPos)) {
            // Real aim confirmed - the actual click still always lands on the button's true center
            // (killer560's own standing rule), same real click-sender every other mode already uses.
            sendNoRotateInteract(client, buttonPos);
            rotateClickFiredFor = buttonPos;
            rotateClickFiredAtMs = System.currentTimeMillis();
            // Diagnostic-only (2026-09-14) - once per real fire, covers both Auto Solve grid clicks and
            // Auto Start's look-only start-button clicks.
            LOGGER.info("[SimonSays][RotateFrame] Approach fired on {} {}ms after {} (elapsedTicks={}, "
                            + "overshootRolled={} curveRolled={} feintRolled={}, final yawDelta={} pitchDelta={}).",
                    buttonPos, diagApproachBeganAtMs > 0 ? rotateClickFiredAtMs - diagApproachBeganAtMs : -1,
                    diagApproachReaim ? "re-aim begin (same target, no roll)" : "approach begin",
                    String.format(Locale.US, "%.1f", rotateApproachElapsedTicks), diagApproachOvershootRolled,
                    diagApproachCurveRolled, diagApproachFeintRolled, String.format(Locale.US, "%.2f", yawDelta),
                    String.format(Locale.US, "%.2f", pitchDelta));
            rotateLastFiredTarget = buttonPos;
            rotateInProgressTarget = null;
            rotateApproachElapsedTicks = 0f;
            rotateApproachStallLogged = false;
            rotateFireNotBeforeMs = 0L;
            SimonSaysConfig spacingCfg = SimonSaysConfig.getInstance();
            // autoStartClicksSent only counts CONSUMED clicks, and the fireAllowed guard above guarantees the
            // previous one was consumed - so this fire is click autoStartClicksSent + 1 of the burst.
            if (buttonPos.equals(START_BUTTON) && autoStartRunning
                    && autoStartClicksSent + 1 < spacingCfg.getAutoStartClicks()) {
                rotateInProgressTarget = START_BUTTON;
                rotateFireNotBeforeMs = rotateClickFiredAtMs + spacingCfg.getAutoStartClickDelayTicks() * 50L;
                diagApproachBeganAtMs = rotateClickFiredAtMs;
                diagApproachReaim = true;
            }
        } else if (!rotateApproachStallLogged && rotateApproachElapsedTicks > 30f) {
            // Diagnostic-only - see rotateApproachStallLogged's own field doc comment. 30 ticks (~1.5s) is
            // already well beyond how long a real settle should ever take at the current turn speed.
            boolean raycastOnTarget = client.hitResult instanceof BlockHitResult hit2 && hit2.getBlockPos().equals(buttonPos);
            LOGGER.warn("[SimonSays][RotateFrame] Approach stuck without firing - target={} elapsedTicks={} "
                            + "yawDelta={} pitchDelta={} overshootYaw={} overshootPitch={} curveOn={} "
                            + "feintYaw={} feintPitch={} raycastOnTarget={} realHitPos={}.",
                    buttonPos, rotateApproachElapsedTicks, yawDelta, pitchDelta, rotateOvershootYawRemaining,
                    rotateOvershootPitchRemaining, rotateCurveOnThisApproach, rotateFeintYawOffset,
                    rotateFeintPitchOffset, raycastOnTarget,
                    client.hitResult instanceof BlockHitResult hit3 ? hit3.getBlockPos() : "none");
            rotateApproachStallLogged = true;
        }
    }

    /** Idle look at the real first grid button of the current sequence ("the 1/5 button essentially, or
     *  the first one from 2/5" - killer560's own clarification, NOT the literal start/reset button) with
     *  a slight resting sway, rather than a hard freeze. Two continuous sine waves at different
     *  frequencies, hard-clamped every frame to a real fraction of the button's own actual angular size
     *  (see the field-group doc comment above `idleSuppressedAfterCompletion` for the full real history
     *  behind this design). Falls back to
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
        // Real bug found and fixed (2026-09-14, killer560's own report: "please make the movement keep
        // the mouse towards the middle it still likes to drift off really far"): stillCatchingUp used to
        // compare the raw (no-sway) distance against a FIXED 3-degree threshold - fine when the sway's own
        // max amplitude is small, but for a button close enough that its real face fills a large angular
        // area, 25% of that real half-extent (maxSway, computed below) can itself exceed 3 degrees. Once
        // the camera settles onto rawTarget + one extreme of the sway, the raw (no-sway) distance reads as
        // "still far" every single frame even though it's just doing normal sway - permanently forcing the
        // FAST 0.35 approach smoothing instead of the slow 0.08 resting smoothing, so the sway itself got
        // dragged back and forth quickly across its whole range instead of gently wobbling - reading as a
        // real, fast drift rather than a small resting movement. maxSway is now computed FIRST and
        // stillCatchingUp compares against a multiple of it (never less than 3 degrees), so the sway's own
        // motion can never by itself trigger the fast-approach path - only a genuine real distance can.
        float maxSway = 0f;
        if (rememberedFirstButton != null) {
            float[] halfExtents = realButtonAngularHalfExtents(client, rememberedFirstButton, eyePos);
            // Real bug found and fixed (2026-09-14, "it still has a tendency to drift right up to the very
            // edge/corner"): yaw and pitch landing near their own max AT THE SAME TIME has a real diagonal
            // distance from center bigger than either axis alone suggests - tightened from 60% to 25% of
            // the real half-extent so even that worst case stays closer to the middle.
            // Real bug found and fixed AGAIN (2026-09-14, "for buttons up high my cursor still drifts off
            // of them"): projecting a real box into yaw/pitch space gets distorted at steep viewing angles
            // (a button well above eye level, similar to how spherical coordinates misbehave near the
            // poles) - one axis's own projected half-extent can come out larger than it should be for that
            // specific angle, even though the OTHER axis's projection is still accurate. Uses the SMALLER
            // of the two real half-extents for BOTH axes instead of each axis's own possibly-inflated
            // bound - a more conservative bound that stays safe even when one axis's projection is off.
            maxSway = Math.min(halfExtents[0], halfExtents[1]) * 0.25f;
        }
        float rawYawDeltaNoSway = Mth.wrapDegrees(rawTargetYaw - currentYaw);
        float rawPitchDeltaNoSway = Mth.wrapDegrees(rawTargetPitch - currentPitch);
        float catchUpThreshold = Math.max(3.0f, maxSway * 1.5f);
        boolean stillCatchingUp = Math.abs(rawYawDeltaNoSway) > catchUpThreshold
                || Math.abs(rawPitchDeltaNoSway) > catchUpThreshold;

        // Real bug found and fixed (2026-09-14, killer560's own report: "dont make it only go one way.
        // It should go down a little up a little may be sideways and whatnot"): the old discrete-twitch
        // model (random kick then fast decay, long pause, repeat) meant most of what was actually visible
        // in a short real observation window was just whichever one or two random directions happened to
        // roll - not a real spread of directions. Replaced with two continuous sine waves at DIFFERENT,
        // fairly fast frequencies for yaw vs pitch (a real Lissajous-style path) - the combined direction
        // of travel keeps changing (mostly horizontal one moment, mostly vertical the next, diagonal in
        // between) without ever settling into looking like it "only goes one way", and never pauses at a
        // single held offset the way the original 9-12-second sine period (Round 118) did either.
        double t = System.currentTimeMillis() / 1000.0;
        float swayYaw = (float) Math.sin(t * 2.6);
        float swayPitch = (float) Math.sin(t * 1.9 + 1.1);
        // Real bug found and fixed AGAIN (2026-09-14, killer560's own report: "it is still drifting up
        // too high and off of the face of the button. Make it so while drifting it cannot go off of the
        // face"): tuning a fixed degree amplitude was never a real guarantee - the same fixed value is
        // "safe" at one real distance/angle and "too much" at another. Hard-clamps the sway to a real
        // fraction of the button's own actual angular size as seen from the player's eye right now (see
        // realButtonAngularHalfExtents above) - a genuine geometric bound, not a tuned guess. The raw sine
        // amplitude above (±1 degree) is deliberately larger than any real clamp bound is ever likely to
        // be, so the clamp - not the raw generator - is what actually determines the real visible motion.
        if (rememberedFirstButton != null) {
            swayYaw = Mth.clamp(swayYaw, -maxSway, maxSway);
            swayPitch = Mth.clamp(swayPitch, -maxSway, maxSway);
        } else {
            swayYaw *= 0.03f;
            swayPitch *= 0.02f;
        }
        // Real bug found and fixed (2026-09-14, killer560's own request: "make the movement happen
        // sometimes on the waiting first button time and others itll more or less be exactly still"):
        // the sway used to run continuously with no variation in WHETHER it moves at all - a real resting
        // human sometimes fidgets and sometimes just holds still for a while. Toggles between a "moving"
        // phase (the sway above, as normal) and a "still" phase (sway zeroed, camera just holds on the
        // raw target) on a random timer, each phase lasting a random 0.8-4 real seconds.
        long nowMsForCalm = System.currentTimeMillis();
        if (nowMsForCalm >= idleSwayPhaseEndsAtMs) {
            idleSwayActive = !idleSwayActive;
            long durationMs = idleSwayActive ? (1500L + (long) (Math.random() * 2500.0))
                    : (800L + (long) (Math.random() * 2000.0));
            idleSwayPhaseEndsAtMs = nowMsForCalm + durationMs;
        }
        if (!idleSwayActive) {
            swayYaw = 0f;
            swayPitch = 0f;
        }

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
        return aimBoxFor(client, pos).getCenter();
    }

    /** The real box to aim at for {@code pos} - its live shape when it has one. For a grid button that's
     *  currently AIR (Hypixel removes all 16 between rounds - see {@link #lastSeenGridButtonBoxes}'s own
     *  doc comment for the real "drifting off the button" bug this fixes), the box that button last really
     *  had, or vanilla's own unpressed west-facing wall button box (confirmed from 26.1.2's ButtonBlock:
     *  6x4x2 px, flush against the lantern wall at x=111 - the same spot this class's own highlight box
     *  already draws) if it was never seen. Never the full block, which is ~0.44 blocks off. */
    private static AABB aimBoxFor(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        var shape = state.getShape(client.level, pos);
        boolean gridButton = GRID_BUTTONS.contains(pos);
        if (!shape.isEmpty()) {
            AABB box = shape.bounds().move(pos);
            if (gridButton && state.is(Blocks.STONE_BUTTON)) {
                lastSeenGridButtonBoxes.put(pos.immutable(), box);
            }
            return box;
        }
        if (gridButton) {
            AABB seen = lastSeenGridButtonBoxes.get(pos);
            if (seen != null) {
                return seen;
            }
            return new AABB(pos.getX() + 14.0 / 16.0, pos.getY() + 6.0 / 16.0, pos.getZ() + 5.0 / 16.0,
                    pos.getX() + 1.0, pos.getY() + 10.0 / 16.0, pos.getZ() + 11.0 / 16.0);
        }
        return new AABB(pos);
    }

    /** Real yaw/pitch (in degrees) from {@code eyePos} to look directly at {@code target} - the same
     *  atan2-based math already duplicated across {@link #applyRotateApproachFrame}/{@link
     *  #applyIdleSwayFrame}, factored out here so {@link #beginRotateApproach}'s feint calculation (see
     *  {@link #rotateFeintYawOffset}'s own doc comment) can reuse it for a second point without
     *  duplicating it a third time. */
    private static float[] yawPitchTo(Vec3 eyePos, Vec3 target) {
        Vec3 diff = target.subtract(eyePos);
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw = (float) (Mth.atan2(diff.z, diff.x) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) -(Mth.atan2(diff.y, horizontalDist) * (180.0 / Math.PI));
        return new float[] {yaw, pitch};
    }

    /** Real bug found and fixed (2026-09-14, killer560's own report: "the drift brought the cursor off
     *  of the face of it... make it so while drifting it cannot go off of the face"): tuning the idle
     *  twitch's fixed degree amplitude down (twice) still weren't a real guarantee - a real button's own
     *  clickable face can appear smaller or larger depending on real distance/viewing angle, so no single
     *  fixed degree value is ever truly safe at every real position. This instead measures the button's
     *  own real angular size AS SEEN FROM THE PLAYER'S EYE right now: projects all 8 corners of its real
     *  shape (same real shape {@link #realBlockCenter} already reads, so it's exactly the same box, not
     *  an approximation) into yaw/pitch space and returns half the real spread in each - a hard geometric
     *  bound the sway can be clamped against, not just a tuned guess. */
    private static float[] realButtonAngularHalfExtents(Minecraft client, BlockPos pos, Vec3 eyePos) {
        AABB box = aimBoxFor(client, pos);
        double minYaw = Double.POSITIVE_INFINITY;
        double maxYaw = Double.NEGATIVE_INFINITY;
        double minPitch = Double.POSITIVE_INFINITY;
        double maxPitch = Double.NEGATIVE_INFINITY;
        for (int cx = 0; cx < 2; cx++) {
            double x = cx == 0 ? box.minX : box.maxX;
            for (int cy = 0; cy < 2; cy++) {
                double y = cy == 0 ? box.minY : box.maxY;
                for (int cz = 0; cz < 2; cz++) {
                    double z = cz == 0 ? box.minZ : box.maxZ;
                    double dx = x - eyePos.x;
                    double dy = y - eyePos.y;
                    double dz = z - eyePos.z;
                    double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                    double yaw = Mth.atan2(dz, dx) * (180.0 / Math.PI) - 90.0;
                    double pitch = -(Mth.atan2(dy, horizontalDist) * (180.0 / Math.PI));
                    minYaw = Math.min(minYaw, yaw);
                    maxYaw = Math.max(maxYaw, yaw);
                    minPitch = Math.min(minPitch, pitch);
                    maxPitch = Math.max(maxPitch, pitch);
                }
            }
        }
        return new float[] {(float) ((maxYaw - minYaw) / 2.0), (float) ((maxPitch - minPitch) / 2.0)};
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
        diagNoteGridClickFired(pos, System.currentTimeMillis());
    }

    /** Diagnostic-only (2026-09-14) - remembers a synthetic GRID click's fire time so onButtonPressed can
     *  log its server-confirmation latency; warns if the previous one was never confirmed at all. */
    private static void diagNoteGridClickFired(BlockPos pos, long firedAtMs) {
        if (!GRID_BUTTONS.contains(pos)) {
            return;
        }
        if (pos.equals(diagPendingConfirmButton)) {
            diagPendingConfirmFires++;
            diagPendingConfirmFiredAtMs = firedAtMs;
            return;
        }
        if (diagPendingConfirmButton != null) {
            LOGGER.warn("[SimonSays] Click on {} never got a server-confirmed POWERED edge ({} fire(s), last {}ms ago) "
                            + "- now firing on {}.", diagPendingConfirmButton, diagPendingConfirmFires,
                    firedAtMs - diagPendingConfirmFiredAtMs, pos);
        }
        diagPendingConfirmButton = pos.immutable();
        diagPendingConfirmFiredAtMs = firedAtMs;
        diagPendingConfirmFirstFiredAtMs = firedAtMs;
        diagPendingConfirmFires = 1;
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

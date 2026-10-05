package com.killer560.hub.experiments;

import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Solves the Experimentation Table's three minigames (Chronomatron, Ultrasequencer, Superpairs) -
 * pure logic, no Minecraft classes touched here (see {@link ExperimentsFeature} for the actual
 * screen-reading/clicking). Ported from the real, working, MIT-licensed
 * <a href="https://github.com/AzureSky0116/astrail-experiment">astrail-experiment</a> mod (targets
 * MC 26.2/Fabric - close enough to our 26.1.2 that every API it uses was independently re-verified
 * against our own decompiled jar via javap before porting, not assumed identical), cross-checked
 * against SkyHanni's real container-title regex and per-tier slot ranges
 * ({@code ExperimentationTableApi.kt}) for the parts astrail's own README didn't spell out in
 * detail. Algorithm, not guessed:
 * <ul>
 *   <li><b>Chronomatron</b> - slot 49 is the control slot: {@code glowstone} means "get ready"
 *   (reset this round's click progress), {@code clock} means "your turn" - the newly revealed note
 *   is the one enchant-glinting (foil) item in slots 10-43; append it to the running memorized
 *   sequence (only once per reveal, latched), then click back the WHOLE sequence in order, one
 *   click per solver call.</li>
 *   <li><b>Ultrasequencer</b> - also slot 49 control, but the whole sequence is revealed at once:
 *   on {@code glowstone}, every dye/lapis/bone-meal item in slots 9-44 is a note, and its stack
 *   COUNT (minus 1) is its position in the sequence - sort by count to get replay order. On
 *   {@code clock}, click the slots back in that order.</li>
 *   <li><b>Superpairs</b> - a REAL memory-match: unlike the other two games, tile identities are
 *   genuinely hidden behind a covering item (glass pane) until clicked, confirmed field-tested
 *   (2026-09-06) after the solver sat idle on a real board because it assumed everything was already
 *   visible. Scans slots 9-44 in a full snake/boustrophedon order (row 1 left-to-right, row 2
 *   right-to-left, ...): first, remembers every tile identity learned so far (from a reveal-click or
 *   already-visible); a discovered-but-unactivated bonus tile (real confirmed effect: bonus clicks
 *   plus an automatic match on the very next click - see {@code isPowerupTile}) is activated before
 *   anything else, spending its free match on the best known target; otherwise, if two known tiles
 *   share an identity, queues both to be clicked; otherwise clicks one still-covered tile to learn
 *   what's under it. Optionally restricted to skip the plain "Experience" reward tiles
 *   ({@code superpairsValuableOnly}) - those always render as a dye-family item, unlike every other
 *   reward category (books, misc items, pet heads, bottles, ...) which varies too much to whitelist -
 *   since Superpairs clicks are a limited resource. Once every tile is discovered and nothing else is
 *   left to explore or match, falls back to matching the highest-count skipped dye pairs rather than
 *   wasting remaining clicks. Since 2026-10-05 it plays in turns of two clicks (a pair is only ever
 *   started when no tile is turned over), reads which tiles are claimed off the board, reads
 *   "Remaining Clicks", orders rewards money, then skipped kinds, then XP by amount, and counts the
 *   board to deduce where a partner or a powerup must be - see {@code BoardFacts}.</li>
 * </ul>
 * <p>
 * Per killer560's request, Chronomatron/Ultrasequencer each get an extra one-time delay
 * ({@code firstClickDelayMs}) before the FIRST click of every newly-revealed round specifically
 * (round 1's first click, round 2's first click, and so on) - not just once for the whole game.
 * Every click after that first one in the same round uses the normal {@code delayMillis} pacing.
 * <p>
 * Field-tested design decision (2026-09-06): the "learn what's actually on screen" logic for each
 * game (see the {@code observe*} methods) is kept completely separate from the "decide what to click"
 * logic (the {@code decide*} methods) - {@link #nextClick} calls both (observe, then decide) for the
 * real auto-clicking bot path, while {@link #observe} alone is exposed for Solver-Only's highlight-only
 * mode, which needs the exact same tested game-state tracking to know what's correct, but must never
 * call {@code MultiPlayerGameMode.handleContainerInput(...)} itself - it only highlights the correct
 * slot(s) on screen, the same way SkyHanni's own "Next Click Helper" does, so a human choosing to use
 * only that mode is doing 100% real manual clicking, not running a macro.
 */
final class ExperimentSolver {

    private static final Logger LOGGER = ModLog.get("killer560smod-experiments-solver");

    enum Mode { NONE, CHRONOMATRON, ULTRASEQUENCER, SUPERPAIRS }

    record Cell(int slot, String itemId, int count, boolean foil, String name, boolean empty, String lore) {
    }

    private Mode mode = Mode.NONE;
    private final List<Integer> chronomatron = new ArrayList<>();
    private boolean chronomatronRevealLatched;
    private int chronomatronClickIndex;
    private long chronomatronRoundReadyAtMs;
    private final Map<Integer, Integer> ultrasequencer = new HashMap<>();
    private int ultrasequencerClickIndex;
    private boolean ultrasequencerReady;
    private long ultrasequencerRoundReadyAtMs;
    private final java.util.Deque<Integer> pairClicks = new ArrayDeque<>();
    /** The FIRST click of a pair whose partner is still waiting in {@link #pairClicks}, or null. If that click
     *  times out with the tile still covered, it did not land, and clicking the partner anyway turns a sure
     *  pair into a miss - which is how "Experiment the Fish" (slots 20/40) was lost in his 2026-10-01 09:33
     *  run: 20 never turned over, the solver clicked 40, then an unrelated 38, and Hypixel paid neither. */
    private Integer superpairsPairFirstSlot;
    private final Map<Integer, Integer> superpairsPairRetries = new HashMap<>();
    /** Re-clicks allowed for a pair's first tile. A re-click on a tile that did turn over costs nothing
     *  (killer560, 2026-10-01: clicking an uncovered tile does not use a click). */
    private static final int SUPERPAIRS_PAIR_FIRST_RETRIES = 2;
    private final Set<Integer> queuedPairSlots = new HashSet<>();
    /** Field-tested (2026-09-06): unlike Chronomatron/Ultrasequencer, Superpairs tiles are genuinely
     *  hidden behind a covering item (glass pane) until clicked - killer560 confirmed the solver never
     *  clicked any of them to start revealing things. This remembers every slot whose real identity
     *  has been learned so far (from a reveal-click OR simply being visible), persisting across ticks
     *  so a match found long after the fact still counts.
     *  <p>
     *  Simplified (2026-09-07) per killer560's "cant you just have it check is this xp if yes then skip if
     *  no then its valuable and something i need to match?" - exactly right, and cleaner than what this
     *  used to be: a SECOND map ({@code knownDyeCells}) existed purely because dye/XP tiles used to be
     *  filtered OUT of this one entirely whenever {@code superpairsValuableOnly} was on, so they had
     *  nowhere else to live for the "board fully explored, fall back to XP" case or the highlight
     *  overlay - both of which then needed to separately merge the two maps back together. Now this one
     *  map ALWAYS remembers every known tile unconditionally (dye/XP included), and the one "is this
     *  worth matching as a priority, or should it wait until nothing else is left" question is answered
     *  once, inline, at each place that actually needs to decide - see {@link #isValuablePair}. */
    private final Map<Integer, Cell> knownSuperpairsCells = new HashMap<>();
    /** Slots already clicked once to reveal them - skipped by future reveal attempts even if they
     *  never actually turned into a real item (e.g. genuinely decorative border glass), so the solver
     *  doesn't loop forever re-clicking a dead slot. */
    private final Set<Integer> superpairsRevealAttempted = new HashSet<>();
    /** Slot of a discovered-but-not-yet-activated Superpairs bonus tile (real confirmed effect:
     *  grants bonus clicks and makes the very next click an automatic match) - null if none known. */
    private Integer superpairsPowerupSlot;
    /** True for exactly one solve() call right after activating a bonus tile - the next click should
     *  be aimed at the best known target instead of the normal priority order. */
    private boolean superpairsPowerupPending;
    /** Real bug found and fixed (2026-09-09) from killer560's report of the solver getting stuck
     *  alternating between the same two slots forever - set to whichever slot was just clicked to
     *  ACTIVATE a bonus tile (as opposed to a normal reveal/pair-completion click), cleared the instant
     *  that click's confirm-wait resolves. A powerup tile's own item never visibly changes when
     *  clicked (it's a persistent activatable button, not a card that flips or disappears), so its
     *  confirm ALWAYS times out - which used to make {@link #observeSuperpairs}'s timeout-cleanup (see
     *  round 22's queuedPairSlots fix) free it from {@link #queuedPairSlots} every single time,
     *  re-exposing the exact same tile as "not yet activated" on the very next scan and clicking it
     *  again forever. Recording which slot this was lets that cleanup skip freeing it specifically,
     *  while still freeing genuinely-stuck PAIR-completion clicks like it's meant to. */
    private Integer superpairsPowerupActivationSlot;
    /** Real bug found and fixed (2026-09-24) from killer560's report: the solver paired a Titanic
     *  Experience Bottle with a Grand one, then later found the real second Titanic but re-clicked
     *  the first one's old partner instead of pairing them, and "kept going down the line not pairing
     *  it" for the rest of the round. Root cause: {@link #clickAnyRemainingKnownTile} (the "spend this
     *  click on literally anything known" last-resort fallback, added per killer560's "it gets stuck
     *  with only 1 click left so just have it click any of them") has no real partner in mind for the
     *  single slot it clicks - it exists purely so an otherwise-unused click doesn't go to waste, not
     *  to complete an actual pair - yet it still added that slot to {@link #queuedPairSlots} the same
     *  way a genuine pair-completion click does. Every OTHER place a queued slot gets freed again only
     *  does so on a genuine confirm TIMEOUT (see the cleanup in {@link #observeSuperpairs}), but
     *  re-clicking an ALREADY-known tile almost always DOES produce a visible state change (hidden ->
     *  revealed again), so it "confirms" normally instead of timing out and is never freed -
     *  permanently blacklisting that slot from {@link #knownSuperpairsCells}'s byKey pairing scan
     *  (which skips anything in {@code queuedPairSlots}) for the rest of the round, even once its real
     *  partner is discovered later. That's exactly what made the real second Titanic unpairable: the
     *  first Titanic had already been spent this way earlier (visible from the outside as "it clicked a
     *  Titanic then a Grand back to back," since a lone spend click looks identical to a real queued
     *  pair click one tick apart), so the byKey scan could never see both Titanic slots at once again.
     *  This records which slot was spent that way so {@link #observeSuperpairs} can free it the moment
     *  its click resolves - confirmed or not - since a lone last-resort spend can never complete a real
     *  match by itself regardless of outcome, unlike every other queuedPairSlots entry. */
    private Integer superpairsSingleSpendSlot;
    /** Every slot ever seen showing a powerup, so a powerup tile that covers back up is never counted as a
     *  hidden reward tile by {@link #readBoard} (only one powerup at a time lives in {@link #superpairsPowerupSlot}). */
    private final Set<Integer> superpairsPowerupSlotsSeen = new HashSet<>();
    /** When each slot was last clicked by the solver - picks which of a kind's revealed tiles is the turn's open
     *  one when a kind has three revealed (a claimed pair plus one just turned over). */
    private final Map<Integer, Long> superpairsClickedAtMs = new HashMap<>();
    /** Since when two unpaired tiles have been showing at once (a missed pair waiting to cover back up), or 0. */
    private long superpairsTwoOpenSinceMs;
    /** How long to wait for a missed pair to cover back up before acting anyway, so a board this solver misreads
     *  can slow it down but never stop it. Hypixel covers them back within a tick or two; the testkit's table in 10. */
    private static final long SUPERPAIRS_TWO_OPEN_MAX_WAIT_MS = 2500;
    /** Diagnostics already logged this board (a kind known twice but not queued, a deduction that fired), so each
     *  is logged once rather than every tick. */
    private final Set<String> superpairsLoggedOnce = new HashSet<>();
    /** True once the "two unpaired tiles stayed up" warning was logged for the current wait. */
    private boolean superpairsTwoOpenWarned;
    /** Per killer560's request: scan the grid top-left to bottom-right in a full snake/boustrophedon
     *  pattern (row 1 left-to-right, row 2 right-to-left, and so on) rather than a flat row-major
     *  scan, so pairs get queued in that visible order. */
    private static final List<Integer> SUPERPAIRS_SNAKE_ORDER = buildSuperpairsSnakeOrder();
    /** The slot most recently clicked by the real auto-click path, together with a snapshot of exactly
     *  what that slot looked like the instant the click was decided - used to detect when Hypixel's own
     *  real reveal/resolve animation for that click has genuinely finished, instead of guessing a fixed
     *  delay. Per killer560's explicit "I dont want to program forced delay i want it to auto detect for
     *  this then add in the random delay" (2026-09-06) - field-tested proof this was needed: the
     *  previous fixed {@code delayMillis} pacing was too fast for the real animation, so the solver
     *  clicked a 3rd tile while the first two were still resolving, throwing off the snake-order
     *  iteration and landing on the WRONG (4th) tile next. Null while nothing is pending confirmation -
     *  see {@link #observeSuperpairs} (clears it once the slot's real state changes) and
     *  {@link #decideSuperpairsClick} (refuses to click again while it's set). The existing random-
     *  delay jitter still applies on top, unchanged - that's handled entirely in
     *  {@code ExperimentsFeature.scheduleClick}, downstream of whatever this solver decides. */
    private Integer superpairsAwaitingConfirmSlot;
    private Cell superpairsAwaitingConfirmPriorCell;
    /** Real bug found and fixed (2026-09-07) from killer560's report "it would wait about 3s then click a
     *  bunch all at once in rapid succession": {@link #superpairsAwaitingConfirmSinceMs} used to be
     *  stamped the instant a click was DECIDED, inside {@link #decideSuperpairsClick} - but the actual
     *  click doesn't fire then, it goes through {@code ExperimentsFeature.scheduleAction}'s own
     *  0..randomDelayMaxMs jitter first. Whenever that jitter was anywhere near or above the 3s
     *  timeout, the solver gave up waiting on a click that was never even sent yet, decided on a new
     *  slot, and queued that one too - repeatedly, every tick - so several real clicks piled up in the
     *  jitter queue and fired back-to-back once their independent random delays elapsed. This flag (and
     *  {@link #superpairsClickSent}) decouples "decided, waiting to be sent" from "sent, waiting to be
     *  confirmed" - the timeout clock only starts once {@link #superpairsClickSent} is actually called,
     *  from the real click execution, not the decision. */
    private boolean superpairsClickSent;
    /** When {@link #superpairsAwaitingConfirmSlot} was set - real bug found and fixed (2026-09-06) from
     *  a real log: with no timeout at all, a single dropped/ignored click (real server lag, or Hypixel
     *  simply not registering that one input) left the gate permanently set, since nothing else could
     *  ever clear it - the solver clicked exactly once and then sat there doing NOTHING for the rest of
     *  the round (confirmed from a real log: one "Clicking slot 9" line, then total silence for 31
     *  seconds until the round timed out on its own). This is also the "combat server lag" mechanism
     *  killer560 asked for: it waits exactly as long as the real animation/round-trip actually takes under
     *  normal conditions, but never waits forever - past {@link #SUPERPAIRS_CONFIRM_TIMEOUT_MS} it gives
     *  up waiting and lets the next decision proceed fresh, rather than staying stuck. */
    private long superpairsAwaitingConfirmSinceMs;
    // Lowered from 3000 (2026-09-07) per killer560's "3s is very long" - a real log confirmed every
    // legitimately-confirmed reveal resolves within ~1s (slot 10 instant, slot 11 ~1s, several more
    // within a second each), while the 3000ms timeouts observed in that same log hit on clicks whose
    // state genuinely never changed at all across the full wait, not just slow ones. So 1000ms cuts the
    // wasted wait on truly-dead clicks without risking cutting off a real still-in-flight confirm.
    // Adaptive Timeout (reworked 2026-10-04, killer560: "it should sense when the server is lagging and
    // auto delay by that amount until it stops lagging") no longer replaces this value - it ADDS to it
    // whatever time the server spent stalled since the click went out. See #superpairsConfirmTimeoutMs.
    private static final long SUPERPAIRS_CONFIRM_TIMEOUT_MS = 1000;
    /** Ceiling on the lag Adaptive Timeout adds, so a dead connection (no pings at all, every millisecond
     *  of which reads as stall) still gives up eventually rather than waiting forever. */
    private static final long SUPERPAIRS_MAX_LAG_EXTENSION_MS = 5000;
    /** {@link ServerLagSensor#lagMs()} at the moment the awaited click was sent; the difference from the
     *  current reading is how long the server has stalled while this click waited. */
    private long superpairsLagAtSendMs;
    /** Real timestamp the most recently SENT Superpairs click actually went out - per killer560's explicit
     *  request (2026-09-07) after seeing genuinely-instant-confirmed reveals/matches fire back-to-back
     *  in the same second: even though that's not a bug (it's the confirm-based "click again the
     *  moment it's confirmed" pacing he originally asked for), he decided it still needs the same
     *  minimum Click Delay (plus its own random jitter on top, same as every other click) that
     *  Chronomatron/Ultrasequencer already respect, purely so it never looks like multiple clicks
     *  fired in the same instant. Deliberately keyed off SEND time via {@link #superpairsClickSent},
     *  not decision time, for the exact same reason {@link #superpairsAwaitingConfirmSinceMs} is -
     *  gating on decision time would let this race against ExperimentsFeature's own jitter again. */
    private long superpairsLastClickSentAtMs;

    private static List<Integer> buildSuperpairsSnakeOrder() {
        List<Integer> order = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            boolean leftToRight = row % 2 == 1;
            if (leftToRight) {
                for (int col = 0; col <= 8; col++) order.add(row * 9 + col);
            } else {
                for (int col = 8; col >= 0; col--) order.add(row * 9 + col);
            }
        }
        return List.copyOf(order);
    }

    Mode select(String title) {
        Mode selected;
        if (title.contains("Chronomatron") && !title.contains("Sta")) selected = Mode.CHRONOMATRON;
        else if (title.contains("Ultrasequencer") && !title.contains("Sta")) selected = Mode.ULTRASEQUENCER;
        else if (title.startsWith("Superpairs (")) selected = Mode.SUPERPAIRS;
        else selected = Mode.NONE;
        if (selected != mode) reset(selected);
        return mode;
    }

    /** Current memorized chain length for Chronomatron/Ultrasequencer (0 for Superpairs/NONE) -
     *  lets {@link ExperimentsFeature} know when autonomous mode should stop grinding a round and
     *  back out instead of continuing to solve it. */
    int currentChainLength() {
        return switch (mode) {
            case CHRONOMATRON -> chronomatron.size();
            case ULTRASEQUENCER -> ultrasequencer.size();
            case SUPERPAIRS, NONE -> 0;
        };
    }

    /** Updates internal knowledge from the current game state only - never decides on or returns a
     *  click. Shared by both {@link #nextClick} (the real auto-clicking bot path) and Solver-Only's
     *  highlight-only path (see class doc), so highlighting always reflects the exact same tested
     *  state-tracking the bot itself relies on. */
    void observe(List<Cell> cells, boolean superpairsValuableOnly, long now) {
        switch (mode) {
            case CHRONOMATRON -> observeChronomatron(cells, now);
            case ULTRASEQUENCER -> observeUltrasequencer(cells, now);
            case SUPERPAIRS -> observeSuperpairs(cells, superpairsValuableOnly, now);
            case NONE -> {
            }
        }
    }

    /** @return the very next slot the bot itself is about to click for Chronomatron, or -1 if none
     *  (round not latched in yet, or the whole current sequence has already been clicked) - for the
     *  highlight overlay to show exactly what to click right now (green), the way SkyHanni's own Next
     *  Click Helper does. Per killer560's explicit request (2026-09-06): shows only the next click and the
     *  one after it, not the whole accumulated history with numbers. */
    int chronomatronNextClickSlot() {
        if (!chronomatronRevealLatched || chronomatronClickIndex >= chronomatron.size()) {
            return -1;
        }
        return chronomatron.get(chronomatronClickIndex);
    }

    /** @return the slot to click AFTER {@link #chronomatronNextClickSlot()} - for the highlight overlay
     *  to show one step ahead (orange), or -1 if there isn't one yet. */
    int chronomatronFollowingClickSlot() {
        if (!chronomatronRevealLatched || chronomatronClickIndex + 1 >= chronomatron.size()) {
            return -1;
        }
        return chronomatron.get(chronomatronClickIndex + 1);
    }

    /** @return the very next slot the bot itself is about to click for Ultrasequencer, or -1 if none is
     *  known yet at that position - same "next click only" highlight design as Chronomatron, per
     *  killer560's explicit request (2026-09-06). Unlike {@link #decideUltrasequencerClick}, this doesn't
     *  also require the control slot to currently show the clock - the whole sequence is disclosed up
     *  front for this game, so showing where the NEXT click will land is useful even during the
     *  "glowstone" reveal phase, before it's actually time to click. */
    int ultrasequencerNextClickSlot() {
        return ultrasequencer.getOrDefault(ultrasequencerClickIndex, -1);
    }

    /** @return the slot to click AFTER {@link #ultrasequencerNextClickSlot()}, or -1 if there isn't one
     *  known yet. */
    int ultrasequencerFollowingClickSlot() {
        return ultrasequencer.getOrDefault(ultrasequencerClickIndex + 1, -1);
    }

    /** Per killer560's report (2026-09-08): Solver Only's Chronomatron highlight froze on the same slot no
     *  matter what he actually clicked, because {@link #chronomatronClickIndex} only ever advances
     *  inside {@link #decideChronomatronClick} - the AUTONOMOUS click path - which Solver Only never
     *  calls at all. This is the Solver-Only equivalent: called from a real mouse click on the
     *  container (see {@code ExperimentsFeature#shouldBlockManualMisclick}), advances the tracked index
     *  when the click matches, so the highlight stays in sync with killer560's own clicks instead of
     *  never moving.
     *  <p>
     *  Matches by real ITEM (color) within the same COLUMN as the expected slot, not the exact recorded
     *  slot - a note can render as a multi-row run of identical-colored blocks in that one column (see
     *  {@link com.killer560.hub.experiments.ExperimentsFeature#superpairsGhostIcon}'s sibling fix,
     *  {@code highlightMatchingChronomatronSlots}), and clicking ANY block in that run is equally
     *  correct, not just the one slot the solver happened to record.
     *  <p>
     *  Real bug found and fixed (2026-09-08), per killer560's report ("wanted me to immediately click it
     *  twice" after a gap): matching by color ALONE, with no column check, also accepted a click on a
     *  completely different note elsewhere on the board that just happens to reuse the same block color
     *  for a later/earlier sequence position - not an actual same-note run. Added the column restriction
     *  (a real run never spans columns) so an unrelated same-colored note in another column can no
     *  longer be mistaken for - or silently satisfy - the current step.
     *  <p>
     *  Real bug found and fixed (2026-09-08), per killer560's report that clicking the correct block,
     *  then the next correct one in order, then the next, could still fail partway through: a min-gap
     *  timer here (since removed) was blocking a legitimately fast SECOND correct click if it landed
     *  within the gap window, mistaking normal skilled fast play for a duplicate. Checked SkyHanni's own
     *  real (decompiled) ExperimentsAddonsHelper.handleChronomatronClick/handleUltrasequencerClick for
     *  comparison per killer560's request - it uses NO timing gate at all, purely comparing each click
     *  against {@code expected[userProgress.size()]}; a genuine duplicate/double-fired click naturally
     *  fails to match once the index has already advanced past it, without needing to guess a timing
     *  threshold at all. This method already worked exactly that way underneath the now-removed gate
     *  ({@code chronomatronClickIndex} only ever advances on a real match), so removing the gate just
     *  stops it from also rejecting fast, genuinely correct clicks.
     *  @return true (and advances the index) if {@code slot} matches the expected next color in the same
     *  column, false (no state change) otherwise. */
    boolean confirmManualChronomatronClick(int slot, List<Cell> cells) {
        if (!chronomatronRevealLatched || chronomatronClickIndex >= chronomatron.size()) {
            return false;
        }
        int expectedSlot = chronomatron.get(chronomatronClickIndex);
        Cell expected = cell(cells, expectedSlot);
        Cell clicked = cell(cells, slot);
        if (expectedSlot % 9 != slot % 9) {
            return false;
        }
        if (expected == null || clicked == null || expected.empty() || clicked.empty()) {
            return false;
        }
        if (!expected.itemId().equals(clicked.itemId())) {
            return false;
        }
        chronomatronClickIndex++;
        return true;
    }

    /** Ultrasequencer equivalent of {@link #confirmManualChronomatronClick} - each sequence position
     *  maps to exactly one unique slot here (no repeated-color runs like Chronomatron), so this matches
     *  by exact slot rather than by item.
     *  <p>
     *  Real bug found and fixed (2026-09-08), per killer560's explicit request that clicking shouldn't
     *  do anything "until the numbers turn back into panes": this never checked the control slot (49)
     *  at all, unlike the autonomous path ({@link #decideUltrasequencerClick}), which correctly only
     *  ever clicks while it shows the clock (the real "now solve it" phase, panes covering the board
     *  again) - not the glowstone phase right after, where the panes still show their real numbers.
     *  {@code ultrasequencer} itself gets populated the moment glowstone appears (that's the only time
     *  the numbers are actually readable to learn the sequence from), so a click matching the right slot
     *  during THAT phase was being confirmed too, even though the round hadn't reached the solvable
     *  phase yet. */
    boolean confirmManualUltrasequencerClick(int slot, List<Cell> cells) {
        Cell control = cell(cells, 49);
        if (control == null || !control.itemId().equals("minecraft:clock")) {
            return false;
        }
        Integer expected = ultrasequencer.get(ultrasequencerClickIndex);
        if (expected == null || expected != slot) {
            return false;
        }
        ultrasequencerClickIndex++;
        return true;
    }

    /** @return every currently-known Superpairs match, trusting {@link #knownSuperpairsCells} as
     *  PERMANENT memory - a tile flipping back to hidden (the normal "peek, then cover back up if not
     *  immediately matched" memory-game mechanic) does NOT make the mod forget what's under it, exactly
     *  matching how {@link #decideSuperpairsClick}'s own pair-queueing already works (it never rechecks
     *  current visibility either - see real bug found and fixed 2026-09-06, per killer560's "will it show
     *  a hidden item... in perpetuity" - the ORIGINAL version of this method required
     *  {@code isRevealedPair(current)}, which made the highlight incorrectly disappear the instant a
     *  learned tile went back to hidden, even though the bot-click path never had that limitation at
     *  all). Only a slot whose current cell is completely EMPTY is excluded, as a proxy for "this pair
     *  was already claimed and removed from the board" - every other current state (still covered by
     *  glass/clock/glowstone, or still showing the same revealed item) keeps counting. Shows XP/dye
     *  matches too, same as everything else - display never filters by {@code superpairsValuableOnly},
     *  only click PRIORITY does (see {@link #isValuablePair}). */
    List<int[]> superpairsKnownMatches(List<Cell> cells) {
        Map<Integer, Cell> bySlot = new HashMap<>();
        for (Cell cell : cells) {
            bySlot.put(cell.slot(), cell);
        }
        Map<String, List<Integer>> byKey = new HashMap<>();
        for (Map.Entry<Integer, Cell> entry : knownSuperpairsCells.entrySet()) {
            int slot = entry.getKey();
            Cell current = bySlot.get(slot);
            if (current == null || current.empty()) continue;
            Cell known = entry.getValue();
            byKey.computeIfAbsent(pairKey(known), k -> new ArrayList<>()).add(slot);
        }
        List<int[]> matches = new ArrayList<>();
        for (List<Integer> slots : byKey.values()) {
            if (slots.size() >= 2) {
                matches.add(new int[]{slots.get(0), slots.get(1)});
            }
        }
        return matches;
    }

    /** @return the subset of {@link #superpairsKnownMatches} that are actually LINKED/found, not just
     *  known - per killer560's request (2026-09-08) to color a genuinely completed pair differently
     *  from one the solver merely remembers the identity of. A real match stays revealed together
     *  permanently once made; two remembered-matching tiles are almost never BOTH currently revealed at
     *  once unless the pair was actually just completed (an unmatched peek covers back up again within
     *  a tick or two of the second tile being revealed), so "both currently revealed right now" is a
     *  reliable proxy for a real, confirmed match without needing an explicit signal from Hypixel. */
    List<int[]> superpairsConfirmedMatches(List<Cell> cells) {
        Map<Integer, Cell> bySlot = new HashMap<>();
        for (Cell cell : cells) {
            bySlot.put(cell.slot(), cell);
        }
        List<int[]> confirmed = new ArrayList<>();
        for (int[] match : superpairsKnownMatches(cells)) {
            Cell a = bySlot.get(match[0]);
            Cell b = bySlot.get(match[1]);
            if (a != null && b != null && isRevealedPair(a) && isRevealedPair(b)) {
                confirmed.add(match);
            }
        }
        return confirmed;
    }

    /** @return every slot Solver-Only mode currently knows the identity of (matched or not, covered or
     *  not) mapped to its real display name - per killer560's "tell me what is under it in case I manually
     *  have to take over to get a super rare or something." Same permanent-memory trust as
     *  {@link #superpairsKnownMatches} (only a completely EMPTY current slot is excluded, as a proxy
     *  for "already claimed"); this covers BOTH confirmed pairs and still-unmatched singles, since a
     *  human wanting to grab something specific needs to see it labeled even before its partner turns
     *  up. Shows XP/dye tiles too - per killer560's "have the solver display even the exp nodes not just
     *  other things besides exp" (2026-09-07): display never filters by click priority. */
    Map<Integer, String> superpairsKnownItemNames(List<Cell> cells) {
        Map<Integer, Cell> bySlot = new HashMap<>();
        for (Cell cell : cells) {
            bySlot.put(cell.slot(), cell);
        }
        Map<Integer, String> names = new HashMap<>();
        for (Map.Entry<Integer, Cell> entry : knownSuperpairsCells.entrySet()) {
            int slot = entry.getKey();
            Cell current = bySlot.get(slot);
            if (current == null || current.empty()) continue;
            Cell known = entry.getValue();
            // Per killer560's request (2026-09-07): XP/dye tiles should just show the XP number, not the
            // full "Enchanting Exp" text - real log confirms the display name is literally e.g. "131k
            // Enchanting Exp"/"38k Enchanting Exp" (the amount is embedded in the name text itself, NOT
            // the stack count - a real log caught that assumption before it shipped), so this just strips
            // the trailing "Enchanting Exp"/"Enchanting XP" words and keeps the leading amount token.
            names.put(slot, isSuperpairsXpTile(known) ? xpAmountLabel(known.name()) : itemLabel(known));
        }
        return names;
    }

    private static final java.util.regex.Pattern BOOK_WRAPPER =
            java.util.regex.Pattern.compile("^Enchanted Book \\((.+)\\)$");
    /** An enchant line from a book's lore: "Power VI", "Ultimate Wise V", "Turbo-Wheat 5". */
    private static final java.util.regex.Pattern ENCHANT_LINE =
            java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z' -]{0,40}?) ([IVXLC]{1,7}|\\d{1,2})$");

    /** The label worth showing for a non-XP tile. killer560, 2026-10-04: "If something like power 6 shows up
     *  in the table right now it just says 'Enchan'... I don't need to know it is an enchanted book, I want
     *  to know if it is Power 6 or Power 7 etc." A book's display name is just "Enchanted Book" (or
     *  "Enchanted Book (Power VI)"), and the enchant itself is in the lore, so a book shows that, with the
     *  level as a number. Anything else shows its whole name; the overlay scales it to fit the slot. */
    static String itemLabel(Cell cell) {
        String name = SECTION_CODE.matcher(cell.name()).replaceAll("").trim();
        java.util.regex.Matcher wrapped = BOOK_WRAPPER.matcher(name);
        if (wrapped.matches()) {
            String enchant = enchantLabel(wrapped.group(1).trim());
            return enchant != null ? enchant : wrapped.group(1).trim();
        }
        if (!name.toLowerCase(java.util.Locale.ROOT).startsWith("enchanted book")) {
            // A tile can also be named for its enchant directly ("Power VI", see bestKnownSingleTarget's
            // doc) - same numeric level then, so books and these read alike.
            String enchant = enchantLabel(name);
            return enchant != null ? enchant : name;
        }
        for (String line : cell.lore().split("\n")) {
            String enchant = enchantLabel(SECTION_CODE.matcher(line).replaceAll("").trim());
            if (enchant != null) {
                return enchant;
            }
        }
        return name;
    }

    /** "Power VI" -> "Power 6"; null if {@code text} is not an enchant-and-level line. */
    private static String enchantLabel(String text) {
        java.util.regex.Matcher m = ENCHANT_LINE.matcher(text);
        if (!m.matches()) {
            return null;
        }
        String level = m.group(2);
        if (!Character.isDigit(level.charAt(0))) {
            int value = ExperimentsProfitTracker.romanToInt(level);
            if (value <= 0) {
                return null;
            }
            level = String.valueOf(value);
        }
        return m.group(1) + " " + level;
    }

    private static final java.util.regex.Pattern SECTION_CODE = java.util.regex.Pattern.compile("§.");
    private static final java.util.regex.Pattern XP_AMOUNT =
            java.util.regex.Pattern.compile("^([\\d.,]+[kKmM]?)\\b");

    /** Extracts just the leading amount token (e.g. "131k") from a real XP tile's display name like
     *  "131k Enchanting Exp" - falls back to the full name if it doesn't match that pattern, so an
     *  unexpected format degrades to the old behavior instead of showing a blank label. */
    private static String xpAmountLabel(String name) {
        // Both patterns hoisted (2026-09-20, FPS pass): this is called per board tile per solver pass, and
        // String.replaceAll plus an inline Pattern.compile meant two fresh regex compiles per tile.
        String stripped = SECTION_CODE.matcher(name).replaceAll("").trim();
        java.util.regex.Matcher m = XP_AMOUNT.matcher(stripped);
        return m.find() ? m.group(1) : stripped;
    }

    /** Per killer560's request: Solver-Only mode's Superpairs highlight should always show SOMETHING to
     *  click, exactly like SkyHanni's own Next Click Helper does, instead of going blank whenever no
     *  confirmed match is currently known (e.g. the very start of a board, or right after the last
     *  known match gets claimed). Mirrors the real bot's own exploration priority in
     *  {@link #decideSuperpairsClick} - a discovered-but-unused bonus tile first, else the next
     *  still-covered tile in snake order - but is READ-ONLY: unlike the real click path, this never
     *  touches {@code queuedPairSlots}/{@code superpairsRevealAttempted}, so merely highlighting a
     *  suggestion can never corrupt the real solving state if killer560 later switches back to Autonomous
     *  mode mid-board. @return the suggested slot, or -1 if the board is genuinely fully explored with
     *  nothing left to discover. */
    int superpairsSuggestedExploreSlot(List<Cell> cells) {
        if (mode != Mode.SUPERPAIRS) {
            return -1;
        }
        if (superpairsPowerupSlot != null) {
            return superpairsPowerupSlot;
        }
        Map<Integer, Cell> bySlot = new HashMap<>();
        for (Cell cell : cells) {
            bySlot.put(cell.slot(), cell);
        }
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (superpairsRevealAttempted.contains(slot) || knownSuperpairsCells.containsKey(slot)) continue;
            Cell cell = bySlot.get(slot);
            if (cell == null) continue;
            // Real bug found (2026-09-07) from a real log: slots showing a blank display name (e.g.
            // "minecraft:black_stained_glass_pane" with name="") are genuine decorative border filler,
            // not real clickable cards - their name never changes no matter how many times they're
            // clicked, confirmed by two separate slots timing out with IDENTICAL prior/current state.
            // Never worth exploring in the first place.
            if (isRevealedPair(cell) || isDyeFamilyItem(cell) || isPowerupTile(cell) || cell.name().isBlank()) continue;
            return slot;
        }
        return -1;
    }

    OptionalInt nextClick(List<Cell> cells, boolean superpairsEnabled, boolean superpairsValuableOnly,
            long nowMillis, long lastClickMillis, long delayMillis, long firstClickDelayMs) {
        observe(cells, superpairsValuableOnly, nowMillis);
        return switch (mode) {
            case CHRONOMATRON -> decideChronomatronClick(nowMillis, lastClickMillis, delayMillis, firstClickDelayMs);
            case ULTRASEQUENCER -> decideUltrasequencerClick(cells, nowMillis, lastClickMillis, delayMillis, firstClickDelayMs);
            // Per killer560's explicit "I dont want to program forced delay i want it to auto detect for
            // this" (2026-09-06): decideSuperpairsClick() refuses to click again until it's actually
            // observed the previous click's real effect land (see superpairsAwaitingConfirmSlot) -
            // that's still the PRIMARY gate. But after seeing genuinely-instant confirmations fire
            // back-to-back in the same second during a real test, killer560 explicitly asked (2026-09-07)
            // for the same minimum Click Delay every other click already respects here too, "as well
            // as the random every time" - so delayMillis is now also enforced (see
            // superpairsLastClickSentAtMs), with ExperimentsFeature's own jitter still layering on top
            // exactly like every other click in this feature.
            case SUPERPAIRS -> superpairsEnabled ? decideSuperpairsClick(cells, superpairsValuableOnly, nowMillis, delayMillis) : OptionalInt.empty();
            case NONE -> OptionalInt.empty();
        };
    }

    private void observeChronomatron(List<Cell> cells, long now) {
        Cell control = cell(cells, 49);
        if (control == null) return;
        if (control.itemId().equals("minecraft:glowstone")) {
            chronomatronRevealLatched = false;
            chronomatronClickIndex = 0;
            return;
        }
        if (!control.itemId().equals("minecraft:clock")) return;
        List<Cell> foils = cells.stream().filter(c -> c.slot() >= 10 && c.slot() <= 43 && c.foil()).toList();
        if (!chronomatronRevealLatched && !foils.isEmpty()) {
            // Diagnostic (2026-09-07), per killer560's report that the highlight only ever lands on the
            // "top" of two vertically-stacked slots he can apparently click either of: if Hypixel is
            // genuinely leaving more than one item foiled at once (e.g. a stale glint from the previous
            // round not yet cleared), .get(0) below always silently picks whichever comes first in slot
            // order (lowest slot number = topmost row), which may or may not be the actually-new note.
            // Not fixed yet - need real evidence of whether foils.size() > 1 actually happens before
            // guessing at which one is correct.
            if (foils.size() > 1) {
                LOGGER.warn("Chronomatron: {} slots foiled simultaneously on reveal ({}) - picking slot {} "
                        + "(lowest slot number). If this is wrong, note which slot was actually correct.",
                        foils.size(), foils.stream().map(Cell::slot).toList(), foils.get(0).slot());
            }
            chronomatron.add(foils.get(0).slot());
            chronomatronRevealLatched = true;
            chronomatronClickIndex = 0;
            chronomatronRoundReadyAtMs = now;
        }
    }

    private OptionalInt decideChronomatronClick(long now, long lastClickMillis, long delayMillis, long firstClickDelayMs) {
        if (!chronomatronRevealLatched || chronomatronClickIndex >= chronomatron.size()) {
            return OptionalInt.empty();
        }
        boolean firstOfRound = chronomatronClickIndex == 0;
        long required = firstOfRound ? firstClickDelayMs : delayMillis;
        long elapsed = firstOfRound ? (now - chronomatronRoundReadyAtMs) : (now - lastClickMillis);
        if (elapsed < required) return OptionalInt.empty();
        return OptionalInt.of(chronomatron.get(chronomatronClickIndex++));
    }

    private void observeUltrasequencer(List<Cell> cells, long now) {
        Cell control = cell(cells, 49);
        if (control == null) return;
        if (control.itemId().equals("minecraft:clock")) {
            ultrasequencerReady = false;
            return;
        }
        if (control.itemId().equals("minecraft:glowstone") && !ultrasequencerReady) {
            ultrasequencer.clear();
            cells.stream()
                    .filter(c -> c.slot() >= 9 && c.slot() <= 44)
                    .filter(ExperimentSolver::isSequenceItem)
                    .sorted(Comparator.comparingInt(Cell::count))
                    .forEach(c -> ultrasequencer.put(c.count() - 1, c.slot()));
            ultrasequencerClickIndex = 0;
            ultrasequencerReady = true;
            ultrasequencerRoundReadyAtMs = now;
        }
    }

    /** Re-checks the control slot itself (rather than relying purely on a latched flag, unlike
     *  Chronomatron) to exactly preserve the original combined-method behavior: a click was only ever
     *  considered while slot 49 actually shows the clock, not merely because {@code ultrasequencer}
     *  still holds data left over from a previous round. */
    private OptionalInt decideUltrasequencerClick(List<Cell> cells, long now, long lastClickMillis, long delayMillis, long firstClickDelayMs) {
        Cell control = cell(cells, 49);
        if (control == null || !control.itemId().equals("minecraft:clock")) {
            return OptionalInt.empty();
        }
        if (!ultrasequencer.containsKey(ultrasequencerClickIndex)) {
            return OptionalInt.empty();
        }
        boolean firstOfRound = ultrasequencerClickIndex == 0;
        long required = firstOfRound ? firstClickDelayMs : delayMillis;
        long elapsed = firstOfRound ? (now - ultrasequencerRoundReadyAtMs) : (now - lastClickMillis);
        if (elapsed < required) return OptionalInt.empty();
        return OptionalInt.of(ultrasequencer.get(ultrasequencerClickIndex++));
    }

    private void observeSuperpairs(List<Cell> cells, boolean valuableOnly, long now) {
        Map<Integer, Cell> bySlot = new HashMap<>();
        for (Cell cell : cells) {
            bySlot.put(cell.slot(), cell);
        }

        // Real root cause found (2026-09-07) by reading SkyHanni's actual working Superpairs source
        // directly (see isRevealedPair's doc) after a real log showed EVERY SINGLE click in a round
        // timing out with zero confirmations - not occasional lag, total failure. The previous version
        // of this check compared raw itemId, which apparently never flips between covered and revealed
        // in the current Hypixel build. Now reuses the exact same isRevealedPair() classification the
        // rest of this class already relies on for identity-tracking (itself switched to SkyHanni's
        // real display-name-pattern approach), so "changed" means the same thing everywhere in this
        // file: covered -> revealed, or revealed -> empty/claimed.
        if (superpairsAwaitingConfirmSlot != null) {
            Cell current = bySlot.get(superpairsAwaitingConfirmSlot);
            boolean changed = superpairsSlotChanged(superpairsAwaitingConfirmPriorCell, current);
            long confirmTimeoutMs = superpairsConfirmTimeoutMs();
            // Real bug found and fixed (2026-09-06) from a real log: with no timeout at all, a single
            // dropped/ignored click left this gate permanently stuck (confirmed: exactly one "Clicking
            // slot 9" line, then total silence for 31 seconds until the round timed out on its own) -
            // still kept as a safety net even after the narrower check above, since a match-completion
            // click might genuinely never change item type or empty-state at all (Hypixel may just mark
            // it matched without swapping the item) - that case still needs a bounded fallback rather
            // than waiting forever for a signal that will never come.
            // Only counts down once the click has actually been sent (see superpairsClickSent) - a
            // decided-but-not-yet-jittered-out click has no real-world effect to wait on yet, so it
            // must never be treated as "timed out."
            boolean timedOut = superpairsClickSent
                    && now - superpairsAwaitingConfirmSinceMs > confirmTimeoutMs;
            if (changed || timedOut) {
                if (timedOut && !changed) {
                    LOGGER.warn("Superpairs click on slot {} never confirmed within {}ms - prior=[itemId={}, "
                            + "empty={}, name='{}'] current={} - giving up waiting and letting the next decision proceed",
                            superpairsAwaitingConfirmSlot, confirmTimeoutMs,
                            superpairsAwaitingConfirmPriorCell.itemId(), superpairsAwaitingConfirmPriorCell.empty(),
                            superpairsAwaitingConfirmPriorCell.name(),
                            current == null ? "null (slot missing from snapshot!)"
                                    : "[itemId=" + current.itemId() + ", empty=" + current.empty()
                                            + ", name='" + current.name() + "']");
                    // Real bug found (2026-09-07) from a real log: a reveal click that timed out used to
                    // stay in superpairsRevealAttempted FOREVER, permanently skipping that slot even
                    // though the log showed real (non-border) tiles occasionally failing to reveal on
                    // the first attempt (server lag, a dropped click, or the covered placeholder text
                    // merely cycling between "?" and "Click any button!" without actually flipping) -
                    // matching killer560's "not actually showing every item uncovered, only some of them."
                    // Only genuinely blank-named border filler (which can never become revealed - see
                    // isRevealedPair) is worth permanently giving up on; anything else gets retried on a
                    // later pass instead of being stuck unexplored for the rest of the round.
                    if (current != null && !current.name().isBlank()) {
                        superpairsRevealAttempted.remove(superpairsAwaitingConfirmSlot);
                    }
                    // Real bug found and fixed (2026-09-09) from killer560's report: "found two power
                    // books but didn't claim both." queuedPairSlots is only ever ADDED to everywhere else
                    // in this class (see decideSuperpairsClickInternal) - nothing ever removed a slot from
                    // it once queued, including right here, where a PAIR-COMPLETING click (not a reveal
                    // click - reveal clicks go through superpairsRevealAttempted instead) times out
                    // without ever confirming. That left the slot permanently stuck in queuedPairSlots,
                    // silently blocking every future pairing attempt involving it for the rest of the
                    // round (see the `if (queuedPairSlots.contains(slot)) continue;` guard there) even
                    // though the card was never actually matched - exactly matching a real log where the
                    // same "known at slots [X, Y] but no pair was queued" diagnostic warning kept firing
                    // every cycle for several seconds straight because Y stayed wrongly reserved. Removing
                    // it here lets the pairing logic reconsider this slot fresh, the same way a timed-out
                    // reveal click already gets retried above.
                    //
                    // EXCEPT a bonus-tile ACTIVATION click (see superpairsPowerupActivationSlot's doc
                    // comment) - real bug found and fixed (2026-09-09) from killer560's report of the
                    // solver getting stuck alternating between the same two slots forever: a powerup
                    // tile's own item never visibly changes when clicked, so its confirm ALWAYS times
                    // out, and freeing it here (like a genuine stuck pair-click) just re-exposed the same
                    // already-activated tile as "not yet activated" on the very next scan, clicking it
                    // again forever. Leaving it queued is correct here - the tile's already spent.
                    int retries = superpairsPairRetries.getOrDefault(superpairsAwaitingConfirmSlot, 0);
                    if (superpairsAwaitingConfirmSlot.equals(superpairsPairFirstSlot) && current != null
                            && !isRevealedPair(current) && retries < SUPERPAIRS_PAIR_FIRST_RETRIES) {
                        // The pair's first tile never turned over: click it again before its partner.
                        // It stays in queuedPairSlots - the pair is still ours.
                        superpairsPairRetries.put(superpairsAwaitingConfirmSlot, retries + 1);
                        pairClicks.addFirst(superpairsAwaitingConfirmSlot);
                        LOGGER.info("Superpairs: first click of a pair on slot {} did not land (still '{}') - "
                                + "re-clicking it before its partner {} (retry {}/{})",
                                superpairsAwaitingConfirmSlot, current.name(), pairClicks.size() > 1
                                        ? java.util.List.copyOf(pairClicks).get(1) : "?",
                                retries + 1, SUPERPAIRS_PAIR_FIRST_RETRIES);
                    } else if (!superpairsAwaitingConfirmSlot.equals(superpairsPowerupActivationSlot)) {
                        queuedPairSlots.remove(superpairsAwaitingConfirmSlot);
                    }
                    superpairsPowerupActivationSlot = null;
                }
                // Real bug found and fixed (2026-09-24, see superpairsSingleSpendSlot's doc): a
                // last-resort single-spend click (clickAnyRemainingKnownTile) has no real partner, so
                // unlike a genuine pair-completion click it must be freed from queuedPairSlots the
                // moment its confirm resolves EITHER way - not just on timeout like the block above -
                // since re-revealing an already-known tile almost always DOES produce a visible change
                // and would otherwise never hit that timeout path at all, leaving it stuck forever.
                if (superpairsAwaitingConfirmSlot.equals(superpairsSingleSpendSlot)) {
                    queuedPairSlots.remove(superpairsAwaitingConfirmSlot);
                    superpairsSingleSpendSlot = null;
                }
                superpairsAwaitingConfirmSlot = null;
                superpairsAwaitingConfirmPriorCell = null;
                superpairsClickSent = false;
            }
        }

        // Learn from whatever's actually showing right now, regardless of whether WE revealed it or
        // it was already visible - covers a slot going empty again once matched too (a stale entry
        // there is harmless since it simply won't be re-validated as still-current by callers). The
        // bonus tile (real confirmed effects: "+1 Click"/"Powerup for next click!" and "Gained +3
        // Clicks"/"Instant powerup!" - wording and amount both vary, so matched by the "powerup"
        // keyword its lore always contains, not an exact name) is tracked separately since it has no
        // partner. Per killer560's "cant you just have it check is this xp if yes then skip if no then its
        // valuable" (2026-09-07): EVERY revealed tile is remembered unconditionally here now, XP/dye
        // included - the valuableOnly "is this worth prioritizing as a match" question only gets asked
        // later, inline, wherever a decision actually needs it (see isValuablePair) - not here at write
        // time, which used to require a second parallel map just to keep XP tiles around for display
        // and the fully-explored fallback.
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            Cell cell = bySlot.get(slot);
            if (cell == null || !isRevealedPair(cell)) continue;
            if (isPowerupTile(cell)) {
                superpairsPowerupSlotsSeen.add(slot);
                if (superpairsPowerupSlot == null && !queuedPairSlots.contains(slot)) {
                    superpairsPowerupSlot = slot;
                }
                continue;
            }
            knownSuperpairsCells.put(slot, cell);
        }
    }

    /** Public entry point for the real auto-click path - gates on {@link #superpairsAwaitingConfirmSlot}
     *  first (refuses to click again until the previous click's real effect has actually been observed,
     *  see {@link #observeSuperpairs}), then a minimum-delay gate (per killer560's explicit 2026-09-07
     *  request after seeing genuinely-instant-confirmed clicks fire back-to-back: "add in my minimum
     *  delay as well as the random every time" - so the same Click Delay setting Chronomatron/
     *  Ultrasequencer already respect now applies here too, purely for pacing, layered on top of the
     *  confirm-based correctness gate rather than replacing it), then delegates to
     *  {@link #decideSuperpairsClickInternal} for the actual priority logic, and records whatever slot
     *  it decides on as newly pending. Note neither timer starts here - see {@link #superpairsClickSent}
     *  - since the caller still has to run this decision through its own jittered scheduling (which adds
     *  the "random every time" on top of this minimum) before the click is real. */
    private OptionalInt decideSuperpairsClick(List<Cell> cells, boolean valuableOnly, long now, long minDelayMs) {
        if (superpairsAwaitingConfirmSlot != null) {
            return OptionalInt.empty();
        }
        if (now - superpairsLastClickSentAtMs < minDelayMs) {
            return OptionalInt.empty();
        }
        OptionalInt click = decideSuperpairsClickInternal(cells, valuableOnly, now);
        if (click.isPresent()) {
            int slot = click.getAsInt();
            superpairsClickedAtMs.put(slot, now);
            superpairsAwaitingConfirmSlot = slot;
            superpairsAwaitingConfirmPriorCell = cell(cells, slot);
            superpairsAwaitingConfirmSinceMs = now;
            superpairsClickSent = false;
        }
        return click;
    }

    /** Called by {@code ExperimentsFeature} the instant a decided Superpairs click is actually sent to
     *  the server (after whatever jitter delay {@code scheduleAction} applied) - starts the real confirm-
     *  timeout clock from here rather than from the earlier decision time. A no-op if {@code slot} isn't
     *  (or is no longer) the slot currently being waited on, so it's safe to call unconditionally from
     *  every click site, not just Superpairs ones. */
    /** "Changed" in the exact sense {@link #observeSuperpairs}'s confirm gate has always used: covered ->
     *  revealed, or revealed -> empty/claimed. */
    private static boolean superpairsSlotChanged(Cell prior, Cell current) {
        return prior != null && current != null
                && (isRevealedPair(current) != isRevealedPair(prior) || current.empty() != prior.empty());
    }

    /** @return the effective Superpairs confirm timeout for the click being waited on: the flat
     *  {@link #SUPERPAIRS_CONFIRM_TIMEOUT_MS}, plus - with Adaptive Timeout on - however long the server
     *  has stalled since that click was sent (capped at {@link #SUPERPAIRS_MAX_LAG_EXTENSION_MS}). No lag
     *  means no extension, so on a healthy server it waits exactly as long as with the setting off. */
    private long superpairsConfirmTimeoutMs() {
        long timeout = SUPERPAIRS_CONFIRM_TIMEOUT_MS;
        if (ExperimentsConfig.getInstance().isSuperpairsAdaptiveTimeout()) {
            long lagSinceSend = ServerLagSensor.lagMs() - superpairsLagAtSendMs;
            timeout += Math.max(0, Math.min(SUPERPAIRS_MAX_LAG_EXTENSION_MS, lagSinceSend));
        }
        return timeout;
    }

    void superpairsClickSent(int slot, long sentAtMs) {
        if (superpairsAwaitingConfirmSlot != null && superpairsAwaitingConfirmSlot == slot) {
            superpairsClickSent = true;
            superpairsAwaitingConfirmSinceMs = sentAtMs;
            superpairsLastClickSentAtMs = sentAtMs;
            superpairsLagAtSendMs = ServerLagSensor.lagMs();
        }
    }

    /**
     * Superpairs decision. Priority, per killer560 (2026-10-05: "prioritize money over xp", skipped rewards "if it
     * reaches the end with clicks left", and "you can calculate how many things are left ... to optimize this"):
     * money (anything that is neither plain XP nor a kind he skips) first, then the skipped kinds (Grand bottles,
     * Guardians - they still sell), then plain XP, highest amount first. The board is read every call
     * ({@link #readBoard}): which tiles are claimed, which one is turned over as the current turn's first click,
     * and the counting facts the deductions use. Clicks come in turns of two, so a turn's first and second click
     * are decided differently - see {@link #firstClickOfTurn} and {@link #secondClickOfTurn}.
     */
    private OptionalInt decideSuperpairsClickInternal(List<Cell> cells, boolean valuableOnly, long now) {
        superpairsPairFirstSlot = null;
        Map<Integer, Cell> bySlot = new HashMap<>();
        for (Cell cell : cells) {
            bySlot.put(cell.slot(), cell);
        }
        BoardFacts board = readBoard(bySlot);
        int remaining = superpairsRemainingClicks(cells);

        // The bonus tile makes the very next click an automatic match - spend it on the best known
        // target: a money tile whose partner is still hidden first, else any known money tile, else (if
        // pairing everything) the highest known XP, else fall through to whatever the normal priority picks.
        if (superpairsPowerupPending) {
            superpairsPowerupPending = false;
            OptionalInt best = bestKnownSingleTarget(valuableOnly, board);
            if (best.isPresent()) {
                queuedPairSlots.add(best.getAsInt());
                Cell target = knownSuperpairsCells.get(best.getAsInt());
                LOGGER.info("Superpairs: powerup match spent on slot {} (itemId={}, name='{}') - reserved for the rest of the round",
                        best.getAsInt(), target == null ? "?" : target.itemId(), target == null ? "?" : target.name());
                return best;
            }
        }

        // A discovered-but-not-yet-activated bonus tile takes priority over normal exploration.
        // Only one KIND arms the next click's auto-match: its lore says "Powerup for next click!" (Instant
        // Find). "Instant powerup!" tiles (the "+479,095 XP" lapis block, "Gained +3 Clicks") apply on the
        // spot. Arming on those too spent a lone click on the Enchanted Book at slot 10 in his 2026-10-01
        // 09:20 run, which reserved it, so when its partner turned up at 34 the pair was blocked.
        // Not while a turn is half done: that click would land as the turn's second click instead.
        if (superpairsPowerupSlot != null && !queuedPairSlots.contains(superpairsPowerupSlot) && board.open.isEmpty()) {
            int slot = superpairsPowerupSlot;
            superpairsPowerupSlot = null;
            Cell tile = bySlot.get(slot);
            superpairsPowerupPending = tile != null && armsNextClick(tile);
            queuedPairSlots.add(slot);
            superpairsPowerupActivationSlot = slot;
            LOGGER.info("Superpairs: activating powerup on slot {} (name='{}', lore='{}') - reserved for the rest of the round",
                    slot, tile == null ? "?" : tile.name(), tile == null ? "?" : tile.lore());
            return OptionalInt.of(slot);
        }

        // A queued pair's second click. Dropped if that tile is already claimed (the first click landed while an
        // Instant Find was armed, which claims both at once): a click on a claimed tile does nothing.
        while (!pairClicks.isEmpty()) {
            int next = pairClicks.poll();
            if (board.claimed.contains(next)) {
                LOGGER.info("Superpairs: slot {} is already claimed - dropping its queued click", next);
                continue;
            }
            superpairsPairFirstSlot = pairClicks.isEmpty() ? null : next;
            return OptionalInt.of(next);
        }

        // Two unpaired tiles up at once is a missed pair the game is about to cover back up; a click now is
        // ignored. Wait for it - but only so long, so a board this misreads slows the solver down, never stops it.
        if (board.open.size() >= 2) {
            if (superpairsTwoOpenSinceMs == 0) {
                superpairsTwoOpenSinceMs = now;
            }
            if (now - superpairsTwoOpenSinceMs < SUPERPAIRS_TWO_OPEN_MAX_WAIT_MS) {
                return OptionalInt.empty();
            }
            if (!superpairsTwoOpenWarned) {
                superpairsTwoOpenWarned = true;
                LOGGER.warn("Superpairs: slots {} stayed turned over unpaired for {}ms - carrying on as if a new turn",
                        board.open, SUPERPAIRS_TWO_OPEN_MAX_WAIT_MS);
            }
            board.open.clear();
        } else {
            superpairsTwoOpenSinceMs = 0;
            superpairsTwoOpenWarned = false;
        }
        if (board.open.size() == 1) {
            return secondClickOfTurn(board.open.get(0), bySlot, board, valuableOnly);
        }
        return firstClickOfTurn(bySlot, board, valuableOnly, remaining);
    }

    /**
     * What one look at the board says, recomputed on every decision ({@link #readBoard}).
     * <p>
     * The counting rule the deductions rest on: every reward tile has exactly one partner, and a powerup has none.
     * Nothing is assumed about how many powerups a board has (it varies: Instant Find, "+N Clicks", the big XP
     * lapis). So each covered tile whose face was never seen ({@link #unknown}) is one of three things: the partner
     * of a known single (a kind seen an odd number of times and not yet claimed), a member of a pair neither of
     * whose tiles has been seen, or a powerup. Every known single's partner must be among the unknown tiles, so
     * {@code hidden() = unknown - singles} is the number of unknown tiles that are NOT a known single's partner:
     * hidden pairs (two each) plus hidden powerups. From that:
     * <ul>
     *   <li>{@code hidden() < 0} cannot happen on a board read correctly, so every deduction switches off
     *       ({@link #consistent}) and the solver plays exactly as it would without them.</li>
     *   <li>{@code hidden() == 0}: every unknown tile is some single's partner. No powerup and no new kind is left
     *       to find, so revealing a tile as a turn's second click, when it cannot match the open tile, learns
     *       nothing that revealing it as a later turn's first click (where it can be matched at once) would not.</li>
     *   <li>{@code hidden() <= 1}: no pair can be wholly hidden (that takes two tiles), so the only rewards left
     *       to find are partners of known singles; with no money single among them, no money is left to find.</li>
     *   <li>one unknown tile and one single: that tile is the single's partner (killer560: "if there is one square
     *       left you know the only one so far that is unmatched is there").</li>
     *   <li>one unknown tile and no single: it is a powerup ("if everything is matched you know it is a powerup").
     *       It cannot be a reward whose partner was claimed, because a claim always takes both tiles - an Instant
     *       Find claim turns both over too.</li>
     * </ul>
     */
    private static final class BoardFacts {
        /** Covered tiles whose face was never seen, in snake order. */
        final List<Integer> unknown = new ArrayList<>();
        /** Known tiles that are covered right now (unclaimed). */
        final Set<Integer> coveredKnown = new HashSet<>();
        /** Tiles showing their face as part of a claimed pair. */
        final Set<Integer> claimed = new HashSet<>();
        /** Tiles showing their face with no partner showing: the current turn's first click (or, two at once, a
         *  missed pair about to cover back up). */
        final List<Integer> open = new ArrayList<>();
        /** One tile per kind seen an odd number of times and not claimed - its partner is still unseen. */
        final List<Cell> singles = new ArrayList<>();
        final Set<String> singleKeys = new HashSet<>();

        int hidden() {
            return unknown.size() - singles.size();
        }

        boolean consistent() {
            return hidden() >= 0;
        }

        boolean anyMoneySingle() {
            for (Cell single : singles) {
                if (isMoney(single)) {
                    return true;
                }
            }
            return false;
        }

        /** The one unknown tile when it can only be a powerup, else null. */
        Integer deducedPowerup() {
            return consistent() && singles.isEmpty() && unknown.size() == 1 ? unknown.get(0) : null;
        }
    }

    private BoardFacts readBoard(Map<Integer, Cell> bySlot) {
        BoardFacts b = new BoardFacts();
        Map<String, List<Integer>> revealedByKey = new java.util.LinkedHashMap<>();
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            Cell cell = bySlot.get(slot);
            if (cell == null || cell.empty() || cell.name().isBlank()) continue;
            if (isRevealedPair(cell)) {
                if (!isPowerupTile(cell)) {
                    revealedByKey.computeIfAbsent(pairKey(cell), k -> new ArrayList<>()).add(slot);
                }
                continue;
            }
            if (knownSuperpairsCells.containsKey(slot)) {
                b.coveredKnown.add(slot);
            } else if (!superpairsPowerupSlotsSeen.contains(slot) && !isDyeFamilyItem(cell) && !isPowerupTile(cell)) {
                b.unknown.add(slot);
            }
        }
        // Two face-up tiles of one kind are a claimed pair (a turn's two clicks only both stay up on a match; an
        // Instant Find claim turns up both as well). An odd one out is the current turn's first click - the
        // most recently clicked of them.
        Map<String, Integer> unclaimed = new HashMap<>();
        Map<String, Cell> sample = new HashMap<>();
        for (Map.Entry<String, List<Integer>> entry : revealedByKey.entrySet()) {
            List<Integer> slots = entry.getValue();
            Integer open = null;
            if (slots.size() % 2 == 1) {
                open = slots.get(0);
                for (int slot : slots) {
                    if (superpairsClickedAtMs.getOrDefault(slot, 0L) > superpairsClickedAtMs.getOrDefault(open, 0L)) {
                        open = slot;
                    }
                }
                b.open.add(open);
                unclaimed.merge(entry.getKey(), 1, Integer::sum);
                sample.putIfAbsent(entry.getKey(), bySlot.get(open));
            }
            for (int slot : slots) {
                if (open == null || slot != open) {
                    b.claimed.add(slot);
                }
            }
        }
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (!b.coveredKnown.contains(slot)) continue;
            Cell known = knownSuperpairsCells.get(slot);
            String key = pairKey(known);
            unclaimed.merge(key, 1, Integer::sum);
            sample.putIfAbsent(key, known);
        }
        for (Map.Entry<String, Integer> entry : unclaimed.entrySet()) {
            if (entry.getValue() % 2 == 1) {
                b.singles.add(sample.get(entry.getKey()));
                b.singleKeys.add(entry.getKey());
            }
        }
        return b;
    }

    /** The next covered tile never clicked or seen, in snake order, or null once there is none. */
    private Integer nextExploreSlot(Map<Integer, Cell> bySlot) {
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (superpairsRevealAttempted.contains(slot) || queuedPairSlots.contains(slot) || knownSuperpairsCells.containsKey(slot)) continue;
            Cell cell = bySlot.get(slot);
            if (cell == null) continue;
            // Same border-filler exclusion as superpairsSuggestedExploreSlot - never worth a click.
            if (isRevealedPair(cell) || isDyeFamilyItem(cell) || isPowerupTile(cell) || cell.name().isBlank()) continue;
            return slot;
        }
        return null;
    }

    /** Neither plain XP nor a kind he skips: books, Titanic bottles, items, pets other than Guardians. */
    private static boolean isMoney(Cell cell) {
        return !isSuperpairsXpTile(cell) && !isUserSkipped(cell);
    }

    /**
     * Whether the deferred rewards may be taken now. Skipped kinds (killer560: "if it reaches the end with clicks left
     * then it can collect those, but I do not want it prioritizing them before everything is unveiled") and, with
     * Skip Plain XP on, XP wait for this. It opens when every tile has been turned over, or when the counting says no
     * money can still be found: at most one unknown tile that is not a known single's partner (so no hidden pair,
     * see {@link BoardFacts}) and no money single whose partner is still out there. What is left to find then is
     * only partners of skipped/XP singles and a powerup, so turning those over first would only spend the clicks the
     * deferred rewards need.
     */
    private static boolean deferredMayGo(BoardFacts board, boolean explored) {
        return explored || (board.consistent() && board.hidden() <= 1 && !board.anyMoneySingle());
    }

    /**
     * A turn's FIRST click (no tile turned over). In order: a known money pair; with "Every Pair" XP only when the
     * clicks left are down to what the known XP pairs need (money over XP, but never a known XP pair lost to a
     * gamble); a new tile while money may still be found; then the end: the deduced powerup, skipped pairs, XP
     * pairs (highest first), the remaining tiles, and finally the last-resort spend.
     */
    private OptionalInt firstClickOfTurn(Map<Integer, Cell> bySlot, BoardFacts board, boolean valuableOnly, int remaining) {
        Integer reveal = nextExploreSlot(bySlot);
        boolean deferredGo = deferredMayGo(board, reveal == null);
        // One click left finishes no pair; a new tile might still be a "+N Clicks" powerup.
        if (remaining == 1 && reveal != null) {
            superpairsRevealAttempted.add(reveal);
            return OptionalInt.of(reveal);
        }
        OptionalInt money = matchKnownPair(board, ExperimentSolver::isMoney, "money");
        if (money.isPresent()) {
            return money;
        }
        // Every unknown tile is the partner of a known single and every single is money (one square left and one
        // single is the smallest case): whatever tile turns over, the second click claims money. As sure as a
        // known money pair, so it goes ahead of XP.
        if (reveal != null && board.consistent() && board.hidden() == 0 && !board.singles.isEmpty()
                && board.singles.stream().allMatch(ExperimentSolver::isMoney)) {
            if (board.unknown.size() == 1 && !logOnce("deduced-pair:" + reveal)) {
                LOGGER.info("Superpairs: slot {} is the last unknown tile and '{}' the only single - it is its partner",
                        reveal, board.singles.get(0).name());
            }
            superpairsRevealAttempted.add(reveal);
            return OptionalInt.of(reveal);
        }
        if (!valuableOnly && !deferredGo) {
            // "Every Pair": XP is wanted, after money. With the clicks left unreadable, as before: take it at once.
            // Otherwise keep exploring while the clicks left cover every known XP pair plus a full exploring turn
            // (a turn's first click commits its second), and take the best XP pair once they no longer would.
            // Once nothing better is left to find this is skipped and the end order below (skipped, then XP) runs.
            int xpPairs = countKnownPairs(board, ExperimentSolver::isSuperpairsXpTile);
            if (xpPairs > 0 && (remaining < 0 || remaining < 2 * xpPairs + 2)) {
                OptionalInt xp = matchHighestValueDyePair(board);
                if (xp.isPresent()) {
                    return xp;
                }
            }
        }
        if (reveal != null && !deferredGo) {
            superpairsRevealAttempted.add(reveal);
            return OptionalInt.of(reveal);
        }
        if (reveal != null && board.unknown.size() > 0 && !logOnce("explored-by-count")) {
            LOGGER.info("Superpairs: no money left to find - {} unknown tile(s), {} known single(s) {} - taking the "
                    + "deferred rewards before turning the rest over", board.unknown.size(), board.singles.size(),
                    board.singles.stream().map(Cell::name).toList());
        }
        // The one unknown tile is a powerup: turn it over first when that cannot cost a deferred pair - it may be
        // "+N Clicks", which pays for itself.
        Integer powerup = board.deducedPowerup();
        if (powerup != null && powerup.equals(reveal)) {
            int deferredPairs = countKnownPairs(board, ExperimentSolver::isUserSkipped)
                    + countKnownPairs(board, ExperimentSolver::isSuperpairsXpTile);
            if (remaining < 0 || remaining - 1 >= 2 * deferredPairs) {
                LOGGER.info("Superpairs: slot {} is the last unknown tile and every known tile is paired - it can only "
                        + "be a powerup; turning it over", powerup);
                superpairsRevealAttempted.add(powerup);
                return OptionalInt.of(powerup);
            }
        }
        OptionalInt skippedMatch = matchSkippedPair(board);
        if (skippedMatch.isPresent()) {
            return skippedMatch;
        }
        OptionalInt dyeMatch = matchHighestValueDyePair(board);
        if (dyeMatch.isPresent()) {
            return dyeMatch;
        }
        if (reveal != null) {
            superpairsRevealAttempted.add(reveal);
            return OptionalInt.of(reveal);
        }
        // Field-tested (2026-09-06): a click could still be stuck unspent - e.g. a single leftover known tile
        // whose real partner was already claimed elsewhere. Per killer560's "it gets stuck with only 1 click
        // left so just have it click any of them" - rather than sit on an unused click forever, spend it.
        return clickAnyRemainingKnownTile(board);
    }

    /**
     * A turn's SECOND click: {@code open} is turned over, and this click either matches it or covers both back up.
     * The open tile's partner, when known (or deduced: the only unknown tile left), is claimed if its kind may be
     * taken now. A deferred kind is still claimed when the count says a reveal here would learn nothing
     * ({@code hidden() == 0}: no powerup or new kind left; the reveal could not match the open tile, and its partner's
     * claim would cost a full turn later anyway) - this click is spent either way, so the claim costs nothing. Otherwise
     * a new tile is turned over (it may be the open tile's partner); with none left and no partner, the open tile's
     * partner was claimed already and the turn is closed on a harmless known tile.
     */
    private OptionalInt secondClickOfTurn(int open, Map<Integer, Cell> bySlot, BoardFacts board, boolean valuableOnly) {
        Cell openCell = bySlot.get(open);
        String key = pairKey(openCell);
        Integer partner = null;
        // Queued slots count here: a tile of the open one's kind can only match it, so a pair that failed once
        // (its first click never landed) is finished now rather than left blocked.
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (slot == open || !board.coveredKnown.contains(slot)) continue;
            if (pairKey(knownSuperpairsCells.get(slot)).equals(key)) {
                partner = slot;
                break;
            }
        }
        boolean deduced = false;
        if (partner == null && board.consistent() && board.unknown.size() == 1 && board.singleKeys.contains(key)
                && board.singles.size() == 1) {
            partner = board.unknown.get(0);
            deduced = true;
        }
        Integer reveal = nextExploreSlot(bySlot);
        if (partner != null) {
            boolean wanted = isMoney(openCell)
                    || (isSuperpairsXpTile(openCell) && !valuableOnly)
                    || deferredMayGo(board, reveal == null);
            boolean nothingToLearn = board.consistent() && board.hidden() == 0;
            if (wanted || nothingToLearn || reveal == null || deduced) {
                queuedPairSlots.add(open);
                queuedPairSlots.add(partner);
                if (deduced) {
                    superpairsRevealAttempted.add(partner);
                }
                LOGGER.info("Superpairs: slot {} is turned over - matching it with {}slot {} (name='{}'){}", open,
                        deduced ? "the last unknown tile, " : "", partner, openCell.name(),
                        wanted ? "" : " - a deferred kind, but this click is spent either way and a reveal would learn nothing");
                return OptionalInt.of(partner);
            }
        }
        if (reveal != null) {
            superpairsRevealAttempted.add(reveal);
            return OptionalInt.of(reveal);
        }
        // Its partner is gone (claimed with an Instant Find): close the turn on another kind so nothing is claimed.
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (!board.coveredKnown.contains(slot) || queuedPairSlots.contains(slot)) continue;
            Cell known = knownSuperpairsCells.get(slot);
            if (pairKey(known).equals(key)) continue;
            queuedPairSlots.add(slot);
            superpairsSingleSpendSlot = slot;
            LOGGER.info("Superpairs: slot {} (name='{}') has no partner left - closing the turn on slot {} (name='{}')",
                    open, openCell.name(), slot, known.name());
            return OptionalInt.of(slot);
        }
        return OptionalInt.empty();
    }

    private boolean logOnce(String what) {
        return !superpairsLoggedOnce.add(what);
    }

    /** Known, covered, unqueued pairs whose kind passes {@code kind}. */
    private int countKnownPairs(BoardFacts board, java.util.function.Predicate<Cell> kind) {
        Map<String, Integer> counts = new HashMap<>();
        for (int slot : board.coveredKnown) {
            Cell known = knownSuperpairsCells.get(slot);
            if (queuedPairSlots.contains(slot) || !kind.test(known)) continue;
            counts.merge(pairKey(known), 1, Integer::sum);
        }
        int pairs = 0;
        for (int n : counts.values()) {
            pairs += n / 2;
        }
        return pairs;
    }

    /** Queues the first known, covered, unqueued pair (snake order) whose kind passes {@code kind}. */
    private OptionalInt matchKnownPair(BoardFacts board, java.util.function.Predicate<Cell> kind, String what) {
        Map<String, Integer> firstSeenThisPass = new HashMap<>();
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (queuedPairSlots.contains(slot) || !board.coveredKnown.contains(slot)) continue;
            Cell known = knownSuperpairsCells.get(slot);
            if (!kind.test(known)) continue;
            String key = pairKey(known);
            Integer first = firstSeenThisPass.putIfAbsent(key, slot);
            if (first != null) {
                queuedPairSlots.add(first);
                queuedPairSlots.add(slot);
                pairClicks.add(slot);
                superpairsPairFirstSlot = first;
                // Diagnostic (2026-09-24), per killer560's report of a Titanic getting queued against a Grand:
                // logging both slots' full remembered identity settles whether a repeat is a genuine key
                // collision (both names would print identical here) or something else.
                LOGGER.info("Superpairs queuing {} pair: slot {} (itemId={}, name='{}') with slot {} "
                                + "(itemId={}, name='{}') on key '{}'", what,
                        first, knownSuperpairsCells.get(first).itemId(), knownSuperpairsCells.get(first).name(),
                        slot, known.itemId(), known.name(), key);
                return OptionalInt.of(first);
            }
        }
        // Diagnostic (2026-09-09, per killer560's "found two power books but didn't claim both"): a kind known
        // twice, unclaimed, that no pair was queued for - logs why (already queued?) once per kind per board.
        Map<String, List<Integer>> byKey = new HashMap<>();
        for (int slot : board.coveredKnown) {
            Cell known = knownSuperpairsCells.get(slot);
            if (kind.test(known)) {
                byKey.computeIfAbsent(pairKey(known), k -> new ArrayList<>()).add(slot);
            }
        }
        for (Map.Entry<String, List<Integer>> entry : byKey.entrySet()) {
            if (entry.getValue().size() >= 2 && !logOnce("unqueued:" + entry.getKey())) {
                LOGGER.warn("Superpairs: key '{}' known at slots {} but no pair was queued - queuedPairSlots={}",
                        entry.getKey(), entry.getValue(), queuedPairSlots);
            }
        }
        return OptionalInt.empty();
    }

    /** Last-resort fallback once nothing else is left to explore or deliberately match - spends a
     *  click that would otherwise go unused on literally any still-available known tile (valuable or
     *  dye), per killer560's explicit request not to let the solver get stuck holding an unspent click. */
    private OptionalInt clickAnyRemainingKnownTile(BoardFacts board) {
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (queuedPairSlots.contains(slot)) continue;
            // Covered only: a claimed tile ignores clicks, and spending on one looped forever (no click used, the
            // confirm timed out, the slot was freed and picked again).
            if (board.coveredKnown.contains(slot)) {
                queuedPairSlots.add(slot);
                // See superpairsSingleSpendSlot's doc (real bug found 2026-09-24): this slot has no
                // known partner - mark it so observeSuperpairs frees it again once the click resolves,
                // instead of leaving it stuck in queuedPairSlots forever and unpairable with its real
                // match if one turns up later.
                superpairsSingleSpendSlot = slot;
                Cell known = knownSuperpairsCells.get(slot);
                LOGGER.info("Superpairs: last-resort single-spend on slot {} (itemId={}, name='{}') - "
                                + "no known partner, spending an otherwise-unused click",
                        slot, known.itemId(), known.name());
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /** A known pair of a skipped reward kind ({@link #isUserSkipped}), queued exactly like a normal pair - used only
     *  once {@link #deferredMayGo}. */
    private OptionalInt matchSkippedPair(BoardFacts board) {
        Map<String, List<Cell>> byKey = new java.util.LinkedHashMap<>();
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            Cell cell = knownSuperpairsCells.get(slot);
            if (cell == null || queuedPairSlots.contains(slot) || !board.coveredKnown.contains(slot) || !isUserSkipped(cell)) continue;
            byKey.computeIfAbsent(pairKey(cell), k -> new ArrayList<>()).add(cell);
        }
        for (List<Cell> group : byKey.values()) {
            if (group.size() < 2) continue;
            int first = group.get(0).slot();
            int second = group.get(1).slot();
            queuedPairSlots.add(first);
            queuedPairSlots.add(second);
            pairClicks.add(second);
            superpairsPairFirstSlot = first;
            LOGGER.info("Superpairs: board explored, collecting skipped reward pair slot {} with slot {} (name='{}')",
                    first, second, group.get(0).name());
            return OptionalInt.of(first);
        }
        return OptionalInt.empty();
    }

    /** Matches the known, unclaimed XP pair worth the most XP (by the amount in its name, see {@link #xpValue}),
     *  last in the priority order. */
    private OptionalInt matchHighestValueDyePair(BoardFacts board) {
        Map<String, List<Cell>> byKey = new java.util.LinkedHashMap<>();
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            Cell cell = knownSuperpairsCells.get(slot);
            if (cell == null || !board.coveredKnown.contains(slot) || !isSuperpairsXpTile(cell) || queuedPairSlots.contains(slot)) continue;
            byKey.computeIfAbsent(pairKey(cell), k -> new ArrayList<>()).add(cell);
        }
        List<Cell> bestPair = null;
        double bestValue = -1;
        for (List<Cell> group : byKey.values()) {
            if (group.size() < 2) continue;
            double value = xpValue(group.get(0));
            if (value > bestValue) {
                bestValue = value;
                bestPair = group;
            }
        }
        if (bestPair == null) {
            return OptionalInt.empty();
        }
        int first = bestPair.get(0).slot();
        int second = bestPair.get(1).slot();
        queuedPairSlots.add(first);
        queuedPairSlots.add(second);
        pairClicks.add(second);
        superpairsPairFirstSlot = first;
        LOGGER.info("Superpairs: board explored, matching XP pair slot {} with slot {} (name='{}')",
                first, second, bestPair.get(0).name());
        return OptionalInt.of(first);
    }

    /** @return the best target to spend a guaranteed-match bonus click on. Real bug found and fixed
     *  (2026-09-07) from killer560's exact field report: this used to only recognize a specific known
     *  ENCHANTED BOOK as a valid target (per an earlier, narrower "spend it on a book/exp thing"
     *  request) - killer560's actual test discovered a "Power 6" item (not a book) as click 3, then the
     *  guaranteed-match powerup as click 4, and the bonus should have been spent claiming Power 6, but
     *  since it isn't literally {@code minecraft:enchanted_book}, this method found nothing and the
     *  bonus was wasted on a random blank tile instead. Now takes ANY known non-dye single (in snake
     *  order, for determinism), matching this class's existing "don't whitelist valuable types, just
     *  exclude the one known-skippable dye category" philosophy (see {@link #isValuablePair}) instead of
     *  whitelisting one specific item type. Falls back to the highest-count known dye only when pairing
     *  everything ({@code !valuableOnly}) and nothing else is known - matching "if the exp mode isn't on
     *  then have it use it on the next available space." Returns empty when nothing at all is known yet,
     *  letting the normal reveal/match priority take over instead.
     *  <p>
     *  2026-10-05 (killer560: "prioritize money over xp"): money before XP even in "Every Pair" mode, where an XP
     *  tile earlier in snake order used to win; among money, a tile whose partner is still unseen first (the match
     *  saves finding it, where a known pair would only save one click); among XP, the largest amount. Covered,
     *  unclaimed tiles only - the matched click has to turn one over. */
    private OptionalInt bestKnownSingleTarget(boolean valuableOnly, BoardFacts board) {
        Integer moneyPairMember = null;
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (queuedPairSlots.contains(slot) || !board.coveredKnown.contains(slot)) continue;
            Cell known = knownSuperpairsCells.get(slot);
            // A powerup's matched click CLAIMS the reward, so a skipped kind must never be its target.
            if (!isMoney(known)) continue;
            if (board.singleKeys.contains(pairKey(known))) {
                return OptionalInt.of(slot);
            }
            if (moneyPairMember == null) {
                moneyPairMember = slot;
            }
        }
        if (moneyPairMember != null) {
            return OptionalInt.of(moneyPairMember);
        }
        if (!valuableOnly) {
            Integer bestDyeSlot = null;
            double bestDyeValue = -1;
            for (int slot : SUPERPAIRS_SNAKE_ORDER) {
                Cell cell = knownSuperpairsCells.get(slot);
                if (cell == null || !board.coveredKnown.contains(slot) || !isSuperpairsXpTile(cell) || queuedPairSlots.contains(slot)) continue;
                if (xpValue(cell) > bestDyeValue) {
                    bestDyeValue = xpValue(cell);
                    bestDyeSlot = slot;
                }
            }
            if (bestDyeSlot != null) {
                return OptionalInt.of(bestDyeSlot);
            }
        }
        return OptionalInt.empty();
    }

    /** The Superpairs board's "Remaining Clicks: N" (slot 4 on Hypixel), or -1 when no such item is showing. */
    private static int superpairsRemainingClicks(List<Cell> cells) {
        for (Cell cell : cells) {
            if (cell.slot() >= 9 && cell.slot() <= 44) continue;
            int remaining = ExperimentsProfitTracker.parseRemainingClicks(cell.name());
            if (remaining >= 0) {
                return remaining;
            }
        }
        return -1;
    }

    /**
     * The identity two tiles must share to be a pair: item and name, plus for a tile named just "Enchanted Book"
     * the enchant from its lore ({@link #itemLabel}) - the name alone would make Power VI and Sharpness V one kind,
     * which pairs two different books and throws off every count in {@link #readBoard}.
     */
    static String pairKey(Cell cell) {
        String key = cell.itemId() + "|" + cell.name();
        if (SECTION_CODE.matcher(cell.name()).replaceAll("").trim().equals("Enchanted Book")) {
            key += "|" + itemLabel(cell);
        }
        return key;
    }

    /** "+479,095 XP" or "144k Enchanting Exp" -> the amount, digits bounded; "1.5M" reads as 1,500,000. */
    private static final Pattern XP_VALUE = Pattern.compile("^\\+?([\\d,]{1,15})(\\.\\d{1,3})?\\s?([kKmM]?)");

    /** The XP a tile is worth, from its name; its stack count when the name carries no amount. */
    static double xpValue(Cell cell) {
        java.util.regex.Matcher m = XP_VALUE.matcher(SECTION_CODE.matcher(cell.name()).replaceAll("").trim());
        if (m.find()) {
            String digits = m.group(1).replace(",", "");
            if (!digits.isEmpty()) {
                double value = Double.parseDouble(digits + (m.group(2) == null ? "" : m.group(2)));
                String unit = m.group(3).toLowerCase(Locale.ROOT);
                return unit.equals("k") ? value * 1_000 : unit.equals("m") ? value * 1_000_000 : value;
            }
        }
        return cell.count();
    }

    /** Real confirmed text (2026-09-06): one instance showed "+1 Click" / "Powerup for next click!",
     *  another showed "Gained +3 Clicks" / "Instant powerup!" - amount and exact wording both vary,
     *  so this matches the one word both share rather than an exact name/lore string. */
    private static boolean isPowerupTile(Cell cell) {
        return cell.lore() != null && cell.lore().toLowerCase(Locale.ROOT).contains("powerup");
    }

    /** Per killer560's correction: reward tiles vary wildly in item type (books, misc items, player
     *  heads for pets, bottles, ...) with no reliable type/name to whitelist. But the plain
     *  "Experience" reward - the one worth skipping - always renders as a dye-family item, the same
     *  family Ultrasequencer's own notes use (see {@link #isDyeFamilyItem}). So instead of trying to
     *  whitelist "valuable," this blacklists dye-family items and treats everything else as valuable. */
    private static boolean isValuablePair(Cell cell) {
        return !isSuperpairsXpTile(cell);
    }

    /**
     * A reward tile he chose never to claim (killer560, 2026-10-05: "skip grand exp bottles (not titanics just
     * grands)" and "skipping guardian pets of all rarities", two separate switches). LAST priority, not forbidden:
     * never paired or a powerup's target while the board is being explored, then collected by
     * {@link #matchSkippedPair} once everything is unveiled if clicks are left ("if it reaches the end with clicks
     * left then it can collect those"). An unpaired reveal claims nothing, so revealing it while exploring is fine.
     * By NAME, colour codes already stripped: "Grand Experience Bottle" (a Titanic is "Titanic Experience
     * Bottle", so it can't match), and any pet tile whose name holds "Guardian" (every rarity shares the name).
     */
    private static boolean isUserSkipped(Cell cell) {
        if (cell == null || cell.name() == null) {
            return false;
        }
        ExperimentsConfig cfg = ExperimentsConfig.getInstance();
        String name = cell.name();
        if (cfg.isSkipGrandExpBottles() && name.contains("Grand Experience Bottle")) {
            return true;
        }
        return cfg.isSkipGuardianPets() && name.contains("Guardian");
    }

    /** "Powerup for next click!" - the lore of Instant Find, the one powerup that matches the next click. */
    private static boolean armsNextClick(Cell cell) {
        return cell.lore() != null && cell.lore().toLowerCase(Locale.ROOT).contains("next click");
    }

    private static final Pattern SUPERPAIRS_XP_NAME = Pattern.compile("\\+[\\d,]+ XP");

    /** A plain Experience reward in Superpairs. Mostly a dye-family item named like "144k Enchanting Exp",
     *  but the big one is a LAPIS BLOCK named "+479,095 XP" (2026-10-01 log, three runs), which
     *  {@link #isDyeFamilyItem} does not cover - so it was treated as valuable, spent a powerup's matched
     *  click and got paired ahead of real rewards. Kept separate from isDyeFamilyItem because Ultrasequencer
     *  uses that one for its notes. */
    private static boolean isSuperpairsXpTile(Cell cell) {
        // By NAME first: every XP tile is named "<n>k Enchanting Exp", whatever item draws it. Cocoa beans
        // (the brown dye) were missed by the item check, so a 25k XP pair was treated as valuable, paired
        // first and took the Instant Find match (2026-10-01 09:20 run).
        return cell.name().endsWith("Enchanting Exp")
                || isDyeFamilyItem(cell)
                || cell.itemId().equals("minecraft:cocoa_beans")
                || cell.itemId().equals("minecraft:lapis_block")
                || SUPERPAIRS_XP_NAME.matcher(cell.name()).matches();
    }

    private void reset(Mode selected) {
        mode = selected;
        chronomatron.clear();
        chronomatronRevealLatched = false;
        chronomatronClickIndex = 0;
        chronomatronRoundReadyAtMs = 0;
        ultrasequencer.clear();
        ultrasequencerClickIndex = 0;
        ultrasequencerReady = false;
        ultrasequencerRoundReadyAtMs = 0;
        pairClicks.clear();
        superpairsPairFirstSlot = null;
        superpairsPairRetries.clear();
        queuedPairSlots.clear();
        knownSuperpairsCells.clear();
        superpairsRevealAttempted.clear();
        superpairsPowerupSlot = null;
        superpairsPowerupPending = false;
        superpairsPowerupActivationSlot = null;
        superpairsSingleSpendSlot = null;
        superpairsPowerupSlotsSeen.clear();
        superpairsClickedAtMs.clear();
        superpairsTwoOpenSinceMs = 0;
        superpairsLoggedOnce.clear();
        superpairsTwoOpenWarned = false;
        superpairsAwaitingConfirmSlot = null;
        superpairsAwaitingConfirmPriorCell = null;
        superpairsAwaitingConfirmSinceMs = 0;
        superpairsClickSent = false;
        // Real bug found and fixed (2026-09-07) from a real log: this used to reset to 0, and
        // decideSuperpairsClick's minimum-delay gate checks "now - superpairsLastClickSentAtMs <
        // minDelayMs" - with a real System.currentTimeMillis() timestamp, "now - 0" is always some huge
        // number, so that gate was trivially satisfied (skipped) for the very first click of every fresh
        // board, letting it fire instantly (the log showed slot 10 clicked twice in the same second the
        // board opened). Seeding this with the current time on reset makes the very first click wait out
        // the same minimum delay as every click after it.
        superpairsLastClickSentAtMs = System.currentTimeMillis();
    }

    private static Cell cell(List<Cell> cells, int slot) {
        return cells.stream().filter(c -> c.slot() == slot).findFirst().orElse(null);
    }

    private static boolean isSequenceItem(Cell cell) {
        return isDyeFamilyItem(cell);
    }

    /** Dyes plus their vanilla equivalents (lapis = blue dye, bone meal = white dye) - Ultrasequencer
     *  uses these as its own notes, and Superpairs' plain "Experience" reward tiles always render as
     *  one of these too (per killer560, 2026-09-06), unlike every other reward category which varies. */
    private static boolean isDyeFamilyItem(Cell cell) {
        return cell.itemId().contains("_dye")
                || cell.itemId().equals("minecraft:lapis_lazuli")
                || cell.itemId().equals("minecraft:bone_meal");
    }

    /** Real bug root-caused (2026-09-07) by directly reading SkyHanni's actual, currently-working
     *  Superpairs source ({@code ExperimentationSuperpairApi.kt}, per killer560's "why do you not just use
     *  the skyhanni code directly... instead of trying to reverse engineer the solver") after a real log
     *  showed the confirm-timeout NEVER once detecting a real reveal across an entire round - not
     *  occasional lag, total failure. SkyHanni doesn't determine hidden-vs-revealed by item TYPE at all;
     *  it matches the item's DISPLAY NAME against the exact set of placeholder texts Hypixel uses for a
     *  covered tile ("?", "Click any button!", "Click a second button!", "Next button is instantly
     *  rewarded!" - {@code unknownSuperpairsClickPattern} in their source). The previous version of this
     *  method instead checked item TYPE (excluding stained_glass/clock/glowstone/black_dye) - if the
     *  current Hypixel build's covered tiles don't reliably use a stained-glass item type any more (or a
     *  revealed reward coincidentally does), that check would silently never flip, which lines up exactly
     *  with the observed 100% timeout rate. Pattern adapted directly from their regex, verified against
     *  the real source rather than guessed - only the leading {@code (?:§.)+} (matching legacy color
     *  codes) was dropped, since {@code Cell.name()} here comes from {@code Component.getString()},
     *  which already returns plain text with no embedded color codes to match against. */
    private static final Pattern SUPERPAIRS_HIDDEN_PATTERN = Pattern.compile(
            "\\?|(?:Click a(?: seco)?n[dy]|Next) button(?: is instantly rewarded)?!?");

    // Package-private (not private) so ExperimentsFeature can reuse the exact same classification to
    // decide when to cache/overlay a real item icon over a covered slot (see its superpairsIconCache).
    static boolean isRevealedPair(Cell cell) {
        if (cell.empty() || cell.name().isBlank()) return false;
        return !SUPERPAIRS_HIDDEN_PATTERN.matcher(cell.name()).matches();
    }
}

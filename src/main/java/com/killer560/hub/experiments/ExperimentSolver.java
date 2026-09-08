package com.killer560.hub.experiments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Queue;
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
 *   wasting remaining clicks.</li>
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

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-experiments-solver");

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
    private final Queue<Integer> pairClicks = new ArrayDeque<>();
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
    private static final long SUPERPAIRS_CONFIRM_TIMEOUT_MS = 1000;
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
     *  by exact slot rather than by item. */
    boolean confirmManualUltrasequencerClick(int slot) {
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
            byKey.computeIfAbsent(known.itemId() + "|" + known.name(), k -> new ArrayList<>()).add(slot);
        }
        List<int[]> matches = new ArrayList<>();
        for (List<Integer> slots : byKey.values()) {
            if (slots.size() >= 2) {
                matches.add(new int[]{slots.get(0), slots.get(1)});
            }
        }
        return matches;
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
            names.put(slot, isDyeFamilyItem(known) ? xpAmountLabel(known.name()) : known.name());
        }
        return names;
    }

    /** Extracts just the leading amount token (e.g. "131k") from a real XP tile's display name like
     *  "131k Enchanting Exp" - falls back to the full name if it doesn't match that pattern, so an
     *  unexpected format degrades to the old behavior instead of showing a blank label. */
    private static String xpAmountLabel(String name) {
        String stripped = name.replaceAll("§.", "").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([\\d.,]+[kKmM]?)\\b").matcher(stripped);
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
            boolean changed = current != null
                    && (isRevealedPair(current) != isRevealedPair(superpairsAwaitingConfirmPriorCell)
                        || current.empty() != superpairsAwaitingConfirmPriorCell.empty());
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
                    && now - superpairsAwaitingConfirmSinceMs > SUPERPAIRS_CONFIRM_TIMEOUT_MS;
            if (changed || timedOut) {
                if (timedOut && !changed) {
                    LOGGER.warn("Superpairs click on slot {} never confirmed within {}ms - prior=[itemId={}, "
                            + "empty={}, name='{}'] current={} - giving up waiting and letting the next decision proceed",
                            superpairsAwaitingConfirmSlot, SUPERPAIRS_CONFIRM_TIMEOUT_MS,
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
                if (superpairsPowerupSlot == null && !queuedPairSlots.contains(slot)) {
                    superpairsPowerupSlot = slot;
                }
                continue;
            }
            // Diagnostic (2026-09-07): logged only the first time a slot is learned, to correlate
            // against the confirm-timeout warnings above - if a slot logs "learned" here shortly after
            // (or well after) its own click timed out above, that proves the reveal DID land and the
            // confirm-check itself has the bug; if it never logs at all, the reveal genuinely never
            // happened for that slot.
            if (!knownSuperpairsCells.containsKey(slot)) {
                LOGGER.info("Superpairs learned slot {}: itemId={} name='{}'", slot, cell.itemId(), cell.name());
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
        OptionalInt click = decideSuperpairsClickInternal(cells, valuableOnly);
        if (click.isPresent()) {
            int slot = click.getAsInt();
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
    void superpairsClickSent(int slot, long sentAtMs) {
        if (superpairsAwaitingConfirmSlot != null && superpairsAwaitingConfirmSlot == slot) {
            superpairsClickSent = true;
            superpairsAwaitingConfirmSinceMs = sentAtMs;
            superpairsLastClickSentAtMs = sentAtMs;
        }
    }

    private OptionalInt decideSuperpairsClickInternal(List<Cell> cells, boolean valuableOnly) {
        Map<Integer, Cell> bySlot = new HashMap<>();
        for (Cell cell : cells) {
            bySlot.put(cell.slot(), cell);
        }

        // The bonus tile makes the very next click an automatic match - spend it on the best known
        // target: a specific known book first, else (if pairing everything) the highest-count known
        // dye, else fall through to whatever the normal priority below picks.
        if (superpairsPowerupPending) {
            superpairsPowerupPending = false;
            OptionalInt best = bestKnownSingleTarget(valuableOnly, bySlot);
            if (best.isPresent()) {
                queuedPairSlots.add(best.getAsInt());
                return best;
            }
        }

        // A discovered-but-not-yet-activated bonus tile takes priority over normal exploration -
        // activating it is free value (bonus clicks) and arms the next click's auto-match.
        if (superpairsPowerupSlot != null && !queuedPairSlots.contains(superpairsPowerupSlot)) {
            int slot = superpairsPowerupSlot;
            superpairsPowerupSlot = null;
            superpairsPowerupPending = true;
            queuedPairSlots.add(slot);
            return OptionalInt.of(slot);
        }

        // A fully-known matching pair beats revealing anything new - queue it before doing more
        // exploration. Per killer560's "is this xp? skip if yes, else its valuable and something i need to
        // match" (2026-09-07): XP/dye pairs are skipped from this PRIORITY search when valuableOnly is
        // on (same as always) - they still get matched eventually via the fully-explored fallback below
        // (matchHighestValueDyePair) rather than being forgotten, since knownSuperpairsCells remembers
        // them unconditionally now.
        if (pairClicks.isEmpty()) {
            Map<String, Integer> firstSeenThisPass = new HashMap<>();
            for (int slot : SUPERPAIRS_SNAKE_ORDER) {
                if (queuedPairSlots.contains(slot)) continue;
                Cell known = knownSuperpairsCells.get(slot);
                if (known == null) continue;
                if (valuableOnly && !isValuablePair(known)) continue;
                String key = known.itemId() + "|" + known.name();
                Integer first = firstSeenThisPass.putIfAbsent(key, slot);
                if (first != null) {
                    pairClicks.add(first);
                    pairClicks.add(slot);
                    queuedPairSlots.add(first);
                    queuedPairSlots.add(slot);
                    break;
                }
            }
        }

        if (!pairClicks.isEmpty()) {
            return OptionalInt.of(pairClicks.poll());
        }

        // Nothing known matches yet - click an unrevealed (still-covered) tile to learn what's under
        // it, the actual "flip a memory-game tile" mechanic Superpairs uses, unlike Chronomatron/
        // Ultrasequencer whose data is fully visible up front without needing to click anything.
        boolean boardFullyExplored = true;
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (superpairsRevealAttempted.contains(slot) || queuedPairSlots.contains(slot) || knownSuperpairsCells.containsKey(slot)) continue;
            Cell cell = bySlot.get(slot);
            if (cell == null) continue;
            // Same border-filler exclusion as superpairsSuggestedExploreSlot - never worth a click.
            if (isRevealedPair(cell) || isDyeFamilyItem(cell) || isPowerupTile(cell) || cell.name().isBlank()) continue;
            boardFullyExplored = false;
            superpairsRevealAttempted.add(slot);
            return OptionalInt.of(slot);
        }

        // Per killer560's request: once every spot has been discovered and there's nothing left to
        // explore or match under the normal (possibly XP-skipping) rules, don't waste remaining
        // clicks - fall back to matching whatever dye pairs were skipped, highest stack count first.
        if (boardFullyExplored) {
            if (valuableOnly) {
                OptionalInt dyeMatch = matchHighestValueDyePair();
                if (dyeMatch.isPresent()) {
                    return dyeMatch;
                }
            }
            // Field-tested (2026-09-06): a click could still be stuck unspent even after the dye
            // fallback above (or in "Every Pair" mode, where that fallback never runs at all) - e.g.
            // a single leftover known tile whose real partner was already claimed elsewhere. Per
            // killer560's "it gets stuck with only 1 click left so just have it click any of them" -
            // rather than sit on an unused click forever, just spend it on any remaining known tile.
            return clickAnyRemainingKnownTile();
        }
        return OptionalInt.empty();
    }

    /** Last-resort fallback once nothing else is left to explore or deliberately match - spends a
     *  click that would otherwise go unused on literally any still-available known tile (valuable or
     *  dye), per killer560's explicit request not to let the solver get stuck holding an unspent click. */
    private OptionalInt clickAnyRemainingKnownTile() {
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (queuedPairSlots.contains(slot)) continue;
            if (knownSuperpairsCells.containsKey(slot)) {
                queuedPairSlots.add(slot);
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /** Matches the highest-stack-count dye/XP pair still sitting unclaimed in
     *  {@link #knownSuperpairsCells} - the last-resort fallback once the board is fully explored and
     *  nothing else is left to do. */
    private OptionalInt matchHighestValueDyePair() {
        Map<String, List<Cell>> byKey = new HashMap<>();
        for (Cell cell : knownSuperpairsCells.values()) {
            if (!isDyeFamilyItem(cell) || queuedPairSlots.contains(cell.slot())) continue;
            byKey.computeIfAbsent(cell.itemId() + "|" + cell.name(), k -> new ArrayList<>()).add(cell);
        }
        List<Cell> bestPair = null;
        int bestCount = -1;
        for (List<Cell> group : byKey.values()) {
            if (group.size() < 2) continue;
            int count = group.get(0).count();
            if (count > bestCount) {
                bestCount = count;
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
     *  letting the normal reveal/match priority take over instead. */
    private OptionalInt bestKnownSingleTarget(boolean valuableOnly, Map<Integer, Cell> bySlot) {
        for (int slot : SUPERPAIRS_SNAKE_ORDER) {
            if (queuedPairSlots.contains(slot)) continue;
            Cell known = knownSuperpairsCells.get(slot);
            if (known == null || bySlot.get(slot) == null) continue;
            if (valuableOnly && !isValuablePair(known)) continue;
            return OptionalInt.of(slot);
        }
        if (!valuableOnly) {
            Integer bestDyeSlot = null;
            int bestDyeCount = -1;
            for (Cell cell : knownSuperpairsCells.values()) {
                if (!isDyeFamilyItem(cell) || queuedPairSlots.contains(cell.slot())) continue;
                if (cell.count() > bestDyeCount) {
                    bestDyeCount = cell.count();
                    bestDyeSlot = cell.slot();
                }
            }
            if (bestDyeSlot != null) {
                return OptionalInt.of(bestDyeSlot);
            }
        }
        return OptionalInt.empty();
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
        return !isDyeFamilyItem(cell);
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
        queuedPairSlots.clear();
        knownSuperpairsCells.clear();
        superpairsRevealAttempted.clear();
        superpairsPowerupSlot = null;
        superpairsPowerupPending = false;
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

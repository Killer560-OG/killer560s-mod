package com.killer560.hub.leapcounter;

import com.killer560.hub.fastleap.Floor7Tracker;
import com.killer560.hub.leapmenu.PartyTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Counts how many party members have spirit-leapt TO you while you stand on a leap spot in the F7/M7 boss
 * (2026-09-16, killer560: "alert me when X amount of people have leaped to me. For S2 it should be 4, S3 it should
 * only be 3, and S4 it should be 4 again"). Polled once per client tick from {@link LeapCounterFeature}; the public
 * API ({@link #count()}, {@link #target()}, {@link #isComplete()}, {@link #reset()}, {@link #countSince(long)}) is
 * what AP3's leap-detector node will read once it is rewired.
 * <p>
 * A blend of two ports:
 * <ul>
 * <li><b>NoammAddons {@code features/impl/floor7/LeapCounter.kt}</b> - counts a teammate the moment a movement /
 * teleport / add-entity packet puts them in the right P3 section while you stand in one of seven hardcoded boxes,
 * deduped by entity id, target capped at {@code min(teammates excluding you, section max)}. Its packet check is
 * really encoding "they got here by a jump, not on foot" - that idea is kept, the boxes are not (they are that mod's
 * coordinates and would break the moment Hypixel moves anything, and killer560 places no nodes for this feature).</li>
 * <li><b>Devonian {@code features/dungeons/f7/LeapCounter.kt}</b> - per-tick polling of teammate entities within a
 * radius (kept, no packet mixin needed), and its important trick: Hypixel prints "You have teleported to Name!"
 * when YOU leap, and everything is suppressed for 3 seconds after that line - otherwise everyone already standing
 * near the spot you just landed on is miscounted as having leapt to you (kept). Devonian compares the player's own
 * SQUARED distance against an UNSQUARED radius ({@code distanceToSqr(...) > it.dist}); every distance here is
 * compared squared-against-squared.</li>
 * </ul>
 * <h2>How an arrival is judged a leap</h2>
 * Every tracked teammate keeps a short ring of {@code (tick, position)} samples. A teammate counts when, on one tick,
 * they are within {@code radius} of you, not yet counted, and EITHER moved at least {@code jumpDistance} blocks
 * since a sample at most {@link #WINDOW_TICKS} ticks old that was itself outside the radius, OR just became tracked
 * (no samples: an entity outside the server's tracking range is not in the client level at all until it is re-added
 * right next to you). The window is several ticks wide on purpose, not a single-tick delta: a remote player's
 * teleport is interpolated client-side over 3 ticks unless it is more than 64 blocks - checked with javap on the
 * 26.1.2 jar, {@code InterpolationHandler(Entity)} constructs with 3 steps and
 * {@code ClientPacketListener.handleEntityPositionSync} only {@code snapTo}s past {@code distanceToSqr > 4096} - so a
 * single-tick delta would see only a third of a short leap. Hypixel's 1.8 server sends any move of 4+ blocks as a
 * teleport, so every leap arrives that way. Sprinting is ~0.28 blocks/tick at base speed; even at the 500 speed
 * cap that is ~1.4/tick, i.e. under 7 blocks over the 5-tick window, which is why 8 is the default threshold and
 * why a walker must ALSO have been outside the radius at the start of the window. Anything that teleports a
 * teammate to you counts - a leap, an etherwarp landing on you - exactly as in both reference mods.
 * <h2>"Standing on a leap spot"</h2>
 * There are no spot coordinates. The rule is: you are in a countable section (P3 S1-S4, the core once "The Core
 * entrance is opening!" moves the stage to S5, or P5 for the relic) and not walking (own movement under
 * {@link #STATIONARY_MAX_DELTA} blocks that tick). Your position when the first leap lands becomes the anchor;
 * moving more than {@code radius} from it means you left the spot and the count resets. Section changes and your
 * own leap also reset it. Section detection is {@link Floor7Tracker} only ({@code getPhaseAt()} / {@code
 * getStageAt()}, falling back to the chat-driven stage when you stand between section boxes).
 */
public final class LeapTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-leapcounter");

    /** Where you can be leapt to. S1-S4 are Floor7Tracker's P3 stages, CORE its S5, RELIC is P5. */
    public enum Section {
        S1, S2, S3, S4, CORE, RELIC;

        public String label() {
            return switch (this) {
                case CORE -> "Core";
                case RELIC -> "Relic";
                default -> name();
            };
        }
    }

    /** Devonian's own-leap regex, verbatim. */
    private static final Pattern SELF_LEAP = Pattern.compile("^You have teleported to \\w{1,16}!$");
    /** Devonian's 3 seconds. */
    private static final long SELF_LEAP_SUPPRESS_MS = 3000L;
    /** How far back a teammate's old position may be for the displacement check (see class doc). */
    private static final int WINDOW_TICKS = 5;
    /** Ticks after entering a section during which a "just became tracked" arrival is NOT counted: on P3 start (and
     *  on a world load into boss) the whole party appears at once around you, and none of them leapt. */
    private static final int WARMUP_TICKS = 10;
    /** Own movement per tick above this = walking, not standing on a spot (sprint is ~0.28/tick). */
    private static final double STATIONARY_MAX_DELTA = 0.2;

    private record Sample(int tick, Vec3 pos) {
    }

    /** Entity id -> recent positions, newest last. Kept across section changes, cleared on world change. */
    private static final Map<Integer, ArrayDeque<Sample>> HISTORY = new HashMap<>();
    /** Entity id -> wall-clock ms it was counted (insertion order = arrival order). */
    private static final Map<Integer, Long> COUNTED = new LinkedHashMap<>();

    private static Object lastLevel = null;
    private static int tick = 0;
    private static int ticksInSection = 0;
    private static Section section = null;
    private static int target = 0;
    private static long lastSelfLeapMs = 0L;
    private static Vec3 lastSelfPos = null;
    private static Vec3 anchor = null;
    private static boolean complete = false;
    private static boolean completionPending = false;

    private LeapTracker() {
    }

    // ---- public API (names fixed: AP3 will call these) ----

    /** Teammates counted as having leapt to you at the current spot. */
    public static int count() {
        return COUNTED.size();
    }

    /** Leaps expected here: {@code min(alive teammates excluding you, configured count for this section)}, or 0
     *  when you are not somewhere leaps are counted. */
    public static int target() {
        return section == null ? 0 : target;
    }

    /** True from the tick the target was reached until the next reset (you leave the spot, leap, or change section). */
    public static boolean isComplete() {
        return complete;
    }

    /** Forgets every counted teammate at the current spot (the anchor re-follows you). Position history is kept, so a
     *  teammate who walked in before the reset still cannot be counted afterwards. */
    public static void reset() {
        if (!COUNTED.isEmpty() || complete) {
            LOGGER.info("[LeapCounter] Reset ({} counted, section {})", COUNTED.size(), section);
        }
        COUNTED.clear();
        complete = false;
        completionPending = false;
        anchor = null;
    }

    /** Teammates counted at or after {@code sinceMs} (wall-clock, {@link System#currentTimeMillis()}) - so a caller
     *  that starts waiting mid-spot can ignore leaps that landed before it asked. */
    public static int countSince(long sinceMs) {
        int n = 0;
        for (long at : COUNTED.values()) {
            if (at >= sinceMs) {
                n++;
            }
        }
        return n;
    }

    /** The section you are being leapt to in, or null. */
    public static Section section() {
        return section;
    }

    /** True once per completion - the feature turns this into the title/sound. */
    static boolean consumeCompletion() {
        boolean pending = completionPending;
        completionPending = false;
        return pending;
    }

    // ---- inputs ----

    /** Every real chat line, already stripped of formatting. */
    static void onChat(String plain) {
        if (plain == null || !SELF_LEAP.matcher(plain).matches()) {
            return;
        }
        // Devonian: nothing counts for 3 s after your own leap. Also a new spot - the old count is meaningless.
        lastSelfLeapMs = System.currentTimeMillis();
        reset();
    }

    /** Feature is off / no world: drop everything so a stale count can't survive to the next run. */
    static void deactivate() {
        if (section != null || !COUNTED.isEmpty()) {
            reset();
        }
        section = null;
        target = 0;
        ticksInSection = 0;
        lastSelfPos = null;
    }

    static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            deactivate();
            HISTORY.clear();
            lastSelfLeapMs = 0L;
        }
        if (client.player == null || client.level == null) {
            deactivate();
            return;
        }
        tick++;
        Section now = currentSection();
        if (now == null) {
            deactivate();
            return;
        }
        if (now != section) {
            reset();
            section = now;
            ticksInSection = 0;
            LOGGER.info("[LeapCounter] Section -> {}", now);
        }
        ticksInSection++;

        LeapCounterConfig cfg = LeapCounterConfig.getInstance();
        int configured = cfg.countFor(section);
        List<String> party = PartyTracker.teammates();
        target = capTarget(configured, party);

        Vec3 self = client.player.position();
        boolean selfMoving = lastSelfPos != null
                && lastSelfPos.distanceToSqr(self) > STATIONARY_MAX_DELTA * STATIONARY_MAX_DELTA;
        lastSelfPos = self;

        double radius = cfg.getRadius();
        double radiusSq = radius * radius;
        double jumpSq = (double) cfg.getJumpDistance() * cfg.getJumpDistance();

        // The anchor follows you until the first leap lands; after that, walking off the spot resets the count.
        if (COUNTED.isEmpty()) {
            anchor = self;
        } else if (anchor != null && anchor.distanceToSqr(self) > radiusSq) {
            LOGGER.info("[LeapCounter] Left the spot ({} counted)", COUNTED.size());
            reset();
            anchor = self;
        }

        long nowMs = System.currentTimeMillis();
        boolean suppressed = lastSelfLeapMs != 0L && nowMs - lastSelfLeapMs < SELF_LEAP_SUPPRESS_MS;
        // A nudge on the exact arrival tick doesn't lose the leap: the displacement check below compares against
        // the OLDEST sample in the window, so the arrival stays visible for WINDOW_TICKS more ticks.
        boolean canCount = !suppressed && !selfMoving && configured > 0;

        for (AbstractClientPlayer p : client.level.players()) {
            if (p == client.player || p.isRemoved() || !isTeammate(p, party)) {
                continue;
            }
            int id = p.getId();
            Vec3 pos = p.position();
            ArrayDeque<Sample> hist = HISTORY.computeIfAbsent(id, k -> new ArrayDeque<>());
            // A gap longer than the window means the entity was out of tracking range (not in level.players()) -
            // treat it as newly tracked rather than measuring a displacement against a stale sample.
            Sample newest = hist.peekLast();
            if (newest != null && tick - newest.tick() > WINDOW_TICKS) {
                hist.clear();
            }
            boolean newlyTracked = hist.isEmpty();
            boolean jumped = false;
            if (!newlyTracked) {
                Sample oldest = hist.peekFirst();
                jumped = oldest.pos().distanceToSqr(pos) >= jumpSq && oldest.pos().distanceToSqr(self) > radiusSq;
            }
            hist.addLast(new Sample(tick, pos));
            while (hist.size() > WINDOW_TICKS + 1) {
                hist.pollFirst();
            }

            if (!canCount || COUNTED.containsKey(id) || pos.distanceToSqr(self) > radiusSq) {
                continue;
            }
            String name = p.getGameProfile().name();
            if (PartyTracker.isDead(name)) {
                // A ghost can't leap, and spectator flight is fast enough to look like one.
                continue;
            }
            boolean arrivedByJump = jumped || (newlyTracked && ticksInSection >= WARMUP_TICKS);
            if (!arrivedByJump) {
                continue;
            }
            COUNTED.put(id, nowMs);
            LOGGER.info("[LeapCounter] {} leapt to you ({}) - {}/{} in {}", name, jumped ? "jump" : "appeared",
                    COUNTED.size(), target, section);
        }
        // Stale entries (teammate left the party / disconnected) so the map can't grow across a long session.
        Iterator<Map.Entry<Integer, ArrayDeque<Sample>>> it = HISTORY.entrySet().iterator();
        while (it.hasNext()) {
            Sample last = it.next().getValue().peekLast();
            if (last == null || tick - last.tick() > 20 * 60) {
                it.remove();
            }
        }

        if (!complete && target > 0 && COUNTED.size() >= target) {
            complete = true;
            completionPending = true;
            LOGGER.info("[LeapCounter] Complete: {}/{} in {}", COUNTED.size(), target, section);
        }
    }

    // ---- helpers ----

    /** NoammAddons' {@code min(dungeonTeammatesNoSelf.size, maxCount)} - a 4-man party must not wait for a 5th leap.
     *  Only alive teammates can leap. With no party known at all (p3sim.net, or the tab list not parsed yet) the
     *  configured number is used as-is rather than capping to 0. */
    private static int capTarget(int configured, List<String> party) {
        if (configured <= 0) {
            return 0;
        }
        int alive = 0;
        for (String name : party) {
            if (!PartyTracker.isDead(name)) {
                alive++;
            }
        }
        return alive <= 0 ? configured : Math.min(alive, configured);
    }

    /** {@code teammates/TeammatesFeature.isTeammate}: a real (v4-UUID, not a Hypixel NPC) player the party tracker
     *  lists, or one the dungeon tab-list class parse knows; with nothing known at all (p3sim.net) every real player
     *  counts, since in a real dungeon the only other real players are your party. */
    private static boolean isTeammate(Player player, List<String> party) {
        if (player.getUUID().version() != 4) {
            return false;
        }
        String name = player.getGameProfile().name();
        for (String member : party) {
            if (member.equalsIgnoreCase(name)) {
                return true;
            }
        }
        if (PartyTracker.classOf(name) != null) {
            return true;
        }
        return party.isEmpty();
    }

    /** Position-based first (it is where YOU stand that matters), chat-driven stage as the fallback for the gaps
     *  between Floor7Tracker's section boxes. Null anywhere leaps aren't counted. */
    private static Section currentSection() {
        if (!Floor7Tracker.inF7Boss()) {
            return null;
        }
        Floor7Tracker.Phase phase = Floor7Tracker.getPhaseAt();
        if (phase == Floor7Tracker.Phase.P5) {
            return Section.RELIC;
        }
        if (phase != Floor7Tracker.Phase.P3) {
            return null;
        }
        Floor7Tracker.Stage stage = Floor7Tracker.getStageAt();
        if (stage == Floor7Tracker.Stage.UNKNOWN) {
            stage = Floor7Tracker.getStage();
        }
        return switch (stage) {
            case S1 -> Section.S1;
            case S2 -> Section.S2;
            case S3 -> Section.S3;
            case S4 -> Section.S4;
            case S5 -> Section.CORE;
            default -> null;
        };
    }
}

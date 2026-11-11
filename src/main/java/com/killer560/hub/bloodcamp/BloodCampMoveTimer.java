package com.killer560.hub.bloodcamp;

import com.killer560.hub.gui.ThemeAccent;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.ModChat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import com.killer560.hub.util.ModLog;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * "Kill Popup" - killer560 (2026-09-21): "make it so it gives a hud popup saying kill as an option for time to kill
 * to skip dialogue."
 * <p>
 * The signal is not the mobs, it is the Watcher's own schedule. He greets the room with one of
 * {@link #BLOOD_OPEN} when the blood door opens, and says {@link #BLOOD_MOVE} ("Let's see how you can handle this.")
 * when he sends the last wave out. From the gap between those two lines the moment he MOVES can be predicted, and
 * killing the remaining mobs on that moment is what skips his closing dialogue. The bucket table below is the same
 * one SkyHanni's own {@code features/dungeon/BloodTimer} and Odin's own {@code dungeon/BloodCamp} use (both
 * javap-read on the real jars, 2026-09-21, and they agree constant for constant):
 * <pre>
 *   gap 31-34s -&gt; he moves 36s after the greeting     gap 25-28s -&gt; 30s
 *   gap 28-31s -&gt; 33s                                 gap 22-25s -&gt; 27s
 *   gap  1-22s -&gt; 24s                                 anything else -&gt; gap + 3s (Odin's generalization)
 * </pre>
 * i.e. the move lands on the next 3-second boundary a couple of seconds after the last wave goes out. SkyHanni fires
 * its own "Kill Blood" title 150 ms (3 ticks) before that instant; that lead is {@link BloodCampConfig#getKillPopupLeadTicks()}
 * here so killer560 can tune it against his own ping instead of living with a hardcoded number.
 * <b>The table is no longer used</b> (bctrig-1104): since SkyBlock 0.27.2 every Watcher moves at a fixed time per floor
 * after his greeting whatever the gap ({@link #fastWatcherMoveTicks}), timed on {@link #elapsedSinceGreeting}.
 * <p>
 * Timed in {@code ClientLevel#getGameTime()} ticks, not wall clock, for the same reason the rest of this feature is:
 * it is the one clock the Watcher and this mod share. Unlike SkyHanni there is no separate server-time mark to take a
 * client/server drift correction from, so the prediction carries whatever drift the tick clock has - flagged, not
 * fixed, because it cannot be checked without a live blood room.
 * <p>
 * Also owns {@link #isFirstSpawns()}: the Watcher's first wave settles 40 ticks slower than every later one, and that
 * "first" state ends exactly on the {@link #BLOOD_MOVE} line (Odin sets its own {@code firstSpawns = false} on that
 * same line). {@link BloodCampFeature} used to clear its own copy after the first tracked mob instead, which made
 * every other mob of the first wave count down two seconds early.
 */
public final class BloodCampMoveTimer {

    private static final Logger LOGGER = ModLog.get("killer560smod-bloodcamp");

    /** The Watcher's greeting when the blood door opens - verbatim from Odin's own {@code BLOOD_START_REGEX}
     *  (the same list {@code splittimers.SplitTimersFeature} already matches on). */
    private static final Pattern BLOOD_OPEN = Pattern.compile(
            "^\\[BOSS] The Watcher: (Congratulations, you made it through the Entrance\\.|Ah, you've finally arrived\\.|"
                    + "Ah, we meet again\\.\\.\\.|Ah, we meet again\\. As I foresaw\\.\\.\\.|So you made it this far\\.\\.\\. interesting\\.|"
                    + "You've managed to scratch and claw your way here, eh\\?|"
                    + "I'm starting to get tired of seeing you around here\\.\\.\\.|Oh\\.\\. hello\\?|"
                    + "Things feel a little more roomy now, eh\\?)$");

    /** Any Watcher line. NoammAddons 1.2.9 {@code DungeonListener} takes the FIRST of these in a run as the blood
     *  opening ({@code watcherMessageRegex}, which posts its new {@code BloodOpenEvent}), so a greeting text this list
     *  does not know yet - 0.27.2 already changed one - still starts the clock. */
    private static final Pattern WATCHER_ANY = Pattern.compile("^\\[BOSS] The Watcher: .+$");

    /** The last wave going out - Odin's own {@code BLOOD_MOVE_REGEX}. */
    private static final Pattern BLOOD_MOVE =
            Pattern.compile("^\\[BOSS] The Watcher: Let's see how you can handle this\\.$");

    /** How long "KILL" stays up once the window opens - SkyHanni's own title length. */
    private static final int KILL_HOLD_TICKS = 30;
    /** The countdown only appears this close to the move, so the popup is not a permanent fixture. */
    private static final int COUNTDOWN_SHOW_TICKS = 200;

    private static long bloodOpenTick = -1L;
    /** {@code util.PerTickServerClock} at the greeting: every timing below counts server ticks since it, stalls left out
     *  (2026-11-01, NoammAddons times its move timer in server ticks too). */
    private static long[] bloodOpenMark;
    private static long bloodOpenClock;
    /** Lag-free ticks after the greeting at which the Watcher moves, or -1 with no prediction. */
    private static long moveAtElapsed = -1L;
    private static boolean firstSpawns = true;
    /** "That will be enough for now." - the Watcher has sent his last mob. */
    private static boolean spawningDone;

    private BloodCampMoveTimer() {
    }

    static void register() {
        // ChatObserver, not Fabric's CHAT/GAME: Odin/NoammAddons/Skyblocker cancel and re-add boss lines, which
        // skips Fabric's events for everyone else (see ChatObserver's class doc).
        ChatObserver.subscribe(BloodCampMoveTimer::onChat);
    }

    /** True until the Watcher sends the last wave out - his first wave settles 40 ticks slower. */
    static boolean isFirstSpawns() {
        return firstSpawns;
    }

    /** Testkit: whether {@link #killTimerAllows} lets an attack through now at {@code lateTicks}. */
    public static boolean killTimerAllowsForTest(int lateTicks) {
        return killTimerAllows(lateTicks);
    }

    /** True once the Watcher said "That will be enough for now." (no more mobs come). */
    static boolean isSpawningDone() {
        return spawningDone;
    }

    /** Whether the greeting was seen in this level. */
    static boolean greetingSeen() {
        return bloodOpenTick >= 0;
    }

    /**
     * Server ticks since the greeting, or -1 without one: the LOWER of the per-tick ping clock and the level's game time.
     * Hypixel sends more pings than ticks in the Blood Room: his 2026-11-04 log has trusted windows of 20-24 pings per 20
     * ticks, and over whole camps the ping clock ran up to 84 ticks ahead of the game time (00:09 camp: 417 pings to 333
     * game ticks between the greeting and KILL, wall 16-18 s), so KILL came up to 4 s early in some camps and the "Last
     * wave out" gap read 2-3 s long in others. The game time is the server's own tick count (set-time packets) and does
     * not count extra pings; the ping clock stands still through a stall, where the game time runs on until the next
     * set-time packet. Either one only ever runs AHEAD of the server, so the lower one is the server (bctrig-1104).
     */
    static long elapsedSinceGreeting() {
        long now = nowTick();
        if (bloodOpenTick < 0 || now < 0) {
            return -1L;
        }
        long byClock = com.killer560.hub.util.PerTickServerClock.now() - bloodOpenClock;
        long byGameTime = now - bloodOpenTick;
        return byGameTime < 0 ? byClock : Math.min(byClock, byGameTime);
    }

    /** Game ticks since the greeting, or -1 without one (the client's game time, stalls included until corrected). */
    private static long gameTicksSinceGreeting() {
        long now = nowTick();
        return bloodOpenTick < 0 || now < bloodOpenTick ? -1L : now - bloodOpenTick;
    }

    /** The server's stall since the greeting, ms (Blood Camp Stats' camp lag). */
    static long lagMsSinceGreeting() {
        return bloodOpenTick < 0 ? 0L : com.killer560.hub.util.PerTickServerClock.stallTicksSince(bloodOpenMark) * 50L;
    }

    static void reset() {
        if (bloodOpenTick >= 0 || moveAtElapsed >= 0 || !firstSpawns) {
            LOGGER.info("[BloodCamp] move timer reset (greeting tick {}, move tick {}, firstSpawns {})", bloodOpenTick,
                    moveAtElapsed, firstSpawns);
        }
        bloodOpenTick = -1L;
        moveAtElapsed = -1L;
        firstSpawns = true;
        spawningDone = false;
    }

    private static void onChat(Component message) {
        String plain = ChatObserver.strip(message).trim();
        if (plain.isEmpty() || !plain.startsWith("[BOSS] The Watcher:")) {
            return;
        }
        long now = nowTick();
        if (now < 0) {
            LOGGER.info("[BloodCamp] Watcher line ignored: no level loaded");
            return;
        }
        if (BLOOD_MOVE.matcher(plain).matches()) {
            firstSpawns = false;
            if (bloodOpenTick < 0) {
                // Joined mid-blood, or the greeting was eaten by another mod's chat rewrite.
                LOGGER.info("[BloodCamp] Watcher's last-wave line seen with no greeting - no move prediction.");
                return;
            }
            long elapsed = elapsedSinceGreeting();
            double gapSeconds = elapsed / 20.0;
            // Every Watcher moves at the floor's fixed time now, whatever the gap (bctrig-1104; killer560, 2026-11-04: "some
            // watchers the kill still comes up way too late"). In 25 camps of his 2026-11-03/04 logs the first skull after
            // the move left the wall 446-474 game ticks after the greeting with gaps from 13.05 s to 22.35 s, the same for
            // the gaps the old table took (18 s and up: 24-27 s, KILL 60 ticks late) as for the rest. The gap is logged only.
            moveAtElapsed = fastWatcherMoveTicks(com.killer560.hub.secrets.DungeonState.getFloor());
            LOGGER.info("[BloodCamp] Last wave out {} s after the greeting ({} game ticks, {} on the ping clock) on {} -> kill"
                            + " {} server ticks after the greeting ({} ticks away, {} ms of server lag left out).",
                    String.format(Locale.US, "%.2f", gapSeconds), gameTicksSinceGreeting(),
                    com.killer560.hub.util.PerTickServerClock.now() - bloodOpenClock,
                    com.killer560.hub.secrets.DungeonState.getFloor(), moveAtElapsed, moveAtElapsed - elapsed,
                    lagMsSinceGreeting());
        } else if (SPAWNING_DONE.matcher(plain).matches()) {
            spawningDone = true;
            LOGGER.info("[BloodCamp] Watcher: last mob sent (\"That will be enough for now.\") at tick {}", now);
        } else if (CAMP_DONE.matcher(plain).matches()) {
            spawningDone = true;
            LOGGER.info("[BloodCamp] Watcher: camp cleared (\"You have proven yourself.\") at tick {}", now);
            // The numbers the sim's camp estimates (docs/SIM.md, Blood camp): read these to refine them.
            LOGGER.info("[BloodCamp] camp census: floor {}, {} skull trips ({} first wave), cleared {} game ticks after the"
                            + " greeting", com.killer560.hub.secrets.DungeonState.getFloor(), censusTrips, censusFirst,
                    bloodOpenTick < 0 ? -1 : gameTicksSinceGreeting());
            BloodCampStats.onCampCleared();
        } else if (BLOOD_OPEN.matcher(plain).matches() || (bloodOpenTick < 0 && WATCHER_ANY.matcher(plain).matches())) {
            bloodOpenTick = now;
            bloodOpenMark = com.killer560.hub.util.PerTickServerClock.mark();
            bloodOpenClock = com.killer560.hub.util.PerTickServerClock.now();
            moveAtElapsed = -1L;
            firstSpawns = true;
            spawningDone = false;
            censusTrips = censusFirst = 0;
            LOGGER.info("[BloodCamp] Watcher greeting seen at tick {} - move prediction armed.", now);
            BloodCampStats.onGreeting();
        } else {
            LOGGER.debug("[BloodCamp] other Watcher line at tick {} (greeting tick {}, move after {})", now, bloodOpenTick,
                    moveAtElapsed);
        }
    }

    /** Kill time for a floor this cannot read, in ticks after the Watcher's first line: NoammAddons 1.2.9
     *  {@code dungeon/BloodCamp.kt} dropped the gap table (SkyHanni/Odin's, which this class's doc describes) and applies
     *  its per-floor time to every run. Since bctrig-1104 so does this class: the old table's long-gap buckets put KILL
     *  60 ticks after the Watcher had moved (see onChat). */
    static final int FAST_WATCHER_MOVE_TICKS = 400;

    /**
     * NoammAddons' per-floor move times (its BloodCamp.kt {@code moveTimes}, "feat(BloodCamp): Watcher Move Timer",
     * 2026-10-16, CC0): server ticks after the first Watcher line, indexed by floor number - Entrance 480, F1 600, F2-F4
     * 500, F5-F6 470, F7 420 (Master Mode by the same number). It replaced the flat 400 ("watcher always moves after 400
     * server ticks", 2026-10-05) this class had copied. A floor this cannot read keeps 400.
     */
    private static final int[] FLOOR_MOVE_TICKS = {480, 600, 500, 500, 500, 470, 470, 420};

    /** Camp census (simblood-1110): skull trips since the greeting, and how many of them were first-wave. */
    private static int censusTrips;
    private static int censusFirst;

    static void countTrip(boolean firstWave) {
        censusTrips++;
        if (firstWave) censusFirst++;
    }

    /** The floor's Watcher move, in server ticks after the greeting (also the sim's camp, simblood-1110). */
    public static int fastWatcherMoveTicks(String floor) {
        if (floor == null || floor.isEmpty()) {
            return FAST_WATCHER_MOVE_TICKS;
        }
        char last = floor.charAt(floor.length() - 1);
        if (floor.equalsIgnoreCase("E") || floor.toUpperCase(Locale.ROOT).startsWith("E")) {
            return FLOOR_MOVE_TICKS[0];
        }
        if (last >= '1' && last <= '7') {
            return FLOOR_MOVE_TICKS[last - '0'];
        }
        return FAST_WATCHER_MOVE_TICKS;
    }

    /** "That will be enough for now." - the Watcher's last summon (NoammAddons' DungeonListener reads the same line). */
    private static final Pattern SPAWNING_DONE =
            Pattern.compile("^\\[BOSS] The Watcher: That will be enough for now\\.$");
    /** "You have proven yourself. You may pass." - the Blood Room is cleared. */
    private static final Pattern CAMP_DONE =
            Pattern.compile("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.$");

    private static long nowTick() {
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? -1L : client.level.getGameTime();
    }

    /**
     * Whether Auto Blood Camp may attack yet: the kill timer - the moment this popup turns to "KILL" - moved by the
     * Trigger Offset, has come (killer560, 2026-10-18: "Blood just attacked for triggerbot before the kill timer went
     * off"). His log of that run: the Trigger Bot clicked at 19:19:53 on a mob's own countdown, in the same second the
     * popup was armed 100 ticks out. Offset 0 opens on the tick "KILL" shows, -1 (the default) one tick after it, +2 two
     * ticks before it.
     * <ul>
     *   <li>No greeting seen in this level (joined mid-blood, or a greeting another mod rewrote): there is no kill timer
     *   to wait for, so this never holds anything back.</li>
     *   <li>Greeting seen, last wave not out yet: the kill timer has not gone off, so nothing is attacked yet.</li>
     * </ul>
     */
    static boolean killTimerAllows(int lateTicks) {
        return ticksUntilKillOpen(lateTicks) <= 0;
    }

    /**
     * Lag-free ticks until {@link #killTimerAllows} opens at {@code lateTicks} (Trigger Offset, positive late): 0 or less
     * once open, 0 with no greeting (nothing to wait for), {@link Long#MAX_VALUE} / 4 while the last wave is not out.
     */
    static long ticksUntilKillOpen(int lateTicks) {
        if (bloodOpenTick < 0) {
            return 0L;
        }
        if (moveAtElapsed < 0) {
            return Long.MAX_VALUE / 4;
        }
        long elapsed = elapsedSinceGreeting();
        if (elapsed < 0) {
            return 0L;
        }
        return moveAtElapsed - BloodCampConfig.getInstance().getKillPopupLeadTicks() + lateTicks - elapsed;
    }

    /** The game tick the popup turns (or turned) to "KILL" (the predicted move less Kill Popup Lead, plus the server
     *  lag since the greeting), or -1 with no prediction. */
    static long killShownTick() {
        if (moveAtElapsed < 0 || bloodOpenTick < 0) {
            return -1L;
        }
        // The game ticks the server has not run yet (a stall the ping clock stood through) push KILL later by as much.
        long game = gameTicksSinceGreeting();
        long elapsed = elapsedSinceGreeting();
        long behind = game < 0 || elapsed < 0 ? 0L : Math.max(0L, game - elapsed);
        return bloodOpenTick + moveAtElapsed - BloodCampConfig.getInstance().getKillPopupLeadTicks() + behind;
    }

    /** Whether the kill timer ("KILL") has gone off: what Blood Camp Stats counts lost ticks from. */
    static boolean killReached() {
        return ticksUntilKillOpen(0) <= 0 && moveAtElapsed >= 0;
    }

    /** Ticks until the Watcher is predicted to move, or {@link Double#NaN} while there is no prediction. */
    private static double ticksUntilMove() {
        long elapsed = elapsedSinceGreeting();
        if (moveAtElapsed < 0 || elapsed < 0) {
            return Double.NaN;
        }
        return (double) (moveAtElapsed - elapsed);
    }

    private static boolean relevant() {
        BloodCampConfig cfg = BloodCampConfig.getInstance();
        return cfg.isEnabled() && cfg.isKillPopup() && DungeonState.isInDungeon();
    }

    /** "Kill in 3.4s", remembered for the last tick count: the number is a whole tick count, so it changes 20 times a
     *  second while the popup is drawn every frame (a String.format per frame otherwise). */
    private static double lastCountdownTicks = Double.NaN;
    private static String lastCountdownText = "";

    private static String countdownText(double ticks) {
        if (ticks != lastCountdownTicks) {
            lastCountdownText = String.format(Locale.US, "Kill in %.1fs", ticks / 20.0);
            lastCountdownTicks = ticks;
        }
        return lastCountdownText;
    }

    /** killer560, 2026-09-27: "make the kill timer thing larger" - multiplies the old 76x14 footprint the
     *  centering math in {@link #render} was written for. */
    private static final float SCALE = 2.0f;
    private static final int BASE_W = 76;
    private static final int BASE_H = 14;

    /** The Watcher's dialogue is the same on every floor, so this one is not restricted to F7/M7 the way the
     *  mob tracking is. Registered into the HUD editor by {@code Killer560ModClient}; drawn in-game by
     *  {@link BloodCampFeature}'s own Fabric HUD layer. */
    static final HudElement HUD = new HudElement() {
        @Override
        public String id() {
            return "blood_camp_kill";
        }

        @Override
        public String displayName() {
            return "Blood Camp Kill Popup";
        }

        @Override
        public int defaultX() {
            return Minecraft.getInstance().getWindow().getGuiScaledWidth() / 2 - width() / 2;
        }

        @Override
        public int defaultY() {
            // killer560, 2026-09-27: "in the top middle of the screen" (was 38% down - dead center).
            return 12;
        }

        @Override
        public int width() {
            return Math.round(BASE_W * SCALE);
        }

        @Override
        public int height() {
            return Math.round(BASE_H * SCALE);
        }

        @Override
        public boolean isEnabledInSettings() {
            // Setting only; relevant() also tests isInDungeon(), which the draw stamp reports instead.
            BloodCampConfig cfg = BloodCampConfig.getInstance();
            return cfg.isEnabled() && cfg.isKillPopup();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            boolean preview = HudVisibility.editorOpen();
            String text;
            int color;
            if (preview) {
                text = "KILL";
                color = 0xFFFF5555;
            } else {
                if (!relevant() || HudVisibility.hidesHud()) {
                    return;
                }
                double ticks = ticksUntilMove() - BloodCampConfig.getInstance().getKillPopupLeadTicks();
                if (Double.isNaN(ticks) || ticks > COUNTDOWN_SHOW_TICKS || ticks < -KILL_HOLD_TICKS) {
                    return;
                }
                if (ticks > 0) {
                    text = countdownText(ticks);
                    color = ThemeAccent.onDark(ThemeAccent.light(0xFF000000 | ModChat.LIGHT_ORANGE));
                } else {
                    text = "KILL";
                    color = 0xFFFF5555;
                }
            }
            Minecraft client = Minecraft.getInstance();
            // killer560, 2026-09-27: "with no background overlay" - the semi-transparent fill this used to
            // draw behind the text is gone. The text itself is drawn scaled up around the box's own center,
            // in the OLD 76x14 coordinate space (BASE_W/BASE_H), so the same centering math as before just
            // ends up SCALE times bigger instead of being rewritten for the new footprint.
            HudSeen.markDrawn(id());
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + width() / 2f, y + height() / 2f);
            graphics.pose().scale(SCALE, SCALE);
            graphics.centeredText(client.font, text, 0, 3 - BASE_H / 2, color);
            graphics.pose().popMatrix();
        }
    };

    /** Testkit: {@link #fastWatcherMoveTicks}. */
    public static int fastWatcherMoveTicksForTest(String floor) { return fastWatcherMoveTicks(floor); }
}

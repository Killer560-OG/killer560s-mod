package com.killer560.hub.fastleap;

import com.killer560.hub.secrets.DungeonState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F7/M7 boss phase + P3 stage tracking, ported from QUOI {@code api/skyblock/dungeon/Floor7.kt},
 * {@code enums/Phase.kt} and {@code enums/Stage.kt} (and {@code Dungeon.getBoss()} for "in boss"). Chat-driven phase
 * ({@link #getPhase()}), position-driven phase/stage ({@link #getPhaseAt()}, {@link #getStageAt()}), and QUOI's
 * {@code DungeonEvent.StageComplete} / {@code StageComplete.Full} events delivered to a {@link Listener}.
 */
public final class Floor7Tracker {

    public enum Phase {
        UNKNOWN, P1, P2, P3, P4, P5
    }

    /** QUOI {@code Stage}: per-section terminal/lever/device progress, gate state, and section transitions. */
    public enum Stage {
        UNKNOWN(0), S1(1), S2(2), S3(3), S4(4), S5(5);

        // QUOI REGEX_TERM_COMPLETED, plus an optional trailing suffix so a line annotated by another mod still matches.
        // The name group is a REAL username, not any 16 characters.
        //
        // ".{1,16}" is anchored but still matches "[VIP] Bob: a" - twelve characters - so any player typing
        // "a completed a terminal! (7/7)" forged a stage completion. For Fast Leap that means an automated leap
        // in P3; for Lever Aura it advances or ends the section counter and levers get clicked out of sequence.
        // A Minecraft name is letters, digits and underscore, which no chat prefix can be.
        private static final Pattern TERM_COMPLETED =
                Pattern.compile("^([A-Za-z0-9_]{1,16}) (activated|completed) a (terminal|lever|device)! \\((\\d)/(\\d)\\)(?:\\s.*)?$");

        public final int number;
        private int current = 0;
        private int total = 0;
        private boolean gateDestroyed = false;
        private long endTime = 0L;

        Stage(int number) {
            this.number = number;
        }

        public boolean gate() {
            return this == S4 || gateDestroyed;
        }

        public boolean objectivesCompleted() {
            return total > 0 && current == total;
        }

        Stage process(String message) {
            Matcher m = TERM_COMPLETED.matcher(message);
            if (m.matches()) {
                current = parse(m.group(4));
                total = parse(m.group(5));
                if (objectivesCompleted() && listener != null) {
                    listener.onStageComplete(this);
                }
            }
            if ("The gate has been destroyed!".equals(message)) {
                gateDestroyed = true;
            }
            if (!objectivesCompleted() || !gate() || endTime != 0L) {
                return this;
            }
            endTime = System.currentTimeMillis();
            return switch (this) {
                case S1 -> S2;
                case S2 -> S3;
                case S3 -> S4;
                default -> this; // S5 starts on core opening
            };
        }

        void reset() {
            current = 0;
            total = 0;
            gateDestroyed = false;
            endTime = 0L;
        }

        static void resetAll() {
            for (Stage s : values()) {
                s.reset();
            }
        }

        private static int parse(String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    public interface Listener {
        /** QUOI {@code DungeonEvent.StageComplete}: every objective of {@code stage} is done (gate may still be up). */
        void onStageComplete(Stage stage);

        /** QUOI {@code DungeonEvent.StageComplete.Full}: the tracker moved past {@code stage}. */
        void onStageCompleteFull(Stage stage);
    }

    private static Listener listener;
    private static Phase phase = Phase.UNKNOWN;
    private static Stage stage = Stage.UNKNOWN;
    private static int simRestarts;

    private Floor7Tracker() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    /** QUOI {@code Dungeon.getBoss()} per floor, from the player's position. */
    public static boolean inBoss() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !DungeonState.isInDungeon()) {
            return false;
        }
        int floor = floorNumber();
        double x = client.player.getX();
        double z = client.player.getZ();
        return switch (floor) {
            case 1 -> x > -71 && z > -39;
            case 2, 3, 4 -> x > -39 && z > -39;
            case 5, 6 -> x > -39 && z > -7;
            case 7 -> x > -7 && z > -7;
            default -> false;
        };
    }

    /** @return 1-7 from DungeonState's "F7"/"M3" floor string (the sim override reports "F7"), else 0. */
    public static int floorNumber() {
        String floor = DungeonState.getFloor();
        if (floor == null || floor.length() < 2) {
            return 0;
        }
        char c = floor.charAt(floor.length() - 1);
        return Character.isDigit(c) ? c - '0' : 0;
    }

    /** QUOI {@code Floor7.inF7Boss}. */
    public static boolean inF7Boss() {
        return DungeonState.isF7OrM7() && inBoss();
    }

    static void onChat(String unformatted) {
        if (!inF7Boss()) {
            return;
        }
        switch (unformatted) {
            case "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!" -> {
                Stage.resetAll();
                if (isOnP3Sim()) {
                    onSimRestart("Maxor");
                } else {
                    updateState(Phase.P1, null);
                }
            }
            case "[BOSS] Storm: Pathetic Maxor, just like expected." -> {
                if (isOnP3Sim()) {
                    Stage.resetAll();
                    onSimRestart("Storm");
                } else {
                    updateState(Phase.P2, null);
                }
            }
            case "[BOSS] Goldor: Who dares trespass into my domain?" -> {
                Stage.resetAll();
                updateState(Phase.P3, Stage.S1);
            }
            // QUOI: not Goldor's death line, since it can be dialogue-skipped
            case "[BOSS] Necron: Finally, I heard so much about you. The Eye likes you very much.",
                 "[BOSS] Necron: You went further than any human before, congratulations." -> updateState(Phase.P4, Stage.UNKNOWN);
            case "The Core entrance is opening!" -> updateState(null, Stage.S5);
            default -> {
                if (phase != Phase.P5 && P5_START.matcher(unformatted).matches()) {
                    updateState(Phase.P5, null);
                }
            }
        }
        // Goldor's opening line can be missed (p3sim skips it; on Hypixel a dialogue-skip perk, a late join, or a
        // changed line): with the chat phase still before P3, the first terminal/lever/device line while standing in
        // P3 starts P3 at the section you're in (not in QUOI). Terminals, levers and devices exist only in P3, so a
        // completion line is proof. Used to require "no boss line at all", which a heard Maxor/Storm line defeated.
        if (beforeP3(phase) && getPhaseAt() == Phase.P3 && Stage.TERM_COMPLETED.matcher(unformatted).matches()) {
            inferP3("a completion line");
        }
        if (phase == Phase.P3 && stage.number >= 1 && stage.number <= 4) {
            Stage next = stage.process(unformatted);
            if (next != stage) {
                updateState(null, next);
            }
        }
    }

    /**
     * The start of Phase 5: Necron's death line, or the Wither King's first line. Since SkyBlock 0.27.2 sped boss
     * dialogue up (Celestial_Milk perk), the reference mods stopped trusting Necron's line for M7 P5: jcnlk's quoi
     * 8562102f ("fix: p5 start not registering", 2026-10-06) switched to "[BOSS] The Wither King: Ohh?" / "You... again?",
     * and Odin 1cf58050 (2026-10-03) moved its last split to "^\[BOSS] The Wither King: You\.\.\. again\?$". Either line
     * counts, whichever arrives first. Anchored and bounded: a player's chat line cannot start with "[BOSS]".
     */
    public static final Pattern P5_START = Pattern.compile(
            "^(?:\\[BOSS\\] Necron: All this, for nothing\\.\\.\\.|\\[BOSS\\] (?:The )?Wither King: .{1,160})$");

    private static boolean beforeP3(Phase p) {
        return p == Phase.UNKNOWN || p == Phase.P1 || p == Phase.P2;
    }

    private static void inferP3(String why) {
        Stage at = getStageAt();
        Stage.resetAll();
        phase = Phase.P3;
        stage = at.number >= 1 && at.number <= 4 ? at : Stage.S1;
        FastLeapFeature.LOGGER.info("[FastLeap] No Goldor line seen - inferred P3 {} from {}", stage, why);
    }

    /** Ticks in a row the player has stood in the P3 band while the chat phase still said before P3. */
    private static int p3BandTicks;
    /** One second in the band: falling through it from P2 to P4 is not possible (P3's floor is in between), so a full
     *  second there is being in P3. */
    private static final int P3_BAND_TICKS = 20;

    /**
     * Position fallback for a missed Goldor line on Hypixel (2026-10-08, "auto terminal is broken, probably because they
     * changed all boss dialogue"): Terminal Aura, AP3 and Fast Leap's P3 parts all wait for the chat phase to say P3.
     * If it still says P1, P2 or nothing after the player has stood in P3's height band for {@link #P3_BAND_TICKS}
     * ticks, P3 starts at the section he is in. Players reach that band only once Storm is dead, so this never runs
     * early; a heard Goldor line still sets P3 the moment it arrives.
     */
    static void tick() {
        if (!beforeP3(phase) || !inF7Boss() || getPhaseAt() != Phase.P3) {
            p3BandTicks = 0;
            return;
        }
        if (++p3BandTicks >= P3_BAND_TICKS) {
            p3BandTicks = 0;
            inferP3("standing in P3 for " + P3_BAND_TICKS + " ticks");
        }
    }

    /**
     * p3sim.net prints Maxor's opening line on every spawn and restart even though the sim IS Phase 3, and most sim
     * modes never print Goldor's line after it. Taking that line as P1 (as Hypixel's is) left the chat phase at P1 for
     * the whole sim session, so every feature gated on "chat says P3, or nothing heard yet" (AP3, Fast Leap's P3 leaps,
     * the section name) was inert there. killer560: "make sure AP3 works in the dungeon sim server, all of their
     * coordinates are the exact same as main so it's useful for configging AP3." Only reached when the server address
     * contains "p3sim" - Hypixel's handling of the same lines is untouched.
     */
    private static void onSimRestart(String who) {
        simRestarts++;
        if (getPhaseAt() == Phase.P3) {
            // Sim spawn (100.5, 117, 40.5) is S1; a restart mid-section lands you back there too. Same S1 fallback and
            // 1..4 cap as the completion-line inference below.
            Stage at = getStageAt();
            phase = Phase.P3;
            stage = at.number >= 1 && at.number <= 4 ? at : Stage.S1;
        } else {
            // Line arrived before the teleport put us in the P3 band - stay undecided so the position fallbacks (here
            // and in Ap3Feature.currentPhase) can still take over, instead of pinning P1.
            phase = Phase.UNKNOWN;
            stage = Stage.UNKNOWN;
        }
        FastLeapFeature.LOGGER.info("[FastLeap] p3sim {} line = sim (re)start -> phase={} stage={}", who, phase, stage);
    }

    /** How many p3sim (re)starts have been heard since the mod loaded - a counter, so a feature can notice one
     *  happened (killer560: a running AP3 chain must stop on a restart, "the fight reset") without a listener. */
    public static int simRestartCount() {
        return simRestarts;
    }

    /** True while connected to p3sim.net (server address contains "p3sim"); same test SkyblockGate uses privately. */
    public static boolean isOnP3Sim() {
        ServerData server = Minecraft.getInstance().getCurrentServer();
        return server != null && server.ip != null && server.ip.toLowerCase(Locale.ROOT).contains("p3sim");
    }

    static void onWorldChange() {
        p3BandTicks = 0;
        Stage.resetAll();
        phase = Phase.UNKNOWN;
        stage = Stage.UNKNOWN;
    }

    private static void updateState(Phase newPhase, Stage newStage) {
        Stage oldStage = stage;
        if (newPhase != null) {
            phase = newPhase;
        }
        if (newStage != null) {
            stage = newStage;
        }
        // QUOI: explicitly setting the same stage still completes it
        if (newStage != null && oldStage != Stage.UNKNOWN && listener != null) {
            listener.onStageCompleteFull(oldStage);
        }
    }

    public static Phase getPhase() {
        return inF7Boss() ? phase : Phase.UNKNOWN;
    }

    public static Phase getPhaseAt() {
        Player player = Minecraft.getInstance().player;
        if (player == null || !inF7Boss()) {
            return Phase.UNKNOWN;
        }
        double y = player.getY();
        if (y > 210) {
            return Phase.P1;
        }
        if (y > 155) {
            return Phase.P2;
        }
        if (y > 100) {
            return Phase.P3;
        }
        if (y > 45) {
            return Phase.P4;
        }
        return Phase.P5;
    }

    public static Stage getStage() {
        return inF7Boss() ? stage : Stage.UNKNOWN;
    }

    public static Stage getStageAt() {
        Player player = Minecraft.getInstance().player;
        if (player == null || getPhaseAt() != Phase.P3) {
            return Stage.UNKNOWN;
        }
        double x = player.getX();
        double z = player.getZ();
        if (in(x, 89, 113) && in(z, 30, 122)) {
            return Stage.S1;
        }
        if (in(x, 19, 111) && in(z, 121, 145)) {
            return Stage.S2;
        }
        if (in(x, -6, 19) && in(z, 51, 143)) {
            return Stage.S3;
        }
        if (in(x, -2, 90) && in(z, 27, 51)) {
            return Stage.S4;
        }
        if (in(x, 41, 68) && in(z, 59, 117)) {
            return Stage.S5;
        }
        return Stage.UNKNOWN;
    }

    public static boolean inPhase(Phase... phases) {
        Phase p = getPhase();
        for (Phase candidate : phases) {
            if (candidate == p) {
                return true;
            }
        }
        return false;
    }

    public static boolean inPhaseAt(Phase target) {
        return getPhaseAt() == target;
    }

    private static boolean in(double v, double min, double max) {
        return v >= min && v <= max;
    }
}

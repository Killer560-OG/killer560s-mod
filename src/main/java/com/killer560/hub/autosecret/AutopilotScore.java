package com.killer560.hub.autosecret;

import com.killer560.hub.scorecalc.ScoreCalculator;

/**
 * Dungeon Autopilot's score model - pure, no Minecraft state, so the testkit can drive it with made-up floors. It is
 * the mod's own score formula ({@link ScoreCalculator}, Odin/NoammAddons/Skytils) turned into "what is one more of this
 * worth":
 * <ul>
 *   <li><b>A room</b> (a clear, or a puzzle's room): 60 room score + 80 skill spread over every room, so
 *       {@code 140 / totalRooms}.</li>
 *   <li><b>A puzzle</b>: its room, plus the 10 skill points an unfinished puzzle costs.</li>
 *   <li><b>A secret</b>: 40 secret score over the floor's required secrets ({@code totalSecrets x required %}) - but only
 *       up to the secrets an S+ still needs ({@link ScoreCalculator#secretsNeededFor}: every room and puzzle assumed done,
 *       bonus, deaths and speed as they are now), plus {@link #SECRET_MARGIN}. Past that a secret buys no score, so Solo
 *       values it at nothing; Party keeps a quarter of its value, because his secrets are the party's insurance against
 *       a teammate's death or a failed puzzle.</li>
 * </ul>
 * <ul>
 *   <li><b>A crypt</b> (killer560, 2026-10-06: "Crypts and princes will come from auto routes"): a route's CRYPT nodes are
 *       worth a bonus point each, up to the 5 the bonus counts, less the crypts already blown. The mimic and the prince
 *       enter only through the bonus the calculator already read - nothing says which route gets a prince.</li>
 * </ul>
 */
public final class AutopilotScore {

    /** Secrets past the formula's bare minimum, for the run's unknowns (a mimic the calculator missed, a late death). */
    public static final int SECRET_MARGIN = 1;
    /** Skill points an unfinished puzzle costs (Odin's puzzle penalty). */
    public static final double PUZZLE_POINTS = 10.0;
    /** Party: what a secret past the S+ need is still worth, as a share of a needed one. */
    public static final double PARTY_SPARE_SECRET_WEIGHT = 0.25;

    private AutopilotScore() {
    }

    /**
     * The run as far as the planner cares. {@code currentTotal} is the score calculator's total (it already counts the
     * blood and boss rooms in), -1 when it has none.
     */
    public record State(String floor, int totalRooms, int completedRooms, int totalSecrets, int secretsFound, int bonus,
                        int deathPenalty, int puzzlesFailed, int speed, int currentTotal, int crypts) {
    }

    /** Crypts the bonus still pays for (it counts five). */
    public static final int CRYPT_CAP = 5;

    /** What a route with {@code cryptNodes} crypt nodes adds in crypt bonus: one a crypt, up to the five left. */
    public static double cryptGain(State s, int cryptNodes) {
        return Math.max(0, Math.min(cryptNodes, CRYPT_CAP - Math.max(0, s.crypts())));
    }

    /** Score for one more completed room. */
    public static double roomValue(State s) {
        return 140.0 / Math.max(1, s.totalRooms());
    }

    /** Score for one finished puzzle: its room and the penalty it lifts. */
    public static double puzzleValue(State s) {
        return roomValue(s) + PUZZLE_POINTS;
    }

    /** Score for one secret while secrets are still needed. */
    public static double secretValue(State s) {
        double required = Math.max(1.0, s.totalSecrets() * ScoreCalculator.requiredSecretFraction(s.floor()));
        return 40.0 / required;
    }

    /** Secrets still worth score: what an S+ needs (plus the margin) less what is found. */
    public static int usefulSecrets(State s) {
        int left = Math.max(0, s.totalSecrets() - s.secretsFound());
        if (s.totalSecrets() <= 0) {
            return left;
        }
        int needed = ScoreCalculator.secretsNeededFor(s.floor(), s.totalSecrets(), s.bonus(), s.deathPenalty(),
                s.puzzlesFailed(), s.speed());
        if (needed == Integer.MAX_VALUE) {
            // Secrets alone cannot reach S+ any more: every one still counts toward the best score left.
            return left;
        }
        return Math.min(left, Math.max(0, needed + SECRET_MARGIN - s.secretsFound()));
    }

    /** What finding {@code n} more secrets is worth to this mode. */
    public static double secretGain(State s, int n, boolean party) {
        if (n <= 0) {
            return 0.0;
        }
        int useful = usefulSecrets(s);
        double v = secretValue(s);
        double gain = Math.min(n, useful) * v;
        if (party) {
            gain += Math.max(0, n - useful) * v * PARTY_SPARE_SECRET_WEIGHT;
        }
        return gain;
    }

    /** The calculator already reads 300 (S+). */
    public static boolean reached300(State s) {
        return s.currentTotal() >= 300;
    }
}

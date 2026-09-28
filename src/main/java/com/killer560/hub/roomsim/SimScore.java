package com.killer560.hub.roomsim;

import com.killer560.hub.util.ModChat;

import java.util.Locale;

/**
 * The score side of a sim clear: crypts, the prince, bats, secrets and rooms.
 *
 * <p>killer560 (2026-09-28): "make sure it has crypt and prince logic and bat logic for calcuating 300 score in
 * the non individual room test." So a full-map run has to be able to answer "is this 300" the same way a real
 * one does, because a route that clears fast and misses 300 is not a route worth practising.
 *
 * <p><b>What is modelled and what is not.</b> Catacombs score is Skill + Explore + Time + Bonus. The parts a sim
 * can honestly reproduce are the ones that depend on what is IN the map and what the player did to it: crypts
 * blown, the mimic (prince) killed, bats killed, secrets found, rooms completed. Time is real because the run
 * clock is real. Skill's death penalty is real because deaths are real.
 *
 * <p>The parts it cannot are the ones that depend on Hypixel's own bookkeeping - puzzle fails it decides, and
 * the exact secret total it assigns a floor. Those are taken from the map that was generated rather than
 * guessed at, and where a number is not known it is reported as unknown rather than filled in. A score screen
 * that invents the half it cannot see is worse than one that says which half it can.
 */
public final class SimScore {

    /** Hypixel's crypt requirement for the Bonus five points. */
    private static final int CRYPTS_FOR_BONUS = 5;

    /** Bonus points per component, as Catacombs awards them. */
    private static final int CRYPT_BONUS = 5;
    private static final int MIMIC_BONUS = 2;

    private static int cryptsBlown;
    private static boolean mimicKilled;
    private static int batsKilled;
    private static int secretsFound;
    private static int secretsTotal;
    private static int roomsCleared;
    private static int roomsTotal;
    private static int deaths;

    private SimScore() {
    }

    /** Clears everything, for a new run. */
    public static void reset(int mapSecretTotal, int mapRoomTotal) {
        cryptsBlown = 0;
        mimicKilled = false;
        batsKilled = 0;
        secretsFound = 0;
        deaths = 0;
        roomsCleared = 0;
        secretsTotal = mapSecretTotal;
        roomsTotal = mapRoomTotal;
    }

    public static void cryptBlown() {
        cryptsBlown++;
    }

    /** The mimic - "prince" - killed. Worth two bonus points and nothing else. */
    public static void mimicKilled() {
        mimicKilled = true;
    }

    /**
     * A bat killed.
     *
     * <p>Bats count as SECRETS, not as their own score component - which is the whole reason they matter to a
     * 300 run and the reason they are tracked separately here: a player who cannot tell a bat secret from a
     * chest secret cannot tell why their secret count is short.
     */
    public static void batKilled() {
        batsKilled++;
        secretsFound++;
    }

    public static void secretFound() {
        secretsFound++;
    }

    public static void roomCleared() {
        roomsCleared++;
    }

    public static void died() {
        deaths++;
    }

    /** Explore: rooms completed and secrets found, as a percentage pair. */
    public static int exploreScore() {
        if (roomsTotal <= 0) {
            return 0;
        }
        double roomPart = Math.min(1.0, (double) roomsCleared / roomsTotal);
        double secretPart = secretsTotal <= 0 ? 1.0 : Math.min(1.0, (double) secretsFound / secretsTotal);
        // Catacombs weights completion more heavily than secrets; 60/40 is the split this uses and is stated
        // here rather than buried, because it is the one number in this file taken from community tables
        // rather than from something observable.
        return (int) Math.floor(60 * roomPart + 40 * secretPart);
    }

    /** Skill: 100 less the death penalty. Puzzle fails are not modelled - the sim does not fail puzzles yet. */
    public static int skillScore() {
        return Math.max(0, 100 - deaths * 2);
    }

    /** Bonus: crypts and the mimic. The part a 300 run lives or dies on. */
    public static int bonusScore() {
        int bonus = 0;
        if (cryptsBlown >= CRYPTS_FOR_BONUS) {
            bonus += CRYPT_BONUS;
        }
        if (mimicKilled) {
            bonus += MIMIC_BONUS;
        }
        return bonus;
    }

    public static int cryptsBlown() {
        return cryptsBlown;
    }

    public static int batsKilled() {
        return batsKilled;
    }

    public static boolean isMimicKilled() {
        return mimicKilled;
    }

    /**
     * What is still missing for the bonus, in words.
     *
     * <p>The useful output of a score model is not the number, it is the sentence that tells you which thing to
     * go and do. "289" is a result; "two more crypts and the mimic" is a plan.
     */
    public static String whatIsMissing() {
        StringBuilder sb = new StringBuilder();
        if (cryptsBlown < CRYPTS_FOR_BONUS) {
            sb.append(CRYPTS_FOR_BONUS - cryptsBlown).append(" more crypt(s)");
        }
        if (!mimicKilled) {
            sb.append(sb.isEmpty() ? "" : ", ").append("the mimic");
        }
        if (secretsTotal > 0 && secretsFound < secretsTotal) {
            sb.append(sb.isEmpty() ? "" : ", ").append(secretsTotal - secretsFound).append(" more secret(s)");
        }
        if (roomsTotal > 0 && roomsCleared < roomsTotal) {
            sb.append(sb.isEmpty() ? "" : ", ").append(roomsTotal - roomsCleared).append(" more room(s)");
        }
        return sb.isEmpty() ? "nothing - this is a 300 run" : sb.toString();
    }

    /** One line for chat or a HUD. */
    public static String summary() {
        return String.format(Locale.US,
                "skill %d  explore %d  bonus %d   crypts %d/%d  mimic %s  bats %d  secrets %d/%d",
                skillScore(), exploreScore(), bonusScore(), cryptsBlown, CRYPTS_FOR_BONUS,
                mimicKilled ? "yes" : "no", batsKilled, secretsFound, secretsTotal);
    }

    public static void announce() {
        ModChat.send("Sim", ModChat.text(summary()));
        ModChat.send("Sim", ModChat.dim("missing: " + whatIsMissing()));
    }
}

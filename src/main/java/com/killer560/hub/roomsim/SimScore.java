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

    /**
     * Bonus scoring, taken from the wiki (checked 2026-09-28) rather than remembered.
     *
     * <p>ONE point per crypt, capped at five - not five points for reaching five, which is what this file had
     * first and is wrong in the way that matters: it made four crypts worth nothing when they are worth four,
     * so a run that was one crypt short of 300 would have read as five short.
     *
     * <p>The mimic is two, on Floor VI and above. Paul's EZPZ perk is ten more when the mayor is Paul, which is
     * a real part of whether a run makes 300 and is therefore a switch rather than something left out.
     */
    private static final int MAX_CRYPT_BONUS = 5;
    private static final int MIMIC_BONUS = 2;
    private static final int PAUL_EZPZ_BONUS = 10;

    /** Whether Paul's EZPZ is on for this run. */
    private static boolean paulEzpz;

    private static int cryptsBlown;
    private static boolean mimicKilled;
    private static int batsKilled;
    private static int secretsFound;
    private static int secretsTotal;
    private static int roomsCleared;
    private static int roomsTotal;
    private static int deaths;

    /**
     * The share of a floor's secrets needed for full marks.
     *
     * <p>Catacombs does not ask for every secret - the explore score divides by a REQUIREMENT that is a
     * fraction of the total, which is why a 300 run does not mean a 100% secret run. Floor VII's is 100%;
     * lower floors ask for less. Kept as a constant here because the sim only builds M7 today, and it is named
     * so the day another floor matters it is one number to change rather than a formula to rediscover.
     */
    private static final double SECRET_REQUIREMENT = 1.0;

    private SimScore() {
    }

    /** Clears everything, for a new run. */
    public static void reset(int mapSecretTotal, int mapRoomTotal) {
        cryptsBlown = 0;
        mimicKilled = false;
        batsKilled = 0;
        secretsFound = 0;
        FOUND_BY_ROOM.clear();
        deaths = 0;
        roomsCleared = 0;
        secretsTotal = mapSecretTotal;
        roomsTotal = mapRoomTotal;
        paulEzpz = false;
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

    /**
     * A secret found AT {@code at}: counted for the run, and for the room standing there, which is the count
     * Hypixel's action bar shows ("x/y Secrets") while you are in that room - {@link SimActionBar} sends it, and
     * Auto Routes' {@code await:<n>} and the live map read it, as they do on Hypixel. Without the per-room count
     * the sim sent no such line at all, so an {@code await} node waited forever in the sim (2026-10-05).
     */
    public static void secretFound(net.minecraft.core.BlockPos at) {
        secretsFound++;
        String room = roomAt(at);
        if (room != null) {
            FOUND_BY_ROOM.merge(room, 1, Integer::sum);
        }
    }

    /** Secrets found so far in the named room this run. */
    public static int foundInRoom(String room) {
        return room == null ? 0 : FOUND_BY_ROOM.getOrDefault(room, 0);
    }

    /** The placed room covering a world position, by the same tile rounding {@code DungeonLayout.roomAtWorld} uses. */
    public static String roomAt(net.minecraft.core.BlockPos at) {
        if (at == null) {
            return null;
        }
        int grid = com.killer560.hub.livemap.DungeonLayout.GRID;
        int gx = Math.max(0, Math.min(grid - 1, (int) Math.round((at.getX()
                - com.killer560.hub.livemap.LiveMapFeature.START_X) / 32.0) * 2));
        int gz = Math.max(0, Math.min(grid - 1, (int) Math.round((at.getZ()
                - com.killer560.hub.livemap.LiveMapFeature.START_Z) / 32.0) * 2));
        return SimRoomIndex.nameAtCell(gz * grid + gx);
    }

    private static final java.util.Map<String, Integer> FOUND_BY_ROOM = new java.util.concurrent.ConcurrentHashMap<>();

    public static void roomCleared() {
        roomsCleared++;
    }

    public static void died() {
        deaths++;
    }

    /** Explore: rooms completed and secrets found, as a percentage pair. */
    /** Secrets found so far this run - read by the sim's sidebar. */
    public static int secretsFound() {
        return secretsFound;
    }

    /** Secrets the generated map contains, or 0 before a map has been built. */
    public static int secretsTotal() {
        return secretsTotal;
    }

    /**
     * The rest of the run's counters, for the HUDs that normally read them off Hypixel's TAB LIST.
     *
     * <p>A singleplayer sim has no tab list - the integrated server lists one player and none of Hypixel's
     * "Secrets Found"/"Crypts"/"Completed Rooms" display names exist - so the Dungeon Info HUD and the Score
     * Calculator found nothing all run and reported "?" ({@code No tab-list 'Secrets Found' line matched} in
     * his log). They read these instead while the sim is active. Same numbers this class already scores with,
     * so the HUD and the sim's own score screen cannot disagree.
     */
    public static int roomsCleared() {
        return roomsCleared;
    }

    public static int roomsTotal() {
        return roomsTotal;
    }

    public static int deaths() {
        return deaths;
    }

    /** Secrets found as a percentage of the floor's total, or 0 before a map has been built. */
    public static double secretsPercent() {
        return secretsTotal <= 0 ? 0.0 : 100.0 * secretsFound / secretsTotal;
    }

    public static int exploreScore() {
        if (roomsTotal <= 0) {
            return 0;
        }
        // The real formula (wiki, 2026-09-28): floor(60 x clearedRooms/totalRooms) + floor(40 x secretsFound /
        // (secretRequirement x totalSecrets)), the secret half capped at 40. Floored SEPARATELY, because
        // flooring the sum instead quietly hands back a point that Hypixel does not.
        double roomPart = Math.min(1.0, (double) roomsCleared / roomsTotal);
        int roomScore = (int) Math.floor(60 * roomPart);
        int secretScore = 40;
        if (secretsTotal > 0) {
            double needed = SECRET_REQUIREMENT * secretsTotal;
            secretScore = (int) Math.floor(40 * Math.min(1.0, secretsFound / needed));
        }
        return roomScore + Math.min(40, secretScore);
    }

    /** Skill: 100 less the death penalty. Puzzle fails are not modelled - the sim does not fail puzzles yet. */
    public static int skillScore() {
        return Math.max(0, 100 - deaths * 2);
    }

    /** Bonus: one per crypt to a maximum of five, two for the mimic, ten for Paul. */
    public static int bonusScore() {
        int bonus = Math.min(cryptsBlown, MAX_CRYPT_BONUS);
        if (mimicKilled) {
            bonus += MIMIC_BONUS;
        }
        if (paulEzpz) {
            bonus += PAUL_EZPZ_BONUS;
        }
        return bonus;
    }

    /** Paul's EZPZ, which is worth ten and decides plenty of 300 runs on its own. */
    public static void setPaulEzpz(boolean on) {
        paulEzpz = on;
    }

    public static boolean isPaulEzpz() {
        return paulEzpz;
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
        if (cryptsBlown < MAX_CRYPT_BONUS) {
            sb.append(MAX_CRYPT_BONUS - cryptsBlown).append(" more crypt(s)");
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
                skillScore(), exploreScore(), bonusScore(), cryptsBlown, MAX_CRYPT_BONUS,
                mimicKilled ? "yes" : "no", batsKilled, secretsFound, secretsTotal);
    }

    public static void announce() {
        ModChat.send("Sim", ModChat.text(summary()));
        ModChat.send("Sim", ModChat.dim("missing: " + whatIsMissing()));
    }
}

package com.killer560.hub.scorecalc;

/**
 * Pure Catacombs score formula - no Minecraft state, so it can be reasoned about (and unit-tested) on its own.
 * <p>
 * Ported from, in order of precedence:
 * <ul>
 * <li><b>Odin</b> (odtheking/Odin, {@code utils/skyblock/dungeon/DungeonUtils.kt#updateScore} +
 * {@code DungeonEnums.kt#Floor.requiredPercentage}): room/skill/secret/bonus maths, the "+1 blood, +1 boss room
 * not yet counted" completed-room fudge, total-room estimate {@code floor(completed / (cleared% / 100) + 0.4)}
 * with a 36-room fallback, puzzle penalty {@code (puzzleCount - completed) * 10}, first-death spirit-pet
 * assumption {@code max(0, deaths * 2 - 1)}, bonus = crypts(max 5) + mimic 2 + prince 1 + bat 1 + Paul 10, and
 * the "min secrets" formula {@code ceil(totalSecrets * req * (40 - bonus + deathPenalty) / 40)}.</li>
 * <li><b>NoammAddons</b> ({@code utils/dungeons/map/handlers/ScoreCalculation.kt}, local copy): the per-floor time
 * limits and the percentage-over-limit speed deduction ({@code getSpeedDeduction}), and secret score taken
 * straight from the tab-list percentage ({@code floor(pct / req / 100 * 40)}).</li>
 * <li><b>Skytils</b> (Skytils/SkytilsMod dev, {@code features/impl/dungeons/ScoreCalculation.kt}): rank letters
 * (D &lt;100, C &lt;160, B &lt;230, A &lt;270, S &lt;300, S+) and the Entrance x0.7 scaling.</li>
 * <li>Hypixel SkyBlock Wiki (hypixelskyblock.minecraft.wiki/w/Dungeon_Score): same secret % table (F1 30% ...
 * F6 85%, F7/Master 100%) and time limits (F1-3/F5 10m, F4/F6 12m, F7 14m, M1-5 8m, M6 10m, M7 14m). The
 * wiki's skill section ("-14 per failed puzzle") and tier-based speed table disagree with all three mods; the
 * mods' values are used because they are what players tune against in real runs.</li>
 * </ul>
 */
public final class ScoreCalculator {

    private ScoreCalculator() {
    }

    /** Everything the formula needs, as read from the tab list / sidebar / chat for the current run. */
    public record Inputs(
            String floor,
            double secretsPercent,
            int secretsFound,
            int crypts,
            int completedRooms,
            int clearedPercent,
            int deaths,
            int puzzleCount,
            int puzzlesCompleted,
            int puzzlesFailed,
            int secondsElapsed,
            boolean bloodDone,
            boolean inBoss,
            boolean mimicKilled,
            boolean princeKilled,
            boolean batKilled,
            boolean paul,
            boolean assumeSpiritPet) {
    }

    /** Result of one calculation. {@code secretsNeeded}/{@code secretsRemaining} are -1 when the total secret
     *  count isn't known yet, and {@code secretsNeeded} is {@link Integer#MAX_VALUE} when S+ can't be reached
     *  by secrets alone (e.g. too slow / too many deaths). */
    public record Result(int total, int skill, int explore, int roomScore, int secretScore, int speed, int bonus,
                         int totalRooms, int totalSecrets, int secretsNeeded, int secretsRemaining, String rank) {
    }

    /** Odin {@code Floor.requiredPercentage}: E 0.3, F1 0.3, F2 0.4, F3 0.5, F4 0.6, F5 0.7, F6 0.85, F7 and every
     *  Master Mode floor 1.0. */
    public static double requiredSecretFraction(String floor) {
        if (floor == null) {
            return 1.0;
        }
        return switch (floor) {
            case "E", "F1" -> 0.3;
            case "F2" -> 0.4;
            case "F3" -> 0.5;
            case "F4" -> 0.6;
            case "F5" -> 0.7;
            case "F6" -> 0.85;
            default -> 1.0;
        };
    }

    /** NoammAddons {@code timeLimit} (seconds before speed score starts dropping). */
    public static int timeLimitSeconds(String floor) {
        if (floor == null) {
            return 600;
        }
        return switch (floor) {
            case "F4", "F6" -> 720;
            case "F7", "M7" -> 840;
            case "M1", "M2", "M3", "M4", "M5" -> 480;
            default -> 600; // E, F1-F3, F5, M6
        };
    }

    public static int floorNumber(String floor) {
        if (floor == null || floor.isEmpty()) {
            return 0;
        }
        char last = floor.charAt(floor.length() - 1);
        return Character.isDigit(last) ? last - '0' : 0;
    }

    /** NoammAddons {@code ScoreCalculation.getSpeedDeduction}: points lost for being {@code percentageOver}% past
     *  the limit - first 20% at 1 per 2%, next 20% at 1 per 3.5%, next 10% at 1 per 4%, next 10% at 1 per 5%,
     *  then 1 per 6%. */
    static float speedDeduction(float percentageOver) {
        float over = percentageOver;
        float deduction = 0f;
        float[][] tiers = {{20f, 2f}, {20f, 3.5f}, {10f, 4f}, {10f, 5f}};
        for (float[] tier : tiers) {
            if (over <= 0) {
                return deduction;
            }
            deduction += Math.min(over, tier[0]) / tier[1];
            over -= tier[0];
        }
        if (over > 0) {
            deduction += over / 6f;
        }
        return deduction;
    }

    public static int speedScore(String floor, int secondsElapsed) {
        int limit = timeLimitSeconds(floor);
        if (secondsElapsed <= limit) {
            return 100;
        }
        float over = (secondsElapsed - limit) * 100f / limit;
        return Math.max(0, (int) (100 - speedDeduction(over)));
    }

    /** Skytils {@code ScoreCalculation.rank}. */
    public static String rank(int score) {
        if (score < 100) {
            return "D";
        }
        if (score < 160) {
            return "C";
        }
        if (score < 230) {
            return "B";
        }
        if (score < 270) {
            return "A";
        }
        if (score < 300) {
            return "S";
        }
        return "S+";
    }

    public static Result calculate(Inputs in) {
        String floor = in.floor();
        int floorNum = floorNumber(floor);
        double req = requiredSecretFraction(floor);

        // Odin: completed rooms plus blood (until its clear registers) and the boss room (until entered).
        int completed = in.completedRooms() + (in.bloodDone() ? 0 : 1) + (in.inBoss() ? 0 : 1);
        int totalRooms = (in.completedRooms() > 0 && in.clearedPercent() > 0)
                ? (int) Math.floor(in.completedRooms() / (in.clearedPercent() * 0.01) + 0.4)
                : 0;
        int roomsForMath = totalRooms != 0 ? totalRooms : 36;
        double clearFraction = (double) completed / roomsForMath;

        // Odin totalSecrets: floor(100 / pct * found + 0.5).
        int totalSecrets = (in.secretsFound() > 0 && in.secretsPercent() > 0)
                ? (int) Math.floor(100.0 / in.secretsPercent() * in.secretsFound() + 0.5)
                : 0;

        // NoammAddons: secret score from the tab percentage directly, so it works before the count line loads.
        int secretScore = clamp((int) Math.floor(in.secretsPercent() / req / 100.0 * 40.0), 0, 40);
        int roomScore = clamp((int) Math.floor(clearFraction * 60.0), 0, 60);
        int skillRooms = clamp((int) Math.floor(clearFraction * 80.0), 0, 80);

        int puzzlePenalty = Math.max(0, in.puzzleCount() - in.puzzlesCompleted()) * 10;
        int deathPenalty = Math.max(0, in.deaths() * 2 - (in.assumeSpiritPet() ? 1 : 0));
        int skill = clamp(20 + skillRooms - puzzlePenalty - deathPenalty, 20, 100);
        int explore = roomScore + secretScore;
        int speed = speedScore(floor, in.secondsElapsed());

        int bonus = Math.min(5, Math.max(0, in.crypts()));
        if (in.mimicKilled() && floorNum >= 6) {
            bonus += 2;
        }
        if (in.princeKilled()) {
            bonus += 1;
        }
        if (in.batKilled()) {
            bonus += 1;
        }
        if (in.paul()) {
            bonus += 10;
        }

        int total;
        if ("E".equals(floor)) {
            // Skytils: Entrance scores are scaled to 70%.
            total = clamp((int) (skill * 0.7), 14, 70) + (int) (roomScore * 0.7) + (int) (secretScore * 0.7)
                    + (int) (speed * 0.7) + (int) Math.ceil(bonus * 0.7);
        } else {
            total = skill + explore + speed + bonus;
        }

        // Secrets for S+ (Odin neededSecretsAmount, extended): assume every room + puzzle ends up done
        // (room 60, skill 100 - deaths - failed puzzles), keep the current speed and bonus.
        int secretsNeeded = -1;
        int secretsRemaining = -1;
        if (totalSecrets > 0 && !"E".equals(floor)) {
            int failedPenalty = Math.max(0, in.puzzlesFailed()) * 10;
            int requiredSecretScore = 40 - bonus + deathPenalty + failedPenalty + (100 - speed);
            if (requiredSecretScore > 40) {
                secretsNeeded = Integer.MAX_VALUE;
            } else {
                secretsNeeded = (int) Math.ceil(totalSecrets * req * Math.max(0, requiredSecretScore) / 40.0);
                secretsRemaining = Math.max(0, secretsNeeded - in.secretsFound());
            }
        }

        return new Result(total, skill, explore, roomScore, secretScore, speed, bonus, totalRooms, totalSecrets,
                secretsNeeded, secretsRemaining, rank(total));
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}

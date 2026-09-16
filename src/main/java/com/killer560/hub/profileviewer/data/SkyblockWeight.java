package com.killer560.hub.profileviewer.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Senither and Lily SkyBlock weight, computed locally from data the profile fetch already has (no network).
 *
 * <p><b>Senither weight</b> is a straight port of Senither's hypixel-skyblock-facade
 * (github.com/Senither/hypixel-skyblock-facade @ 28cb19ca100fbbe21b33a78803939f3fe2f60dcc):
 * {@code src/generators/SkillsGenerator.ts}, {@code SlayersGenerator.ts}, {@code DungeonsGenerator.ts},
 * {@code src/constants/GeneralSkillsExperience.ts}, {@code DungeonsExperience.ts}, and
 * {@code src/utils/Hypixel.ts#sumWeight} for the totals.
 *
 * <p><b>Lily weight</b> is a straight port of Antonio32A's lilyweight (the library behind the
 * lilyweight-worker API; github.com/Antonio32A/lilyweight @ 41a90aa1ffe8609d8f95935dc242736c2f11c8c9):
 * {@code lib/skillWeight.js}, {@code lib/dungeonExpWeight.js}, {@code lib/dungeonCompWeight.js},
 * {@code lib/slayerWeight.js}, {@code lib/utils.js#getLevelFromXP} and {@code lib/constants.json}
 * (the constant tables below were generated from that JSON, not hand-typed).
 *
 * <p>Deliberate deviations, both forced by today's API shape rather than the formulas:
 * <ul>
 *   <li>Hypixel's {@code tier_completions} objects now carry a {@code "total"} key; lilyweight's
 *   {@code Object.entries} loop would multiply it by {@code undefined} (NaN). Only numeric floor keys are
 *   used here (NEU's Java port does the same). Master mode "floor 0" is ignored for the same reason.</li>
 *   <li>With the Skills API off both upstreams fall back to the /player achievements endpoint; this viewer
 *   doesn't fetch that, so skill weight is reported as 0 and {@link Result#skillsApi()} is false.</li>
 * </ul>
 */
public final class SkyblockWeight {

    private SkyblockWeight() {
    }

    /** One weight contributor (a skill, a slayer, catacombs, a class...). {@code detail} is tooltip text. */
    public record Part(String name, double weight, double overflow, List<String> detail) {
        public double total() {
            return weight + overflow;
        }
    }

    /** A category (skills / slayers / dungeons) with its summed weight and overflow. */
    public record Group(String name, double weight, double overflow, List<Part> parts) {
        public double total() {
            return weight + overflow;
        }
    }

    public record Result(String system, Group skills, Group slayers, Group dungeons, boolean skillsApi) {
        public double weight() {
            return skills.weight() + slayers.weight() + dungeons.weight();
        }

        public double overflow() {
            return skills.overflow() + slayers.overflow() + dungeons.overflow();
        }

        public double total() {
            return weight() + overflow();
        }
    }

    /**
     * Raw inputs. Skill ids are lowercase ({@code mining}, ...), {@code skillXp} is null when the Skills API
     * is off. Slayer ids are the API keys ({@code zombie}, {@code spider}, {@code wolf}, {@code enderman},
     * {@code blaze}); class ids are {@code healer, mage, berserk, archer, tank}. Completions are keyed by floor.
     */
    public record Input(Map<String, Double> skillXp, double catacombsXp, Map<String, Double> classXp,
                        Map<Integer, Double> normalCompletions, Map<Integer, Double> masterCompletions,
                        Map<String, Double> slayerXp) {
    }

    public record Both(Result senither, Result lily) {
    }

    // ------------------------------------------------------------------ profile adapter

    public static Both compute(SbProfile p) {
        Input in = inputOf(p);
        return new Both(senither(in), lily(in));
    }

    public static Input inputOf(SbProfile p) {
        Map<String, Double> skills = null;
        if (p.skillXp != null) {
            skills = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : p.skillXp.entrySet()) {
                skills.put(e.getKey().toLowerCase(Locale.ROOT), (double) e.getValue());
            }
        }
        Map<String, Double> classes = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : p.dungeons.classXp().entrySet()) {
            classes.put(e.getKey(), (double) e.getValue());
        }
        Map<Integer, Double> normal = new TreeMap<>();
        for (Map.Entry<Integer, SbProfile.Floor> e : p.dungeons.normal().entrySet()) {
            normal.put(e.getKey(), (double) e.getValue().completions());
        }
        Map<Integer, Double> master = new TreeMap<>();
        for (Map.Entry<Integer, SbProfile.Floor> e : p.dungeons.master().entrySet()) {
            master.put(e.getKey(), (double) e.getValue().completions());
        }
        Map<String, Double> slayers = new LinkedHashMap<>();
        for (Map.Entry<String, SbProfile.SlayerStat> e : p.slayers.entrySet()) {
            slayers.put(e.getKey(), (double) e.getValue().xp());
        }
        return new Input(skills, p.dungeons.catacombsXp(), classes, normal, master, slayers);
    }

    private static double get(Map<String, Double> m, String key) {
        if (m == null) {
            return 0;
        }
        Double v = m.get(key);
        return v == null || v.isNaN() ? 0 : Math.max(0, v);
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    private static String xp(double v) {
        return String.format(Locale.US, "%,d", (long) Math.floor(v));
    }

    private static String cap(String id) {
        return id.isEmpty() ? id : Character.toUpperCase(id.charAt(0)) + id.substring(1).toLowerCase(Locale.ROOT);
    }

    // =================================================================== Senither

    private static final double SEN_LEVEL_50_SKILL_XP = 55172425;
    private static final double SEN_LEVEL_60_SKILL_XP = 111672425;
    private static final double SEN_LEVEL_50_DUNGEON_XP = 569809640;

    /** SkillsGenerator.skillGroups, in the generator's weightSkills order. */
    private record SenSkill(String id, double exponent, double divider, int maxLevel) {
    }

    private static final List<SenSkill> SEN_SKILLS = List.of(
            new SenSkill("mining", 1.18207448, 259634, 60),
            new SenSkill("foraging", 1.232826, 259634, 50),
            new SenSkill("enchanting", 0.96976583, 882758, 60),
            new SenSkill("farming", 1.217848139, 220689, 60),
            new SenSkill("combat", 1.15797687265, 275862, 60),
            new SenSkill("fishing", 1.406418, 88274, 50),
            new SenSkill("alchemy", 1.0, 1103448, 50),
            new SenSkill("taming", 1.14744, 441379, 50));

    /** SlayersGenerator.weights: API key, display name, divider, modifier. No blaze/vampire upstream. */
    private record SenSlayer(String apiId, String name, double divider, double modifier) {
    }

    private static final List<SenSlayer> SEN_SLAYERS = List.of(
            new SenSlayer("zombie", "Revenant", 2208, 0.15),
            new SenSlayer("spider", "Tarantula", 2118, 0.08),
            new SenSlayer("wolf", "Sven", 1962, 0.015),
            new SenSlayer("enderman", "Enderman", 1430, 0.017));

    /** DungeonsGenerator.weights. */
    private static final double SEN_CATACOMBS_WEIGHT = 0.0002149604615;
    private static final double SEN_CLASS_WEIGHT = 0.0000045254834;
    private static final List<String> CLASSES = List.of("healer", "mage", "berserk", "archer", "tank");

    public static Result senither(Input in) {
        // ---- skills (SkillsGenerator.calculateSkillProperties / calculateSkillWeight)
        List<Part> skillParts = new ArrayList<>();
        double sw = 0;
        double so = 0;
        for (SenSkill s : SEN_SKILLS) {
            double exp = get(in.skillXp(), s.id());
            double level = senSkillLevel(exp, s.maxLevel());
            double maxXp = s.maxLevel() == 60 ? SEN_LEVEL_60_SKILL_XP : SEN_LEVEL_50_SKILL_XP;
            double base = Math.pow(level * 10, 0.5 + s.exponent() + level / 100) / 1250;
            double overflow = 0;
            if (exp > maxXp) {
                base = jsRound(base);
                overflow = Math.pow((exp - maxXp) / s.divider(), 0.968);
            }
            sw += base;
            so += overflow;
            skillParts.add(new Part(cap(s.id()), base, overflow, List.of(
                    String.format(Locale.US, "Level %.2f (cap %d)", level, s.maxLevel()),
                    "XP: " + xp(exp))));
        }
        Group skills = new Group("Skills", in.skillXp() == null ? 0 : sw, in.skillXp() == null ? 0 : so,
                in.skillXp() == null ? List.of() : skillParts);

        // ---- slayers (SlayersGenerator.calculateWeight)
        List<Part> slayerParts = new ArrayList<>();
        double slw = 0;
        double slo = 0;
        for (SenSlayer s : SEN_SLAYERS) {
            double exp = get(in.slayerXp(), s.apiId());
            double base;
            double overflow = 0;
            if (exp <= 1000000) {
                base = exp == 0 ? 0 : exp / s.divider();
            } else {
                base = 1000000 / s.divider();
                double remaining = exp - 1000000;
                double modifier = s.modifier();
                while (remaining > 0) {
                    double left = Math.min(remaining, 1000000);
                    overflow += Math.pow(left / (s.divider() * (1.5 + modifier)), 0.942);
                    modifier += s.modifier();
                    remaining -= left;
                }
            }
            slw += base;
            slo += overflow;
            slayerParts.add(new Part(s.name(), base, overflow, List.of("XP: " + xp(exp))));
        }
        Group slayers = new Group("Slayers", slw, slo, slayerParts);

        // ---- dungeons (DungeonsGenerator.calculateWeight; catacombs + every class)
        List<Part> dungeonParts = new ArrayList<>();
        double[] cata = senDungeonWeight(SEN_CATACOMBS_WEIGHT, in.catacombsXp());
        dungeonParts.add(new Part("Catacombs", cata[0], cata[1], List.of(
                String.format(Locale.US, "Level %.2f", cata[2]), "XP: " + xp(in.catacombsXp()))));
        double dw = 0;
        double dov = 0;
        for (String c : CLASSES) {
            double exp = get(in.classXp(), c);
            double[] w = senDungeonWeight(SEN_CLASS_WEIGHT, exp);
            dw += w[0];
            dov += w[1];
            dungeonParts.add(new Part(cap(c), w[0], w[1], List.of(
                    String.format(Locale.US, "Level %.2f", w[2]), "XP: " + xp(exp))));
        }
        // DungeonsGenerator.build: sumWeights(classes) + catacombs.
        dw += cata[0];
        dov += cata[1];
        Group dungeons = new Group("Dungeons", dw, dov, dungeonParts);

        return new Result("Senither", skills, slayers, dungeons, in.skillXp() != null);
    }

    /** SkillsGenerator.calculateSkillLevel: fractional level from the per-level XP table, capped. */
    static double senSkillLevel(double experience, int maxLevel) {
        int level = 0;
        for (double toRemove : SEN_SKILL_XP) {
            experience -= toRemove;
            if (experience < 0) {
                return Math.min(level + (1 - (experience * -1) / toRemove), maxLevel);
            }
            level++;
        }
        return Math.min(level, maxLevel);
    }

    /** DungeonsGenerator.calculateLevel. */
    static double senDungeonLevel(double experience) {
        int level = 0;
        for (double toRemove : SEN_DUNGEON_XP) {
            experience -= toRemove;
            if (experience < 0) {
                return level + (1 - (experience * -1) / toRemove);
            }
            level++;
        }
        return Math.min(level, 50);
    }

    /** DungeonsGenerator.calculateWeight -> {weight, overflow, level}. */
    private static double[] senDungeonWeight(double percentageModifier, double experience) {
        double level = senDungeonLevel(experience);
        double base = Math.pow(level, 4.5) * percentageModifier;
        if (experience <= SEN_LEVEL_50_DUNGEON_XP) {
            return new double[]{base, 0, level};
        }
        double remaining = experience - SEN_LEVEL_50_DUNGEON_XP;
        double splitter = (4 * SEN_LEVEL_50_DUNGEON_XP) / base;
        return new double[]{Math.floor(base), Math.pow(remaining / splitter, 0.968), level};
    }

    /** JavaScript Math.round (round half toward +infinity). */
    private static double jsRound(double v) {
        return Math.floor(v + 0.5);
    }

    // =================================================================== Lily

    /** lib/index.js: "Order of skills: enchanting, taming, alchemy, mining, farming, foraging, combat, fishing". */
    private static final List<String> LILY_SKILLS = List.of(
            "enchanting", "taming", "alchemy", "mining", "farming", "foraging", "combat", "fishing");
    /** lib/index.js: "Order of slayers: zombie, spider, wolf, enderman, blaze". */
    private static final List<String> LILY_SLAYERS = List.of("zombie", "spider", "wolf", "enderman", "blaze");
    private static final List<String> LILY_SLAYER_NAMES = List.of("Revenant", "Tarantula", "Sven", "Enderman", "Blaze");
    private static final double[] LILY_SLAYER_DIVIDERS = {9250, 7019.57, 2982.06, 996.3003, 935.0455};
    private static final double[] LILY_SLAYER_XP_MULT = {1, 1.6, 3.6, 10, 10};

    private static final double LILY_SKILL_MAX_XP = 111672425;
    private static final double LILY_SKILL_OVERALL = 1.8162162162162162;
    private static final double LILY_DUNGEON_MAX_XP = 569809640;
    private static final double LILY_DUNGEON_OVERALL = 1.2733079672009226;

    public static Result lily(Input in) {
        // ---- skills (skillWeight.js getSkillWeight, levels from utils.js getLevelFromXP)
        int n8 = LILY_SKILLS.size();
        int[] levels = new int[n8];
        double[] xps = new double[n8];
        double skillAvg = 0;
        for (int i = 0; i < n8; i++) {
            xps[i] = get(in.skillXp(), LILY_SKILLS.get(i));
            levels[i] = lilyLevelFromXp(xps[i]);
            skillAvg += levels[i];
        }
        skillAvg /= n8;
        double n = 12 * Math.pow(skillAvg / 60, 2.44780217148309);
        double r2 = Math.pow(2, 1.0 / 2);
        double skillRating = 0;
        double[] baseParts = new double[n8];
        for (int i = 0; i < n8; i++) {
            double[] srw = LILY_SKILL_RATIO_WEIGHT[i];
            double mult = srw[srw.length - 1];
            double temp = n * srw[levels[i]] * mult + mult * Math.pow(levels[i] / 60.0, r2);
            baseParts[i] = temp;
            skillRating += temp;
        }
        skillRating *= LILY_SKILL_OVERALL;
        double overflowRating = 0;
        double[] overflowParts = new double[n8];
        for (int i = 0; i < n8; i++) {
            if (xps[i] > LILY_SKILL_MAX_XP) {
                double effectiveOver = Math.pow(xps[i] - LILY_SKILL_MAX_XP, LILY_SKILL_FACTORS[i]);
                double rating = effectiveOver / LILY_SKILL_MAX_XP;
                double t = rating * LILY_SKILL_OVERFLOW_MULTIPLIERS[i];
                if (t > 0) {
                    overflowParts[i] = LILY_SKILL_OVERALL * t;
                    overflowRating += LILY_SKILL_OVERALL * (rating * LILY_SKILL_OVERFLOW_MULTIPLIERS[i]);
                }
            }
        }
        List<Part> skillParts = new ArrayList<>();
        for (int i = 0; i < n8; i++) {
            skillParts.add(new Part(cap(LILY_SKILLS.get(i)), baseParts[i] * LILY_SKILL_OVERALL, overflowParts[i], List.of(
                    "Level " + levels[i] + " (Lily levels scale to 60)", "XP: " + xp(xps[i]))));
        }
        boolean api = in.skillXp() != null;
        Group skills = new Group("Skills", api ? skillRating : 0, api ? overflowRating : 0, api ? skillParts : List.of());

        // ---- slayers (slayerWeight.js getSlayerWeight)
        double individual = 0;
        double extra = 0;
        List<Part> slayerParts = new ArrayList<>();
        for (int i = 0; i < LILY_SLAYERS.size(); i++) {
            double exp = get(in.slayerXp(), LILY_SLAYERS.get(i));
            double value = lilySlayerValue(exp, i);
            individual += value / LILY_SLAYER_DIVIDERS[i];
            extra += LILY_SLAYER_XP_MULT[i] * exp;
            double part = 2 * (value / LILY_SLAYER_DIVIDERS[i] + LILY_SLAYER_XP_MULT[i] * exp / 1000000);
            slayerParts.add(new Part(LILY_SLAYER_NAMES.get(i), part, 0, List.of("XP: " + xp(exp),
                    "Effective XP: " + xp(value))));
        }
        extra /= 1000000;
        double slayerWeight = 2 * (individual + extra);
        Group slayers = new Group("Slayers", slayerWeight, 0, slayerParts);

        // ---- dungeons (dungeonExpWeight.js + dungeonCompWeight.js)
        double cataExp = lilyDungeonExpWeight(in.catacombsXp());
        double[] comp = lilyCompletionWeight(in.normalCompletions(), in.masterCompletions());
        List<Part> dungeonParts = new ArrayList<>();
        dungeonParts.add(new Part("Catacombs XP", cataExp, 0, List.of("XP: " + xp(in.catacombsXp()))));
        dungeonParts.add(new Part("Completions", comp[0], 0, List.of(completionLine(in.normalCompletions(), "F", 0))));
        dungeonParts.add(new Part("Master Completions", comp[1], 0, List.of(completionLine(in.masterCompletions(), "M", 1),
                "Upper bound: " + fmt(comp[2]))));
        Group dungeons = new Group("Dungeons", cataExp + comp[0] + comp[1], 0, dungeonParts);

        return new Result("Lily", skills, slayers, dungeons, api);
    }

    private static String completionLine(Map<Integer, Double> map, String prefix, int from) {
        StringBuilder sb = new StringBuilder();
        for (int f = from; f <= 7; f++) {
            double v = map == null || map.get(f) == null ? 0 : map.get(f);
            if (v <= 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(f == 0 && from == 0 ? "E" : prefix + f).append(": ").append(xp(v));
        }
        return sb.length() == 0 ? "No completions" : sb.toString();
    }

    /** utils.js getLevelFromXP (integer level, max 60). */
    static int lilyLevelFromXp(double xp) {
        double xpAdded = 0;
        for (int i = 0; i < 61; i++) {
            xpAdded += LILY_SKILL_XP_PER_LEVEL[i];
            if (xp < xpAdded) {
                return (int) Math.floor((i - 1) + (xp - (xpAdded - LILY_SKILL_XP_PER_LEVEL[i])) / LILY_SKILL_XP_PER_LEVEL[i]);
            }
        }
        return 60;
    }

    /** dungeonExpWeight.js getDungeonExpWeight. */
    static double lilyDungeonExpWeight(double cataXP) {
        int intLevel = -1;
        for (double t : LILY_DUNGEON_XP_TABLE) {
            if (cataXP >= t) {
                intLevel++;
            } else {
                break;
            }
        }
        double level = intLevel;
        if (intLevel != 50) {
            double nextLvlXP = LILY_DUNGEON_XP_TABLE[intLevel + 1] - LILY_DUNGEON_XP_TABLE[intLevel];
            double progress = Math.floor((cataXP - LILY_DUNGEON_XP_TABLE[intLevel]) / nextLvlXP * 1000) / 1000;
            level += progress;
        }
        double n = 0;
        double extra = 0;
        if (cataXP < LILY_DUNGEON_MAX_XP) {
            n = 0.2 * Math.pow(level / 50, 1.538679118869934);
        } else {
            double part = 142452410;
            extra = 500 * Math.pow((cataXP - LILY_DUNGEON_MAX_XP) / part, 1 / 1.781925776625157);
        }
        if (level != 0) {
            if (cataXP < LILY_DUNGEON_MAX_XP) {
                return LILY_DUNGEON_OVERALL * ((Math.pow(1.18340401286164044, level + 1) - 1.05994990217254) * (1 + n));
            }
            return (4100 + extra) * 2;
        }
        return 0;
    }

    /** dungeonCompWeight.js getDungeonCompletionWeight -> {normal rating, master rating, master upper bound}. */
    static double[] lilyCompletionWeight(Map<Integer, Double> cataCompl, Map<Integer, Double> mCataCompl) {
        double max1000 = 0;
        double mMax1000 = 0;
        for (int i = 0; i < LILY_COMPLETION_WORTH.length; i++) {
            if (i < 8) {
                max1000 += LILY_COMPLETION_WORTH[i];
            } else {
                mMax1000 += LILY_COMPLETION_WORTH[i];
            }
        }
        max1000 *= 1000;
        mMax1000 *= 1000;

        double upperBound = 1500;
        double score = 0;
        for (Map.Entry<Integer, Double> e : sorted(cataCompl).entrySet()) {
            int floor = e.getKey();
            if (floor < 0 || floor > 7) {
                continue;
            }
            double amount = e.getValue();
            double excess = 0;
            if (amount > 1000) {
                excess = amount - 1000;
                amount = 1000;
            }
            double floorScore = amount * LILY_COMPLETION_WORTH[floor];
            if (excess > 0) {
                floorScore *= Math.log(excess / 1000 + 1) / Math.log(7.5) + 1;
            }
            score += floorScore;
        }
        double rating = score / max1000 * upperBound * 2;

        Map<Integer, Double> master = sorted(mCataCompl);
        for (Map.Entry<Integer, Double> e : master.entrySet()) {
            int floor = e.getKey();
            if (floor >= 1 && floor <= 7) {
                double amount = e.getValue();
                double threshold = 20;
                if (amount >= threshold) {
                    upperBound += LILY_COMPLETION_BUFFS[floor];
                } else {
                    upperBound += LILY_COMPLETION_BUFFS[floor] * Math.pow(amount / threshold, 1.840896416);
                }
            }
        }
        double masterScore = 0;
        for (Map.Entry<Integer, Double> e : master.entrySet()) {
            int floor = e.getKey();
            if (floor < 1 || floor > 7) {
                continue;
            }
            double amount = e.getValue();
            double excess = 0;
            if (amount > 1000) {
                excess = amount - 1000;
                amount = 1000;
            }
            double floorScore = amount * LILY_COMPLETION_WORTH[7 + floor];
            if (excess > 0) {
                floorScore *= (Math.log((excess / 1000) + 1) / Math.log(6)) + 1;
            }
            masterScore += floorScore;
        }
        double masterRating = (masterScore / mMax1000) * upperBound * 2;
        return new double[]{rating, masterRating, upperBound};
    }

    private static Map<Integer, Double> sorted(Map<Integer, Double> m) {
        Map<Integer, Double> out = new TreeMap<>();
        if (m != null) {
            for (Map.Entry<Integer, Double> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && !e.getValue().isNaN()) {
                    out.put(e.getKey(), Math.max(0, e.getValue()));
                }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** slayerWeight.js getSlayerScore. */
    private static double lilySlayerScore(double exp) {
        double d = exp / 100000;
        if (exp >= 6416) {
            double bigD = (d - Math.pow(3, (-5.0 / 2))) * (d + Math.pow(3, -5.0 / 2));
            double u = Math.cbrt(3 * (d + Math.sqrt(bigD)));
            double v = Math.cbrt(3 * (d - Math.sqrt(bigD)));
            return u + v - 1;
        }
        // acos() is NaN for |arg| > 1; JS returns NaN there too, but a NaN here would poison the whole weight, so
        // the argument is clamped (it can only exceed 1 by rounding, right at the 6416 XP branch boundary).
        double acosArg = Math.max(-1.0, Math.min(1.0, d * Math.pow(3, 5.0 / 2)));
        return Math.sqrt(4.0 / 3) * Math.cos(Math.acos(acosArg) / 3) - 1;
    }

    /** slayerWeight.js getSlayerValue (with getEffectiveXP / getActualXP inlined). */
    static double lilySlayerValue(double xp, int ind) {
        double score = Math.floor(lilySlayerScore(xp));
        double scaling = LILY_SLAYER_DEPRECATION_SCALING[ind];
        double total = 0;
        for (int i = 1; i <= score; i++) {
            total += (Math.pow(i, 2) + i) * Math.pow(scaling, i);
        }
        total = jsRound((1000000 * total * (0.05 / scaling)) * 100) / 100;
        double actualXP = ((Math.pow(score, 3) / 6) + (Math.pow(score, 2) / 2) + (score / 3)) * 100000;
        double distance = xp - actualXP;
        double effectiveDistance = distance * Math.pow(scaling, score);
        return total + effectiveDistance;
    }

    // ------------------------------------------------------------------ constant tables (generated)

    // Senither GeneralSkillsExperience.ts
    private static final double[] SEN_SKILL_XP = {
            50.0, 125.0, 200.0, 300.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0, 3500.0, 5000.0, 7500.0, 10000.0,
            15000.0, 20000.0, 30000.0, 50000.0, 75000.0, 100000.0, 200000.0, 300000.0, 400000.0, 500000.0,
            600000.0, 700000.0, 800000.0, 900000.0, 1000000.0, 1100000.0, 1200000.0, 1300000.0, 1400000.0,
            1500000.0, 1600000.0, 1700000.0, 1800000.0, 1900000.0, 2000000.0, 2100000.0, 2200000.0, 2300000.0,
            2400000.0, 2500000.0, 2600000.0, 2750000.0, 2900000.0, 3100000.0, 3400000.0, 3700000.0, 4000000.0,
            4300000.0, 4600000.0, 4900000.0, 5200000.0, 5500000.0, 5800000.0, 6100000.0, 6400000.0, 6700000.0,
            7000000.0};
    // Senither DungeonsExperience.ts
    private static final double[] SEN_DUNGEON_XP = {
            50.0, 75.0, 110.0, 160.0, 230.0, 330.0, 470.0, 670.0, 950.0, 1340.0, 1890.0, 2665.0, 3760.0, 5260.0,
            7380.0, 10300.0, 14400.0, 20000.0, 27600.0, 38000.0, 52500.0, 71500.0, 97000.0, 132000.0, 180000.0,
            243000.0, 328000.0, 445000.0, 600000.0, 800000.0, 1065000.0, 1410000.0, 1900000.0, 2500000.0,
            3300000.0, 4300000.0, 5600000.0, 7200000.0, 9200000.0, 12000000.0, 15000000.0, 19000000.0,
            24000000.0, 30000000.0, 38000000.0, 48000000.0, 60000000.0, 75000000.0, 93000000.0, 116250000.0};
    // lilyweight constants.json skillXPPerLevel
    private static final double[] LILY_SKILL_XP_PER_LEVEL = {
            0.0, 50.0, 125.0, 200.0, 300.0, 500.0, 750.0, 1000.0, 1500.0, 2000.0, 3500.0, 5000.0, 7500.0,
            10000.0, 15000.0, 20000.0, 30000.0, 50000.0, 75000.0, 100000.0, 200000.0, 300000.0, 400000.0,
            500000.0, 600000.0, 700000.0, 800000.0, 900000.0, 1000000.0, 1100000.0, 1200000.0, 1300000.0,
            1400000.0, 1500000.0, 1600000.0, 1700000.0, 1800000.0, 1900000.0, 2000000.0, 2100000.0, 2200000.0,
            2300000.0, 2400000.0, 2500000.0, 2600000.0, 2750000.0, 2900000.0, 3100000.0, 3400000.0, 3700000.0,
            4000000.0, 4300000.0, 4600000.0, 4900000.0, 5200000.0, 5500000.0, 5800000.0, 6100000.0, 6400000.0,
            6700000.0, 7000000.0};
    // lilyweight constants.json dungeonExperienceTable (last entry is the JS sentinel 1e54)
    private static final double[] LILY_DUNGEON_XP_TABLE = {
            0.0, 50.0, 125.0, 235.0, 395.0, 625.0, 955.0, 1425.0, 2095.0, 3045.0, 4385.0, 6275.0, 8940.0,
            12700.0, 17960.0, 25340.0, 35640.0, 50040.0, 70040.0, 97640.0, 135640.0, 188140.0, 259640.0,
            356640.0, 488640.0, 668640.0, 911640.0, 1239640.0, 1684640.0, 2284640.0, 3084640.0, 4149640.0,
            5559640.0, 7459640.0, 9959640.0, 13259640.0, 17559640.0, 23159640.0, 30359640.0, 39559640.0,
            51559640.0, 66559640.0, 85559640.0, 109559640.0, 139559640.0, 177559640.0, 225559640.0, 285559640.0,
            360559640.0, 453559640.0, 569809640.0, 1.0E54};
    private static final double[] LILY_COMPLETION_WORTH = {
            0.0827, 2.1034, 4.5966, 7.9383, 13.4018, 23.1071, 43.7857, 63.3437, 29.048912, 38.548938, 51.624065,
            67.004612, 75.234512, 99.20524, 295.090592};
    /** constants.json dungeonCompletionBuffs, index = master floor (0 unused). */
    private static final double[] LILY_COMPLETION_BUFFS = {0.0, 62.5, 125.0, 225.0, 387.5, 500.0, 700.0, 1500.0};
    private static final double[] LILY_SKILL_OVERFLOW_MULTIPLIERS = {7.0, 4.0, 1.5, 25.0, 70.0, 125.0, 125.0, 85.0};
    private static final double[] LILY_SKILL_FACTORS = {0.956018746, 0.9422102267, 0.9227482118, 0.9713042815, 0.9861914807, 0.9892892803, 0.9892892803, 0.9828798757};
    private static final double[] LILY_SLAYER_DEPRECATION_SCALING = {0.72529102591, 0.7732512436, 0.8085205492, 0.8374104242, 0.842};
    /** constants.json skillRatioWeight, in its key order; the last entry of each row is the skill multiplier. */
    private static final double[][] LILY_SKILL_RATIO_WEIGHT = {
            // enchanting
            {0.0, 0.0125, 0.025, 0.0375, 0.05, 0.0625, 0.075, 0.0875, 0.1, 0.1125, 0.125, 0.1375, 0.15, 0.1625,
             0.175, 0.1875, 0.2, 0.2125, 0.225, 0.2375, 0.25, 0.2625, 0.275, 0.2875, 0.3, 0.3125, 0.325, 0.3375,
             0.35, 0.3625, 0.375, 0.3875, 0.4, 0.4125, 0.425, 0.4375, 0.45, 0.4625, 0.475, 0.4875, 0.5, 0.5125,
             0.525, 0.5375, 0.55, 0.5625, 0.575, 0.5875, 0.6, 0.6125, 0.625, 0.640625, 0.65625, 0.671875, 0.6875,
             0.703125, 0.71875, 0.734375, 0.75, 0.765625, 0.78125, 30.0},
            // taming
            {0.0, 0.01625, 0.0325, 0.04875, 0.065, 0.08125, 0.0975, 0.11375, 0.13, 0.14625, 0.1625, 0.17875,
             0.195, 0.21125, 0.2275, 0.24375, 0.26, 0.27625, 0.2925, 0.30875, 0.325, 0.34125, 0.3575, 0.37375,
             0.39, 0.40625, 0.4225, 0.43875, 0.455, 0.47125, 0.4875, 0.50375, 0.52, 0.53625, 0.5525, 0.56875,
             0.585, 0.60125, 0.6175, 0.63375, 0.65, 0.66625, 0.6825, 0.69875, 0.715, 0.73125, 0.7475, 0.76375,
             0.78, 0.79625, 0.8125, 0.832813, 0.853125, 0.873437, 0.89375, 0.914062, 0.934375, 0.954688, 0.975,
             0.995313, 1.015625, 35.0},
            // alchemy
            {0.0, 0.01625, 0.0325, 0.04875, 0.065, 0.08125, 0.0975, 0.11375, 0.13, 0.14625, 0.1625, 0.17875,
             0.195, 0.21125, 0.2275, 0.24375, 0.26, 0.27625, 0.2925, 0.30875, 0.325, 0.34125, 0.3575, 0.37375,
             0.39, 0.40625, 0.4225, 0.43875, 0.455, 0.47125, 0.4875, 0.50375, 0.52, 0.53625, 0.5525, 0.56875,
             0.585, 0.60125, 0.6175, 0.63375, 0.65, 0.66625, 0.6825, 0.69875, 0.715, 0.73125, 0.7475, 0.76375,
             0.78, 0.79625, 0.8125, 0.832813, 0.853125, 0.873437, 0.89375, 0.914062, 0.934375, 0.954688, 0.975,
             0.995313, 1.015625, 40.0},
            // mining
            {0.0, 0.01875, 0.0375, 0.05625, 0.075, 0.09375, 0.1125, 0.13125, 0.15, 0.16875, 0.1875, 0.20625,
             0.225, 0.24375, 0.2625, 0.28125, 0.3, 0.31875, 0.3375, 0.35625, 0.375, 0.39375, 0.4125, 0.43125,
             0.45, 0.46875, 0.4875, 0.50625, 0.525, 0.54375, 0.5625, 0.58125, 0.6, 0.61875, 0.6375, 0.65625,
             0.675, 0.69375, 0.7125, 0.73125, 0.75, 0.76875, 0.7875, 0.80625, 0.825, 0.84375, 0.8625, 0.88125,
             0.9, 0.91875, 0.9375, 0.960938, 0.984375, 1.007812, 1.03125, 1.054688, 1.078125, 1.101562, 1.125,
             1.148438, 1.171875, 60.0},
            // farming
            {0.0, 0.0275, 0.055, 0.0825, 0.11, 0.1375, 0.165, 0.1925, 0.22, 0.2475, 0.275, 0.3025, 0.33, 0.3575,
             0.385, 0.4125, 0.44, 0.4675, 0.495, 0.5225, 0.55, 0.5775, 0.605, 0.6325, 0.66, 0.6875, 0.715, 0.7425,
             0.77, 0.7975, 0.825, 0.8525, 0.88, 0.9075, 0.935, 0.9625, 0.99, 1.0175, 1.045, 1.0725, 1.1, 1.1275,
             1.155, 1.1825, 1.21, 1.2375, 1.265, 1.2925, 1.32, 1.3475, 1.375, 1.409375, 1.44375, 1.478125, 1.5125,
             1.546875, 1.58125, 1.615625, 1.65, 1.684375, 1.71875, 80.0},
            // foraging
            {0.0, 0.025, 0.05, 0.075, 0.1, 0.125, 0.15, 0.175, 0.2, 0.225, 0.25, 0.275, 0.3, 0.325, 0.35, 0.375,
             0.4, 0.425, 0.45, 0.475, 0.5, 0.525, 0.55, 0.575, 0.6, 0.625, 0.65, 0.675, 0.7, 0.725, 0.75, 0.775,
             0.8, 0.825, 0.85, 0.875, 0.9, 0.925, 0.95, 0.975, 1.0, 1.025, 1.05, 1.075, 1.1, 1.125, 1.15, 1.175,
             1.2, 1.225, 1.25, 1.28125, 1.3125, 1.34375, 1.375, 1.40625, 1.4375, 1.46875, 1.5, 1.53125, 1.5625,
             80.0},
            // combat
            {0.0, 0.025, 0.05, 0.075, 0.1, 0.125, 0.15, 0.175, 0.2, 0.225, 0.25, 0.275, 0.3, 0.325, 0.35, 0.375,
             0.4, 0.425, 0.45, 0.475, 0.5, 0.525, 0.55, 0.575, 0.6, 0.625, 0.65, 0.675, 0.7, 0.725, 0.75, 0.775,
             0.8, 0.825, 0.85, 0.875, 0.9, 0.925, 0.95, 0.975, 1.0, 1.025, 1.05, 1.075, 1.1, 1.125, 1.15, 1.175,
             1.2, 1.225, 1.25, 1.28125, 1.3125, 1.34375, 1.375, 1.40625, 1.4375, 1.46875, 1.5, 1.53125, 1.5625,
             85.0},
            // fishing
            {0.0, 0.02125, 0.0425, 0.06375, 0.085, 0.10625, 0.1275, 0.14875, 0.17, 0.19125, 0.2125, 0.23375,
             0.255, 0.27625, 0.2975, 0.31875, 0.34, 0.36125, 0.3825, 0.40375, 0.425, 0.44625, 0.4675, 0.48875,
             0.51, 0.53125, 0.5525, 0.57375, 0.595, 0.61625, 0.6375, 0.65875, 0.68, 0.70125, 0.7225, 0.74375,
             0.765, 0.78625, 0.8075, 0.82875, 0.85, 0.87125, 0.8925, 0.91375, 0.935, 0.95625, 0.9775, 0.99875,
             1.02, 1.04125, 1.0625, 1.089063, 1.115625, 1.142187, 1.16875, 1.195312, 1.221875, 1.248437, 1.275,
             1.301562, 1.328125, 85.0},
    };
}

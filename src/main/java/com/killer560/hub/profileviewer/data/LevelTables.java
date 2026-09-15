package com.killer560.hub.profileviewer.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * XP -> level tables, taken from the same data the SkyBlock Profile Viewer mod (meowdding/skyblock-pv)
 * loads rather than hand-typed:
 * <ul>
 *   <li>Skills: Hypixel's keyless {@code /v2/resources/skyblock/skills} resource (what skyblock-pv's
 *   {@code SkillAPI} reads). A snapshot ships in {@code skills.json}; a live copy is fetched once per
 *   session in the background and replaces it when it arrives.</li>
 *   <li>Catacombs / class XP, slayer levels, pet XP curve + rarity offsets + per-pet overwrites:
 *   meowdding-repo {@code repo/pv/*} (MIT), bundled as-is.</li>
 * </ul>
 * All level math mirrors skyblock-pv's {@code SkillAPI}, {@code CatacombsCodecs}, {@code SlayerCodecs}
 * and {@code PetsData}.
 */
public final class LevelTables {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-profileviewer");
    private static final String BASE = "/assets/killer560smod/profileviewer/";

    public record Skill(String id, String name, int maxLevel, long[] totalXp) {
    }

    public record Slayer(String key, String name, String apiId, long[] leveling, int[] bossXp) {
    }

    public record Level(int level, double progress, long xpIntoLevel, long xpForLevel, boolean maxed) {
        public double fractional() {
            return maxed ? level : level + progress;
        }
    }

    private static volatile Map<String, Skill> skills = Map.of();
    private static long[] catacombsXp = new long[0];
    private static long catacombsOverflow = 200_000_000L;
    private static Map<String, Slayer> slayers = Map.of();
    private static int[] petRarityOffsets = {0, 6, 11, 16, 20, 20};
    private static int[] petXpCurve = new int[0];
    private static Map<String, PetOverwrite> petOverwrites = Map.of();
    private static final AtomicBoolean LIVE_SKILLS_REQUESTED = new AtomicBoolean(false);
    private static boolean loaded = false;

    private record PetOverwrite(int[] xpCurve, int[] rarityOffsets, int levelCap) {
    }

    private LevelTables() {
    }

    public static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            JsonObject obj = read("skills.json");
            if (obj != null) {
                skills = parseSkills(obj.getAsJsonObject("skills"));
            }
        } catch (Exception e) {
            LOGGER.warn("[ProfileViewer] bundled skills table unreadable", e);
        }
        try {
            JsonObject obj = read("catacombs.json");
            if (obj != null) {
                catacombsXp = toLongs(obj.getAsJsonArray("experience"));
                catacombsOverflow = obj.get("experience_per_overflow").getAsLong();
            }
        } catch (Exception e) {
            LOGGER.warn("[ProfileViewer] bundled catacombs table unreadable", e);
        }
        try {
            JsonObject obj = read("slayers.json");
            if (obj != null) {
                Map<String, Slayer> out = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("slayers").entrySet()) {
                    JsonObject s = e.getValue().getAsJsonObject();
                    out.put(e.getKey(), new Slayer(e.getKey(), s.get("name").getAsString(), s.get("id").getAsString(),
                            toLongs(s.getAsJsonArray("leveling")), toInts(s.getAsJsonArray("boss_xp"))));
                }
                slayers = Collections.unmodifiableMap(out);
            }
        } catch (Exception e) {
            LOGGER.warn("[ProfileViewer] bundled slayer table unreadable", e);
        }
        try {
            JsonObject obj = read("pets.json");
            if (obj != null) {
                petRarityOffsets = toInts(obj.getAsJsonArray("rarity_offsets"));
                petXpCurve = toInts(obj.getAsJsonArray("xp_curve"));
                Map<String, PetOverwrite> out = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("overwrites").entrySet()) {
                    JsonObject o = e.getValue().getAsJsonObject();
                    int[] curve = o.has("xp_curve") ? toInts(o.getAsJsonArray("xp_curve")) : null;
                    int[] offsets = o.has("rarity_offsets") ? toInts(o.getAsJsonArray("rarity_offsets")) : null;
                    int cap = o.has("level_cap") ? o.get("level_cap").getAsInt() : 100;
                    out.put(e.getKey().toUpperCase(Locale.ROOT), new PetOverwrite(curve, offsets, cap));
                }
                petOverwrites = out;
            }
        } catch (Exception e) {
            LOGGER.warn("[ProfileViewer] bundled pet table unreadable", e);
        }
        try {
            requestLiveSkills();
        } catch (Throwable t) {
            LOGGER.debug("[ProfileViewer] live skills refresh not started");
        }
    }

    /** One background refresh per session from Hypixel's keyless skills resource. */
    private static void requestLiveSkills() {
        if (!LIVE_SKILLS_REQUESTED.compareAndSet(false, true)) {
            return;
        }
        ProfileViewerApi.getKeylessJson("https://api.hypixel.net/v2/resources/skyblock/skills").thenAccept(json -> {
            if (json == null || !json.has("skills") || !json.get("skills").isJsonObject()) {
                return;
            }
            try {
                JsonObject in = json.getAsJsonObject("skills");
                JsonObject converted = new JsonObject();
                for (Map.Entry<String, JsonElement> e : in.entrySet()) {
                    JsonObject s = e.getValue().getAsJsonObject();
                    JsonObject c = new JsonObject();
                    c.addProperty("name", s.get("name").getAsString());
                    c.addProperty("maxLevel", s.get("maxLevel").getAsInt());
                    JsonArray xp = new JsonArray();
                    for (JsonElement lvl : s.getAsJsonArray("levels")) {
                        xp.add(lvl.getAsJsonObject().get("totalExpRequired").getAsLong());
                    }
                    c.add("xp", xp);
                    converted.add(e.getKey(), c);
                }
                Map<String, Skill> parsed = parseSkills(converted);
                if (!parsed.isEmpty()) {
                    skills = parsed;
                }
            } catch (Exception e) {
                LOGGER.debug("[ProfileViewer] live skills table unparseable, keeping bundled copy");
            }
        });
    }

    private static Map<String, Skill> parseSkills(JsonObject obj) {
        Map<String, Skill> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            out.put(e.getKey().toUpperCase(Locale.ROOT), new Skill(e.getKey().toUpperCase(Locale.ROOT),
                    s.get("name").getAsString(), s.get("maxLevel").getAsInt(), toLongs(s.getAsJsonArray("xp"))));
        }
        return Collections.unmodifiableMap(out);
    }

    private static JsonObject read(String file) throws Exception {
        try (InputStream in = LevelTables.class.getResourceAsStream(BASE + file)) {
            if (in == null) {
                LOGGER.warn("[ProfileViewer] missing bundled resource {}", file);
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static long[] toLongs(JsonArray arr) {
        long[] out = new long[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).getAsLong();
        }
        return out;
    }

    private static int[] toInts(JsonArray arr) {
        int[] out = new int[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).getAsInt();
        }
        return out;
    }

    // ------------------------------------------------------------------ skills

    /** Skill ids in display order (NEU order), keyed without the {@code SKILL_} prefix. */
    public static final List<String> SKILL_ORDER = List.of(
            "FARMING", "MINING", "COMBAT", "FORAGING", "FISHING", "ENCHANTING",
            "ALCHEMY", "CARPENTRY", "TAMING", "HUNTING", "RUNECRAFTING", "SOCIAL");

    /** skyblock-pv's MainScreen leaves these two out of the skill average. */
    public static boolean countsForAverage(String skillId) {
        return !"RUNECRAFTING".equals(skillId) && !"SOCIAL".equals(skillId);
    }

    public static Skill skill(String id) {
        ensureLoaded();
        return skills.get(id);
    }

    /** @param cap the profile-specific level cap (farming/taming/foraging raise theirs), or -1 for the
     *             table's own max level. */
    public static Level skillLevel(String id, long xp, int cap) {
        Skill s = skill(id);
        if (s == null || s.totalXp().length == 0) {
            return new Level(0, 0, xp, 0, false);
        }
        int max = Math.min(s.totalXp().length, cap > 0 ? cap : s.maxLevel());
        return cumulative(s.totalXp(), xp, max);
    }

    /** Level from a cumulative table where {@code table[i]} is the total XP needed for level i+1. */
    public static Level cumulative(long[] table, long xp, int maxLevel) {
        int level = 0;
        while (level < maxLevel && level < table.length && xp >= table[level]) {
            level++;
        }
        if (level >= maxLevel || level >= table.length) {
            long prev = level == 0 ? 0 : table[Math.min(level, table.length) - 1];
            return new Level(level, 1, xp - prev, 0, true);
        }
        long prev = level == 0 ? 0 : table[level - 1];
        long need = table[level] - prev;
        double progress = need <= 0 ? 1 : Math.max(0, Math.min(1, (double) (xp - prev) / need));
        return new Level(level, progress, xp - prev, need, false);
    }

    // ------------------------------------------------------------------ catacombs / classes

    /** Catacombs and class levels share the one table. Past 50 the overflow levels use a flat
     *  {@code experience_per_overflow} step (skyblock-pv's "withOverflow"). */
    public static Level catacombsLevel(long xp, boolean overflow) {
        ensureLoaded();
        Level base = cumulative(catacombsXp, xp, catacombsXp.length);
        if (!base.maxed() || !overflow || catacombsOverflow <= 0) {
            return base;
        }
        long over = xp - catacombsXp[catacombsXp.length - 1];
        int extra = (int) (over / catacombsOverflow);
        long into = over % catacombsOverflow;
        return new Level(catacombsXp.length + extra, (double) into / catacombsOverflow, into, catacombsOverflow, false);
    }

    // ------------------------------------------------------------------ slayers

    public static List<Slayer> slayers() {
        ensureLoaded();
        return new ArrayList<>(slayers.values());
    }

    public static Level slayerLevel(Slayer slayer, long xp) {
        return cumulative(slayer.leveling(), xp, slayer.leveling().length);
    }

    // ------------------------------------------------------------------ pets

    public static final List<String> RARITIES = List.of(
            "COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL");

    public static int rarityIndex(String tier) {
        int i = RARITIES.indexOf(tier == null ? "" : tier.toUpperCase(Locale.ROOT));
        return Math.max(0, i);
    }

    public static int rarityColor(String tier) {
        return switch (tier == null ? "" : tier.toUpperCase(Locale.ROOT)) {
            case "UNCOMMON" -> 0x55FF55;
            case "RARE" -> 0x5555FF;
            case "EPIC" -> 0xAA00AA;
            case "LEGENDARY" -> 0xFFAA00;
            case "MYTHIC" -> 0xFF55FF;
            case "DIVINE" -> 0x55FFFF;
            case "SPECIAL", "VERY_SPECIAL" -> 0xFF5555;
            default -> 0xFFFFFF;
        };
    }

    /** skyblock-pv {@code PetsData}: curve = xp_curve.drop(offset(rarity)).take(levelCap - 1), cumulative
     *  from 0; level = index of the last cumulative value <= exp, + 1. */
    public static Level petLevel(String type, String tier, double exp) {
        ensureLoaded();
        PetOverwrite ow = petOverwrites.get(type == null ? "" : type.toUpperCase(Locale.ROOT));
        int[] curve = ow != null && ow.xpCurve() != null ? ow.xpCurve() : petXpCurve;
        int[] offsets = ow != null && ow.rarityOffsets() != null ? ow.rarityOffsets() : petRarityOffsets;
        int cap = ow != null ? ow.levelCap() : 100;
        int rarity = Math.min(rarityIndex(tier), Math.max(0, offsets.length - 1));
        int offset = offsets.length == 0 ? 0 : offsets[rarity];
        int len = Math.max(0, Math.min(cap - 1, curve.length - offset));
        long[] cum = new long[len + 1];
        for (int i = 0; i < len; i++) {
            cum[i + 1] = cum[i] + curve[offset + i];
        }
        long xp = (long) exp;
        int idx = 0;
        for (int i = 0; i < cum.length; i++) {
            if (cum[i] <= xp) {
                idx = i;
            }
        }
        int level = idx + 1;
        if (idx >= cum.length - 1) {
            return new Level(level, 1, xp - cum[cum.length - 1], 0, true);
        }
        long need = cum[idx + 1] - cum[idx];
        return new Level(level, need <= 0 ? 1 : (double) (xp - cum[idx]) / need, xp - cum[idx], need, false);
    }
}

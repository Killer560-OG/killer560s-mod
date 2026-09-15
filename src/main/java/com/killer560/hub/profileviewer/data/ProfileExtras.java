package com.killer560.hub.profileviewer.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.killer560.hub.profileviewer.data.SbProfile.asArr;
import static com.killer560.hub.profileviewer.data.SbProfile.asObj;
import static com.killer560.hub.profileviewer.data.SbProfile.num;
import static com.killer560.hub.profileviewer.data.SbProfile.path;
import static com.killer560.hub.profileviewer.data.SbProfile.str;

/**
 * Everything the extra Profile Viewer pages read from one member's {@code /v2/skyblock/profiles} entry. JSON
 * paths follow skyblock-pv's {@code SkyBlockProfile.fromJson}, {@code MiningCore}/{@code SkillTree},
 * {@code ForagingCore}, {@code CrimsonIsleData}, {@code RiftData}, {@code FarmingData}, {@code Currency} and
 * {@code InventoryData}. Every field tolerates a missing/renamed key (0 / empty / null "not present").
 */
public final class ProfileExtras {

    /** Item id -> amount, summed over every co-op member (skyblock-pv does the same). Null = API off. */
    public final Map<String, Long> collections;
    public final Mining mining;
    public final Foraging foraging;
    /** Mob id -> kills. Null when the profile has no bestiary object. */
    public final Map<String, Long> bestiaryKills;
    public final Crimson crimson;
    public final Rift rift;
    public final Farming farming;
    public final Misc misc;

    public record Crystal(String state, int found, int placed) {
    }

    public record ForgeSlot(int slot, String type, String id, long startTime) {
    }

    public record Mining(boolean present, long hotmXp, int selectedSlot, Map<String, Integer> nodes, String selectedAbility,
                         long[] powderTotal, long[] powderAvailable, Map<String, Crystal> crystals, List<ForgeSlot> forge,
                         long oresMined, int mineshafts, List<String> fossils, Map<String, Integer> corpses) {
    }

    public record Foraging(boolean present, long hotfXp, Map<String, Integer> nodes, long forestTotal, long forestAvailable,
                           long desertTotal, long desertAvailable, Map<String, Integer> treeGifts) {
    }

    public record Crimson(boolean present, String selectedFaction, int magesRep, int barbariansRep,
                          Map<String, Integer> kuudraCompletions, Map<String, Integer> kuudraHighestWave,
                          Map<String, Integer> dojoPoints, Map<String, Integer> dojoTimes,
                          int matriarchPearls, long matriarchLastAttempt) {
    }

    public record Trophy(String type, long timestamp, int visits) {
    }

    public record Rift(boolean present, long motes, long lifetimeMotes, int visits, List<String> souls, List<String> eyes,
                       List<String> cats, List<Trophy> trophies, int grubberStacks, long secondsSitting,
                       JsonObject inventory) {
    }

    public record Farming(boolean present, Map<String, Integer> medalInventory, int doubleDrops, int levelCapPerk,
                          boolean personalBestsPerk, int contests, Map<String, Integer> contestsPerCrop,
                          Map<String, Integer> medalsEarned, Map<String, Long> personalBests,
                          Map<String, List<String>> uniqueBrackets, int copper, int larvaConsumed) {
    }

    public record Misc(Map<String, Long> kills, Map<String, Long> deaths, Map<String, Long> essence, Map<String, Long> sacks,
                       Map<String, Long> auctions, long highestCrit, long itemsFished, boolean cookieBuff,
                       int minionsCrafted, int magicalPower, String selectedPower, int fairyExchanges,
                       long giftsGiven, long giftsReceived, long mythosKills, long sbXp, boolean sacksApi) {
    }

    ProfileExtras(JsonObject profile, JsonObject member) {
        collections = parseCollections(profile);
        mining = parseMining(member);
        foraging = parseForaging(member);
        JsonObject bestiary = asObj(path(member, "bestiary.kills"));
        bestiaryKills = bestiary == null ? null : longMap(bestiary);
        crimson = parseCrimson(asObj(member.get("nether_island_player_data")));
        rift = parseRift(member);
        farming = parseFarming(member);
        misc = parseMisc(profile, member);
    }

    // ------------------------------------------------------------------ collections

    private static Map<String, Long> parseCollections(JsonObject profile) {
        JsonObject members = asObj(profile.get("members"));
        if (members == null) {
            return null;
        }
        Map<String, Long> out = new TreeMap<>();
        boolean any = false;
        for (Map.Entry<String, JsonElement> e : members.entrySet()) {
            JsonObject m = asObj(e.getValue());
            JsonObject col = m == null ? null : asObj(m.get("collection"));
            if (col == null) {
                continue;
            }
            any = true;
            for (Map.Entry<String, JsonElement> c : col.entrySet()) {
                out.merge(c.getKey(), (long) num(c.getValue()), Long::sum);
            }
        }
        return any ? Collections.unmodifiableMap(out) : null;
    }

    // ------------------------------------------------------------------ mining / foraging

    private static Mining parseMining(JsonObject member) {
        JsonObject core = asObj(member.get("mining_core"));
        JsonObject tree = asObj(member.get("skill_tree"));
        int slot = (int) num(path(member, "skill_tree.selected_skill_tree_slot.mining"));
        String suffix = slot >= 2 ? "_" + slot : "";
        Map<String, Integer> nodes = nodes(asObj(path(member, "skill_tree.nodes.mining" + suffix)));
        String ability = str(asObj(path(member, "skill_tree.selected_ability")), "mining" + suffix, null);

        String[] powders = {"mithril", "gemstone", "glacite"};
        long[] total = new long[3];
        long[] available = new long[3];
        for (int i = 0; i < 3; i++) {
            if (core == null) {
                continue;
            }
            total[i] = (long) num(core.get("powder_" + powders[i]));
            long spent = (long) num(core.get("powder_spent_" + powders[i] + (slot >= 2 ? "_" + slot : "")));
            available[i] = Math.max(0, total[i] - spent);
        }

        Map<String, Crystal> crystals = new LinkedHashMap<>();
        JsonObject cr = core == null ? null : asObj(core.get("crystals"));
        if (cr != null) {
            for (Map.Entry<String, JsonElement> e : cr.entrySet()) {
                JsonObject c = asObj(e.getValue());
                if (c != null) {
                    crystals.put(e.getKey(), new Crystal(str(c, "state", "NOT_FOUND"), (int) num(c.get("total_found")), (int) num(c.get("total_placed"))));
                }
            }
        }

        List<ForgeSlot> forge = new ArrayList<>();
        JsonObject f1 = asObj(path(member, "forge.forge_processes.forge_1"));
        if (f1 != null) {
            for (Map.Entry<String, JsonElement> e : f1.entrySet()) {
                JsonObject s = asObj(e.getValue());
                if (s == null) {
                    continue;
                }
                int idx;
                try {
                    idx = Integer.parseInt(e.getKey());
                } catch (NumberFormatException ex) {
                    idx = (int) num(s.get("slot"));
                }
                forge.add(new ForgeSlot(idx, str(s, "type", ""), str(s, "id", ""), (long) num(s.get("startTime"))));
            }
            forge.sort((a, b) -> Integer.compare(a.slot(), b.slot()));
        }

        JsonObject glacite = asObj(member.get("glacite_player_data"));
        List<String> fossils = strings(glacite == null ? null : asArr(glacite.get("fossils_donated")));
        Map<String, Integer> corpses = new LinkedHashMap<>();
        JsonObject looted = glacite == null ? null : asObj(glacite.get("corpses_looted"));
        if (looted != null) {
            for (Map.Entry<String, JsonElement> e : looted.entrySet()) {
                corpses.put(e.getKey(), (int) num(e.getValue()));
            }
        }
        return new Mining(core != null || tree != null, (long) num(path(member, "skill_tree.experience.mining")), slot, nodes, ability,
                total, available, crystals, forge, (long) num(path(member, "player_stats.pets.milestone.ores_mined")),
                glacite == null ? 0 : (int) num(glacite.get("mineshafts_entered")), fossils, corpses);
    }

    private static Foraging parseForaging(JsonObject member) {
        int slot = (int) num(path(member, "skill_tree.selected_skill_tree_slot.foraging"));
        String suffix = slot >= 2 ? "_" + slot : "";
        Map<String, Integer> nodes = nodes(asObj(path(member, "skill_tree.nodes.foraging" + suffix)));
        JsonObject whispers = asObj(path(member, "foraging_core.whispers"));
        String slotKey = String.valueOf(Math.max(1, slot));
        long forestTotal = (long) num(path(whispers, "forest.total"));
        long forestSpent = (long) num(path(whispers, "forest." + slotKey + ".spent"));
        long desertTotal = (long) num(path(whispers, "desert.total"));
        long desertSpent = (long) num(path(whispers, "desert." + slotKey + ".spent"));
        Map<String, Integer> gifts = new LinkedHashMap<>();
        JsonObject tg = asObj(path(member, "foraging.tree_gifts"));
        if (tg != null) {
            for (String t : List.of("FIG", "MANGROVE", "HELIX")) {
                gifts.put(t, (int) num(tg.get(t)));
            }
        }
        long xp = (long) num(path(member, "skill_tree.experience.foraging"));
        return new Foraging(xp > 0 || member.has("foraging_core"), xp, nodes, forestTotal, Math.max(0, forestTotal - forestSpent),
                desertTotal, Math.max(0, desertTotal - desertSpent), gifts);
    }

    private static Map<String, Integer> nodes(JsonObject obj) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (obj == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (!e.getKey().startsWith("toggle_") && e.getValue().isJsonPrimitive()) {
                int v = (int) num(e.getValue());
                if (v > 0) {
                    out.put(e.getKey(), v);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ crimson isle

    private static final List<String> KUUDRA_IDS = List.of("none", "hot", "burning", "fiery", "infernal");
    private static final List<String> DOJO_IDS = List.of("mob_kb", "wall_jump", "archer", "snake", "sword_swap", "fireball", "lock_head");

    private static Crimson parseCrimson(JsonObject d) {
        JsonObject data = d == null ? new JsonObject() : d;
        JsonObject kuudra = asObj(data.get("kuudra_completed_tiers"));
        Map<String, Integer> comps = new LinkedHashMap<>();
        Map<String, Integer> waves = new LinkedHashMap<>();
        for (String id : KUUDRA_IDS) {
            comps.put(id, kuudra == null ? 0 : (int) num(kuudra.get(id)));
            waves.put(id, kuudra == null ? 0 : (int) num(kuudra.get("highest_wave_" + id)));
        }
        JsonObject dojo = asObj(data.get("dojo"));
        Map<String, Integer> points = new LinkedHashMap<>();
        Map<String, Integer> times = new LinkedHashMap<>();
        for (String id : DOJO_IDS) {
            points.put(id, dojo == null || !dojo.has("dojo_points_" + id) ? -1 : (int) num(dojo.get("dojo_points_" + id)));
            times.put(id, dojo == null || !dojo.has("dojo_time_" + id) ? -1 : (int) num(dojo.get("dojo_time_" + id)));
        }
        return new Crimson(d != null, str(data, "selected_faction", ""),
                Math.max(0, (int) num(data.get("mages_reputation"))), Math.max(0, (int) num(data.get("barbarians_reputation"))),
                comps, waves, points, times, (int) num(path(data, "matriarch.pearls_collected")),
                (long) num(path(data, "matriarch.last_attempt")));
    }

    // ------------------------------------------------------------------ rift

    private static Rift parseRift(JsonObject member) {
        JsonObject r = asObj(member.get("rift"));
        JsonObject stats = asObj(path(member, "player_stats.rift"));
        List<Trophy> trophies = new ArrayList<>();
        JsonArray arr = r == null ? null : asArr(path(r, "gallery.secured_trophies"));
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject t = asObj(el);
                if (t != null) {
                    trophies.add(new Trophy(str(t, "type", ""), (long) num(t.get("timestamp")), (int) num(t.get("visits"))));
                }
            }
        }
        return new Rift(r != null || stats != null, (long) num(path(member, "currencies.motes_purse")),
                (long) num(stats == null ? null : stats.get("lifetime_motes_earned")),
                (int) num(stats == null ? null : stats.get("visits")),
                strings(r == null ? null : asArr(path(r, "enigma.found_souls"))),
                strings(r == null ? null : asArr(path(r, "wither_cage.killed_eyes"))),
                strings(r == null ? null : asArr(path(r, "dead_cats.found_cats"))),
                trophies, r == null ? 0 : (int) num(path(r, "castle.grubber_stacks")),
                r == null ? 0 : (long) num(path(r, "village_plaza.lonely.seconds_sitting")),
                r == null ? null : asObj(r.get("inventory")));
    }

    // ------------------------------------------------------------------ farming

    private static Farming parseFarming(JsonObject member) {
        JsonObject j = asObj(member.get("jacobs_contest"));
        Map<String, Integer> medalInv = new LinkedHashMap<>();
        for (String m : List.of("bronze", "silver", "gold")) {
            medalInv.put(m, j == null ? 0 : (int) num(path(j, "medals_inv." + m)));
        }
        Map<String, Integer> perCrop = new TreeMap<>();
        Map<String, Integer> earned = new LinkedHashMap<>();
        for (String m : List.of("bronze", "silver", "gold", "platinum", "diamond")) {
            earned.put(m, 0);
        }
        int contests = 0;
        JsonObject cs = j == null ? null : asObj(j.get("contests"));
        if (cs != null) {
            for (Map.Entry<String, JsonElement> e : cs.entrySet()) {
                contests++;
                String[] parts = e.getKey().split(":", 3);
                String crop = parts.length == 3 ? parts[2] : e.getKey();
                perCrop.merge(crop, 1, Integer::sum);
                String medal = str(asObj(e.getValue()), "claimed_medal", null);
                if (medal != null && !medal.isBlank()) {
                    earned.merge(medal.toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
                }
            }
        }
        Map<String, Long> pbs = new TreeMap<>();
        JsonObject pb = j == null ? null : asObj(j.get("personal_bests"));
        if (pb != null) {
            pbs.putAll(longMap(pb));
        }
        Map<String, List<String>> brackets = new LinkedHashMap<>();
        JsonObject ub = j == null ? null : asObj(j.get("unique_brackets"));
        if (ub != null) {
            for (Map.Entry<String, JsonElement> e : ub.entrySet()) {
                brackets.put(e.getKey().toLowerCase(java.util.Locale.ROOT), strings(asArr(e.getValue())));
            }
        }
        JsonObject garden = asObj(member.get("garden_player_data"));
        return new Farming(j != null, medalInv, j == null ? 0 : (int) num(path(j, "perks.double_drops")),
                j == null ? 0 : (int) num(path(j, "perks.farming_level_cap")),
                j != null && num(path(j, "perks.personal_bests")) > 0 || (j != null && isTrue(path(j, "perks.personal_bests"))),
                contests, perCrop, earned, pbs, brackets,
                garden == null ? 0 : (int) num(garden.get("copper")), garden == null ? 0 : (int) num(garden.get("larva_consumed")));
    }

    // ------------------------------------------------------------------ misc

    private static Misc parseMisc(JsonObject profile, JsonObject member) {
        JsonObject stats = asObj(member.get("player_stats"));
        Map<String, Long> kills = sortedDesc(longMap(asObj(stats == null ? null : stats.get("kills"))));
        Map<String, Long> deaths = sortedDesc(longMap(asObj(stats == null ? null : stats.get("deaths"))));
        Map<String, Long> essence = new LinkedHashMap<>();
        JsonObject ess = asObj(path(member, "currencies.essence"));
        if (ess != null) {
            for (Map.Entry<String, JsonElement> e : ess.entrySet()) {
                essence.put(e.getKey(), (long) num(asObj(e.getValue()) == null ? e.getValue() : e.getValue().getAsJsonObject().get("current")));
            }
        }
        JsonObject sacksObj = asObj(path(member, "inventory.sacks_counts"));
        Map<String, Long> sacks = new LinkedHashMap<>();
        if (sacksObj != null) {
            for (Map.Entry<String, Long> e : sortedDesc(longMap(sacksObj)).entrySet()) {
                if (e.getValue() > 0) {
                    sacks.put(e.getKey(), e.getValue());
                }
            }
        }
        Map<String, Long> auctions = longMap(asObj(stats == null ? null : stats.get("auctions")));
        int minions = 0;
        JsonObject members = asObj(profile.get("members"));
        if (members != null) {
            java.util.Set<String> unique = new java.util.HashSet<>();
            for (Map.Entry<String, JsonElement> e : members.entrySet()) {
                unique.addAll(strings(asArr(path(asObj(e.getValue()), "player_data.crafted_generators"))));
            }
            unique.remove("");
            minions = unique.size();
        }
        return new Misc(kills, deaths, essence, sacks, auctions,
                (long) num(stats == null ? null : stats.get("highest_critical_damage")),
                (long) num(path(member, "player_stats.items_fished.total")),
                isTrue(path(member, "profile.cookie_buff_active")), minions,
                (int) num(path(member, "accessory_bag_storage.highest_magical_power")),
                str(asObj(member.get("accessory_bag_storage")), "selected_power", ""),
                (int) num(path(member, "fairy_soul.fairy_exchanges")),
                (long) num(path(member, "player_stats.gifts.total_given")),
                (long) num(path(member, "player_stats.gifts.total_received")),
                (long) num(path(member, "player_stats.mythos.kills")),
                (long) num(path(member, "leveling.experience")), sacksObj != null);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isTrue(JsonElement el) {
        try {
            return el != null && el.isJsonPrimitive() && el.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> strings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) {
            return out;
        }
        for (JsonElement el : arr) {
            if (el != null && el.isJsonPrimitive()) {
                out.add(el.getAsString());
            }
        }
        return out;
    }

    private static Map<String, Long> longMap(JsonObject obj) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (obj == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                out.put(e.getKey(), (long) num(e.getValue()));
            }
        }
        return out;
    }

    private static Map<String, Long> sortedDesc(Map<String, Long> in) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(in.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : list) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}

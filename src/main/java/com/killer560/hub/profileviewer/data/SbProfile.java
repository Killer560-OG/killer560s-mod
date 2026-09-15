package com.killer560.hub.profileviewer.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.item.LegacyItems;
import com.killer560.hub.profileviewer.item.PetIcons;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One SkyBlock profile for one member, parsed from the raw {@code /v2/skyblock/profiles} JSON. JSON paths
 * follow skyblock-pv's {@code SkyBlockProfile.fromJson} / {@code Currency} / {@code DungeonData} /
 * {@code SlayerTypeData} / {@code Pet} / {@code InventoryData}. Cheap stats are parsed up front (off the
 * render thread, in {@code ProfileViewerApi}); item NBT is decoded lazily per profile by
 * {@link #inventories()}.
 */
public final class SbProfile {

    public final String profileId;
    public final String cuteName;
    public final boolean selected;
    public final String gameMode;
    public final int memberCount;
    public final UUID member;

    public final long firstJoin;
    public final int sbLevel;
    public final int sbLevelProgress;
    public final int fairySouls;
    public final double purse;
    /** -1 when the Banking API is off. */
    public final double bank;
    public final double personalBank;
    public final double motes;

    /** null when the Skills API is off. */
    public final Map<String, Long> skillXp;
    public final Map<String, Integer> skillCaps;

    public final Map<String, SlayerStat> slayers;
    public final DungeonStats dungeons;
    public final List<Pet> pets;
    public final int petScore;

    public final boolean inventoryApi;
    private final JsonObject inventoryJson;
    private final JsonObject sharedInventoryJson;
    private CompletableFuture<Inventories> inventories;

    public record SlayerStat(long xp, Map<Integer, Integer> kills) {
    }

    public record Floor(long completions, long fastestS, long fastestSPlus, long bestScore) {
    }

    public record DungeonStats(long catacombsXp, String selectedClass, Map<String, Long> classXp, long secrets,
                               Map<Integer, Floor> normal, Map<Integer, Floor> master, long totalRuns) {
    }

    public static final class Pet {
        public final String type;
        public final String tier;
        public final double exp;
        public final boolean active;
        public final String heldItem;
        public final String skin;
        public final int candyUsed;
        public final LevelTables.Level level;
        private ItemStack icon;

        Pet(JsonObject o) {
            type = str(o, "type", "UNKNOWN");
            tier = str(o, "tier", "COMMON");
            exp = num(o, "exp");
            active = o.has("active") && o.get("active").isJsonPrimitive() && o.get("active").getAsBoolean();
            heldItem = str(o, "heldItem", null);
            skin = str(o, "skin", null);
            candyUsed = (int) num(o, "candyUsed");
            level = LevelTables.petLevel(type, tier, exp);
        }

        public String displayName() {
            return titleCase(type);
        }

        /** Skull icon once the NEU-repo texture has loaded (cached), a bone until then. */
        public ItemStack icon() {
            if (icon != null) {
                return icon;
            }
            ItemStack now = PetIcons.icon(type, tier, skin);
            if (!now.is(net.minecraft.world.item.Items.BONE)) {
                icon = now;
            }
            return now;
        }
    }

    public record Backpack(int slot, ItemStack icon, List<ItemStack> items) {
    }

    public record Inventories(List<ItemStack> inventory, List<ItemStack> armor, List<ItemStack> equipment,
                              List<List<ItemStack>> enderChest, List<Backpack> backpacks,
                              List<List<ItemStack>> wardrobe, int wardrobeEquipped,
                              List<List<ItemStack>> accessories, List<ItemStack> personalVault,
                              List<ItemStack> quiver, List<ItemStack> fishingBag, List<ItemStack> potionBag) {
    }

    private SbProfile(JsonObject profile, JsonObject memberObj, UUID member) {
        this.member = member;
        profileId = str(profile, "profile_id", "");
        cuteName = str(profile, "cute_name", "Unknown");
        selected = profile.has("selected") && profile.get("selected").isJsonPrimitive() && profile.get("selected").getAsBoolean();
        gameMode = str(profile, "game_mode", "normal");
        JsonObject members = obj(profile, "members");
        memberCount = members == null ? 1 : members.size();

        firstJoin = (long) num(path(memberObj, "profile.first_join"));
        int sbXp = (int) num(path(memberObj, "leveling.experience"));
        sbLevel = sbXp / 100;
        sbLevelProgress = sbXp % 100;
        fairySouls = (int) num(path(memberObj, "fairy_soul.total_collected"));
        purse = num(path(memberObj, "currencies.coin_purse"));
        motes = num(path(memberObj, "currencies.motes_purse"));
        JsonElement bankEl = path(profile, "banking.balance");
        bank = bankEl == null ? -1 : num(bankEl);
        personalBank = num(path(memberObj, "profile.bank_account"));

        JsonObject experience = asObj(path(memberObj, "player_data.experience"));
        if (experience == null) {
            skillXp = null;
        } else {
            Map<String, Long> xp = new LinkedHashMap<>();
            for (String skill : LevelTables.SKILL_ORDER) {
                xp.put(skill, (long) num(experience.get("SKILL_" + skill)));
            }
            skillXp = Collections.unmodifiableMap(xp);
        }
        Map<String, Integer> caps = new LinkedHashMap<>();
        caps.put("FARMING", 50 + (int) num(path(memberObj, "jacobs_contest.perks.farming_level_cap")));
        JsonArray sacrificed = asArr(path(memberObj, "pets_data.pet_care.pet_types_sacrificed"));
        caps.put("TAMING", 50 + (sacrificed == null ? 0 : sacrificed.size()));
        caps.put("FORAGING", 50 + (int) num(path(memberObj, "player_data.experience.SKILL_FORAGING_extra_level_cap")));
        skillCaps = Collections.unmodifiableMap(caps);

        Map<String, SlayerStat> sl = new LinkedHashMap<>();
        JsonObject bosses = asObj(path(memberObj, "slayer.slayer_bosses"));
        if (bosses != null) {
            for (Map.Entry<String, JsonElement> e : bosses.entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject b = e.getValue().getAsJsonObject();
                Map<Integer, Integer> kills = new TreeMap<>();
                for (Map.Entry<String, JsonElement> k : b.entrySet()) {
                    if (k.getKey().startsWith("boss_kills_tier_")) {
                        try {
                            kills.put(Integer.parseInt(k.getKey().substring("boss_kills_tier_".length())), (int) num(k.getValue()));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                sl.put(e.getKey(), new SlayerStat((long) num(b.get("xp")), kills));
            }
        }
        slayers = Collections.unmodifiableMap(sl);

        dungeons = parseDungeons(asObj(memberObj.get("dungeons")));

        List<Pet> petList = new ArrayList<>();
        JsonArray petsArr = asArr(path(memberObj, "pets_data.pets"));
        if (petsArr != null) {
            for (JsonElement p : petsArr) {
                if (p.isJsonObject()) {
                    try {
                        petList.add(new Pet(p.getAsJsonObject()));
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        petList.sort(Comparator.<Pet>comparingInt(p -> p.active ? 0 : 1)
                .thenComparing(Comparator.<Pet>comparingInt(p -> LevelTables.rarityIndex(p.tier)).reversed())
                .thenComparing(Comparator.<Pet>comparingInt(p -> p.level.level()).reversed())
                .thenComparing(p -> p.type));
        pets = Collections.unmodifiableList(petList);
        petScore = (int) num(path(memberObj, "leveling.highest_pet_score"));

        inventoryJson = asObj(memberObj.get("inventory"));
        sharedInventoryJson = asObj(memberObj.get("shared_inventory"));
        inventoryApi = inventoryJson != null && inventoryJson.has("inv_contents");
    }

    public static List<SbProfile> parseAll(JsonObject root, UUID member) {
        List<SbProfile> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        JsonArray arr = asArr(root.get("profiles"));
        if (arr == null) {
            return out;
        }
        String key = ProfileViewerApi.dashless(member);
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject profile = el.getAsJsonObject();
            JsonObject memberObj = asObj(path(profile, "members." + key));
            if (memberObj == null) {
                continue;
            }
            // Profiles the player left/was removed from carry a deletion notice.
            if (path(memberObj, "profile.deletion_notice") != null) {
                continue;
            }
            try {
                out.add(new SbProfile(profile, memberObj, member));
            } catch (Exception ignored) {
            }
        }
        out.sort(Comparator.comparing((SbProfile p) -> !p.selected).thenComparing(p -> p.cuteName));
        return out;
    }

    // ------------------------------------------------------------------ dungeons

    private static DungeonStats parseDungeons(JsonObject d) {
        if (d == null) {
            return new DungeonStats(0, "", Map.of(), 0, Map.of(), Map.of(), 0);
        }
        JsonObject cata = asObj(path(d, "dungeon_types.catacombs"));
        JsonObject master = asObj(path(d, "dungeon_types.master_catacombs"));
        Map<String, Long> classes = new LinkedHashMap<>();
        for (String c : List.of("healer", "mage", "berserk", "archer", "tank")) {
            classes.put(c, (long) num(path(d, "player_classes." + c + ".experience")));
        }
        Map<Integer, Floor> normal = floors(cata);
        Map<Integer, Floor> mm = floors(master);
        long runs = 0;
        for (Floor f : normal.values()) {
            runs += f.completions();
        }
        for (Floor f : mm.values()) {
            runs += f.completions();
        }
        return new DungeonStats(cata == null ? 0 : (long) num(cata.get("experience")),
                str(d, "selected_dungeon_class", ""), classes, (long) num(d.get("secrets")), normal, mm, runs);
    }

    private static Map<Integer, Floor> floors(JsonObject type) {
        Map<Integer, Floor> out = new TreeMap<>();
        if (type == null) {
            return out;
        }
        JsonObject completions = asObj(type.get("tier_completions"));
        JsonObject sPlus = asObj(type.get("fastest_time_s_plus"));
        JsonObject s = asObj(type.get("fastest_time_s"));
        JsonObject score = asObj(type.get("best_score"));
        for (int floor = 0; floor <= 7; floor++) {
            String k = String.valueOf(floor);
            long comps = completions == null ? 0 : (long) num(completions.get(k));
            long fs = s == null ? 0 : (long) num(s.get(k));
            long fsp = sPlus == null ? 0 : (long) num(sPlus.get(k));
            long best = score == null ? 0 : (long) num(score.get(k));
            if (comps > 0 || fs > 0 || fsp > 0 || best > 0) {
                out.put(floor, new Floor(comps, fs, fsp, best));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ inventories

    /** Decodes every inventory blob for this profile once, on the API executor. */
    public synchronized CompletableFuture<Inventories> inventories() {
        if (inventories == null) {
            inventories = CompletableFuture.supplyAsync(this::decodeInventories, ProfileViewerApi.EXECUTOR);
        }
        return inventories;
    }

    private Inventories decodeInventories() {
        JsonObject inv = inventoryJson == null ? new JsonObject() : inventoryJson;
        JsonObject bags = asObj(inv.get("bag_contents"));

        List<ItemStack> inventory = LegacyItems.decodeInventory(inv.get("inv_contents"));
        List<ItemStack> armor = LegacyItems.decodeInventory(inv.get("inv_armor"));
        List<ItemStack> equipment = LegacyItems.decodeInventory(inv.get("equipment_contents"));
        List<List<ItemStack>> ender = chunk(LegacyItems.decodeInventory(inv.get("ender_chest_contents")), 45);
        List<List<ItemStack>> wardrobe = chunk(LegacyItems.decodeInventory(inv.get("wardrobe_contents")), 36);
        int wardrobeEquipped = (int) num(inv.get("wardrobe_equipped_slot"));

        Map<Integer, ItemStack> icons = new TreeMap<>();
        JsonObject iconObj = asObj(inv.get("backpack_icons"));
        if (iconObj != null) {
            for (Map.Entry<String, JsonElement> e : iconObj.entrySet()) {
                List<ItemStack> one = LegacyItems.decodeInventory(e.getValue());
                if (!one.isEmpty()) {
                    icons.put(parseInt(e.getKey()), one.get(0));
                }
            }
        }
        List<Backpack> backpacks = new ArrayList<>();
        JsonObject bpObj = asObj(inv.get("backpack_contents"));
        if (bpObj != null) {
            Map<Integer, List<ItemStack>> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonElement> e : bpObj.entrySet()) {
                sorted.put(parseInt(e.getKey()), LegacyItems.decodeInventory(e.getValue()));
            }
            for (Map.Entry<Integer, List<ItemStack>> e : sorted.entrySet()) {
                backpacks.add(new Backpack(e.getKey(), icons.getOrDefault(e.getKey(), ItemStack.EMPTY), e.getValue()));
            }
        }

        List<List<ItemStack>> accessories = bags == null ? List.of()
                : chunk(LegacyItems.decodeInventory(bags.get("talisman_bag")), 45);
        List<ItemStack> vault = LegacyItems.decodeInventory(inv.get("personal_vault_contents"));
        List<ItemStack> quiver = bags == null ? List.of() : LegacyItems.decodeInventory(bags.get("quiver"));
        List<ItemStack> fishing = bags == null ? List.of() : LegacyItems.decodeInventory(bags.get("fishing_bag"));
        List<ItemStack> potion = bags == null ? List.of() : LegacyItems.decodeInventory(bags.get("potion_bag"));

        return new Inventories(inventory, armor, equipment, ender, backpacks, wardrobe, wardrobeEquipped,
                accessories, vault, quiver, fishing, potion);
    }

    private static <T> List<List<T>> chunk(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            out.add(new ArrayList<>(list.subList(i, Math.min(list.size(), i + size))));
        }
        return out;
    }

    // ------------------------------------------------------------------ json helpers

    static JsonElement path(JsonObject root, String dotted) {
        JsonElement cur = root;
        for (String part : dotted.split("\\.")) {
            if (cur == null || !cur.isJsonObject()) {
                return null;
            }
            cur = cur.getAsJsonObject().get(part);
        }
        return cur == null || cur.isJsonNull() ? null : cur;
    }

    static JsonObject obj(JsonObject o, String key) {
        return o == null ? null : asObj(o.get(key));
    }

    static JsonObject asObj(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    static JsonArray asArr(JsonElement el) {
        return el != null && el.isJsonArray() ? el.getAsJsonArray() : null;
    }

    static double num(JsonElement el) {
        try {
            return el != null && el.isJsonPrimitive() ? el.getAsDouble() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static double num(JsonObject o, String key) {
        return o == null ? 0 : num(o.get(key));
    }

    static String str(JsonObject o, String key, String def) {
        if (o == null) {
            return def;
        }
        JsonElement el = o.get(key);
        try {
            return el != null && el.isJsonPrimitive() ? el.getAsString() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String titleCase(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : id.toLowerCase(Locale.ROOT).split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}

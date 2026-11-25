package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.ExtraTables;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.ProfileExtras;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.profileviewer.item.ItemIcons;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * NEU Classic's second Basic page, the SkyBlock Level page (over pv_levels.png): the Level bar at 163, 30 and eight task
 * bars, 110 wide, at 23 and 299 across and 25, 55, 85, 115 down (Core, Dungeon, Slaying, Skill Related on the left;
 * Essence, Misc., Story, Event on the right). Each bar shows its name in red with the percent of the category's XP flush
 * right, the pink bar (gold when full) and its icon at 0.7x; the hover lists the category's tasks ("Name: 12% (x/y XP)",
 * a plain "x XP" for uncapped tasks, red "NOT DETECTABLE!" for Museum) and the bar's own Progress line. The task XP table is
 * bundled (sblevels.json); the numbers are worked out once per profile off the render thread.
 */
final class NeuLevelPage {

    private static final String SKULL_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODdk"
            + "ODg1YjMyYjBkZDJkNmI3ZjFiNTgyYTM0MTg2ZjhhNTM3M2M0NjU4OWEyNzM0MjMxMzJiNDQ4YjgwMzQ2MiJ9fX0=";
    private static ItemStack skull;

    /** The SkyBlock level skull the original shows on the Basic page, the Level bar and the Level side button. */
    static ItemStack skull() {
        if (skull == null) skull = com.killer560.hub.profileviewer.item.LegacyItems.skull(SKULL_TEXTURE, null);
        return skull;
    }

    private static JsonObject table;

    private static synchronized JsonObject table() {
        if (table == null) {
            try (InputStream in = NeuLevelPage.class.getResourceAsStream("/assets/killer560smod/profileviewer/sblevels.json")) {
                table = in == null ? new JsonObject() : JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                table = new JsonObject();
            }
        }
        return table;
    }

    /** One drawn bar: where, its name, icon, the right-hand text, how full, maxed, and its whole hover. */
    private record Bar(int x, int y, String name, ItemStack icon, String right, float done, boolean gold, List<Component> tip) {
    }

    private final ProfileViewerScreen s;
    private SbProfile modelFor;
    private boolean modelFull;
    private CompletableFuture<List<Bar>> model;

    NeuLevelPage(ProfileViewerScreen s) {
        this.s = s;
    }

    void draw(GuiGraphicsExtractor g, SbProfile p, int mx, int my) {
        CompletableFuture<ProfileExtras> ef = p.extras();
        CompletableFuture<List<ExtraTables.CollectionCategory>> cf = ExtraTables.collections();
        CompletableFuture<List<ExtraTables.BestiaryCategory>> bf = ExtraTables.bestiary();
        boolean full = ef.isDone() && cf.isDone() && bf.isDone();
        if (modelFor != p || model == null || full && !modelFull && model.isDone()) {
            modelFor = p;
            modelFull = full;
            ProfileExtras ex = ef.isDone() ? ExtraTables.now(ef) : null;
            List<ExtraTables.CollectionCategory> cols = cf.isDone() ? ExtraTables.now(cf) : null;
            List<ExtraTables.BestiaryCategory> best = bf.isDone() ? ExtraTables.now(bf) : null;
            model = CompletableFuture.supplyAsync(() -> build(p, ex, cols, best), ProfileViewerApi.EXECUTOR);
        }
        List<Bar> bars = model.isDone() && !model.isCompletedExceptionally() ? model.getNow(null) : null;
        if (bars == null) return;
        int l = s.panelX, t = s.panelY;
        List<Component> tip = null;
        for (Bar b : bars) {
            int x = l + b.x, y = t + b.y;
            PvStyleChrome.neuAligned(g, b.name, 0xFFFF5555, b.right, 0xFFFFFFFF, x + 14, y - 4, 90);
            PvStyleChrome.neuBar(g, x, y + 6, 110, b.done, b.gold);
            g.pose().pushMatrix();
            g.pose().translate(x, y - 6);
            g.pose().scale(0.7f, 0.7f);
            g.item(b.icon, 0, 0);
            g.pose().popMatrix();
            if (mx > x && mx < x + 120 && my > y - 4 && my < y + 13 && !s.dropdownOpen) tip = b.tip;
        }
        if (tip != null) s.pendingTooltip = tip;
    }

    // ---- building (off the render thread) ----

    private static Bar bar(String name, ItemStack icon, int x, int y, long level, double xp, double max, boolean percent, List<String> lore) {
        if (xp < 0) xp = 0;
        double frac = xp / max;
        List<Component> tip = new ArrayList<>();
        for (String line : lore) tip.add(Component.literal(line));
        if (!lore.isEmpty()) tip.add(Component.literal(""));
        tip.add(Component.literal("§7Progress: §5" + (int) (frac * 100) + "% §8(" + fmt(xp) + "/" + fmt(max) + " XP)"));
        return new Bar(x, y, name, icon, percent ? (int) (frac * 100) + "%" : String.valueOf(level), (float) Math.min(1, frac), xp >= max,
                List.copyOf(tip));
    }

    /** One task line, as the original words it. */
    private static String lore(String name, double got, double max, boolean noLimit) {
        if (max == 0 && got == 0 && !noLimit) return "§6" + name + ": §c§lNOT DETECTABLE!";
        if (noLimit) return "§6" + name + ": §e" + fmt(got) + " XP";
        int pct = (int) (got / max * 100);
        if (got >= max) return "§6" + name + ": §a" + pct + "% §8(" + fmt(got) + "/" + fmt(max) + " XP)";
        if (got == -1) return "§6" + name + ": §c§lCOLLECTION DISABLED!";
        return "§6" + name + ": §e" + pct + "% §8(" + fmt(got) + "/" + fmt(max) + " XP)";
    }

    private static String fmt(double v) { return String.format(Locale.US, "%,d", (int) v); }

    private static int i(JsonObject o, String k) { return o == null || !o.has(k) ? 0 : o.get(k).getAsInt(); }

    private static int at(JsonArray a, int idx) { return a == null || idx < 0 || idx >= a.size() ? 0 : a.get(idx).getAsInt(); }

    private static int size(JsonArray a) { return a == null ? 0 : a.size(); }

    private static List<Bar> build(SbProfile p, ProfileExtras ex, List<ExtraTables.CollectionCategory> cols, List<ExtraTables.BestiaryCategory> best) {
        JsonObject c = table();
        JsonObject cat = c.getAsJsonObject("category_xp");
        List<Bar> out = new ArrayList<>();
        out.add(bar("Level", skull(), 163, 30, p.sbLevel, p.sbLevelProgress, 100, false, List.of()));
        List<String> tasks = new ArrayList<>();
        JsonArray done = p.memberArr("leveling.completed_tasks");
        List<String> doneTasks = new ArrayList<>();
        if (done != null) for (JsonElement e : done) if (e.isJsonPrimitive()) doneTasks.add(e.getAsString());

        // Core Task
        JsonObject core = c.getAsJsonObject("core_task");
        int skillXp = 0;
        if (p.skillXp != null) {
            for (String id : List.of("TAMING", "MINING", "FORAGING", "ENCHANTING", "CARPENTRY", "FARMING", "COMBAT", "FISHING", "ALCHEMY")) {
                int lv = LevelTables.skillLevel(id, p.skillXp.getOrDefault(id, 0L), p.skillCaps.getOrDefault(id, -1)).level();
                for (int n = 1; n <= lv; n++) skillXp += n <= 10 ? 5 : n <= 25 ? 10 : n <= 50 ? 20 : n <= 60 ? 30 : 0;
            }
        } else {
            skillXp = -1;
        }
        int mp = (int) p.memberNum("accessory_bag_storage.highest_magical_power");
        int petXp = p.petScore * i(core, "pet_score_xp");
        int fairyXp = (int) (p.memberNum("fairy_soul.total_collected") - p.memberNum("fairy_soul.unspent_souls")) / 5 * i(core, "fairy_souls_xp");
        int collXp = -1, minionXp = -1;
        if (cols != null && ex != null) {
            collXp = 0;
            for (ExtraTables.CollectionCategory cc : cols) {
                for (ExtraTables.CollectionItem it : cc.items()) {
                    collXp += ExtraPages.countAtOrBelow(it.tiers(), ex.collections.getOrDefault(it.id(), 0L)) * i(core, "collections_xp");
                }
            }
            JsonObject mxp = c.getAsJsonObject("minion_xp");
            for (int tier : craftedGenerators(p).values()) {
                for (int n = 1; n <= tier; n++) minionXp += i(mxp, String.valueOf(n));
            }
        }
        int bankXp = 0, travelXp = 0;
        JsonObject bankUps = core.getAsJsonObject("bank_upgrades_xp");
        for (String name : doneTasks) {
            if (name.startsWith("FAST_TRAVEL_")) travelXp += i(core, "fast_travel_unlocked_xp");
            bankXp += i(bankUps, name);
        }
        tasks.add(lore("Skill Level Up", skillXp, i(core, "skill_level_up"), false));
        tasks.add(lore("Museum Progression", 0, 0, false));
        tasks.add(lore("Fairy Soul", fairyXp, i(core, "fairy_souls"), false));
        tasks.add(lore("Accessory Bag", mp, 0, true));
        tasks.add(lore("Pet Score", petXp, 0, true));
        tasks.add(lore("Collections", collXp, i(core, "collections"), false));
        tasks.add(lore("Craft Minions", minionXp, i(core, "craft_minions"), false));
        tasks.add(lore("Bank Upgrade", bankXp, i(core, "bank_upgrades"), false));
        tasks.add(lore("Fast Travel Scroll", travelXp, i(core, "fast_travel_unlocked"), false));
        out.add(bar("Core Task", new ItemStack(Items.NETHER_STAR), 23, 25, 0,
                skillXp + fairyXp + collXp + minionXp + bankXp + travelXp, i(cat, "core_task"), true, tasks));

        // Dungeon Task
        JsonObject dung = c.getAsJsonObject("dungeon_task");
        int cataLv = 0, cataXp = 0, classXp = 0, floorXp = 0;
        if (p.dungeons != null) {
            cataLv = LevelTables.catacombsLevel(p.dungeons.catacombsXp(), false).level();
            for (int n = 1; n <= cataLv; n++) cataXp += n < 40 ? 20 : 40;
            for (String cl : List.of("healer", "mage", "berserk", "archer", "tank")) {
                int lv = LevelTables.catacombsLevel(p.dungeons.classXp().getOrDefault(cl, 0L), false).level();
                classXp += Math.min(lv, 50) * i(dung, "class_xp");
            }
            JsonObject normal = p.memberObj("dungeons.dungeon_types.catacombs.tier_completions");
            JsonArray per = dung.getAsJsonArray("complete_catacombs");
            for (int f = 0; f < size(per); f++) if (normal != null && normal.has(String.valueOf(f))) floorXp += at(per, f);
            JsonObject master = p.memberObj("dungeons.dungeon_types.master_catacombs.tier_completions");
            for (int f = 0; f <= 7; f++) if (master != null && master.has(String.valueOf(f))) floorXp += i(dung, "complete_master_catacombs");
        }
        tasks = new ArrayList<>();
        tasks.add(lore("Catacombs Level Up", cataXp, i(dung, "catacombs_level_up"), false));
        tasks.add(lore("Class Level Up", classXp, i(dung, "class_level_up"), false));
        tasks.add(lore("Complete Dungeons", floorXp, i(dung, "complete_dungeon"), false));
        out.add(bar("Dungeon Task", ItemIcons.catalogIcon("WITHER_RELIC"), 23, 55, cataLv, cataXp + classXp + floorXp,
                i(dung, "catacombs_level_up") + i(dung, "class_level_up") + i(dung, "complete_dungeon"), true, tasks));

        // Essence (the original's lines come in its own map order, which a HashMap of the same keys repeats)
        JsonObject ess = c.getAsJsonObject("essence_shop_task");
        JsonArray steps = ess.getAsJsonArray("essence_shop_xp");
        Map<String, Integer> shops = new HashMap<>();
        JsonObject perks = c.getAsJsonObject("essence_perks");
        for (Map.Entry<String, JsonElement> e : perks.entrySet()) {
            int sum = 0;
            for (JsonElement perk : e.getValue().getAsJsonArray()) {
                int lv = (int) p.memberNum("player_data.perks." + perk.getAsString());
                for (int n = Math.min(lv, size(steps)) - 1; n >= 0; n--) sum += at(steps, n);
            }
            shops.merge(e.getKey(), sum, Integer::sum);
        }
        for (String k : ess.keySet()) if (k.endsWith("_shop")) shops.putIfAbsent(k.split("_shop")[0].toUpperCase(Locale.ROOT), 0);
        tasks = new ArrayList<>();
        int essTotal = 0;
        for (Map.Entry<String, Integer> e : shops.entrySet()) {
            String key = e.getKey().toLowerCase(Locale.ROOT) + "_shop";
            if (!ess.has(key)) continue;
            tasks.add(lore(SbProfile.titleCase(e.getKey().substring("ESSENCE_".length())) + " Essence", e.getValue(), i(ess, key), false));
            essTotal += e.getValue();
        }
        out.add(bar("Essence", ItemIcons.catalogIcon("ESSENCE_WITHER"), 299, 25, essTotal, essTotal, i(cat, "essence_shop_task"), true, tasks));

        // Misc. Task (the original draws nothing without Crimson Isle data)
        if (p.memberObj("nether_island_player_data") != null) out.add(misc(p, c, cat, doneTasks));

        // Skill Related Task
        if (p.skillXp != null) out.add(skillRelated(p, c, cat, ex));

        // Slaying Task
        out.add(slaying(p, c, cat, ex, best));

        // Story Task
        JsonObject story = c.getAsJsonObject("story_task");
        JsonObject objectives = p.memberObj("objectives");
        int storyXp = completed(objectives, story.getAsJsonArray("complete_objectives_names")) * i(story, "complete_objectives_xp");
        int riftXp = completed(objectives, story.getAsJsonArray("rift_guide_names")) * i(story, "rift_guide_xp");
        tasks = new ArrayList<>();
        tasks.add(lore("Complete Objectives", storyXp, i(story, "complete_objectives"), false));
        tasks.add(lore("Rift Guide", riftXp, i(story, "rift_guide"), false));
        out.add(bar("Story Task", new ItemStack(Items.MAP), 299, 85, 0, storyXp + riftXp, i(cat, "story_task"), true, tasks));

        // Event Task
        JsonObject ev = c.getAsJsonObject("event_task");
        int fiesta = 0, festival = 0, spooky = 0;
        if (p.memberObj("leveling") != null) {
            fiesta = capOrAmount((int) p.memberNum("leveling.mining_fiesta_ores_mined"), 1_000_000, 5_000);
            festival = capOrAmount((int) p.memberNum("leveling.fishing_festival_sharks_killed"), 5_000, 50);
            JsonObject sp = ev.getAsJsonObject("spooky_festival_xp");
            for (String name : doneTasks) spooky += i(sp, name);
        }
        Set<String> medals = new HashSet<>();
        for (String b : List.of("gold", "platinum", "diamond")) {
            JsonArray a = p.memberArr("jacobs_contest.unique_brackets." + b);
            if (a != null) for (JsonElement e : a) medals.add(e.getAsString());
        }
        int medalXp = medals.size() * i(ev, "jacob_farming_contest_xp");
        tasks = new ArrayList<>();
        tasks.add(lore("Mining Fiesta", fiesta, i(ev, "mining_fiesta"), false));
        tasks.add(lore("Fishing Festival", festival, i(ev, "fishing_festival"), false));
        tasks.add(lore("Jacob's Farming Contest", medalXp, i(ev, "jacob_farming_contest"), false));
        tasks.add(lore("Spooky Festival", spooky, i(ev, "spooky_festival"), false));
        out.add(bar("Event Task", new ItemStack(Items.CLOCK), 299, 115, 0, fiesta + festival + medalXp + spooky, i(cat, "event_task"), true, tasks));
        return List.copyOf(out);
    }

    private static int capOrAmount(int amount, int cap, int per) {
        if (amount == 0) return 0;
        return (amount > cap ? cap : amount) / per;
    }

    private static int completed(JsonObject objectives, JsonArray names) {
        int n = 0;
        if (objectives == null || names == null) return 0;
        for (JsonElement e : names) {
            JsonObject o = SbProfile.asObj(objectives.get(e.getAsString()));
            if (o != null && o.has("status") && "COMPLETE".equals(o.get("status").getAsString())) n++;
        }
        return n;
    }

    /** Each minion type's highest crafted tier over every member of the profile ("WHEAT_5"). */
    private static Map<String, Integer> craftedGenerators(SbProfile p) {
        Map<String, Integer> out = new HashMap<>();
        JsonObject members = p.profileObj("members");
        if (members == null) return out;
        for (Map.Entry<String, JsonElement> m : members.entrySet()) {
            JsonObject mo = SbProfile.asObj(m.getValue());
            JsonObject pd = mo == null ? null : SbProfile.asObj(mo.get("player_data"));
            JsonElement arr = pd == null ? null : pd.get("crafted_generators");
            if (arr == null || !arr.isJsonArray()) continue;
            for (JsonElement e : arr.getAsJsonArray()) {
                String id = e.getAsString();
                int us = id.lastIndexOf('_');
                if (us <= 0) continue;
                try {
                    out.merge(id.substring(0, us), Integer.parseInt(id.substring(us + 1)), Math::max);
                } catch (NumberFormatException ignored) {
                    // not a tiered id
                }
            }
        }
        return out;
    }

    private static Bar misc(SbProfile p, JsonObject c, JsonObject cat, List<String> doneTasks) {
        JsonObject m = c.getAsJsonObject("miscellaneous_task");
        int bagUps = 0, peppers = 0, powers = 0, jyrre = 0, larva = 0, truffles = 0;
        JsonArray unlocked = p.memberArr("accessory_bag_storage.unlocked_powers");
        if (unlocked != null) {
            bagUps = (int) p.memberNum("accessory_bag_storage.bag_upgrades_purchased") * i(m, "accessory_bag_upgrades_xp");
            peppers = (int) p.memberNum("player_data.reaper_peppers_eaten") * i(m, "reaper_peppers_xp");
            powers = unlocked.size() * i(m, "unlocking_powers_xp");
            jyrre = (int) p.memberNum("winter_player_data.refined_jyrre_uses") * i(m, "refined_jyrre_xp");
            larva = (int) p.memberNum("garden_player_data.larva_consumed") * i(m, "wriggling_larva_xp");
            truffles = (int) p.memberNum("events.easter.refined_dark_cacao_truffles") * i(m, "refined_dark_cacao_truffles_xp");
        }
        int dojo = 0, relays = 0, abiphone = 0;
        JsonObject phone = p.memberObj("nether_island_player_data.abiphone");
        if (phone != null) relays = ((int) p.memberNum("nether_island_player_data.abiphone.operator_chip.repaired_index") + 1) * i(m, "unlocking_relays_xp");
        if (p.memberObj("nether_island_player_data.dojo") != null) {
            int points = 0;
            for (String test : List.of("mob_kb", "wall_jump", "archer", "sword_swap", "snake", "lock_head", "fireball")) {
                points += (int) p.memberNum("nether_island_player_data.dojo.dojo_points_" + test);
            }
            int rank = 0;
            for (int need : new int[]{0, 1000, 2000, 4000, 6000, 7000}) if (points > need) rank++;
            JsonArray dx = m.getAsJsonArray("the_dojo_xp");
            for (int n = 0; n < rank; n++) dojo += at(dx, n);
        }
        if (p.memberObj("leveling") != null && p.memberArr("leveling.completed_tasks") != null && phone != null && phone.has("active_contacts")) {
            abiphone = (int) doneTasks.stream().filter(t -> t.startsWith("ABIPHONE_")).count() * i(m, "abiphone_contacts_xp");
        }
        int harp = 0;
        JsonObject songs = m.getAsJsonObject("harp_songs_names");
        for (String name : doneTasks) {
            int us = name.lastIndexOf('_');
            if (us > 0) harp += i(songs, name.substring(0, us)) / 4;
        }
        int community = 0;
        JsonObject cu = p.profileObj("community_upgrades");
        JsonArray states = cu == null ? null : cu.has("upgrade_states") && cu.get("upgrade_states").isJsonArray() ? cu.getAsJsonArray("upgrade_states") : null;
        JsonObject maxes = m.getAsJsonObject("community_shop_upgrades_max");
        if (states != null) {
            for (JsonElement e : states) {
                JsonObject st = SbProfile.asObj(e);
                if (st == null || !st.has("upgrade")) continue;
                String up = st.get("upgrade").getAsString();
                if (maxes.has(up) && maxes.get(up).getAsInt() >= i(st, "tier")) community += i(m, "community_shop_upgrades_xp");
            }
        }
        int bank = 0;
        JsonArray bankXp = m.getAsJsonArray("personal_bank_upgrades_xp");
        for (int n = 1; n <= (int) p.memberNum("profile.personal_bank_upgrade"); n++) bank += at(bankXp, n - 1);
        int charms = size(p.memberArr("rift.gallery.secured_trophies")) * i(m, "timecharm_xp");
        int burger = (int) p.memberNum("rift.castle.grubber_stacks") * i(m, "mcgrubber_burger_xp");
        int serum = i(m, "metaphysical_serum_xp") * (int) p.memberNum("experimentation.serums_drank");
        int consumables = peppers + burger + serum + jyrre + larva + truffles;
        List<String> tasks = new ArrayList<>();
        tasks.add(lore("Accessory Bag Upgrades", bagUps, 0, true));
        tasks.add(lore("Consumable Items", consumables, i(m, "consumable_items"), false));
        tasks.add(lore("Timecharms", charms, i(m, "timecharm"), false));
        tasks.add(lore("Unlocking Powers", powers, 0, true));
        tasks.add(lore("The Dojo", dojo, i(m, "the_dojo"), false));
        tasks.add(lore("Harp Songs", harp, i(m, "harp_songs"), false));
        tasks.add(lore("Abiphone Contacts", abiphone, i(m, "abiphone_contacts"), false));
        tasks.add(lore("Community Shop Upgrades", community, i(m, "community_shop_upgrades"), false));
        tasks.add(lore("Personal Bank Upgrades", bank, i(m, "personal_bank_upgrades"), false));
        tasks.add(lore("Upgraded Relays", relays, i(m, "unlocking_relays"), false));
        return bar("Misc. Task", new ItemStack(Items.MAP), 299, 55, 0, dojo + harp + abiphone + community + bank + charms + relays + consumables,
                i(cat, "miscellaneous_task"), true, tasks);
    }

    private static Bar skillRelated(SbProfile p, JsonObject c, JsonObject cat, ProfileExtras ex) {
        JsonObject sk = c.getAsJsonObject("skill_related_task");
        JsonObject mining = sk.getAsJsonObject("mining");
        long[] hotmTable = ExtraTables.tableLongs(ExtraTables.bundled().get("hotm_levels"));
        int hotm = ex == null ? 0 : LevelTables.cumulative(hotmTable, ex.mining.hotmXp(), hotmTable.length).level();
        double hotmXp = 0;
        for (int n = 1; n <= hotm; n++) hotmXp += at(mining.getAsJsonArray("hotm_xp"), n - 1);
        // powder: the original adds the unspent powder twice (spent + unspent, then + unspent again)
        double[] under = {2400, 2500, 2500}, cap = {12_500_000, 20_000_000, 20_000_000}, k = {3.75, 4.25, 4.25};
        String[] kinds = {"mithril", "gemstone", "glacite"};
        for (int n = 0; n < 3; n++) {
            double have = p.memberNum("mining_core.powder_" + kinds[n]);
            double total = p.memberNum("mining_core.powder_spent_" + kinds[n]) + have + have;
            double low = Math.min(350000.0, total), over = Math.max(0, Math.min(total, cap[n]) - 350000.0);
            hotmXp += Math.floor(low / under[n]) + Math.floor(k[n] * (Math.sqrt(1 + 8 * Math.sqrt((1758267.0 / cap[n]) * over + 9)) - 3));
        }
        int potm = 0;
        int peak = ex == null ? 0 : ex.mining.nodes().getOrDefault("special_0", 0);
        for (int n = 1; n <= peak; n++) potm += at(mining.getAsJsonArray("potm_xp"), n - 1);
        int commissions = 0;
        JsonArray tutorial = p.memberArr("objectives.tutorial");
        JsonArray cmXp = mining.getAsJsonArray("commission_milestone_xp");
        if (tutorial != null) {
            for (JsonElement e : tutorial) {
                if (!e.isJsonPrimitive()) continue;
                String str = e.getAsString();
                for (int n = 1; n <= size(cmXp); n++) if (str.equals("commission_milestone_reward_skyblock_xp_tier_" + n)) commissions += at(cmXp, n - 1);
            }
        }
        int fossils = size(p.memberArr("glacite_player_data.fossils_donated")) * i(mining, "fossil_research_xp");
        int rock = milestone(p.memberNum("player_stats.pets.milestone.ores_mined"), mining.getAsJsonArray("rock_milestone_required"), i(mining, "rock_milestone_xp"));
        JsonObject farming = sk.getAsJsonObject("farming");
        int anita = (int) (p.memberNum("jacobs_contest.perks.double_drops") + p.memberNum("jacobs_contest.perks.farming_level_cap"))
                * i(farming, "anita_shop_upgrades_xp");
        JsonObject fishing = sk.getAsJsonObject("fishing");
        JsonArray tfXp = fishing.getAsJsonArray("trophy_fish_xp");
        int trophy = 0;
        JsonObject tf = p.memberObj("trophy_fish");
        if (tf != null) {
            String[] tiers = {"_bronze", "_silver", "_gold", "_diamond"};
            for (Map.Entry<String, JsonElement> e : tf.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) continue;
                for (int n = 0; n < 4; n++) if (e.getKey().endsWith(tiers[n])) trophy += at(tfXp, n);
            }
        }
        int dolphin = milestone(p.memberNum("player_stats.pets.milestone.sea_creatures_killed"), fishing.getAsJsonArray("dolphin_milestone_required"),
                i(fishing, "dolphin_milestone_xp"));
        int nucleus = (int) p.memberNum("leveling.completions.NUCLEUS_RUNS") * i(mining, "crystal_nucleus_xp");
        List<String> tasks = new ArrayList<>();
        tasks.add(lore("Heart of the Mountain", hotmXp, i(mining, "hotm"), false));
        tasks.add(lore("Commission Milestones", commissions, i(mining, "commission_milestone"), false));
        tasks.add(lore("Crystal Nucleus", nucleus, i(mining, "crystal_nucleus"), false));
        tasks.add(lore("Anita's Shop Upgrade", anita, i(farming, "anita_shop_upgrades"), false));
        tasks.add(lore("Peak of the Mountain", potm, i(mining, "potm"), false));
        tasks.add(lore("Fossil Research", fossils, i(mining, "fossil_research"), false));
        tasks.add(lore("Trophy Fish", trophy, i(fishing, "trophy_fish"), false));
        tasks.add(lore("Rock Milestone", rock, i(mining, "rock_milestone"), false));
        tasks.add(lore("Dolphin Milestone", dolphin, i(fishing, "dolphin_milestone"), false));
        int total = (int) (hotmXp + commissions + anita + potm + trophy + rock + dolphin + nucleus + fossils);
        return bar("Skill Related Task", new ItemStack(Items.DIAMOND_SWORD), 23, 115, 0, total, i(cat, "skill_related_task"), true, tasks);
    }

    private static int milestone(double have, JsonArray required, int each) {
        int xp = 0;
        if (required != null) for (JsonElement e : required) if (have >= e.getAsInt()) xp += each;
        return xp;
    }

    private static final int[] BOSS_LOW = {25, 50, 100, 150, 250, 1000}, THORN = {25, 50, 150, 250, 400, 1000},
            BOSS_HIGH = {50, 100, 150, 250, 500, 750, 1000};

    private static Bar slaying(SbProfile p, JsonObject c, JsonObject cat, ProfileExtras ex, List<ExtraTables.BestiaryCategory> best) {
        JsonObject sl = c.getAsJsonObject("slaying_task");
        JsonArray lvXp = sl.getAsJsonArray("slayer_level_up_xp");
        int slayerXp = 0;
        for (LevelTables.Slayer def : LevelTables.slayers()) {
            SbProfile.SlayerStat st = p.slayers.get(def.apiId());
            int lv = LevelTables.slayerLevel(def, st == null ? 0 : st.xp()).level();
            for (int n = 0; n < lv; n++) slayerXp += at(lvXp, n);
        }
        // boss collections: a master floor counts twice when its normal floor was cleared too, once otherwise (as the original adds them)
        Map<String, Double> comps = new HashMap<>();
        JsonObject normal = p.memberObj("dungeons.dungeon_types.catacombs.tier_completions");
        JsonObject master = p.memberObj("dungeons.dungeon_types.master_catacombs.tier_completions");
        if (normal != null) for (Map.Entry<String, JsonElement> e : normal.entrySet()) comps.merge(e.getKey(), SbProfile.num(e.getValue()), Double::sum);
        if (master != null) {
            for (Map.Entry<String, JsonElement> e : master.entrySet()) {
                double v = SbProfile.num(e.getValue());
                comps.put(e.getKey(), comps.containsKey(e.getKey()) ? comps.get(e.getKey()) + v * 2 : v);
            }
        }
        JsonObject bc = sl.getAsJsonObject("boss_collections_xp");
        JsonArray dcx = bc.getAsJsonArray("dungeon_collection_xp");
        int bossXp = 0;
        for (int f = 1; f <= 7; f++) {
            Double v = comps.get(String.valueOf(f));
            if (v == null) continue;
            int[] need = f <= 3 ? BOSS_LOW : f == 4 ? THORN : BOSS_HIGH;
            for (int n = 0; n < need.length; n++) if (v >= need[n]) bossXp += at(dcx, n);
        }
        int kuudraXp = 0;
        if (p.memberObj("nether_island_player_data") != null) {
            JsonObject tiers = p.memberObj("nether_island_player_data.kuudra_completed_tiers");
            List<String> names = List.of("none", "hot", "burning", "fiery", "infernal");
            int kuudraColl = 0;
            if (tiers != null) {
                for (Map.Entry<String, JsonElement> e : tiers.entrySet()) {
                    int idx = names.indexOf(e.getKey());
                    if (idx < 0) continue;
                    kuudraXp += at(sl.getAsJsonArray("defeat_kuudra_xp"), idx);
                    kuudraColl += (idx + 1) * (int) SbProfile.num(e.getValue());
                }
            }
            int[] steps = {10, 100, 500, 2000, 5000}, gain = {10, 15, 20, 25, 30};
            for (int n = 0; n < steps.length; n++) if (kuudraColl >= steps[n]) bossXp += gain[n];
        }
        int bestiaryXp = 0;
        if (ex != null && best != null) {
            int tiers = 0;
            for (ExtraTables.BestiaryCategory bcat : best) {
                for (ExtraTables.BestiaryFamily fam : bcat.families()) tiers += ExtraPages.countAtOrBelow(fam.tiers(), ExtraPages.familyKills(ex, fam));
            }
            bestiaryXp = Math.min(tiers * i(sl, "bestiary_family_xp") + tiers / 100 * i(sl, "bestiary_milestone_xp"), i(sl, "bestiary_progress"));
        }
        int myth = Math.min((int) p.memberNum("player_stats.mythos.kills") / 100, i(sl, "mythological_kills"));
        int dragons = 0;
        for (Map.Entry<String, JsonElement> e : sl.getAsJsonObject("slay_dragons_xp").entrySet()) dragons += e.getValue().getAsInt();
        int defeat = 0;
        JsonArray dsx = sl.getAsJsonArray("defeat_slayers_xp");
        String[] bosses = {"zombie", "spider", "wolf", "enderman", "blaze", "vampire"};
        int[] highest = {5, 5, 4, 4, 4, 5};
        for (int b = 0; b < bosses.length; b++) {
            for (int n = 0; n < 5 && n < highest[b]; n++) {
                if (p.memberNum("slayer.slayer_bosses." + bosses[b] + ".boss_kills_tier_" + n) != 0) defeat += at(dsx, n);
            }
        }
        int arachne = 0;
        for (JsonElement e : sl.getAsJsonArray("defeat_arachne_xp")) arachne += e.getAsInt();
        List<String> tasks = new ArrayList<>();
        tasks.add(lore("Slayer Level Up", slayerXp, i(sl, "slayer_level_up"), false));
        tasks.add(lore("Boss Collections", bossXp, i(sl, "boss_collections"), false));
        tasks.add(lore("Bestiary Progress", bestiaryXp, i(sl, "bestiary_progress"), false));
        tasks.add(lore("Mythological Kills", myth, i(sl, "mythological_kills"), false));
        tasks.add(lore("Slay Dragons", dragons, i(sl, "slay_dragons"), false));
        tasks.add(lore("Defeat Slayers", defeat, i(sl, "defeat_slayers"), false));
        tasks.add(lore("Defeat Kuudra", kuudraXp, i(sl, "defeat_kuudra"), false));
        tasks.add(lore("Defeat Arachne", arachne, i(sl, "defeat_arachne"), false));
        return bar("Slaying Task", new ItemStack(Items.GOLDEN_SWORD), 23, 85, 0,
                slayerXp + bossXp + myth + dragons + defeat + kuudraXp + arachne + bestiaryXp, i(cat, "slaying_task"), true, tasks);
    }

    /** For the testkit: the drawn bars' names and right-hand texts, "" until built. */
    String debugBars() {
        List<Bar> bars = model != null && model.isDone() && !model.isCompletedExceptionally() ? model.getNow(null) : null;
        if (bars == null) return "";
        StringBuilder b = new StringBuilder();
        for (Bar bar : bars) b.append(bar.name).append('=').append(bar.right).append(';');
        return b.toString();
    }

    /** For the testkit: the plain text of one bar's hover, or "". */
    String debugTip(String name) {
        List<Bar> bars = model != null && model.isDone() && !model.isCompletedExceptionally() ? model.getNow(null) : null;
        if (bars == null) return "";
        for (Bar bar : bars) {
            if (!bar.name.equals(name)) continue;
            StringBuilder b = new StringBuilder();
            for (Component c : bar.tip) b.append(c.getString()).append('|');
            return b.toString();
        }
        return "";
    }
}

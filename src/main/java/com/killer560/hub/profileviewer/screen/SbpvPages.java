package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.compat.McItems;
import com.killer560.hub.profileviewer.api.ProfileViewerApi;
import com.killer560.hub.profileviewer.data.ExtraTables;
import com.killer560.hub.profileviewer.data.LevelTables;
import com.killer560.hub.profileviewer.data.ProfileExtras;
import com.killer560.hub.profileviewer.data.SbProfile;
import com.killer560.hub.profileviewer.item.ItemIcons;
import com.killer560.hub.profileviewer.item.LegacyItems;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.commas;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.inside;

/**
 * SkyBlockPV's pages that have no page of ours in the other styles: Loadout (LoadoutTab), Fishing (FishingScreen),
 * Foraging (MainForagingScreen plus its HotF Tree category), Chocolate Factory (ChocolateFactoryScreen) and Combat's
 * "Kills and Deaths" category (MobScreen). Everything a page writes is built once per profile off the render thread
 * ({@link #model}); a frame only lays out and draws the cached text and stacks.
 */
final class SbpvPages {

    private final ProfileViewerScreen s;
    private SbProfile modelFor;
    private CompletableFuture<Model> model;
    private int fishTab;
    private int loadoutSel = 1;
    private final int[] killScroll = new int[2];

    SbpvPages(ProfileViewerScreen screen) {
        this.s = screen;
    }

    // ---------------------------------------------------------------- shared look

    static final Identifier CLIPBOARD = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/icon_clipboard");
    static final Identifier SWORD = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/icon_sword");
    private static final Identifier SLOT_BONE = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_bone");
    private static final Identifier SLOT_BAIT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_bait");
    private static final ItemStack GRAY_DYE = new ItemStack(McItems.GRAY_DYE);
    /** Catppuccin Mocha text / sapphire / red / surface0, the Loadout tab's hover and divider colors. */
    private static final int MOCHA_TEXT = 0xFFCDD6F4, MOCHA_SAPPHIRE = 0xFF74C7EC, MOCHA_RED = 0xFFF38BA8, MOCHA_SURFACE0 = 0xFF313244;

    /** SkyBlockPV's number helpers: toFormattedString (grouping), round() ({@code #.##}) and shorten(n) (compact, US). */
    static String fmt(double v) {
        return NumberFormat.getNumberInstance(Locale.US).format(v);
    }

    static String round(double v) {
        java.text.DecimalFormat f = new java.text.DecimalFormat("#.##", java.text.DecimalFormatSymbols.getInstance(Locale.US));
        return f.format(v);
    }

    static String shorten(double v, int digits) {
        NumberFormat f = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT);
        f.setMaximumFractionDigits(digits);
        return f.format(v);
    }

    /** FishingScreen's stack counts: compact, no fraction, rounded down. */
    private static String compactFloor(long v) {
        NumberFormat f = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT);
        f.setRoundingMode(RoundingMode.FLOOR);
        return f.format(v);
    }

    private int text() {
        return PvStyleChrome.sbpvText(s.pvStyle());
    }

    private Font font() {
        return s.font();
    }

    private boolean hover(int mx, int my, int x, int y, int w, int h) {
        return inside(mx, my, x, y, w, h) && !s.dropdownOpen;
    }

    private static List<Component> lines(List<String> l) {
        List<Component> out = new ArrayList<>(l.size());
        for (String x : l) out.add(Component.literal(x));
        return List.copyOf(out);
    }

    /** A label (PvWidgets.label): title bar w - 10 wide with the title centered over w and the icon 4 in, then the box
     *  (contentH + 14 high); w = contentW + padding + 20 and the content centered in w. Returns {contentX, contentY}. */
    private int[] label(GuiGraphicsExtractor g, String title, Identifier icon, int x, int y, int contentW, int contentH, int padding) {
        int w = contentW + padding + 20;
        s.sbpvLabel(g, title, x, y, w, contentH + 14, text(), icon);
        return new int[] {x + (w - contentW) / 2, y + 27};
    }

    private static int labelW(int contentW, int padding) {
        return contentW + padding + 20;
    }

    private static int labelH(int contentH) {
        return 20 + contentH + 14;
    }

    /** Lines of text (9 high each, {@code gap} between), each with its hover; returns the widest line. */
    private int widest(String[] l) {
        int w = 0;
        for (String x : l) w = Math.max(w, font().width(x));
        return w;
    }

    private void textLines(GuiGraphicsExtractor g, String[] l, List<Component>[] tips, int x, int y, int gap, int mx, int my) {
        for (int i = 0; i < l.length; i++) {
            g.text(font(), l[i], x, y, text(), false);
            if (tips != null && tips[i] != null && hover(mx, my, x, y, font().width(l[i]), 9)) {
                s.pendingTooltip = tips[i];
            }
            y += 9 + gap;
        }
    }

    /** An item in a 20 px slot (ExtraDisplays.inventorySlot around a 2 px padded item), with its stack text and hover. */
    private void slotItem(GuiGraphicsExtractor g, ItemStack st, String stackText, int x, int y, List<Component> tip, int mx, int my) {
        PvStyleChrome.slot(g, s.pvStyle(), x, y, 20);
        g.item(st, x + 2, y + 2);
        if (stackText != null && !stackText.isEmpty()) {
            g.itemDecorations(font(), st, x + 2, y + 2, stackText);
        }
        if (hover(mx, my, x, y, 20, 20)) {
            if (tip != null) {
                s.pendingTooltip = tip;
            } else if (!st.isEmpty()) {
                s.pendingItemTooltip = st;
            }
        }
    }

    // ---------------------------------------------------------------- model

    private record Fish(String id, String name, List<String> obtaining) {
    }

    /** FishData.kt's TrophyFishType, in its order (name color, obfuscation, how it is caught). */
    private static final Fish[] FISH = {
            new Fish("SULPHUR_SKITTER", "§fSulphur Skitter", List.of("§7Caught near Sulphur blocks.")),
            new Fish("OBFUSCATED_FISH_1", "§f§kObfuscated 1", List.of("§7Caught with Corrupted Bait.")),
            new Fish("STEAMING_HOT_FLOUNDER", "§fSteaming-Hot Flounder", List.of("§7Found in Volcano Geysers.")),
            new Fish("GUSHER", "§fGusher", List.of("§7Caught after a volcano eruption.")),
            new Fish("BLOBFISH", "§fBlobfish", List.of("§7Caught everywhere.")),
            new Fish("OBFUSCATED_FISH_2", "§a§kObfuscated 2", List.of("§7Caught with §7§kObfuscated 1 §r§7Bait.")),
            new Fish("SLUGFISH", "§aSlugfish", List.of("§7Bobber must be active for 20 seconds.")),
            new Fish("FLYFISH", "§aFlyfish", List.of("§7Caught from 8 blocks above.", "§7Found in Blazing Volcano.")),
            new Fish("OBFUSCATED_FISH_3", "§9§kObfuscated 3", List.of("§7Caught with §7§kObfuscated 2 §r§7Bait.")),
            new Fish("LAVA_HORSE", "§9Lavahorse", List.of("§7Caught everywhere.")),
            new Fish("MANA_RAY", "§9Mana Ray", List.of("§7Lured by having a high amount of mana.", "§7 §o(at least 1,200)")),
            new Fish("VOLCANIC_STONEFISH", "§9Volcanic Stonefish", List.of("§7Found in Blazing Volcano.")),
            new Fish("VANILLE", "§9Vanille", List.of("§7Only caught with §aStarter Lava", "§aRod §7with no enchantments.")),
            new Fish("SKELETON_FISH", "§5Skeleton Fish", List.of("§7Found in Burning Desert.")),
            new Fish("MOLDFIN", "§5Moldfin", List.of("§7Found in Mystic Marsh.")),
            new Fish("SOUL_FISH", "§5Soul Fish", List.of("§7Found in Stronghold.")),
            new Fish("KARATE_FISH", "§5Karate Fish", List.of("§7Found in Dojo.")),
            new Fish("GOLDEN_FISH", "§6Golden Fish", List.of("§7Found swimming around in the lava."))};
    /** FishData.kt's TrophyFrogType. */
    private static final Fish[] FROGS = {
            new Fish("COMMON_FROG", "§fCommon Frog", List.of("§7Caught everywhere.")),
            new Fish("LEAP_FROG", "§aLeap Frog", List.of("§7Caught while midair.")),
            new Fish("WETLANDS_FROG", "§aWetlands Frog", List.of("§7Caught during rain.")),
            new Fish("REALITY_HOPPER", "§aReality Hopper", List.of("§7Caught in Wormholes on the Lotus Atoll.")),
            new Fish("EXPLODING_FROG", "§aExploding Frog", List.of("§7Obtained by combining Lily Pads until they explode.")),
            new Fish("BLESSED_FROG", "§9Blessed Frog", List.of("§7Caught with an active Frogcoin blessing.")),
            new Fish("SEA_FROG", "§9Sea Frog", List.of("§7Caught while underwater.")),
            new Fish("BULLFROG", "§9Bullfrog", List.of("§7Caught while wearing a Red Sweater.")),
            new Fish("TREE_FROG", "§5Tree Frog", List.of("§7Caught while standing on leaves.")),
            new Fish("CAVE_FROG", "§5Cave Frog", List.of("§7Found in the Lotus Eater's Cave.")),
            new Fish("HIGHLANDS_FROG", "§5Highlands Frog", List.of("§7Found in the Lotus Highlands.")),
            new Fish("PUDDLE_JUMPER", "§6Puddle Jumper", List.of("§7Caught when flying around the Lotus Atoll."))};
    /** TrophyTier: NONE (the total), BRONZE, SILVER, GOLD, DIAMOND - its display name, bold name suffix and the frog check color. */
    private static final String[] TIER = {"", "BRONZE", "SILVER", "GOLD", "DIAMOND"};
    private static final String[] TIER_NAME = {"Total", "§8Bronze", "§7Silver", "§6Gold", "§bDiamond"};
    private static final String[] TIER_SUFFIX = {"", " §8§lBRONZE", " §7§lSILVER", " §6§lGOLD", " §b§lDIAMOND"};
    private static final String[] TIER_CHECK = {"§c", "§8", "§7", "§6", "§b"};
    private static final String[] RANKS = {"§8Novice", "§7Adept", "§6Expert", "§bMaster"};
    private static final int[] DOLPHIN_KILLS = {250, 1000, 2500, 5000, 10000};
    private static final String[] DOLPHIN = {"§fCommon", "§aUncommon", "§9Rare", "§5Epic", "§6Legendary"};
    private static final String[] DOLPHIN_PLAIN = {"Common", "Uncommon", "Rare", "Epic", "Legendary"};
    /** FishingScreen's three essence perks: id, then the hover (its shop reference lines, then the perk's own lines). */
    private static final String[][] FISHING_PERKS = {
            {"drake_piper", "§fCan be upgraded at the §dIce Essence Shop", "§fon §cJerry's Workshop", "",
                    "§fIncrease the chance to spawn a §6Reindrake", "§fby §a10%§f while on §cJerry's Workshop§f."},
            {"midas_lure", "§fCan be upgraded at the §dGold Essence Shop", "§fin the §6Royal Mines§f in the §2Dwarven Mines", "",
                    "§fIncreases your chances of fishing up a ", "§6GOLD§f tier §6Trophies§f by §a2-20%§f."},
            {"radiant_fisher", "§fCan be upgraded at the §dDiamond Essence Shop", "§fin the §5Crystal Nucleus§f in the §5Crystal Hollows", "",
                    "§fIncreases your chances of fishing up a", "§bDIAMOND§f tier §6Trophies§f by §a2-20%§f."}};

    /** EssenceData.addPerk: "Name: level/max", the level green when maxed, else red (names and maxima from the bundled table). */
    private static String perkLine(SbProfile p, String id) {
        JsonObject perks = ExtraTables.bundled().getAsJsonObject("sbpv_perks");
        JsonObject perk = perks == null ? null : perks.getAsJsonObject(id);
        int max = perk != null && perk.has("max_level") ? perk.get("max_level").getAsInt() : 0;
        int level = (int) p.memberNum("player_data.perks." + id);
        return (perk != null && perk.has("name") ? perk.get("name").getAsString() : "Unknown") + ": " + (level == max ? "§a" : "§c") + level
                + "§r/" + max;
    }

    /** A cell of the trophy tables: its stack, stack text and hover. */
    private record Cell(ItemStack stack, String count, List<Component> tip) {
    }

    private record Loadout(int id, String name, Integer armorSet, Integer equipSet, Integer hotm, Integer hotf, String pet, String bait,
                           boolean empty) {
    }

    private static final class Model {
        String[] fishInfo, fishStats, cfInfo, forInfo;
        List<Component>[] fishInfoTips, fishStatTips, cfInfoTips, forInfoTips;
        float hotfProgress;
        boolean hotfMaxed;
        Cell[][] bigTable;   // [tier row DIAMOND..NONE][fish]
        Cell[] smallTable, frogTable;
        String[] kills, deaths;
        ItemStack[] upgrades;
        ItemStack[] employeeIcons;
        String[] employeeLevels;
        List<Component>[] employeeTips;
        boolean cfPresent;
        ItemStack[] rarityIcons;
        String[] rarityCounts;
        List<Component>[] rarityTips;
        /** Foraging: the two whisper lines (their total in the color scaled by 0.75) and the three Personal Bests labels. */
        Component[] forWhispers = new Component[0];
        String[] pbTitles = new String[0];
        String[][] pbLines = new String[0][];
        /** Loadout trees: [0 HotM, 1 HotF][0 = the selected slot, 1..5 = slot n]; null without skill tree data. */
        final Tree[][] trees = new Tree[2][6];
        int[] treeSelected = new int[2];
        CompletableFuture<SbpvGear.Gear> fishGear;
        int[] empW, rarW;
        final Map<Integer, Loadout> loadouts = new LinkedHashMap<>();
        Loadout[] ordered = new Loadout[0];
        final Map<Integer, List<ItemStack>> armorSets = new HashMap<>(), equipSets = new HashMap<>();
        int equippedArmor, equippedEquip;
        final Map<String, ItemStack> pets = new HashMap<>();
        final Map<String, String> petLevels = new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Model build(SbProfile p, ProfileExtras ex) {
        Model m = new Model();
        buildFishing(p, m);
        buildKills(ex, m);
        buildCf(p, m);
        buildForaging(p, ex, m);
        buildLoadouts(p, m);
        buildTrees(p, m);
        return m;
    }

    /** SimpleSkillTreeVisualizer's grid: one 20 px slot per node at row 9 - y, column x (rows/columns with no node take no room). */
    private record Tree(int w, int h, int[] x, int[] y, ItemStack[] icons, String[] counts, List<Component>[] tips) {
    }

    private static final String[] TREE_KEY = {"hotm", "hotf"}, TREE_SKILL = {"mining", "foraging"},
            TREE_CORE = {"core_of_the_mountain", "center_of_the_forest"};
    /** SkillTreeItems per tree: core (max, unlocked, leveling, locked), ability (selected, unlocked, locked), node (disabled, max, unlocked, locked). */
    private static final net.minecraft.world.item.Item[][] TREE_CORE_ITEMS = {
            {Items.DIAMOND_BLOCK, Items.COPPER_BLOCK.weathering().unaffected(), Items.REDSTONE_BLOCK, Items.BEDROCK},
            {Items.OAK_WOOD, Items.STRIPPED_BIRCH_WOOD, Items.STRIPPED_OAK_WOOD, Items.STRIPPED_PALE_OAK_WOOD}};
    private static final net.minecraft.world.item.Item[][] TREE_ABILITY_ITEMS = {
            {Items.EMERALD_BLOCK, Items.REDSTONE_BLOCK, Items.COAL_BLOCK}, {Items.OAK_SAPLING, Items.CHERRY_SAPLING, Items.PALE_OAK_SAPLING}};
    private static final net.minecraft.world.item.Item[][] TREE_NODE_ITEMS = {
            {Items.REDSTONE, Items.DIAMOND, Items.EMERALD, Items.COAL}, {Items.STRIPPED_MANGROVE_LOG, Items.OAK_LOG, Items.STRIPPED_OAK_LOG, Items.PALE_OAK_BUTTON}};

    private static void buildTrees(SbProfile p, Model m) {
        JsonObject st = p.memberObj("skill_tree");
        JsonObject all = ExtraTables.bundled().getAsJsonObject("sbpv_trees");
        if (st == null || all == null) return;
        for (int t = 0; t < 2; t++) {
            int sel = (int) p.memberNum("skill_tree.selected_skill_tree_slot." + TREE_SKILL[t]);
            m.treeSelected[t] = Math.max(1, sel);
            for (int slot = 1; slot <= 5; slot++) m.trees[t][slot] = tree(st, all.getAsJsonArray(TREE_KEY[t]), t, slot);
            m.trees[t][0] = m.trees[t][m.treeSelected[t]];
        }
    }

    @SuppressWarnings("unchecked")
    private static Tree tree(JsonObject st, com.google.gson.JsonArray rows, int t, int slot) {
        String suffix = slot > 1 ? "_" + slot : "";
        JsonObject nodes = asObj(st, "nodes", TREE_SKILL[t] + suffix), base = asObj(st, "nodes", TREE_SKILL[t]);
        JsonElement selAbility = asObj(st, "selected_ability", null) == null ? null : st.getAsJsonObject("selected_ability").get(TREE_SKILL[t] + suffix);
        String selected = selAbility == null || !selAbility.isJsonPrimitive() ? null : selAbility.getAsString();
        int abilityLevel = nodes != null && nodes.has(TREE_CORE[t]) && nodes.get(TREE_CORE[t]).getAsInt() >= 1 ? 2 : 1;
        java.util.TreeSet<Integer> cols = new java.util.TreeSet<>(), rowSet = new java.util.TreeSet<>();
        for (JsonElement e : rows) {
            cols.add(e.getAsJsonArray().get(2).getAsInt());
            rowSet.add(9 - e.getAsJsonArray().get(3).getAsInt());
        }
        List<Integer> colList = new ArrayList<>(cols), rowList = new ArrayList<>(rowSet);
        int n = rows.size();
        Tree tr = new Tree(colList.size() * 20, rowList.size() * 20, new int[n], new int[n], new ItemStack[n], new String[n], new List[n]);
        for (int i = 0; i < n; i++) {
            com.google.gson.JsonArray r = rows.get(i).getAsJsonArray();
            String id = r.get(0).getAsString(), type = r.get(1).getAsString(), name = r.get(5).getAsString();
            int max = r.get(4).getAsInt();
            int level = nodes != null && nodes.has(id) && nodes.get(id).isJsonPrimitive() ? nodes.get(id).getAsInt() : -1;
            net.minecraft.world.item.Item item;
            if (type.equals("CORE")) {
                item = level >= max ? TREE_CORE_ITEMS[t][0] : level < 0 ? TREE_CORE_ITEMS[t][3] : level == 1 ? TREE_CORE_ITEMS[t][1] : TREE_CORE_ITEMS[t][2];
            } else if (type.equals("ABILITY")) {
                if (level != -1) level = abilityLevel;
                item = id.equals(selected) ? TREE_ABILITY_ITEMS[t][0] : level > 0 ? TREE_ABILITY_ITEMS[t][1] : TREE_ABILITY_ITEMS[t][2];
            } else {
                JsonElement toggle = base == null ? null : base.get("toggle_" + id);
                boolean disabled = toggle != null && toggle.isJsonPrimitive() && !toggle.getAsBoolean();
                item = disabled ? TREE_NODE_ITEMS[t][0] : level < 0 ? TREE_NODE_ITEMS[t][3] : level >= max ? TREE_NODE_ITEMS[t][1] : TREE_NODE_ITEMS[t][2];
            }
            tr.x[i] = colList.indexOf(r.get(2).getAsInt()) * 20;
            tr.y[i] = rowList.indexOf(9 - r.get(3).getAsInt()) * 20;
            tr.icons[i] = new ItemStack(item);
            tr.counts[i] = level > 1 ? String.valueOf(level) : "";
            tr.tips[i] = lines(List.of((level > 0 ? "§a" : "§c") + name, "§7Level: " + Math.max(0, level) + "/" + max));
        }
        return tr;
    }

    private static JsonObject asObj(JsonObject o, String a, String b) {
        JsonElement e = o.get(a);
        if (e == null || !e.isJsonObject()) return null;
        if (b == null) return e.getAsJsonObject();
        JsonElement f = e.getAsJsonObject().get(b);
        return f != null && f.isJsonObject() ? f.getAsJsonObject() : null;
    }

    @SuppressWarnings("unchecked")
    private static void buildFishing(SbProfile p, Model m) {
        JsonObject tf = p.memberObj("trophy_fish");
        Map<String, Integer> got = new HashMap<>();
        String last = "";
        int totalCaught = 0, rank = 0;
        if (tf != null) {
            for (Map.Entry<String, JsonElement> e : tf.entrySet()) {
                if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isNumber()) {
                    got.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            if (tf.has("last_caught") && tf.get("last_caught").isJsonPrimitive()) last = tf.get("last_caught").getAsString();
            totalCaught = (int) p.memberNum("trophy_fish.total_caught");
            if (tf.has("rewards") && tf.get("rewards").isJsonArray()) {
                for (JsonElement r : tf.getAsJsonArray("rewards")) {
                    int v = r.isJsonPrimitive() ? r.getAsInt() : 0;
                    if (v != 0 && v <= RANKS.length) rank = Math.max(rank, v);
                }
            }
        }
        List<String> info = new ArrayList<>();
        List<List<Component>> infoTips = new ArrayList<>();
        String lastName = null;
        int slash = last.indexOf('/');
        if (slash > 0) {
            for (Fish f : FISH) {
                if (f.id.equalsIgnoreCase(last.substring(0, slash))) {
                    int t = tierIndex(last.substring(slash + 1));
                    lastName = f.name + TIER_SUFFIX[t];
                }
            }
        }
        info.add(lastName == null ? "§cNever caught a trophy fish!" : "Last Catch: " + lastName);
        infoTips.add(null);
        info.add("Trophy Rank: " + (rank == 0 ? "§cNone" : RANKS[rank - 1]));
        infoTips.add(null);
        if (!"island".equals(p.gameMode)) {
            for (String[] perk : FISHING_PERKS) {
                info.add(perkLine(p, perk[0]));
                String[] tip = new String[perk.length - 1];
                System.arraycopy(perk, 1, tip, 0, tip.length);
                infoTips.add(lines(List.of(tip)));
            }
        }
        long seaKills = (long) p.memberNum("player_stats.pets.milestone.sea_creatures_killed");
        int dolphin = -1;
        for (int i = 0; i < DOLPHIN_KILLS.length; i++) if (DOLPHIN_KILLS[i] <= seaKills) dolphin = i;
        info.add("Dolphin Pet: " + (dolphin < 0 ? "§cNone" : DOLPHIN[dolphin]));
        List<String> dt = new ArrayList<>();
        dt.add("§fSea Creatures Killed: §b" + fmt(seaKills));
        dt.add("");
        for (int i = 0; i < DOLPHIN_KILLS.length; i++) {
            dt.add(DOLPHIN_KILLS[i] <= seaKills ? DOLPHIN[i] + " Dolphin§f!" : "§8§m" + DOLPHIN_PLAIN[i] + " Dolphin!");
        }
        infoTips.add(lines(dt));
        m.fishInfo = info.toArray(new String[0]);
        m.fishInfoTips = infoTips.toArray(new List[0]);

        long sharks = (long) p.memberNum("leveling.fishing_festival_sharks_killed");
        List<String> st = new ArrayList<>();
        List<List<Component>> stTips = new ArrayList<>();
        String sc = sharks >= 5000 ? "§a" : sharks >= 2500 ? "§e" : sharks >= 1 ? "§c" : "§4";
        st.add("Festival sharks killed: " + sc + fmt(Math.min(5000, sharks)) + "§r/" + fmt(5000));
        List<String> shTip = new ArrayList<>(List.of("§b+1 Sbxp §fper 50 sharks killed!", "", "§fTotal sharks killed: " + fmt(sharks)));
        if (sharks < 5000) shTip.add("§fTotal Progress: §3" + fmt((float) (sharks / 5000f) * 100) + "%");
        stTips.add(lines(shTip));
        st.add("Sea creatures killed: " + fmt(seaKills));
        stTips.add(null);
        st.add("Total Catches: " + fmt(p.memberNum("player_stats.items_fished.total")));
        stTips.add(null);
        st.add("Normal Catches: " + fmt(p.memberNum("player_stats.items_fished.normal")));
        stTips.add(null);
        st.add("Treasures Found: " + fmt(p.memberNum("player_stats.items_fished.treasure") + p.memberNum("player_stats.items_fished.large_treasure")));
        stTips.add(null);
        st.add("Trophy Fishes Caught: " + fmt(totalCaught));
        List<String> tierTip = new ArrayList<>();
        for (int t = 1; t < TIER.length; t++) {
            int sum = 0;
            boolean any = false;
            for (Map.Entry<String, Integer> e : got.entrySet()) {
                if (e.getKey().endsWith(TIER[t].toLowerCase(Locale.ROOT))) {
                    sum += e.getValue();
                    any = true;
                }
            }
            if (any) tierTip.add("§fTotal " + TIER_NAME[t] + "§f Caught: " + sum);
        }
        stTips.add(tierTip.isEmpty() ? null : lines(tierTip));
        JsonObject lv = p.memberObj("leveling");
        java.util.Set<String> tasks = new java.util.HashSet<>();
        if (lv != null && lv.has("completed_tasks") && lv.get("completed_tasks").isJsonArray()) {
            for (JsonElement e : lv.getAsJsonArray("completed_tasks")) {
                if (e.isJsonPrimitive() && e.getAsString().startsWith("TROPHY_")) tasks.add(e.getAsString());
            }
        }
        int frogDone = 0;
        for (String task : tasks) {
            for (Fish f : FROGS) {
                if (task.startsWith("TROPHY_" + f.id + "_")) {
                    frogDone++;
                    break;
                }
            }
        }
        st.add("Trophy Frogs Caught: " + fmt(p.memberNum("player_stats.items_fished.trophy_frog")));
        stTips.add(lines(List.of("§fCompleted Trophy Frog Tiers: " + frogDone + "/" + FROGS.length * 4)));
        m.fishStats = st.toArray(new String[0]);
        m.fishStatTips = stTips.toArray(new List[0]);

        // Trophies: the full table (tiers DIAMOND..NONE down, fish across) and the 6 x 3 one of each fish's best.
        m.bigTable = new Cell[5][FISH.length];
        m.smallTable = new Cell[FISH.length];
        for (int f = 0; f < FISH.length; f++) {
            Fish fish = FISH[f];
            int[] caught = new int[5];
            boolean[] has = new boolean[5];
            for (int t = 0; t < 5; t++) {
                String key = fish.id.toLowerCase(Locale.ROOT) + (t == 0 ? "" : "_" + TIER[t].toLowerCase(Locale.ROOT));
                caught[t] = got.getOrDefault(key, 0);
                has[t] = got.containsKey(key);
            }
            List<String> tip = new ArrayList<>(fish.obtaining);
            tip.add("");
            for (int t = 4; t >= 0; t--) tip.add(TIER_NAME[t] + "§r: " + caught[t]);
            for (int row = 0; row < 5; row++) {
                int t = 4 - row;
                ItemStack icon = has[t] ? ItemIcons.catalogIcon(fish.id + "_" + TIER[Math.max(1, t)]) : GRAY_DYE;
                String count = has[t] ? compactFloor(caught[t]) : "";
                List<String> full = new ArrayList<>();
                full.add(fish.name + TIER_SUFFIX[t]);
                full.addAll(tip);
                m.bigTable[row][f] = new Cell(icon, "0".equals(count) ? "" : count, lines(full));
            }
            int best = 0;
            for (int t = 4; t >= 1; t--) {
                if (has[t]) {
                    best = t;
                    break;
                }
            }
            List<String> full = new ArrayList<>();
            full.add(fish.name + TIER_SUFFIX[best]);
            full.addAll(tip);
            m.smallTable[f] = new Cell(best == 0 ? GRAY_DYE : ItemIcons.catalogIcon(fish.id + "_" + TIER[best]),
                    caught[0] != 0 ? compactFloor(caught[0]) : "", lines(full));
        }
        m.frogTable = new Cell[FROGS.length];
        for (int f = 0; f < FROGS.length; f++) {
            Fish frog = FROGS[f];
            int best = 0;
            List<String> tip = new ArrayList<>(frog.obtaining);
            tip.add("");
            for (int t = 4; t >= 1; t--) {
                boolean done = tasks.contains("TROPHY_" + frog.id + "_" + TIER[t]);
                if (done && best == 0) best = t;
                tip.add(TIER_NAME[t] + "§r: " + (done ? "§a✔" : "§c❌"));
            }
            tip.add("");
            tip.add("§7Note: Catch counts per tier are currently not provided by Hypixel.");
            List<String> full = new ArrayList<>();
            full.add(frog.name + TIER_SUFFIX[best]);
            full.addAll(tip);
            m.frogTable[f] = new Cell(best == 0 ? GRAY_DYE : ItemIcons.catalogIcon(frog.id + "_" + TIER[best]),
                    best == 0 ? "" : TIER_CHECK[best] + "✔", lines(full));
        }
    }

    private static int tierIndex(String name) {
        for (int t = 1; t < TIER.length; t++) if (TIER[t].equalsIgnoreCase(name)) return t;
        return 0;
    }

    /** MobScreen: kills and deaths per mob, "_<digits>" level suffixes folded together, sorted high to low. */
    private static void buildKills(ProfileExtras ex, Model m) {
        m.kills = mobLines(ex == null ? Map.of() : ex.misc.kills());
        m.deaths = mobLines(ex == null ? Map.of() : ex.misc.deaths());
    }

    private static String[] mobLines(Map<String, Long> raw) {
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            String id = e.getKey();
            int us = id.lastIndexOf('_');
            if (us > 0 && us < id.length() - 1 && id.substring(us + 1).chars().allMatch(Character::isDigit)) id = id.substring(0, us);
            merged.merge(id, e.getValue(), Long::sum);
        }
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(merged.entrySet());
        sorted.removeIf(e -> e.getValue() == 0L);
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        String[] out = new String[sorted.size()];
        for (int i = 0; i < out.length; i++) {
            StringBuilder name = new StringBuilder();
            for (String part : sorted.get(i).getKey().split("_")) {
                if (name.length() > 0) name.append(' ');
                if (!part.isEmpty()) name.append(Character.toTitleCase(part.charAt(0))).append(part.substring(1));
            }
            out[i] = name + ": " + fmt(sorted.get(i).getValue());
        }
        return out;
    }

    /** The seven employees (events.easter.employees), their names and chocolate per second per level. */
    private static final String[] EMP_IDS = {"rabbit_bro", "rabbit_cousin", "rabbit_sis", "rabbit_father", "rabbit_grandma", "rabbit_uncle", "rabbit_dog"};
    private static final String[] EMP_NAMES = {"Rabbit Bro", "Rabbit Cousin", "Rabbit Sis", "Rabbit Daddy", "Rabbit Granny", "Rabbit Uncle", "Rabbit Dog"};
    /** SkyBlockRarity order up to DIVINE and each rarity's color. */
    private static final List<String> RARITY_ORDER = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE");
    private static final String[] RARITY_COLOR = {"§f", "§a", "§9", "§5", "§6", "§d", "§b"};
    private static final long SB_EPOCH = 1_560_275_700_000L, SB_YEAR = 446_400_000L;

    private static String empColor(int level) {
        return level <= 9 ? "§f" : level <= 74 ? "§a" : level <= 124 ? "§9" : level <= 174 ? "§5" : level <= 199 ? "§6"
                : level <= 219 ? "§d" : level <= 235 ? "§b" : "§7";
    }

    @SuppressWarnings("unchecked")
    private static void buildCf(SbProfile p, Model m) {
        JsonObject cf = p.memberObj("events.easter");
        m.cfPresent = cf != null;
        if (cf == null) return;
        long choc = (long) p.memberNum("events.easter.chocolate"), total = (long) p.memberNum("events.easter.total_chocolate");
        long since = (long) p.memberNum("events.easter.chocolate_since_prestige");
        int prestige = (int) p.memberNum("events.easter.chocolate_level");
        List<String> l = new ArrayList<>();
        List<List<Component>> t = new ArrayList<>();
        l.add("Chocolate: §6" + shorten(choc, 1));
        l.add("Total Chocolate: §6" + shorten(total, 1));
        l.add("Chocolate since Prestige: §6" + shorten(since, 1));
        JsonObject data = ExtraTables.bundled().getAsJsonObject("sbpv_cf");
        JsonObject per = data == null ? new JsonObject() : data.getAsJsonObject("prestige");
        if (prestige != per.size() + 1) {
            JsonElement next = per.get(String.valueOf(prestige + 1));
            long needed = (next == null ? 0 : next.getAsLong()) - since;
            l.add("Chocolate for next Prestige: §6" + (needed <= 0 ? "§aReady!" : shorten(needed, 1)));
        }
        l.add("Prestige Level: §d" + prestige);
        l.add("Barn Capacity: §a" + ((int) p.memberNum("events.easter.rabbit_barn_capacity_level") * 2 + 18));
        long seen = (long) p.memberNum("events.easter.last_viewed_chocolate_factory");
        l.add("Last Updated: " + java.time.format.DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss")
                .format(java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(seen), java.time.ZoneId.systemDefault())));
        for (int i = 0; i < l.size(); i++) t.add(null);
        int slots = (int) p.memberNum("events.easter.rabbit_hitmen.rabbit_hitmen_slots");
        l.add("Hitman Slots: §c" + slots + " Unlocked§r - §c"
                + (int) p.memberNum("events.easter.rabbit_hitmen.missed_uncollected_eggs") + " Ready");
        long[] cost = data == null ? new long[0] : ExtraTables.longs(data.get("hitman_cost"));
        long paid = slots - 1 >= 0 && slots - 1 < cost.length ? cost[slots - 1] : 0, all = cost.length == 0 ? 0 : cost[cost.length - 1];
        t.add(lines(List.of("§7Paid: §6" + fmt(paid) + "§7/§6" + shorten(all, 2) + "§7 (§6" + round(all == 0 ? 0 : paid * 100.0 / all) + "%§7)")));
        List<int[]> bits = new ArrayList<>();
        JsonObject cb = p.memberObj("events.easter.chocobits");
        if (cb != null && cb.has("owned") && cb.get("owned").isJsonArray()) {
            int nowYear = (int) ((System.currentTimeMillis() - SB_EPOCH) / SB_YEAR) + 1;
            for (JsonElement e : cb.getAsJsonArray("owned")) {
                JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : null;
                int id = o != null && o.has("id") ? o.get("id").getAsInt() : -1;
                if (id > 0) {
                    bits.add(new int[] {id, o.has("owned_year") ? o.get("owned_year").getAsInt() : nowYear,
                            o.has("expiry_year") ? o.get("expiry_year").getAsInt() : nowYear});
                }
            }
        }
        bits.sort((a, b) -> Integer.compare(a[2], b[2]));
        l.add("Chocobits: §6" + fmt(bits.size()) + "§r / §6" + fmt(p.memberNum("events.easter.chocobits.total_found")));
        List<String> bt = new ArrayList<>(List.of("§cCurrent §r / §cTotal Found", ""));
        if (bits.isEmpty()) bt.add("§cNONE");
        for (int[] b : bits) {
            long until = SB_EPOCH + (long) (b[2] - 1) * SB_YEAR - System.currentTimeMillis();
            bt.add("Chocobit §e" + fmt(b[0]) + "§r, found in year §a" + fmt(b[1]) + "§r, expires in §6" + readable(until));
        }
        t.add(lines(bt));
        m.cfInfo = l.toArray(new String[0]);
        m.cfInfoTips = t.toArray(new List[0]);

        m.upgrades = new ItemStack[] {
                upgrade(Items.COOKIE, "Click Upgrade", (int) p.memberNum("events.easter.click_upgrades") + 1,
                        "§7Increases the amount of §6Chocolate ", "§7you get per click."),
                upgrade(Items.CLOCK, "Time Tower", (int) p.memberNum("events.easter.time_tower.level"),
                        "§7Increases your §6Chocolate Production ", "§7for §a1h §7per charge.", "",
                        "§7Charges: §d" + (int) p.memberNum("events.easter.time_tower.charges") + "§7/§d3"),
                upgrade(Items.RABBIT_FOOT, "Rabbit Shrine", (int) p.memberNum("events.easter.rabbit_rarity_upgrades"),
                        "§7Increases the chance of getting ", "§dhigher rarity rabbits §7during §dHoppity's Hunt§7."),
                upgrade(data != null && data.has("coach_jackrabbit") ? LegacyItems.skull(data.get("coach_jackrabbit").getAsString(), null)
                                : new ItemStack(Items.PLAYER_HEAD), "Coach Jackrabbit", (int) p.memberNum("events.easter.chocolate_multiplier_upgrades"),
                        "§7Increases the amount of ", "§6Chocolate §7you get per second.")};
        JsonObject textures = data == null ? null : data.getAsJsonObject("textures");
        // Rarities: one chip per rarity, DIVINE first (the repo's rarity map turned around), uniques and total per rarity.
        JsonObject owned = p.memberObj("events.easter.rabbits");
        JsonObject rab = data == null ? new JsonObject() : data.getAsJsonObject("rabbits");
        List<String> rarities = new ArrayList<>();
        for (String r : RARITY_ORDER) if (rab.has(r)) rarities.add(0, r);
        m.rarityIcons = new ItemStack[rarities.size()];
        m.rarityCounts = new String[rarities.size()];
        m.rarityTips = new List[rarities.size()];
        for (int i = 0; i < rarities.size(); i++) {
            String r = rarities.get(i), c = RARITY_COLOR[RARITY_ORDER.indexOf(r)];
            int uniques = 0;
            long rabbitTotal = 0;
            for (String name : ExtraTables.strings(rab.get(r))) {
                JsonElement n = owned == null ? null : owned.get(name);
                if (n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isNumber()) {
                    uniques++;
                    rabbitTotal += n.getAsLong();
                }
            }
            m.rarityIcons[i] = skull(textures, r);
            m.rarityCounts[i] = c + uniques;
            m.rarityTips[i] = lines(List.of(c + "§l" + r + "§r §7(" + c + uniques + "§7)", "§7Uniques: " + c + uniques, "§7Total: §6" + rabbitTotal));
        }
        m.employeeIcons = new ItemStack[EMP_IDS.length];
        m.employeeLevels = new String[EMP_IDS.length];
        m.employeeTips = new List[EMP_IDS.length];
        for (int i = 0; i < EMP_IDS.length; i++) {
            int lvl = (int) p.memberNum("events.easter.employees." + EMP_IDS[i]);
            String c = empColor(lvl);
            m.employeeIcons[i] = lvl > 0 ? skull(textures, EMP_IDS[i]) : GRAY_DYE;
            m.employeeLevels[i] = c + lvl;
            m.employeeTips[i] = lines(List.of(c + "§l" + EMP_NAMES[i] + "§r§7 (" + c + lvl + "§7)", "",
                    "§7Produces §6+" + fmt((long) lvl * (i + 1)) + " Chocolate§7 per second."));
        }
    }

    private static ItemStack upgrade(net.minecraft.world.item.Item item, String name, int level, String... lore) {
        return upgrade(new ItemStack(item), name, level, lore);
    }

    private static ItemStack skull(JsonObject textures, String id) {
        JsonElement t = textures == null ? null : textures.get(id);
        return t == null ? new ItemStack(Items.BARRIER) : LegacyItems.skull(t.getAsString(), null);
    }

    private static ItemStack upgrade(ItemStack st, String name, int level, String... lore) {
        st.set(DataComponents.CUSTOM_NAME, Component.literal("§d" + name + " " + level).withStyle(x -> x.withItalic(false)));
        List<Component> l = lines(List.of(lore));
        st.set(DataComponents.LORE, new ItemLore(l, l));
        return st;
    }

    /** toReadableTime(maxUnits = 3): "1d, 2h, 3m" from the biggest non-zero unit. */
    private static String readable(long ms) {
        long sec = Math.max(0, ms / 1000);
        long[] v = {sec / 86400, sec / 3600 % 24, sec / 60 % 60, sec % 60};
        String[] u = {"d", "h", "m", "s"};
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < 4 && n < 3; i++) {
            if (v[i] != 0) {
                if (n++ > 0) sb.append(", ");
                sb.append(v[i]).append(u[i]);
            }
        }
        return n == 0 ? "0 seconds" : sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void buildForaging(SbProfile p, ProfileExtras ex, Model m) {
        ProfileExtras.Foraging fo = ex == null ? null : ex.foraging;
        List<String> l = new ArrayList<>();
        List<List<Component>> t = new ArrayList<>();
        long xp = fo == null ? 0 : fo.hotfXp();
        long[] table = ExtraTables.tableLongs(ExtraTables.bundled().get("hotf_levels"));
        LevelTables.Level lvl = LevelTables.cumulative(table, xp, table.length);
        int level = Math.max(1, lvl.level());
        m.hotfMaxed = lvl.maxed();
        m.hotfProgress = m.hotfMaxed ? 1f : (float) Math.max(0, Math.min(1, lvl.progress()));
        l.add("§7HotF: " + level);
        t.add(lines(m.hotfMaxed ? List.of("§eHotF Level: " + level, "§7Progress to Max: §6Maxed")
                : List.of("§eHotF Level: " + level, "§7Progress to Level " + (level + 1) + ": §e" + fmt(lvl.xpIntoLevel()) + "§6/§e"
                + fmt(lvl.xpForLevel()) + "§a (" + round(m.hotfProgress * 100) + "%)")));
        JsonObject data = ExtraTables.bundled().getAsJsonObject("sbpv_foraging");
        JsonObject gifts = data == null ? null : data.getAsJsonObject("tree_gifts"), misc = data == null ? new JsonObject() : data.getAsJsonObject("misc");
        for (String g : List.of("MANGROVE", "FIG", "HELIX")) {
            String name = g.charAt(0) + g.substring(1).toLowerCase(Locale.ROOT);
            int claimed = (int) p.memberNum("foraging.tree_gifts.milestone_tier_claimed." + g);
            int max = gifts != null && gifts.has(g.toLowerCase(Locale.ROOT)) ? gifts.getAsJsonArray(g.toLowerCase(Locale.ROOT)).size() : 0;
            l.add(name + " Gifts: " + (claimed >= max ? "§a" : "§c") + claimed + "§r/§a" + max);
            t.add(null);
            l.add("Total " + name + " Gifts: " + (int) p.memberNum("foraging.tree_gifts." + g));
            t.add(null);
        }
        l.add("Whispers (Current/Total)");
        t.add(null);
        m.forWhispers = new Component[] {
                whisper("Forest", 0x00AAAA, fo == null ? 0 : fo.forestAvailable(), fo == null ? 0 : fo.forestTotal()),
                whisper("Desert", 0xFFAA00, fo == null ? 0 : fo.desertAvailable(), fo == null ? 0 : fo.desertTotal())};
        m.forInfo = l.toArray(new String[0]);
        m.forInfoTips = t.toArray(new List[0]);
        // Personal Bests: Fig, Mangrove, Helix (Agatha's perks for the first two, Miria's for Helix).
        JsonObject perks = p.memberObj("player_data.perks");
        String[][] types = {{"Fig", "agatha", "fig"}, {"Mangrove", "agatha", "mangrove"}, {"Helix", "miria", "helix"}};
        m.pbTitles = new String[3];
        m.pbLines = new String[3][];
        for (int i = 0; i < 3; i++) {
            String name = types[i][0], id = types[i][2], pre = types[i][1] + "_" + id;
            int fortune = perks != null && perks.has(pre + "_fortune") ? perks.get(pre + "_fortune").getAsInt() : 0;
            boolean unlocked = perks != null && perks.has(pre + "_personal_best");
            int maxFortune = misc.has(id + "_fortune") ? misc.get(id + "_fortune").getAsInt() : 0;
            int pbMax = misc.has(id + "_personal_best") ? misc.get(id + "_personal_best").getAsInt() : 0;
            int pb = (int) p.memberNum("foraging.starlyn.personal_bests." + id.toUpperCase(Locale.ROOT) + "_LOG");
            List<String> pl = new ArrayList<>();
            pl.add(name + " Personal Bests: " + (unlocked ? "§aYes" : "§cNo"));
            if (unlocked) pl.add(name + " Best: " + (pb >= pbMax ? "§a" : "§c") + fmt(Math.min(pb, pbMax)) + "§r/§a" + fmt(pbMax));
            pl.add(name + " Fortune Level: " + (fortune >= maxFortune ? "§a" : "§c") + fortune + "§r/§a" + maxFortune);
            m.pbTitles[i] = name;
            m.pbLines[i] = pl.toArray(new String[0]);
        }
    }

    /** "Name: available/total": name and available in the color, ": " and "/" dark gray, the total in the color scaled by 0.75. */
    private static Component whisper(String name, int color, long available, long total) {
        int dim = ((color >> 16 & 0xFF) * 3 / 4) << 16 | ((color >> 8 & 0xFF) * 3 / 4) << 8 | (color & 0xFF) * 3 / 4;
        return Component.literal(name).withStyle(x -> x.withColor(color))
                .append(Component.literal(": ").withStyle(x -> x.withColor(0x555555)))
                .append(Component.literal(fmt(available)).withStyle(x -> x.withColor(color)))
                .append(Component.literal("/").withStyle(x -> x.withColor(0x555555)))
                .append(Component.literal(fmt(total)).withStyle(x -> x.withColor(dim)));
    }

    private static void buildLoadouts(SbProfile p, Model m) {
        JsonObject lo = p.memberObj("loadout");
        for (SbProfile.Pet pet : p.pets) {
            JsonElement u = pet.info.get("uuid");
            if (u != null && u.isJsonPrimitive()) {
                m.pets.put(u.getAsString(), pet.icon());
                m.petLevels.put(u.getAsString(), String.valueOf(pet.level.level()));
            }
        }
        if (lo == null) return;
        m.equippedArmor = sets(lo.getAsJsonObject("armor"), m.armorSets, "HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS");
        m.equippedEquip = sets(lo.getAsJsonObject("equipment"), m.equipSets, "EQUIPMENT_SLOT_1", "EQUIPMENT_SLOT_2", "EQUIPMENT_SLOT_3",
                "EQUIPMENT_SLOT_4");
        JsonObject saved = lo.getAsJsonObject("loadouts");
        if (saved == null) return;
        List<Loadout> list = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : saved.entrySet()) {
            if (!e.getValue().isJsonObject()) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            int id = o.has("id") ? o.get("id").getAsInt() : 0;
            String name = o.has("name") ? o.get("name").getAsString() : "Loadout " + id;
            Integer armor = optInt(o, "armor_set_id"), equip = optInt(o, "equipment_set_id"), hotm = optInt(o, "mining_core_selected_slot"),
                    hotf = optInt(o, "foraging_core_selected_slot");
            String pet = optStr(o, "pet"), bait = optStr(o, "favoredBait");
            boolean empty = armor == null && equip == null && hotm == null && hotf == null && optStr(o, "power_stone") == null
                    && optInt(o, "tuning_points_slot") == null && pet == null && bait == null && name.equals("Loadout " + id);
            list.add(new Loadout(id, name, armor, equip, hotm, hotf, pet, bait, empty));
        }
        list.sort((a, b) -> Integer.compare(a.id, b.id));
        for (Loadout l : list) m.loadouts.put(l.id, l);
        m.ordered = list.toArray(new Loadout[0]);
    }

    private static int sets(JsonObject obj, Map<Integer, List<ItemStack>> into, String... slots) {
        if (obj == null) return 0;
        int equipped = obj.has("equipped_set") ? obj.get("equipped_set").getAsInt() : 0;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (e.getKey().equals("equipped_set") || !e.getValue().isJsonObject()) continue;
            JsonObject set = e.getValue().getAsJsonObject();
            List<ItemStack> items = new ArrayList<>(4);
            for (String slot : slots) {
                JsonObject so = set.getAsJsonObject(slot);
                List<ItemStack> dec = so != null && so.has("data") ? LegacyItems.decodeBase64(so.get("data").getAsString()) : List.of();
                items.add(dec.isEmpty() ? ItemStack.EMPTY : dec.get(0));
            }
            into.put(set.has("id") ? set.get("id").getAsInt() : 0, items);
        }
        return equipped;
    }

    private static Integer optInt(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsInt();
    }

    private static String optStr(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    // ---------------------------------------------------------------- pages

    /** Draws page {@code page}; false when the model is still building (the caller shows its loading line). */
    boolean draw(GuiGraphicsExtractor g, int page, SbProfile p, int mx, int my) {
        if (modelFor != p || model == null) {
            modelFor = p;
            killScroll[0] = killScroll[1] = 0;
            model = p.extras().thenApplyAsync(ex -> build(p, ex), ProfileViewerApi.EXECUTOR);
        }
        Model m = model.isDone() && !model.isCompletedExceptionally() ? model.getNow(null) : null;
        if (m == null) return false;
        switch (page) {
            case ProfileViewerScreen.PAGE_KILLS -> drawKills(g, m, mx, my);
            case ProfileViewerScreen.PAGE_FISHING -> drawFishing(g, p, m, mx, my);
            case ProfileViewerScreen.PAGE_CF -> drawCf(g, m, mx, my);
            case ProfileViewerScreen.PAGE_FORAGING -> drawForaging(g, m, mx, my);
            case ProfileViewerScreen.PAGE_LOADOUT -> drawLoadouts(g, p, m, mx, my);
            default -> {
                return true;
            }
        }
        return true;
    }

    boolean failed() {
        return model != null && model.isCompletedExceptionally();
    }

    /** Mouse wheel on Kills and Deaths: the column under the mouse scrolls. */
    void scroll(double mx, double scrollY) {
        int col = mx < s.panelX + s.panelW / 2.0 ? 0 : 1;
        killScroll[col] = Math.max(0, killScroll[col] - (int) Math.signum(scrollY));
    }

    private void drawKills(GuiGraphicsExtractor g, Model m, int mx, int my) {
        int cw = s.panelW / 2 - 20, ch = s.panelH - 20;
        int x0 = s.panelX + (s.panelW - (cw * 2 + 5)) / 2, y0 = s.panelY + (s.panelH - (9 + 5 + ch)) / 2;
        String[][] cols = {m.kills, m.deaths};
        String[] titles = {"Kills", "Deaths"};
        for (int c = 0; c < 2; c++) {
            int x = x0 + c * (cw + 5);
            g.text(font(), titles[c], x + (cw - font().width(titles[c])) / 2, y0, text(), false);
            int ly = y0 + 14, rows = ch / 9;
            killScroll[c] = Math.max(0, Math.min(killScroll[c], Math.max(0, cols[c].length - rows)));
            g.enableScissor(x, ly, x + cw, ly + ch);
            for (int i = killScroll[c]; i < cols[c].length && i < killScroll[c] + rows + 1; i++) {
                g.text(font(), cols[c][i], x, ly + (i - killScroll[c]) * 9, text(), false);
            }
            g.disableScissor();
        }
    }

    private void drawFishing(GuiGraphicsExtractor g, SbProfile p, Model m, int mx, int my) {
        if (m.fishGear == null) {
            m.fishGear = p.inventories().handle((inv, err) -> inv).thenApplyAsync(
                    inv -> {
                        List<List<ItemStack>> sets = new ArrayList<>(m.equipSets.values());
                        sets.addAll(m.armorSets.values());
                        return SbpvGear.build(inv, sets, "fishing", "rods", "trophy_armor");
                    }, ProfileViewerApi.EXECUTOR);
        }
        SbpvGear.Gear gear = m.fishGear.isDone() && !m.fishGear.isCompletedExceptionally() ? m.fishGear.getNow(null) : null;
        int infoW = widest(m.fishInfo), statW = widest(m.fishStats);
        int infoLW = labelW(infoW, 10), statLW = labelW(statW, 10), gearLW = labelW(GEAR_W, 0);
        int infoH = labelH(m.fishInfo.length * 9), statH = labelH(m.fishStats.length * 9), gearH = labelH(GEAR_H);
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        // FishingScreen.create's four layouts, tried in its order.
        if (infoLW + statLW + gearLW < bw && gearH + 165 < bh) {
            fishInfo(g, m, bx, by + 5, mx, my);
            fishStats(g, m, bx + infoLW, by + 5, mx, my);
            drawGear(g, gear, ROD_SLOT, bx + infoLW + statLW, by + 5, mx, my);
            drawTrophies(g, m, bx, by + bh - 5 - trophyHeight(bw), bw, mx, my);
        } else if (infoLW + statLW < bw && gearH + 10 + infoH < bh) {
            int tw = bw - gearLW, bottom = by + bh - 10;
            fishInfo(g, m, bx, by + 5, mx, my);
            fishStats(g, m, bx + infoLW, by + 5, mx, my);
            drawGear(g, gear, ROD_SLOT, bx, bottom - Math.max(gearH, trophyHeight(tw)), mx, my);
            drawTrophies(g, m, bx + gearLW, bottom - trophyHeight(tw), tw, mx, my);
        } else if (gearLW + statLW < bw && gearH + 10 + infoH < bh) {
            int tw = bw - infoLW, bottom = by + bh - 10;
            fishInfo(g, m, bx, by + 5, mx, my);
            drawTrophies(g, m, bx + infoLW, by + 5, tw, mx, my);
            drawGear(g, gear, ROD_SLOT, bx, bottom - Math.max(gearH, statH), mx, my);
            fishStats(g, m, bx + gearLW, bottom - statH, mx, my);
        } else {
            // The last fallback: each section centered, one under the other, 10 apart, scrolled with the wheel.
            int trophyW = bw - 60, y = by + 5 - s.pageScroll * 10;
            g.enableScissor(bx, by, bx + bw, by + bh);
            fishInfo(g, m, bx + (bw - infoLW) / 2, y, mx, my);
            y += infoH + 10;
            fishStats(g, m, bx + (bw - statLW) / 2, y, mx, my);
            y += statH + 10;
            drawGear(g, gear, ROD_SLOT, bx + (bw - gearLW) / 2, y, mx, my);
            y += gearH + 10;
            drawTrophies(g, m, bx + 30, y, trophyW, mx, my);
            g.disableScissor();
        }
    }

    private void fishInfo(GuiGraphicsExtractor g, Model m, int x, int y, int mx, int my) {
        int[] c = label(g, "Information", CLIPBOARD, x, y, widest(m.fishInfo), m.fishInfo.length * 9, 10);
        textLines(g, m.fishInfo, m.fishInfoTips, c[0], c[1], 0, mx, my);
    }

    private void fishStats(GuiGraphicsExtractor g, Model m, int x, int y, int mx, int my) {
        int[] c = label(g, "Stats", null, x, y, widest(m.fishStats), m.fishStats.length * 9, 10);
        textLines(g, m.fishStats, m.fishStatTips, c[0], c[1], 0, mx, my);
    }

    /** The Gear label: side armor column (24), 5, armor + equipment 2 x 4 (44), 5, the four best tools (24); 84 high. */
    private static final int GEAR_W = 24 + 5 + 44 + 5 + 24, GEAR_H = 84;
    private static final Identifier ROD_SLOT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_rod");
    private static final Identifier[] ARMOR_SLOTS = {Identifier.withDefaultNamespace("container/slot/helmet"),
            Identifier.withDefaultNamespace("container/slot/chestplate"), Identifier.withDefaultNamespace("container/slot/leggings"),
            Identifier.withDefaultNamespace("container/slot/boots")};
    private static final Identifier[] EQUIP_SLOTS = {Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_necklace"),
            Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_cloak"), Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_belt"),
            Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_glove")};

    private void drawGear(GuiGraphicsExtractor g, SbpvGear.Gear gear, Identifier toolIcon, int x, int y, int mx, int my) {
        int[] c = label(g, "Gear", null, x, y, GEAR_W, GEAR_H, 0);
        int ax = c[0] + 29, tx = ax + 49;
        for (int i = 0; i < 4; i++) {
            int sy = c[1] + 2 + i * 20;
            gearSlot(g, gear == null || gear.side() == null ? ItemStack.EMPTY : gear.side().get(i), ARMOR_SLOTS[i], c[0] + 2, sy, mx, my);
            gearSlot(g, gear == null ? ItemStack.EMPTY : gear.armor().get(i), ARMOR_SLOTS[i], ax + 2, sy, mx, my);
            gearSlot(g, gear == null ? ItemStack.EMPTY : gear.equip().get(i), EQUIP_SLOTS[i], ax + 22, sy, mx, my);
            gearSlot(g, gear == null ? ItemStack.EMPTY : gear.tools().get(i), toolIcon, tx + 2, sy, mx, my);
        }
    }

    private void gearSlot(GuiGraphicsExtractor g, ItemStack st, Identifier empty, int x, int y, int mx, int my) {
        if (!st.isEmpty()) {
            slotItem(g, st, null, x, y, null, mx, my);
            return;
        }
        PvStyleChrome.slot(g, s.pvStyle(), x, y, 20);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, empty, x + 2, y + 2, 16, 16);
    }

    private int trophyContentH(int innerW) {
        int table = fishTab == 1 ? 3 * 20 + 4 : innerW < 470 ? 3 * 20 + 4 : 5 * 24 + 4 * 4;
        return 16 + 4 + table + 2;
    }

    private int trophyHeight(int w) {
        return labelH(trophyContentH(w - 10));
    }

    /** The Trophies label: "Trophy Fish" / "Trophy Frog" buttons, then the full fish table when 480+ wide, else 6 x 3. */
    private void drawTrophies(GuiGraphicsExtractor g, Model m, int x, int y, int w, int mx, int my) {
        int inner = w - 10;
        s.sbpvLabel(g, "Trophies", x, y, w, trophyContentH(inner) + 14, text(), null);
        int cy = y + 27, bw = Math.min(inner / 2 - 4, 120), rowW = 4 + bw * 2 + 4;
        int bx = x + (w - rowW) / 2 + 4;
        String[] names = {"Trophy Fish", "Trophy Frog"};
        for (int i = 0; i < 2; i++) {
            int tx = bx + i * (bw + 4);
            boolean h = hover(mx, my, tx, cy, bw, 16);
            PvStyleChrome.button(g, s.pvStyle(), tx, cy, bw, 16, names[i], h, false);
            final int tab = i;
            s.hotspots.add(new ProfileViewerScreen.Hotspot(tx, cy, bw, 16, () -> fishTab = tab));
        }
        cy += 20;
        if (fishTab == 1) {
            grid(g, m.frogTable, 4, x + (w - (4 * 20 + 4)) / 2, cy, mx, my);
        } else if (w < 480) {
            grid(g, m.smallTable, 6, x + (w - (6 * 20 + 4)) / 2, cy, mx, my);
        } else {
            int tw = FISH.length * 24 + (FISH.length - 1) * 4, tx = x + (w - tw) / 2;
            for (int row = 0; row < 5; row++) {
                for (int f = 0; f < FISH.length; f++) {
                    Cell c = m.bigTable[row][f];
                    int cx = tx + f * 28, ry = cy + row * 28;
                    PvStyleChrome.slot(g, s.pvStyle(), cx, ry, 24);
                    g.item(c.stack, cx + 4, ry + 4);
                    if (!c.count.isEmpty()) g.itemDecorations(font(), c.stack, cx + 4, ry + 4, c.count);
                    if (hover(mx, my, cx, ry, 24, 24)) s.pendingTooltip = c.tip;
                }
            }
        }
    }

    /** ExtraDisplays.inventoryBackground(cols, rows) around 2 px padded cells of 2 px padded items. */
    private void grid(GuiGraphicsExtractor g, Cell[] cells, int cols, int x, int y, int mx, int my) {
        for (int i = 0; i < cells.length; i++) {
            Cell c = cells[i];
            slotItem(g, c.stack, c.count, x + 2 + (i % cols) * 20, y + 2 + (i / cols) * 20, c.tip, mx, my);
        }
    }

    private void drawCf(GuiGraphicsExtractor g, Model m, int mx, int my) {
        if (!m.cfPresent) return;
        if (m.empW == null) {
            m.empW = chipWidths(m.employeeLevels);
            m.rarW = chipWidths(m.rarityCounts);
        }
        int infoW = widest(m.cfInfo);
        int empGridW = chipGridW(m.empW), empGridH = chipGridH(m.empW.length);
        int rarGridW = chipGridW(m.rarW), rarGridH = chipGridH(m.rarW.length);
        int empLW = labelW(empGridW, 0), upLW = labelW(4 * 20 + 3 * 2 + 4, 0), infoLW = labelW(infoW, 0), rarLW = labelW(rarGridW, 0);
        int leftW = Math.max(empLW, upLW), leftH = labelH(empGridH) + 3 + labelH(24);
        int infoH = labelH(m.cfInfo.length * 9), rarH = labelH(rarGridH);
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        // horizontal(3, centered): [Employees over Upgrades], Information, Rarities; else all four in one scrolled column.
        boolean row = leftW + infoLW + rarLW + 6 <= bw;
        int totalW = leftW + 3 + infoLW + 3 + rarLW, x = bx + (bw - totalW) / 2, h = Math.max(leftH, Math.max(infoH, rarH));
        int y = by + (bh - h) / 2;
        if (!row) {
            x = bx + (bw - leftW) / 2;
            y = by - s.pageScroll * 10;
            g.enableScissor(bx, by, bx + bw, by + bh);
        }
        int ey = row ? y + (h - leftH) / 2 : y;
        int[] c = label(g, "Employees", null, x + (leftW - empLW) / 2, ey, empGridW, empGridH, 0);
        chips(g, m.employeeIcons, m.employeeLevels, m.employeeTips, m.empW, c[0], c[1], empGridW, mx, my);
        ey += labelH(empGridH) + 3;
        c = label(g, "Upgrades", null, x + (leftW - upLW) / 2, ey, 4 * 20 + 3 * 2 + 4, 24, 0);
        for (int i = 0; i < 4; i++) {
            slotItem(g, m.upgrades[i], null, c[0] + 2 + i * 22, c[1] + 2, null, mx, my);
        }
        int ix = row ? x + leftW + 3 : bx + (bw - infoLW) / 2, iy = row ? y + (h - infoH) / 2 : ey + labelH(24) + 3;
        c = label(g, "Information", CLIPBOARD, ix, iy, infoW, m.cfInfo.length * 9, 0);
        textLines(g, m.cfInfo, m.cfInfoTips, c[0], c[1], 0, mx, my);
        int rx = row ? ix + infoLW + 3 : bx + (bw - rarLW) / 2, ry = row ? y + (h - rarH) / 2 : iy + infoH + 3;
        c = label(g, "Rarities", null, rx, ry, rarGridW, rarGridH, 0);
        chips(g, m.rarityIcons, m.rarityCounts, m.rarityTips, m.rarW, c[0], c[1], rarGridW, mx, my);
        if (!row) g.disableScissor();
    }

    /** PvWidgets.iconNumberElement widths: a 12 px icon, 1 gap, the text, padded 2 around (and 2 after the text). */
    private int[] chipWidths(String[] texts) {
        int[] w = new int[texts.length];
        for (int i = 0; i < w.length; i++) w[i] = 2 + 12 + 1 + font().width(texts[i]) + 2;
        return w;
    }

    /** Chips in rows of four, 1 apart, each row centered (chunked(4).toRow(1).toColumn(1, CENTER)). */
    private static int chipGridW(int[] w) {
        int max = 0;
        for (int r = 0; r * 4 < w.length; r++) max = Math.max(max, chipRowW(w, r));
        return max;
    }

    private static int chipRowW(int[] w, int r) {
        int sum = -1;
        for (int i = r * 4; i < Math.min(w.length, r * 4 + 4); i++) sum += w[i] + 1;
        return sum;
    }

    private static int chipGridH(int n) {
        int rows = (n + 3) / 4;
        return rows == 0 ? 0 : rows * 17 - 1;
    }

    private void chips(GuiGraphicsExtractor g, ItemStack[] icons, String[] texts, List<Component>[] tips, int[] w, int x, int y, int gridW,
                       int mx, int my) {
        for (int r = 0; r * 4 < w.length; r++) {
            int cx = x + (gridW - chipRowW(w, r)) / 2, cy = y + r * 17;
            for (int i = r * 4; i < Math.min(w.length, r * 4 + 4); i++) {
                PvStyleChrome.chip(g, s.pvStyle(), cx, cy, w[i], 16);
                g.pose().pushMatrix();
                g.pose().translate(cx + 2, cy + 2);
                g.pose().scale(0.75f, 0.75f);
                g.item(icons[i], 0, 0);
                g.pose().popMatrix();
                g.text(font(), texts[i], cx + 15, cy + 4, text(), false);
                if (hover(mx, my, cx, cy, w[i], 16)) s.pendingTooltip = tips[i];
                cx += w[i] + 1;
            }
        }
    }

    private static final ItemStack[] FORAGING_ICONS = {new ItemStack(Items.OAK_WOOD), new ItemStack(Items.OAK_SAPLING)};
    private static final List<?>[] FORAGING_TIPS = {List.of(Component.literal("Main")), List.of(Component.literal("HotF Tree"))};

    /** MainForagingScreen's Information (HotF level with its bar and hover, gifts, whispers), with the Main and HotF Tree
     *  categories on the right edge; HotF Tree shows our perk list. */
    @SuppressWarnings("unchecked")
    private void drawForaging(GuiGraphicsExtractor g, Model m, int mx, int my) {
        s.subView = Math.max(0, Math.min(1, s.subView));
        for (int i = 0; i < 2; i++) {
            int tx = s.panelX + s.panelW - 9, ty = s.panelY + 20 + 22 * i;
            final int idx = i;
            PvStyleChrome.sideTab(g, s.pvStyle(), tx, ty, i == s.subView);
            g.item(FORAGING_ICONS[i], tx + 10, ty + 2);
            s.hotspots.add(new ProfileViewerScreen.Hotspot(tx, ty, 31, 20, () -> s.subView = idx));
            if (hover(mx, my, tx, ty, 31, 20)) s.pendingTooltip = (List<Component>) FORAGING_TIPS[i];
        }
        if (s.subView == 1) {
            s.drawForagingTree(g, mx, my);
            return;
        }
        int w = Math.max(91, widest(m.forInfo));
        for (Component wl : m.forWhispers) w = Math.max(w, font().width(wl));
        // vertical(3): the HotF display (16), the gift lines, a 1 px spacer, the Whispers header and the two whisper lines.
        int h = 16 + (m.forInfo.length + 1) * 9 + 1 + (m.forInfo.length + 2) * 3;
        int infoLW = labelW(w, 10), infoH = labelH(h);
        int pbW = 0, pbH = -3;
        for (String[] pl : m.pbLines) {
            pbW = Math.max(pbW, labelW(widest(pl), 10));
            pbH += labelH(pl.length * 12 - 3) + 3;
        }
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        boolean column = infoLW + pbW + 5 > bw || infoH > bh || pbH > bh;
        int x, y, px, py;
        if (column) {
            y = by - s.pageScroll * 10;
            x = bx + (bw - infoLW) / 2;
            py = y + infoH + 5;
            px = bx + (bw - pbW) / 2;
            g.enableScissor(bx, by, bx + bw, by + bh);
        } else {
            x = bx + (bw - (infoLW + 5 + pbW)) / 2;
            px = x + infoLW + 5;
            y = by + (bh - infoH) / 2;
            py = by + (bh - pbH) / 2;
        }
        int[] c = label(g, "Information", CLIPBOARD, x, y, w, h, 10);
        g.text(font(), m.forInfo[0], c[0], c[1], text(), false);
        PvStyleChrome.bar(g, c[0], c[1] + 11, m.hotfProgress);
        if (hover(mx, my, c[0], c[1], 91, 16)) s.pendingTooltip = m.forInfoTips[0];
        int ly = c[1] + 16 + 3;
        for (int i = 1; i < m.forInfo.length; i++) {
            if (i == m.forInfo.length - 1) ly += 4;
            g.text(font(), m.forInfo[i], c[0], ly, text(), false);
            ly += 12;
        }
        for (Component wl : m.forWhispers) {
            g.text(font(), wl, c[0], ly, -1, false);
            ly += 12;
        }
        for (int i = 0; i < m.pbLines.length; i++) {
            int cw = widest(m.pbLines[i]), lw = labelW(cw, 10);
            c = label(g, m.pbTitles[i], null, px + (pbW - lw) / 2, py, cw, m.pbLines[i].length * 12 - 3, 10);
            textLines(g, m.pbLines[i], null, c[0], c[1], 3, mx, my);
            py += labelH(m.pbLines[i].length * 12 - 3) + 3;
        }
        if (column) g.disableScissor();
    }

    // ---------------------------------------------------------------- loadouts

    private Object loFor;
    private int loSelFor;
    private List<ItemStack> loArmor, loEquip;
    private ItemStack[] loIcons;
    private List<Component>[] loTips;

    @SuppressWarnings("unchecked")
    private void drawLoadouts(GuiGraphicsExtractor g, SbProfile p, Model m, int mx, int my) {
        SbProfile.Inventories inv = p.inventories().isDone() && !p.inventories().isCompletedExceptionally() ? p.inventories().getNow(null) : null;
        int gridW = 3 * 20 + 4, gridH = 9 * 20 + 4;
        int selLW = labelW(gridW, 0), selH = labelH(gridH);
        int bx = s.panelX, by = s.contentY, bh = s.panelY + s.panelH - s.contentY;
        int x = bx, y = by + Math.max(0, (bh - selH) / 2);
        int[] c = label(g, "Loadouts", null, x, y, gridW, gridH, 0);
        Object key = inv == null ? m : inv;
        if (loFor != key || loSelFor != loadoutSel) {
            loSelFor = loadoutSel;
            Loadout cur = m.loadouts.get(loadoutSel);
            loArmor = entry(m.armorSets, cur == null ? null : cur.armorSet, m.equippedArmor, inv == null ? null : reversed(inv.armor()));
            loEquip = entry(m.equipSets, cur == null ? null : cur.equipSet, m.equippedEquip, inv == null ? null : inv.equipment());
            // Icons and hovers are built once per profile (again when the inventories finish loading), never per frame.
            loFor = key;
            loIcons = new ItemStack[27];
            loTips = new List[27];
            for (int i = 0; i < 27; i++) {
                Loadout l = i < m.ordered.length ? m.ordered[i] : null;
                loIcons[i] = icon(l, m, inv);
                loTips[i] = loadoutTip(l, i, m, inv);
            }
        }
        for (int i = 0; i < 27; i++) {
            int col = i % 3, row = i / 3, id = i + 1;
            Loadout l = i < m.ordered.length ? m.ordered[i] : null;
            int sx = c[0] + 2 + col * 20, sy = c[1] + 2 + row * 20;
            slotItem(g, loIcons[i], null, sx, sy, loTips[i], mx, my);
            final int sel = l != null ? l.id : id;
            s.hotspots.add(new ProfileViewerScreen.Hotspot(sx, sy, 20, 20, () -> loadoutSel = sel));
        }
        int sepX = x + selLW + 5;
        g.fill(sepX, by + (bh - (bh - 20)) / 2, sepX + 1, by + (bh - (bh - 20)) / 2 + bh - 20, MOCHA_SURFACE0);
        int mainX = sepX + 6, mainW = s.panelW - selLW - 10;
        Loadout sel = m.loadouts.get(loadoutSel);
        // mainView: HOTM Loadout, Equipment, HOTF Loadout in a row; else Equipment over the two trees; else all in a column.
        Tree hotm = m.trees[0][sel != null && sel.hotm != null && sel.hotm >= 1 && sel.hotm <= 5 ? sel.hotm : 0];
        Tree hotf = m.trees[1][sel != null && sel.hotf != null && sel.hotf >= 1 && sel.hotf <= 5 ? sel.hotf : 0];
        int eqW = labelW(EQ_W, 0), eqH = labelH(EQ_H);
        int hmW = labelW(hotm == null ? 0 : hotm.w, 0), hmH = labelH(hotm == null ? 0 : hotm.h);
        int hfW = labelW(hotf == null ? 0 : hotf.w, 0), hfH = labelH(hotf == null ? 0 : hotf.h);
        int layout = hmW + eqW + hfW + 10 <= mainW && (mainW - eqW - 25) / 2 >= 90 ? 1 : hmW + hfW + 5 <= mainW && (mainW - 20) / 2 >= 90 ? 2 : 3;
        int ch = layout == 1 ? Math.max(eqH, Math.max(hmH, hfH)) : layout == 2 ? eqH + 10 + Math.max(hmH, hfH) : eqH + 10 + hmH + 10 + hfH;
        boolean scroll = ch > bh;
        int top = scroll ? by - s.pageScroll * 10 : by + (bh - ch) / 2;
        if (scroll) g.enableScissor(mainX, by, mainX + mainW, by + bh);
        if (layout == 1) {
            int x0 = mainX + (mainW - (hmW + eqW + hfW + 10)) / 2;
            drawTree(g, "HOTM Loadout", hotm, x0, top + (ch - hmH) / 2, mx, my);
            drawEquipment(g, m, sel, x0 + hmW + 5, top + (ch - eqH) / 2, mx, my);
            drawTree(g, "HOTF Loadout", hotf, x0 + hmW + 5 + eqW + 5, top + (ch - hfH) / 2, mx, my);
        } else if (layout == 2) {
            drawEquipment(g, m, sel, mainX + (mainW - eqW) / 2, top, mx, my);
            int rowH = Math.max(hmH, hfH), x0 = mainX + (mainW - (hmW + hfW + 5)) / 2, ty = top + eqH + 10;
            drawTree(g, "HOTM Loadout", hotm, x0, ty + (rowH - hmH) / 2, mx, my);
            drawTree(g, "HOTF Loadout", hotf, x0 + hmW + 5, ty + (rowH - hfH) / 2, mx, my);
        } else {
            drawEquipment(g, m, sel, mainX + (mainW - eqW) / 2, top, mx, my);
            drawTree(g, "HOTM Loadout", hotm, mainX + (mainW - hmW) / 2, top + eqH + 10, mx, my);
            drawTree(g, "HOTF Loadout", hotf, mainX + (mainW - hfW) / 2, top + eqH + 10 + hmH + 10, mx, my);
        }
        if (scroll) g.disableScissor();
    }

    /** Equipment: armor and equipment columns in a 2 x 4 inventory, then the pet and the bait slot, 5 apart, centered. */
    private static final int EQ_W = 2 * 20 + 4, EQ_H = 4 * 20 + 4 + 5 + 20 + 5 + 20;

    private void drawTree(GuiGraphicsExtractor g, String title, Tree t, int x, int y, int mx, int my) {
        int[] c = label(g, title, null, x, y, t == null ? 0 : t.w, t == null ? 0 : t.h, 0);
        if (t == null) return;
        for (int i = 0; i < t.icons.length; i++) slotItem(g, t.icons[i], t.counts[i], c[0] + t.x[i], c[1] + t.y[i], t.tips[i], mx, my);
    }

    private void drawEquipment(GuiGraphicsExtractor g, Model m, Loadout sel, int ex, int ey, int mx, int my) {
        List<ItemStack> armor = loArmor, equip = loEquip;
        int contentW = EQ_W;
        int[] c = label(g, "Equipment", null, ex, ey, EQ_W, EQ_H, 0);
        for (int i = 0; i < 4; i++) {
            gearSlot(g, armor != null && i < armor.size() ? armor.get(i) : ItemStack.EMPTY, ARMOR_SLOTS[i], c[0] + 2, c[1] + 2 + i * 20, mx, my);
            gearSlot(g, equip != null && i < equip.size() ? equip.get(i) : ItemStack.EMPTY, EQUIP_SLOTS[i], c[0] + 22, c[1] + 2 + i * 20, mx, my);
        }
        int py = c[1] + 4 * 20 + 4 + 5, px = c[0] + (contentW - 20) / 2;
        ItemStack pet = sel == null || sel.pet == null ? null : m.pets.get(sel.pet);
        if (pet != null) {
            slotItem(g, pet, m.petLevels.get(sel.pet), px, py, null, mx, my);
        } else {
            PvStyleChrome.slot(g, s.pvStyle(), px, py, 20);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_BONE, px + 2, py + 2, 16, 16);
            if (hover(mx, my, px, py, 20, 20)) s.pendingTooltip = List.of(Component.literal("Selected Pet"));
        }
        py += 25;
        if (sel != null && sel.bait != null) {
            slotItem(g, ItemIcons.forId(sel.bait), null, px, py, null, mx, my);
        } else {
            PvStyleChrome.slot(g, s.pvStyle(), px, py, 20);
            g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_BAIT, px + 2, py + 2, 16, 16);
            if (hover(mx, my, px, py, 20, 20)) s.pendingTooltip = List.of(Component.literal("Favored Bait"));
        }
    }

    private static List<ItemStack> reversed(List<ItemStack> l) {
        List<ItemStack> r = new ArrayList<>(l);
        java.util.Collections.reverse(r);
        return r;
    }

    private static List<ItemStack> entry(Map<Integer, List<ItemStack>> sets, Integer id, int equipped, List<ItemStack> worn) {
        if (id != null && id == equipped) return worn;
        return id == null ? null : sets.get(id);
    }

    private ItemStack icon(Loadout l, Model m, SbProfile.Inventories inv) {
        if (l == null) return RED_DYE;
        for (List<ItemStack> set : List.of(nonNull(entry(m.armorSets, l.armorSet, m.equippedArmor, inv == null ? null : reversed(inv.armor()))),
                nonNull(entry(m.equipSets, l.equipSet, m.equippedEquip, inv == null ? null : inv.equipment())))) {
            for (ItemStack st : set) if (!st.isEmpty()) return st;
        }
        if (l.hotm != null) return HOTM;
        if (l.hotf != null) return HOTF;
        if (l.pet != null && m.pets.containsKey(l.pet)) return m.pets.get(l.pet);
        return l.empty ? GRAY_DYE : GREEN_DYE;
    }

    private static List<ItemStack> nonNull(List<ItemStack> l) {
        return l == null ? List.of() : l;
    }

    private static final ItemStack RED_DYE = new ItemStack(McItems.RED_DYE), GREEN_DYE = new ItemStack(McItems.GREEN_DYE),
            HOTM = new ItemStack(Items.DIAMOND_PICKAXE), HOTF = new ItemStack(Items.OAK_SAPLING);

    private List<Component> loadoutTip(Loadout l, int index, Model m, SbProfile.Inventories inv) {
        List<Component> t = new ArrayList<>();
        if (l == null || l.empty) {
            t.add(Component.literal("Template " + index).withStyle(x -> x.withColor(MOCHA_TEXT))
                    .append(Component.literal(l == null ? " (Locked)" : " (Empty)").withStyle(x -> x.withColor(MOCHA_RED))));
        } else {
            t.add(Component.literal(l.name).withStyle(x -> x.withColor(MOCHA_TEXT)));
        }
        t.add(kv("Id - ", Component.literal(String.valueOf(l != null ? l.id : index)).withStyle(x -> x.withColor(MOCHA_SAPPHIRE))));
        entries(t, "Armor Set", l == null ? null : l.armorSet,
                entry(m.armorSets, l == null ? null : l.armorSet, m.equippedArmor, inv == null ? null : reversed(inv.armor())));
        entries(t, "Equipment Set", l == null ? null : l.equipSet,
                entry(m.equipSets, l == null ? null : l.equipSet, m.equippedEquip, inv == null ? null : inv.equipment()));
        t.add(kv("Hotm Preset - ", value(l == null || l.hotm == null ? null : String.valueOf(l.hotm))));
        t.add(kv("Hotf Preset - ", value(l == null || l.hotf == null ? null : String.valueOf(l.hotf))));
        t.add(kv("Favored Bait - ", l == null || l.bait == null ? value(null) : ItemIcons.forId(l.bait).getHoverName()));
        return t;
    }

    private static Component kv(String k, Component v) {
        return Component.literal(k).withStyle(x -> x.withColor(MOCHA_TEXT)).append(v);
    }

    private static Component value(String v) {
        return v != null ? Component.literal(v) : Component.literal("None").withStyle(x -> x.withColor(MOCHA_RED));
    }

    private static void entries(List<Component> t, String name, Integer id, List<ItemStack> list) {
        t.add(kv(name + " - ", value(id == null ? null : String.valueOf(id))));
        if (id != null && list != null && !list.isEmpty()) {
            for (ItemStack st : list) {
                t.add(Component.literal(" - ").withStyle(x -> x.withColor(MOCHA_TEXT))
                        .append(st.isEmpty() ? Component.literal("None").withStyle(x -> x.withColor(MOCHA_RED)) : st.getHoverName()));
            }
            t.add(Component.empty());
        }
    }
}

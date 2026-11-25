package com.killer560.hub.profileviewer.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.killer560.hub.compat.McItems;
import com.killer560.hub.itembrowser.NeuItemData;
import com.killer560.hub.itembrowser.SkyblockItemEntry;
import com.killer560.hub.itembrowser.SkyblockItemRepository;
import com.killer560.hub.itembrowser.SkyblockItemStackFactory;
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
import java.util.Comparator;
import java.util.Arrays;
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
        /** The same trees with the tier column, for the Mining and Foraging tree categories (index 1..5). */
        final Tree[][] fullTrees = new Tree[2][6];
        /** Mining (MainMiningScreen, GlaciteScreen): info lines (0 = the HotM line over its bar), powder table, crystals, forge. */
        String[] mineInfo = new String[0], glaInfo = new String[0], corpses = new String[0];
        List<Component>[] mineInfoTips;
        float hotmProgress;
        boolean stranded, glacite;
        String[][] powder = new String[0][];
        boolean[] crystalOk = new boolean[0];
        ItemStack[] crystalIcons = new ItemStack[0];
        List<Component>[] crystalTips;
        ItemStack[] forgeItems = new ItemStack[0], fossilIcons = new ItemStack[0];
        String[] forgeSlot = new String[0], forgeTime = new String[0];
        List<Component>[] forgeTips, fossilTips;
        /** Per forge slot: when it finishes (0 = no time yet), its colored name and number; own = the viewer's own profile. */
        long[] forgeEnd = new long[0];
        String[] forgeName = new String[0];
        int[] forgeNo = new int[0];
        boolean forgeOwn;
        long scrapSacks;
        CompletableFuture<Object[]> mineGear;
        int[] treeSelected = new int[2];
        CompletableFuture<SbpvGear.Gear> fishGear;
        int[] empW, rarW;
        final Map<Integer, Loadout> loadouts = new LinkedHashMap<>();
        Loadout[] ordered = new Loadout[0];
        final Map<Integer, List<ItemStack>> armorSets = new HashMap<>(), equipSets = new HashMap<>();
        int equippedArmor, equippedEquip;
        final Map<String, ItemStack> pets = new HashMap<>();
        final Map<String, String> petLevels = new HashMap<>();
        ProfileExtras.Farming farming;
        /** The Farming page, built once the garden arrives. */
        CompletableFuture<Farm> farm;
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
        buildMining(p, ex, m);
        m.farming = ex.farming;
        return m;
    }

    private static final String[] ROCK_RARITY = {"§fCommon", "§aUncommon", "§9Rare", "§5Epic", "§6Legendary"};
    private static final int[] ROCK_ORES = {2500, 7500, 20000, 100000, 250000};
    private static final String[] MINE_PERKS = {"fungus_fortuna", "harena_fortuna", "treasures_of_the_earth", "dwarven_training",
            "eager_miner", "rhinestone_infusion", "high_roller", "return_to_sender"};
    private static final String[] GLACITE_PERKS = {"frozen_skin", "prehistorian", "resourceful", "dwarven_expertise",
            "chilled_to_the_bone", "cut_loose", "sleight_of_hand"};
    private static final String[][] CORPSES = {{"lapis", "§9"}, {"tungsten", "§7"}, {"umber", "§6"}, {"vanguard", "§b"}};

    @SuppressWarnings("unchecked")
    private static void buildMining(SbProfile p, ProfileExtras ex, Model m) {
        ProfileExtras.Mining mi = ex == null ? null : ex.mining;
        if (mi == null) return;
        m.stranded = "island".equals(p.gameMode);
        m.glacite = p.memberObj("glacite_player_data") != null;
        List<String> l = new ArrayList<>();
        List<List<Component>> t = new ArrayList<>();
        int rock = -1;
        for (int i = 0; i < ROCK_ORES.length; i++) if (ROCK_ORES[i] <= mi.oresMined()) rock = i;
        List<String> rockTip = new ArrayList<>(List.of("§fOres Mined: §b" + fmt(mi.oresMined()), ""));
        for (int i = 0; i < ROCK_ORES.length; i++) {
            rockTip.add(ROCK_ORES[i] <= mi.oresMined() ? "§f" + ROCK_RARITY[i] + " Rock§f!" : "§8§m" + ROCK_RARITY[i].substring(2) + " Rock!");
        }
        String rockLine = "§8Rock Pet: " + (rock < 0 ? "§cNone" : ROCK_RARITY[rock]);
        long[] table = ExtraTables.tableLongs(ExtraTables.bundled().get("hotm_levels"));
        LevelTables.Level lvl = LevelTables.cumulative(table, mi.hotmXp(), table.length);
        if (!m.stranded) {
            int level = lvl.level();
            m.hotmProgress = lvl.maxed() ? 1f : (float) Math.max(0, Math.min(1, lvl.progress()));
            l.add("§8HotM: " + level);
            t.add(lines(lvl.maxed() ? List.of("§eHotM Level: " + level, "§7Progress to Max: §6Maxed")
                    : List.of("§eHotM Level: " + level, "§7Progress to Level " + (level + 1) + ": §e" + fmt(lvl.xpIntoLevel()) + "§6/§e"
                    + fmt(lvl.xpForLevel()) + "§a (" + round(m.hotmProgress * 100) + "%)")));
            int runs = Integer.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                ProfileExtras.Crystal c = mi.crystals().get(ExtraPages.CRYSTALS[i]);
                runs = Math.min(runs, c == null ? 0 : c.placed());
            }
            l.add("§8Total Runs: " + fmt(mi.crystals().isEmpty() ? 0 : runs));
            t.add(null);
            l.add(rockLine);
            t.add(lines(rockTip));
            for (String perk : MINE_PERKS) {
                l.add(perkLine(p, perk));
                t.add(null);
            }
        } else {
            l.add(rockLine);
            t.add(lines(rockTip));
        }
        m.mineInfo = l.toArray(new String[0]);
        m.mineInfoTips = t.toArray(new List[0]);
        String[] names = {"§2Mithril", "§dGemstone", "§bGlacite"};
        m.powder = new String[4][];
        m.powder[0] = new String[] {"", "Current", "Total"};
        for (int i = 0; i < 3; i++) {
            m.powder[i + 1] = new String[] {names[i], shorten(mi.powderAvailable()[i], 1), shorten(mi.powderTotal()[i], 1)};
        }
        int n = ExtraPages.CRYSTALS.length;
        m.crystalOk = new boolean[n];
        m.crystalTips = new List[n];
        m.crystalIcons = new ItemStack[n];
        for (int i = 0; i < n; i++) {
            m.crystalIcons[i] = ItemIcons.collectionIcon(ExtraPages.CRYSTALS[i].toUpperCase(Locale.ROOT));
            ProfileExtras.Crystal c = mi.crystals().get(ExtraPages.CRYSTALS[i]);
            String state = c == null ? "NOT_FOUND" : c.state();
            m.crystalOk[i] = state.equals("FOUND") || state.equals("PLACED");
            List<String> tip = new ArrayList<>(List.of("§l" + SbProfile.titleCase(ExtraPages.CRYSTALS[i]), "§7State: " + SbProfile.titleCase(state),
                    "§7Found: " + fmt(c == null ? 0 : c.found())));
            if (c != null && c.placed() > 0) tip.add("§7Placed: " + fmt(c.placed()));
            m.crystalTips[i] = lines(tip);
        }
        int f = mi.forge().size();
        m.forgeItems = new ItemStack[f];
        m.forgeSlot = new String[f];
        m.forgeTime = new String[f];
        m.forgeTips = new List[f];
        m.forgeEnd = new long[f];
        m.forgeName = new String[f];
        m.forgeNo = new int[f];
        m.forgeOwn = p.member.equals(net.minecraft.client.Minecraft.getInstance().getUser().getProfileId());
        JsonObject mineNodes = asObj(p.memberObj("skill_tree") == null ? new JsonObject() : p.memberObj("skill_tree"), "nodes",
                "mining" + (m.treeSelected[0] > 1 ? "_" + m.treeSelected[0] : ""));
        int quick = mineNodes != null && mineNodes.has("forge_time") ? mineNodes.get("forge_time").getAsInt() : 0;
        for (int i = 0; i < f; i++) {
            ProfileExtras.ForgeSlot slot = mi.forge().get(i);
            m.forgeItems[i] = ItemIcons.collectionIcon(slot.id());
            m.forgeSlot[i] = "§lSlot " + slot.slot();
            m.forgeNo[i] = slot.slot();
            m.forgeTime[i] = "";
            final int k = i;
            // the recipe time comes from the item repo; until it arrives the row shows no time (no recipe = Ready, as the original)
            com.killer560.hub.itembrowser.NeuItemData.future(slot.id()).handle((info, err) -> {
                forgeRow(m, k, slot, info, quick);
                return null;
            });
        }
        List<String> gl = new ArrayList<>();
        gl.add("§8Mineshaft Entered: " + fmt(mi.mineshafts()));
        for (String perk : GLACITE_PERKS) gl.add(perkLine(p, perk));
        m.glaInfo = gl.toArray(new String[0]);
        JsonObject fossils = ExtraTables.section("fossils");
        List<ItemStack> icons = new ArrayList<>();
        List<List<Component>> tips = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : fossils.entrySet()) {
            JsonObject fo = e.getValue().isJsonObject() ? e.getValue().getAsJsonObject() : new JsonObject();
            boolean have = mi.fossils().contains(e.getKey().split("_")[0]) || mi.fossils().contains(e.getKey());
            String pet = fo.has("pet") ? fo.get("pet").getAsString() : "";
            boolean inMenu = false;
            for (SbProfile.Pet pt : p.pets) if (pt.type.equals(pet)) inMenu = true;
            icons.add(have ? ItemIcons.forId(e.getKey()) : new ItemStack(McItems.GRAY_DYE));
            tips.add(lines(List.of("§l" + (fo.has("name") ? fo.get("name").getAsString() : SbProfile.titleCase(e.getKey())),
                    "§7Donated: " + (have ? "§aYes" : "§cNo"),
                    "§7In Pet Menu: " + (inMenu ? "§aYes" : "§cNo" + " §7(§a" + SbProfile.titleCase(pet) + "§7)"))));
        }
        m.fossilIcons = icons.toArray(new ItemStack[0]);
        m.fossilTips = tips.toArray(new List[0]);
        List<String> cl = new ArrayList<>();
        for (String[] c : CORPSES) cl.add("§8" + SbProfile.titleCase(c[0]) + " Corpses: " + c[1] + fmt(mi.corpses().getOrDefault(c[0], 0)));
        int max = ExtraPages.corpseMilestones().size(), cur = ExtraPages.corpseMilestone(mi.corpses());
        cl.add("§8Corpse Milestone: " + (cur >= max ? "§a" : "§c") + cur + "§8/" + max);
        m.corpses = cl.toArray(new String[0]);
        Long sacks = ex.misc == null ? null : ex.misc.sacks().get("SUSPICIOUS_SCRAP");
        m.scrapSacks = sacks == null ? 0 : sacks;
    }

    /** One forge row's time and hover (MainMiningScreen.getForge): recipe time cut by Quick Forge and Cole's Molten Forge,
     *  minus the time since the start; "Ready" at or below zero. */
    private static void forgeRow(Model m, int i, ProfileExtras.ForgeSlot slot, com.killer560.hub.itembrowser.NeuItemData.Info info, int quick) {
        long raw = info == null ? 0 : info.forgeSeconds() * 1000L;
        double cut = (1 - HotmTree.quickForgePercent(quick) / 100) * (moltenForge() ? 0.75 : 1);
        long left = (long) (raw * cut) + slot.startTime() - System.currentTimeMillis();
        String time = left <= 0 ? "§aReady" : "§7" + readable(left, 2, "min", "0s");
        String name = info != null && info.displayName() != null ? info.displayName().replaceAll("§.", "")
                : m.forgeItems[i].getHoverName().getString();
        List<String> tip = new ArrayList<>(List.of("§l" + name, "§7Time Remaining: " + time,
                "§7Started: " + new java.text.SimpleDateFormat("dd.MM HH:mm:ss").format(new java.util.Date(slot.startTime()))));
        // The original offers the reminder on the viewer's own profile while the slot is still forging.
        if (m.forgeOwn && left > 0) {
            tip.add("");
            tip.add("§aClick to set a reminder");
        }
        m.forgeName[i] = info != null && info.displayName() != null ? info.displayName() : "§7Slot " + slot.slot();
        m.forgeEnd[i] = m.forgeOwn && left > 0 ? left + System.currentTimeMillis() : 0;
        m.forgeTips[i] = lines(tip);
        m.forgeTime[i] = time;
    }

    /** The forge slot click: a reminder at its finish time, or "Process already finished!" once it is done. */
    static void forgeClick(Model m, int i) {
        long left = m.forgeEnd[i] - System.currentTimeMillis();
        if (left <= 0) {
            com.killer560.hub.util.ModChat.send("Profile Viewer", Component.literal("§cProcess already finished!"));
            return;
        }
        com.killer560.hub.util.ModChat.send("Profile Viewer", Component.literal("§7Reminder set for " + m.forgeName[i] + "§7 in §e§7"
                + readable(left, 2, "min", "0s") + "§7!"));
        com.killer560.hub.profileviewer.PvReminders.add("forge_slot_" + m.forgeNo[i], "§7Forge Process for " + m.forgeName[i] + "§7 is done!",
                m.forgeEnd[i]);
    }

    private static boolean moltenForge() {
        for (com.killer560.hub.scoreboard.ScoreboardExtraData.Candidate c : new com.killer560.hub.scoreboard.ScoreboardExtraData.Candidate[] {
                com.killer560.hub.scoreboard.ScoreboardExtraData.mayor(), com.killer560.hub.scoreboard.ScoreboardExtraData.minister()}) {
            if (c != null) for (com.killer560.hub.scoreboard.ScoreboardExtraData.Perk pk : c.perks()) if ("Molten Forge".equals(pk.name())) return true;
        }
        return false;
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
            long[] table = ExtraTables.tableLongs(ExtraTables.bundled().get(TREE_KEY[t] + "_levels"));
            LevelTables.Level lvl = LevelTables.cumulative(table, (long) p.memberNum("skill_tree.experience." + TREE_SKILL[t]), table.length);
            int treeLevel = lvl.level();
            for (int slot = 1; slot <= 5; slot++) {
                m.trees[t][slot] = tree(st, all.getAsJsonArray(TREE_KEY[t]), t, slot, treeLevel);
                m.fullTrees[t][slot] = withTiers(m.trees[t][slot], t, lvl);
            }
            m.trees[t][0] = m.trees[t][m.treeSelected[t]];
            m.fullTrees[t][0] = m.fullTrees[t][m.treeSelected[t]];
        }
    }

    /** LoadoutSkillTreeScreen's grid (showTier): the tier column (x -2) and a 5 px spacer (x -1) before the nodes. */
    @SuppressWarnings("unchecked")
    private static Tree withTiers(Tree tr, int t, LevelTables.Level lvl) {
        JsonObject all = ExtraTables.bundled().getAsJsonObject("sbpv_tiers");
        com.google.gson.JsonArray tiers = all == null ? new com.google.gson.JsonArray() : all.getAsJsonArray(TREE_KEY[t]);
        int n = tr.x.length, k = tiers.size(), level = lvl.level();
        // the node rows run 9 - y down from the top; a tier's row is the same 9 - y.
        int rows = Math.max(tr.h / 20, k);
        Tree out = new Tree(25 + tr.w, Math.max(tr.h, rows * 20), new int[n + k], new int[n + k], new ItemStack[n + k], new String[n + k], new List[n + k]);
        int shift = (rows * 20 - tr.h);
        for (int i = 0; i < n; i++) {
            out.x[i] = tr.x[i] + 25;
            out.y[i] = tr.y[i] + shift;
            out.icons[i] = tr.icons[i];
            out.counts[i] = tr.counts[i];
            out.tips[i] = tr.tips[i];
        }
        for (int j = 0; j < k; j++) {
            com.google.gson.JsonArray r = tiers.get(j).getAsJsonArray();
            int y = r.get(0).getAsInt(), tier = y + 1;
            boolean unlocked = level >= tier, unlocking = !unlocked && level + 1 >= tier;
            out.x[n + j] = 0;
            out.y[n + j] = (rows - 1 - y) * 20;
            out.icons[n + j] = new ItemStack(unlocked ? McItems.GREEN_STAINED_GLASS_PANE : unlocking ? McItems.YELLOW_STAINED_GLASS_PANE : McItems.RED_STAINED_GLASS_PANE);
            out.counts[n + j] = "";
            List<String> tip = new ArrayList<>();
            tip.add((unlocked ? "§a" : unlocking ? "§e" : "§c") + r.get(1).getAsString());
            if (unlocking) {
                float progress = lvl.xpForLevel() <= 0 ? 0 : (float) lvl.xpIntoLevel() / lvl.xpForLevel();
                int green = Math.round(25 * progress);
                tip.add("");
                tip.add("§7Progress: §e" + round(progress * 100) + "%");
                tip.add("§2§m" + " ".repeat(green) + "§f§m" + " ".repeat(25 - green) + "§r §e" + fmt(lvl.xpIntoLevel()) + "§6/§e"
                        + java.text.NumberFormat.getCompactNumberInstance(Locale.US, java.text.NumberFormat.Style.SHORT).format(lvl.xpForLevel()));
            }
            tip.add("");
            tip.add("§7Rewards");
            tip.addAll(ExtraTables.strings(r.get(2)));
            tip.add("");
            tip.add(unlocked ? "§a§lUNLOCKED" : unlocking ? "§c§lLOCKED" : "§cRequires Tier " + y);
            out.tips[n + j] = lines(tip);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Tree tree(JsonObject st, com.google.gson.JsonArray rows, int t, int slot, int treeLevel) {
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
            boolean disabled = false;
            if (type.equals("CORE")) {
                item = level >= max ? TREE_CORE_ITEMS[t][0] : level < 0 ? TREE_CORE_ITEMS[t][3] : level == 1 ? TREE_CORE_ITEMS[t][1] : TREE_CORE_ITEMS[t][2];
            } else if (type.equals("ABILITY")) {
                if (level != -1) level = abilityLevel;
                item = id.equals(selected) ? TREE_ABILITY_ITEMS[t][0] : level > 0 ? TREE_ABILITY_ITEMS[t][1] : TREE_ABILITY_ITEMS[t][2];
            } else {
                JsonElement toggle = base == null ? null : base.get("toggle_" + id);
                disabled = toggle != null && toggle.isJsonPrimitive() && !toggle.getAsBoolean();
                item = disabled ? TREE_NODE_ITEMS[t][0] : level < 0 ? TREE_NODE_ITEMS[t][3] : level >= max ? TREE_NODE_ITEMS[t][1] : TREE_NODE_ITEMS[t][2];
            }
            tr.x[i] = colList.indexOf(r.get(2).getAsInt()) * 20;
            tr.y[i] = rowList.indexOf(9 - r.get(3).getAsInt()) * 20;
            tr.icons[i] = new ItemStack(item);
            tr.counts[i] = level > 1 ? String.valueOf(level) : "";
            boolean picked = id.equals(selected);
            tr.tips[i] = SbpvTreeTips.tip(id, name, type, level, max, type.equals("ABILITY") ? !picked : disabled, picked, treeLevel);
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
            bt.add("Chocobit §e" + fmt(b[0]) + "§r, found in year §a" + fmt(b[1]) + "§r, expires in §6" + readable(until, 3, "m", "0 seconds"));
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
    /** The originals' readable times: the {@code units} biggest non-zero units of d, h, minutes, s, comma separated
     *  ({@code none} when all are zero). Chocolate Factory uses 3 units and "m", the forge 2 and "min". */
    static String readable(long ms, int units, String minute, String none) {
        long sec = Math.max(0, ms / 1000);
        long[] v = {sec / 86400, sec / 3600 % 24, sec / 60 % 60, sec % 60};
        String[] u = {"d", "h", minute, "s"};
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int i = 0; i < 4 && n < units; i++) {
            if (v[i] > 0) {
                if (n++ > 0) sb.append(", ");
                sb.append(v[i]).append(u[i]);
            }
        }
        return n == 0 ? none : sb.toString();
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
            case ProfileViewerScreen.PAGE_MINING -> drawMining(g, p, m, mx, my);
            case ProfileViewerScreen.PAGE_FARMING -> {
                return drawFarming(g, p, m, mx, my);
            }
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

    // ---------------------------------------------------------------- farming

    /** GardenResource.actualValues, in its order. */
    private static final String[] CROPS = {"WHEAT", "PUMPKIN", "POTATO_ITEM", "SUGAR_CANE", "MELON", "CARROT_ITEM", "INK_SACK:3", "NETHER_STALK",
            "CACTUS", "MUSHROOM_COLLECTION", "MOONFLOWER", "DOUBLE_PLANT", "WILD_ROSE"};
    /** GardenChip, its order; the API key is the lower-case name. */
    private static final String[] CHIPS = {"cropshot", "evergreen", "hypercharge", "mechamind", "overdrive", "quickdraw", "rarefinder", "sowledge",
            "synthesis", "vermin_vaporizer"};
    /** MedalType and its colors. */
    private static final String[] MEDALS = {"bronze", "silver", "gold", "platinum", "diamond"};
    private static final String[] MEDAL_COLOR = {"§c", "§7", "§6", "§3", "§b"};
    private static final String VISITOR_RARITY = "URLMS";
    private static final String[] VISITOR_NAME = {"§aUncommon", "§9Rare", "§6Legendary", "§dMythic", "§cSpecial"};
    private static final Identifier MINECART_SLOT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_minecart"),
            WATERING_CAN_SLOT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_watering_can"),
            HOE_SLOT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_hoe"),
            AXE_SLOT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_axe");
    /** BaseFarmingScreen's FarmingCategory: Main, Visitors, Crop, Mutations, Composter. */
    private static final List<?>[] FARMING_TIPS = {List.of(Component.literal("Main")), List.of(Component.literal("Visitors")),
            List.of(Component.literal("Crop")), List.of(Component.literal("Mutations")), List.of(Component.literal("Composter"))};
    private static final String[] COMPOSTER_UPGRADES = {"SPEED", "MULTI_DROP", "FUEL_CAP", "ORGANIC_MATTER_CAP", "COST_REDUCTION"};
    private static final String[] GREENHOUSE_UPGRADES = {"GROWTH_SPEED", "YIELD", "PLOT_LIMIT"};
    private static final java.util.Set<String> RARE_CROPS = java.util.Set.of("CROPIE", "SQUASH", "FERMENTO", "CONDENSED_FERMENTO",
            "HELIANTHUS", "CONDENSED_HELIANTHUS");
    private static ItemStack[] farmingIcons;
    /** The Gear label (armor and equipment, pets, vacuum over watering can) and the 3 x 5 / 2 x 5 contest and chip tables. */
    private static final int FARM_GEAR_W = 44 + 5 + 24 + 5 + 24, TABLE_H = 5 * 20 + 4;

    /** FarmingScreen and CropScreen, all text and hovers built off the render thread. */
    private static final class Farm {
        String[] info, visitors;
        List<Component>[] infoTips, visitorTips;
        /** Visitors lines followed by a 5 px gap (after Unique Served and after the last rarity). */
        int[] visitorGaps;
        final ItemStack[] contestIcons = new ItemStack[CROPS.length], chipIcons = new ItemStack[CHIPS.length];
        final List<Component>[] contestTips = new List[CROPS.length], chipTips = new List[CHIPS.length];
        final String[] chipText = new String[CHIPS.length];
        final ItemStack[] pets = new ItemStack[4];
        final String[] petLevels = new String[4];
        ItemStack[] queue;
        List<Component>[] queueTips;
        /** VisitorScreen's and MutationScreen's grouped slots. */
        List<GCell> visitorCells, mutationCells;
        ItemStack mutationTab, composterTab;
        int queueRows;
        /** ComposterScreen: Information and Greenhouse lines, the six upgrade slots, the 5 x 5 plot map (row-major, top row first). */
        String[] compInfo, greenhouse;
        List<Component>[] greenhouseTips;
        final ItemStack[] compUps = new ItemStack[6], plots = new ItemStack[25];
        final String[] compUpText = new String[6];
        final List<Component>[] compUpTips = new List[6], plotTips = new List[25];
        /** Crop: [crop][0 upgrade, 1 milestone]; the tool's empty icon per crop. */
        final ItemStack[][] crop = new ItemStack[CROPS.length][2];
        final String[][] cropText = new String[CROPS.length][2];
        final List<Component>[][] cropTips = new List[CROPS.length][2];
        final Identifier[] toolEmpty = new Identifier[CROPS.length];
        /** {armor+equipment Gear, vacuum, watering can, ItemStack[] best tool per crop}. */
        CompletableFuture<Object[]> gear;
    }

    /** A grouped slot: rarity group (index in GROUP_ORDER, -1 = none, drawn last), slot tint, hover, the filters it shows in
     *  (bit per filter), the search text (null = always matches), the real icon and the one drawn when {@code flag} says so. */
    private record GCell(int group, int color, List<Component> tip, int filters, String search, boolean flag, ItemStack icon, ItemStack dim) {
    }

    private static final List<String> GROUP_ORDER = List.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL",
            "VERY_SPECIAL");
    /** Each rarity's color, and the darker one VisitorScreen and MutationScreen tint a slot not visited / not analyzed. */
    private static final int[] GROUP_COLOR = {0xFFFFFF, 0x55FF55, 0x5555FF, 0xAA00AA, 0xFFAA00, 0xFF55FF, 0x55FFFF, 0xFF5555, 0xFF5555};
    private static final int GRAY = 0xAAAAAA, DARK_GRAY = 0x555555;
    private static final String[][] GROUP_FILTERS = {{"All", "Never Visited", "Visited", "Visited (None Completed)", "Offer Completed"},
            {"All", "Undiscovered", "Discovered", "Discovered (not analyzed)", "Analyzed"}};
    private static final String[] GROUP_EMPTY = {"No Visitor matches the input!", "No mutation matches the input!"};
    private final int[] groupFilter = new int[2];

    /** A visitor's look: its skin from the bundled table (texture hash), else its item, else a plain head. */
    private static ItemStack visitorIcon(JsonObject skins, String id) {
        String v = skins != null && skins.has(id) ? skins.get(id).getAsString() : null;
        if (v == null) return new ItemStack(Items.PLAYER_HEAD);
        if (v.startsWith("@")) return ItemIcons.forItem(v.substring(1));
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + v + "\"}}}";
        return LegacyItems.skull(java.util.Base64.getEncoder().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)), null);
    }

    /** VisitorScreen (every repo visitor with its commission, then unknown ones) and MutationScreen (analyzed, discovered and
     *  repo crops, distinct) as grouped slots. */
    private static void buildGrids(Farm f, SbProfile p, JsonObject t, JsonObject garden) {
        JsonObject skins = t.has("visitor_skins") ? t.getAsJsonObject("visitor_skins") : null;
        JsonObject names = t.has("visitors") ? t.getAsJsonObject("visitors") : new JsonObject();
        JsonObject comm = SbProfile.asObj(garden.get("commission_data"));
        JsonObject visits = comm == null ? null : SbProfile.asObj(comm.get("visits")), done = comm == null ? null : SbProfile.asObj(comm.get("completed"));
        List<GCell> vc = new ArrayList<>();
        java.util.Set<String> known = new java.util.HashSet<>();
        String rarities = "URLMS";
        String[] rarityNames = {"UNCOMMON", "RARE", "LEGENDARY", "MYTHIC", "SPECIAL"};
        int[] notVisited = {0x00AA00, 0x0000AA, 0xFFFF55, 0xAA00AA, 0xAA0000};
        for (JsonElement idEl : t.has("visitor_order") ? t.getAsJsonArray("visitor_order") : new JsonArray()) {
            String id = idEl.getAsString(), nm = names.has(id) ? names.get(id).getAsString() : "U" + id;
            known.add(id);
            int r = Math.max(0, rarities.indexOf(nm.charAt(0)));
            int group = GROUP_ORDER.indexOf(rarityNames[r]);
            boolean has = visits != null && visits.has(id);
            vc.add(visitorCell(group, has ? GROUP_COLOR[group] : notVisited[r], VISITOR_NAME[r].substring(0, 2) + nm.substring(1), id, rarityNames[r],
                    has ? (int) SbProfile.num(visits.get(id)) : -1, (int) gnum(done, id), visitorIcon(skins, id), new ItemStack(McItems.GRAY_DYE)));
        }
        if (visits != null) {
            for (Map.Entry<String, JsonElement> e : visits.entrySet()) {
                if (known.contains(e.getKey())) continue;
                vc.add(visitorCell(-1, GRAY, "§7" + e.getKey(), null, null, (int) SbProfile.num(e.getValue()), (int) gnum(done, e.getKey()),
                        new ItemStack(Items.BARRIER), new ItemStack(Items.BARRIER)));
            }
        }
        f.visitorCells = vc;
        JsonObject gp = p.memberObj("garden_player_data");
        java.util.Set<String> analyzed = lowerSet(gp, "analyzed_greenhouse_crops"), discovered = lowerSet(gp, "discovered_greenhouse_crops");
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(analyzed);
        all.addAll(discovered);
        Map<String, JsonArray> data = new HashMap<>();
        for (JsonElement m : t.has("mutations") ? t.getAsJsonArray("mutations") : new JsonArray()) {
            JsonArray a = m.getAsJsonArray();
            String id = a.get(0).getAsString().toLowerCase(Locale.ROOT);
            all.add(id);
            data.put(id, a);
        }
        String[] tint = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "SPECIAL"};
        int[] tintColor = {GRAY, 0x00AA00, 0x0000AA, 0xFF55FF, 0xFFFF55, 0xAA00AA, 0xAA0000};
        List<GCell> mc = new ArrayList<>();
        for (String id : all) {
            JsonArray d = data.get(id);
            boolean analyzable = d == null || d.get(3).getAsInt() != 0;
            boolean an = analyzed.contains(id) || !analyzable, disc = discovered.contains(id);
            int group = d == null ? -1 : GROUP_ORDER.indexOf(d.get(2).getAsString());
            int ti = d == null ? -1 : List.of(tint).indexOf(d.get(2).getAsString());
            int color = an && d == null ? DARK_GRAY : !an && ti >= 0 ? tintColor[ti] : group >= 0 ? GROUP_COLOR[group] : DARK_GRAY;
            ItemStack base = d == null ? null : ItemIcons.catalogIcon(d.get(0).getAsString());
            List<Component> tip = new ArrayList<>();
            tip.add(base != null ? base.getHoverName() : Component.literal(id));
            tip.add(Component.empty());
            tip.add(Component.literal("§7Discovered: " + (disc ? "§aYes" : "§cNo")));
            tip.add(Component.literal("§7Analyzed: " + (!analyzable ? "§6N/A" : an ? "§aYes" : "§cNo")));
            int filters = 1 | (disc ? 0 : 2) | (disc ? 4 : 0) | (disc && !an ? 8 : 0) | (an ? 16 : 0);
            ItemStack dim = d == null ? new ItemStack(Items.BARRIER) : new ItemStack(McItems.GRAY_DYE);
            mc.add(new GCell(group, color, List.copyOf(tip), filters, null, disc, base == null ? dim : base, dim));
        }
        f.mutationCells = mc;
        f.mutationTab = ItemIcons.catalogIcon("LONELILY");
    }

    private static GCell visitorCell(int group, int color, String name, String id, String rarity, int visits, int accepted, ItemStack icon, ItemStack dim) {
        List<String> tip = new ArrayList<>(List.of(name));
        if (visits >= 0) {
            tip.add("");
            tip.add("§7Visits: §a" + visits);
            tip.add("§7Accepted: §a" + accepted);
            tip.add("§7Rejected: §a" + (visits - accepted));
        }
        boolean has = visits >= 0;
        int filters = 1 | (has ? 0 : 2) | (has && visits >= 1 ? 4 : 0) | (has && visits >= 1 && accepted <= 0 ? 8 : 0) | (has && accepted >= 1 ? 16 : 0);
        String search = id == null ? null : (name.replaceAll("§.", "") + "|" + id + "|" + rarity).toLowerCase(Locale.ROOT);
        return new GCell(group, color, lines(tip), filters, search, has, icon, dim);
    }

    private static java.util.Set<String> lowerSet(JsonObject o, String key) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (o != null && o.has(key) && o.get(key).isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray(key)) if (e.isJsonPrimitive()) out.add(e.getAsString().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** GroupedScreen: a 100 px search box and a 100 px Filter button (left click next, right click back) 20 below the top,
     *  then the matching slots grouped by rarity (rarest last, none at the end), width / 24 to a row, 4 between groups,
     *  centered in the area 45 below the top and 5 above the bottom, scrolled when taller. {@code kind} 0 visitors, 1 mutations. */
    @SuppressWarnings("unchecked")
    private void drawGrouped(GuiGraphicsExtractor g, List<GCell> cells, int kind, int mx, int my) {
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        int rx = bx + (bw - 205) / 2, ry = by + 20;
        String query = s.pageSearch(rx, ry);
        int fx = rx + 105, filter = groupFilter[kind];
        boolean onFilter = hover(mx, my, fx, ry, 100, 20);
        PvStyleChrome.button(g, s.pvStyle(), fx, ry, 100, 20, "Filter", onFilter, false);
        String[] names = GROUP_FILTERS[kind];
        s.hotspots.add(new ProfileViewerScreen.Hotspot(fx, ry, 100, 20, () -> { groupFilter[kind] = (groupFilter[kind] + 1) % names.length; s.pageScroll = 0; }));
        s.rightHotspots.add(new ProfileViewerScreen.Hotspot(fx, ry, 100, 20,
                () -> { groupFilter[kind] = (groupFilter[kind] + names.length - 1) % names.length; s.pageScroll = 0; }));
        if (onFilter) {
            List<Component> tip = new ArrayList<>(List.of(Component.literal("§7Click to cycle through the filters!"),
                    Component.literal("§8Left Click / Right Click"), Component.empty()));
            for (int i = 0; i < names.length; i++) tip.add(Component.literal(i == filter ? "§7> §a" + names[i] : " §c" + names[i]));
            s.pendingTooltip = tip;
        }
        int perRow = Math.max(1, bw / 24), fy = by + 45, fh = bh - 50;
        List<List<GCell>> groups = new ArrayList<>();
        for (int gi = 0; gi <= GROUP_ORDER.size(); gi++) {
            int want = gi == GROUP_ORDER.size() ? -1 : gi;
            List<GCell> in = new ArrayList<>();
            for (GCell c : cells) {
                if (c.group == want && (c.filters >> filter & 1) != 0 && (query.isEmpty() || c.search == null || c.search.contains(query))) in.add(c);
            }
            if (!in.isEmpty()) groups.add(in);
        }
        if (groups.isEmpty()) {
            String t = GROUP_EMPTY[kind];
            g.text(font(), t, bx + (bw - font().width(t)) / 2, fy + fh / 2 - 4, 0xFFFF5555, false);
            return;
        }
        int total = -4;
        for (List<GCell> in : groups) total += 4 + (in.size() + perRow - 1) / perRow * 20;
        s.pageScroll = Math.max(0, Math.min(s.pageScroll, Math.max(0, total - fh + 9) / 10));
        int y = total <= fh ? fy + (fh - total) / 2 : fy - s.pageScroll * 10;
        g.enableScissor(bx, fy, bx + bw, fy + fh);
        for (List<GCell> in : groups) {
            for (int r = 0; r * perRow < in.size(); r++) {
                int n = Math.min(perRow, in.size() - r * perRow), x = bx + (bw - n * 20) / 2;
                for (int k = 0; k < n; k++) {
                    GCell c = in.get(r * perRow + k);
                    boolean real = kind == 0 ? c.flag || filter == 1 : c.flag && filter != 1;
                    PvStyleChrome.slot(g, s.pvStyle(), x, y, 20, 0xFF000000 | c.color);
                    g.item(real ? c.icon : c.dim, x + 2, y + 2);
                    if (my >= fy && my < fy + fh && hover(mx, my, x, y, 20, 20)) s.pendingTooltip = c.tip;
                    x += 20;
                }
                y += 20;
            }
            y += 4;
        }
        g.disableScissor();
    }

    private static JsonObject gardenTable() {
        JsonObject t = ExtraTables.bundled().getAsJsonObject("sbpv_garden");
        return t == null ? new JsonObject() : t;
    }

    /** The original's cum_int_list: running sums from 0 with repeats dropped. */
    private static long[] cumulative(long[] steps) {
        long[] out = new long[steps.length + 1];
        int n = 1;
        for (long v : steps) {
            long sum = out[n - 1] + v;
            if (sum != out[n - 1]) out[n++] = sum;
        }
        return Arrays.copyOf(out, n);
    }

    /** Index of the last bracket at or below {@code v}, -1 when none. */
    private static int lastAtMost(long[] b, long v) {
        int i = -1;
        for (int k = 0; k < b.length; k++) if (b[k] <= v) i = k;
        return i;
    }

    private static String pct(double part, double whole) {
        return round((float) part / (float) whole * 100);
    }

    /** An item's name in its rarity color (the original's lazy item stack name), "Unknown Item" when the catalog lacks it. */
    private static String itemName(String id, boolean colored) {
        SkyblockItemEntry e = SkyblockItemRepository.findById(id);
        return e == null ? "Unknown Item" : (colored ? SkyblockItemStackFactory.tierColorCode(e.tier()) : "") + e.name();
    }

    private static long gnum(JsonObject o, String key) {
        return o == null ? 0 : (long) SbProfile.num(o.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Farm buildFarm(SbProfile p, ProfileExtras.Farming fa, JsonObject garden) {
        Farm f = new Farm();
        JsonObject t = gardenTable();
        JsonObject upgrades = SbProfile.asObj(garden.get("garden_upgrades"));
        // Information
        long xp = gnum(garden, "garden_experience");
        long[] br = cumulative(ExtraTables.tableLongs(ExtraTables.section("garden").get("garden_level")));
        int lvl = Math.max(0, lastAtMost(br, xp)) + 1;
        long total = br[br.length - 1];
        List<String> lvlTip = new ArrayList<>();
        if (lvl != br.length) {
            long req = lvl >= br.length - 1 ? 0 : br[lvl] - br[Math.max(lvl - 1, 0)], cur = xp - br[Math.max(lvl - 1, 0)];
            lvlTip.add("§7To lvl " + lvl + ": §2" + cur + "§a/§2" + req + (req != 0 ? "§7 (§3" + pct(cur, req) + "%§7)" : ""));
            lvlTip.add("§7To max: §2" + fmt(xp) + "§a/§2" + fmt(total) + "§7 (§3" + pct(xp, total) + "%§7)");
        } else {
            lvlTip.add("§7Total xp: §2" + fmt(xp - total));
        }
        int larvaMax = ExtraTables.section("garden").has("max_larva_consumed") ? ExtraTables.section("garden").get("max_larva_consumed").getAsInt() : 5;
        Map<String, Integer> inv = fa == null ? Map.of() : fa.medalInventory();
        f.info = new String[] {"§8Copper: §c" + fmt(fa == null ? 0 : fa.copper()), "§8Garden Level: §2" + lvl,
                "§8Contests Participated: §e" + (fa == null ? 0 : fa.contests()),
                "§8Medals: §c" + fmt(inv.getOrDefault("bronze", 0)) + "§8/§7" + fmt(inv.getOrDefault("silver", 0)) + "§8/§6" + fmt(inv.getOrDefault("gold", 0)),
                "§8Larva Consumed: " + (fa != null && fa.larvaConsumed() == larvaMax ? "§a" : "§c") + (fa == null ? 0 : fa.larvaConsumed()) + "§8/" + larvaMax,
                null, null};
        f.infoTips = new List[7];
        f.infoTips[1] = lines(lvlTip);
        String[] perkNames = {"Farming Level Cap", "Double Drops"};
        String[] perkKeys = {"level_cap", "bonus_drops"};
        int[] perkLevels = {fa == null ? 0 : fa.levelCapPerk(), fa == null ? 0 : fa.doubleDrops()};
        for (int k = 0; k < 2; k++) {
            JsonArray values = t.has(perkKeys[k]) ? t.getAsJsonArray(perkKeys[k]) : new JsonArray();
            int pl = perkLevels[k], size = values.size();
            f.info[5 + k] = "§8" + perkNames[k] + ": " + (pl == size ? "§a" : "§c") + pl + "§8/" + size;
            if (pl == size || size == 0) continue;
            JsonObject paid = pl > 1 ? values.get(pl - 1).getAsJsonObject() : new JsonObject();
            List<String> tip = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : values.get(size - 1).getAsJsonObject().entrySet()) {
                long need = e.getValue().getAsLong(), have = gnum(paid, e.getKey());
                tip.add("§7" + (e.getKey().equals("gold_medal") ? "Gold Medal" : itemName(e.getKey(), false)) + ": §2" + fmt(have) + "§a/§2" + fmt(need)
                        + "§7 (§3" + pct(have, need) + "%§7)");
            }
            f.infoTips[5 + k] = lines(tip);
        }
        buildVisitors(f, t, garden, upgrades);
        buildContests(p, fa, f, t);
        // Chips
        long[] chips = ExtraTables.tableLongs(t.get("chips"));
        long chipMax = Arrays.stream(chips).max().orElse(1);
        for (int i = 0; i < CHIPS.length; i++) {
            int level = (int) p.memberNum("player_data.garden_chips." + CHIPS[i]);
            String color = level == 0 ? "§c" : level <= 10 ? "§9" : level <= 15 ? "§5" : "§6";
            String id = CHIPS[i].toUpperCase(Locale.ROOT) + "_GARDEN_CHIP";
            f.chipIcons[i] = ItemIcons.collectionIcon(id);
            f.chipText[i] = color + level;
            int idx = Math.max(0, Math.min(level - 1, 20));
            long spent = idx < chips.length ? chips[idx] : 0;
            String sow = "§7Sowdust: " + (spent == 0 ? "§c" : spent == chipMax ? "§2" : "§a") + fmt(spent) + "§7/§2" + shorten(chipMax, 3) + "§7 (§3"
                    + pct(spent, chipMax) + "%§7)";
            String head = color + NeuItemData.nameFor(id);
            f.chipTips[i] = lines(List.of(head, "", "", sow));
            final int slot = i;
            // the chip's ability text: its lore from the "Ability: " line to the next blank line
            NeuItemData.future(id).thenAccept(info -> {
                if (info == null) return;
                List<String> out = new ArrayList<>(List.of(head, ""));
                boolean on = false;
                for (String l : info.lore()) {
                    String plain = l.replaceAll("§.", "");
                    if (!on && plain.startsWith("Ability: ")) on = true;
                    else if (on && plain.isEmpty()) break;
                    if (on) out.add(l);
                }
                out.add("");
                out.add(sow);
                f.chipTips[slot] = lines(out);
            });
        }
        // Pets: the farming pets, lowest rarity first then most exp, one per type
        List<String> petIds = ExtraTables.strings(ExtraTables.bundled().getAsJsonObject("sbpv_gear").getAsJsonObject("farming").get("pets"));
        List<SbProfile.Pet> pets = new ArrayList<>();
        for (SbProfile.Pet pet : p.pets) if (petIds.contains(pet.type)) pets.add(pet);
        pets.sort(Comparator.<SbProfile.Pet>comparingInt(x -> LevelTables.RARITIES.indexOf(x.tier)).thenComparing(x -> -x.exp));
        java.util.Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (SbProfile.Pet pet : pets) {
            if (n == 4) break;
            if (!seen.add(pet.type)) continue;
            f.pets[n] = pet.icon();
            f.petLevels[n++] = String.valueOf(pet.level.level());
        }
        buildCrops(f, t, garden);
        buildGrids(f, p, t, garden);
        buildComposter(f, t, garden);
        return f;
    }

    /** ComposterScreen's content from the garden and the bundled composter, greenhouse and plot tables. */
    @SuppressWarnings("unchecked")
    private static void buildComposter(Farm f, JsonObject t, JsonObject garden) {
        f.composterTab = ItemIcons.catalogIcon("COMPOST");
        JsonObject cd = SbProfile.asObj(garden.get("composter_data"));
        JsonObject cu = cd == null ? null : SbProfile.asObj(cd.get("upgrades"));
        long last = gnum(cd, "last_save");
        f.compInfo = new String[] {"Organic Matter Stored: §a" + fmt(cd == null ? 0 : SbProfile.num(cd.get("organic_matter"))),
                "Fuel Stored: §2" + fmt(cd == null ? 0 : SbProfile.num(cd.get("fuel_units"))),
                "Compost Stored: §c" + fmt(gnum(cd, "compost_items")),
                "Last Update: §8" + java.time.format.DateTimeFormatter.ofPattern("uuuu/MM/dd HH:mm:ss")
                        .format(java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(last), java.time.ZoneId.systemDefault()))};
        JsonArray slots = garden.has("greenhouse_slots") && garden.get("greenhouse_slots").isJsonArray() ? garden.getAsJsonArray("greenhouse_slots") : new JsonArray();
        JsonObject gu = SbProfile.asObj(garden.get("garden_upgrades"));
        JsonObject gh = t.has("greenhouse") ? t.getAsJsonObject("greenhouse") : new JsonObject();
        List<String> gl = new ArrayList<>(List.of("Spaces: §a" + (slots.size() + 12) + "§2/§a100"));
        List<List<Component>> gt = new ArrayList<>();
        gt.add(null);
        for (String key : GREENHOUSE_UPGRADES) {
            JsonObject d = gh.has(key) ? gh.getAsJsonObject(key) : null;
            if (d == null) continue;
            int level = (int) gnum(gu, key), size = d.getAsJsonArray("upgrades").size();
            gl.add("§8" + d.get("name").getAsString().replaceAll("§.", "") + ": §a" + level + "§2/§a" + size);
            List<String> tip = new ArrayList<>(List.of(d.get("name").getAsString(), ""));
            tip.addAll(upgradeLines(d, level));
            tip.add("");
            tip.addAll(progress(d, level).values());
            gt.add(lines(tip));
        }
        f.greenhouse = gl.toArray(new String[0]);
        f.greenhouseTips = gt.toArray(new List[0]);
        JsonObject comp = t.has("composter") ? t.getAsJsonObject("composter") : new JsonObject();
        for (int i = 0; i < COMPOSTER_UPGRADES.length; i++) {
            JsonObject d = comp.has(COMPOSTER_UPGRADES[i]) ? comp.getAsJsonObject(COMPOSTER_UPGRADES[i]) : null;
            int level = (int) gnum(cu, COMPOSTER_UPGRADES[i].toLowerCase(Locale.ROOT));
            if (d == null) {
                f.compUps[i] = new ItemStack(Items.BARRIER);
                f.compUpTips[i] = lines(List.of("§cError", "Data for " + COMPOSTER_UPGRADES[i] + " was null which shouldn't have happened"));
                continue;
            }
            int size = d.getAsJsonArray("upgrades").size();
            Identifier itemId = Identifier.tryParse(d.get("item").getAsString());
            f.compUps[i] = itemId == null ? new ItemStack(Items.BARRIER)
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(itemId).map(ItemStack::new).orElseGet(() -> new ItemStack(Items.BARRIER));
            f.compUpText[i] = String.valueOf(level);
            List<String> tip = new ArrayList<>(List.of("§l" + d.get("name").getAsString()));
            tip.addAll(upgradeLines(d, level));
            tip.add("");
            tip.add("§7Level §a" + level + "§2/§a" + size);
            tip.add("");
            Map<String, String> map = progress(d, level);
            tip.add("§7Copper needed");
            tip.add(map.getOrDefault("copper", ":("));
            tip.add("");
            tip.add("§7Crops needed");
            for (Map.Entry<String, String> e : map.entrySet()) if (!RARE_CROPS.contains(e.getKey()) && !e.getKey().equalsIgnoreCase("copper")) tip.add(e.getValue());
            tip.add("");
            tip.add("§7Rare Crops needed");
            for (Map.Entry<String, String> e : map.entrySet()) if (RARE_CROPS.contains(e.getKey())) tip.add(e.getValue());
            f.compUpTips[i] = lines(tip);
        }
        // The Compost Speed attribute (a shard attribute; its level is kept with the composter's upgrades).
        int attr = (int) gnum(cu, "compost_speed_attribute");
        f.compUps[5] = ItemIcons.catalogIcon("ATTRIBUTE_SHARD");
        f.compUpText[5] = String.valueOf(attr);
        f.compUpTips[5] = lines(List.of("§aCompost Speed", "§7Level: §a" + attr));
        // Plots: every plot black (its name), unlocked ones green, a locked one shows its next cost; the barn skin in the middle.
        JsonArray plots = t.has("plots") ? t.getAsJsonArray("plots") : new JsonArray();
        java.util.Set<String> open = new java.util.HashSet<>();
        if (garden.has("unlocked_plots_ids") && garden.get("unlocked_plots_ids").isJsonArray()) {
            for (JsonElement e : garden.getAsJsonArray("unlocked_plots_ids")) open.add(e.getAsString());
        }
        Map<String, Integer> openByType = new java.util.HashMap<>();
        for (JsonElement e : plots) {
            String id = e.getAsJsonArray().get(0).getAsString();
            if (open.contains(id)) openByType.merge(id.substring(0, id.indexOf('_')), 1, Integer::sum);
        }
        JsonObject costs = t.has("plot_cost") ? t.getAsJsonObject("plot_cost") : new JsonObject();
        ItemStack black = new ItemStack(McItems.BLACK_STAINED_GLASS_PANE), green = new ItemStack(McItems.GREEN_STAINED_GLASS_PANE);
        for (JsonElement e : plots) {
            JsonArray pl = e.getAsJsonArray();
            String id = pl.get(0).getAsString(), type = id.substring(0, id.indexOf('_'));
            int cell = (4 - pl.get(2).getAsInt()) * 5 + pl.get(1).getAsInt();
            String num = pl.get(3).getAsString();
            if (open.contains(id)) {
                f.plots[cell] = green;
                f.plotTips[cell] = lines(List.of("§aPlot§7 - §b" + num));
                continue;
            }
            f.plots[cell] = black;
            JsonArray cost = costs.has(type) ? costs.getAsJsonArray(type) : new JsonArray();
            int have = openByType.getOrDefault(type, 0);
            if (have >= cost.size()) {
                f.plotTips[cell] = lines(List.of("§ePlot§7 - §b" + num));
            } else {
                JsonObject c = cost.get(have).getAsJsonObject();
                boolean bundle = c.get("bundle").getAsBoolean();
                f.plotTips[cell] = lines(List.of("§ePlot§7 - §b" + num, itemName(bundle ? "ENCHANTED_COMPOST" : "COMPOST", true) + "§8 x" + c.get("amount").getAsInt()));
            }
        }
        JsonObject skins = t.has("barn_skins") ? t.getAsJsonObject("barn_skins") : new JsonObject();
        String skin = garden.has("selected_barn_skin") ? garden.get("selected_barn_skin").getAsString() : "";
        JsonArray sk = skins.has(skin) ? skins.getAsJsonArray(skin) : null;
        f.plots[12] = sk == null ? new ItemStack(Items.BARRIER) : ItemIcons.forId(sk.get(1).getAsString());
        f.plotTips[12] = lines(List.of(sk == null ? "§cUnknown Barn Skin (" + skin + ")" : sk.get(0).getAsString()));
    }

    /** StaticComposterData.getTooltipForLevel: the tooltip with its reward (PERCENTAGE as a whole number, INTEGER grouped). */
    private static List<String> upgradeLines(JsonObject d, int level) {
        double reward = (int) SbpvTreeTips.eval(d.get("formula").getAsString(), Map.of("level", (double) level));
        String r = "INTEGER".equals(d.get("format").getAsString()) ? fmt(reward) : round(reward);
        return List.of(d.get("tooltip").getAsString().replace("%reward%", r).split("\n"));
    }

    /** ComposterScreen.getProgressMap: per item of the max level, used of needed and the percent, keyed by item. */
    private static Map<String, String> progress(JsonObject d, int level) {
        JsonArray ups = d.getAsJsonArray("upgrades");
        JsonObject cur = level != 0 && level <= ups.size() ? ups.get(level - 1).getAsJsonObject() : new JsonObject();
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> need : ups.get(ups.size() - 1).getAsJsonObject().entrySet()) {
            long used = gnum(cur, need.getKey()), max = need.getValue().getAsLong();
            out.put(need.getKey(), "§7" + (need.getKey().equalsIgnoreCase("copper") ? "§cCopper" : itemName(need.getKey(), true)) + "§7 §e" + fmt(used)
                    + "§6/§e" + fmt(max) + " §7(§3" + pct(used, max) + "%§7)");
        }
        return out;
    }

    private static final int PLOTS_W = 5 * 20 + 4, UPS_W = 6 * 20 + 4;

    /** ComposterScreen: Plots beside Information, Greenhouse and Upgrades (10 apart); stacked and scrolled when it does not fit. */
    private void drawComposter(GuiGraphicsExtractor g, Farm f, int mx, int my) {
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        int infoCW = widest(f.compInfo), ghCW = widest(f.greenhouse);
        int plotsW = labelW(PLOTS_W, 0), plotsH = labelH(PLOTS_W), infoW = labelW(infoCW, 0), infoH = labelH(f.compInfo.length * 9),
                ghW = labelW(ghCW, 0), ghH = labelH(f.greenhouse.length * 9), upW = labelW(UPS_W, 0), upH = labelH(24);
        int colW = Math.max(infoW, Math.max(ghW, upW)), colH = infoH + 10 + ghH + 10 + upH;
        int w = plotsW + colW, h = Math.max(plotsH, colH);
        g.enableScissor(bx, by, bx + bw, by + bh);
        if (w <= bw && h <= bh) {
            int x = bx + (bw - w) / 2, y = by + (bh - h) / 2;
            composterPlots(g, f, x, y + (h - plotsH) / 2, mx, my);
            x += plotsW;
            int y2 = y + (h - colH) / 2;
            composterInfo(g, f.compInfo, null, "Information", x + (colW - infoW) / 2, y2, infoCW, mx, my);
            composterInfo(g, f.greenhouse, f.greenhouseTips, "Greenhouse", x + (colW - ghW) / 2, y2 + infoH + 10, ghCW, mx, my);
            composterUpgrades(g, f, x + (colW - upW) / 2, y2 + infoH + 10 + ghH + 10, mx, my);
        } else {
            int total = plotsH + 5 + upH + 5 + ghH + 5 + infoH;
            s.pageScroll = Math.max(0, Math.min(s.pageScroll, Math.max(0, total - bh + 9) / 10));
            int y = by - s.pageScroll * 10;
            composterPlots(g, f, bx + (bw - plotsW) / 2, y, mx, my);
            y += plotsH + 5;
            composterUpgrades(g, f, bx + (bw - upW) / 2, y, mx, my);
            y += upH + 5;
            composterInfo(g, f.greenhouse, f.greenhouseTips, "Greenhouse", bx + (bw - ghW) / 2, y, ghCW, mx, my);
            composterInfo(g, f.compInfo, null, "Information", bx + (bw - infoW) / 2, y + ghH + 5, infoCW, mx, my);
        }
        g.disableScissor();
    }

    private void composterPlots(GuiGraphicsExtractor g, Farm f, int x, int y, int mx, int my) {
        int[] c = label(g, "Plots", null, x, y, PLOTS_W, PLOTS_W, 0);
        for (int i = 0; i < 25; i++) {
            ItemStack st = f.plots[i] == null ? ItemStack.EMPTY : f.plots[i];
            slotItem(g, st, null, c[0] + 2 + i % 5 * 20, c[1] + 2 + i / 5 * 20, f.plotTips[i], mx, my);
        }
    }

    private void composterInfo(GuiGraphicsExtractor g, String[] l, List<Component>[] tips, String title, int x, int y, int w, int mx, int my) {
        int[] c = label(g, title, null, x, y, w, l.length * 9, 0);
        textLines(g, l, tips, c[0], c[1], 0, mx, my);
    }

    private void composterUpgrades(GuiGraphicsExtractor g, Farm f, int x, int y, int mx, int my) {
        int[] c = label(g, "Upgrades", null, x, y, UPS_W, 24, 0);
        for (int i = 0; i < 6; i++) slotItem(g, f.compUps[i], f.compUpText[i], c[0] + 2 + i * 20, c[1] + 2, f.compUpTips[i], mx, my);
    }

    @SuppressWarnings("unchecked")
    private static void buildVisitors(Farm f, JsonObject t, JsonObject garden, JsonObject upgrades) {
        JsonObject comm = SbProfile.asObj(garden.get("commission_data"));
        JsonObject visits = comm == null ? null : SbProfile.asObj(comm.get("visits")), done = comm == null ? null : SbProfile.asObj(comm.get("completed"));
        JsonObject names = t.has("visitors") ? t.getAsJsonObject("visitors") : new JsonObject();
        long[][] sums = new long[VISITOR_RARITY.length() + 1][2];
        if (visits != null) {
            for (Map.Entry<String, JsonElement> e : visits.entrySet()) {
                long v = (long) SbProfile.num(e.getValue()), c = gnum(done, e.getKey());
                sums[0][0] += c;
                sums[0][1] += v;
                int r = names.has(e.getKey()) ? VISITOR_RARITY.indexOf(names.get(e.getKey()).getAsString().charAt(0)) : -1;
                if (r >= 0) {
                    sums[r + 1][0] += c;
                    sums[r + 1][1] += v;
                }
            }
        }
        List<String> l = new ArrayList<>();
        List<List<Component>> tips = new ArrayList<>();
        long nonSpecial = 0;
        JsonArray rarities = t.has("visitor_rarities") ? t.getAsJsonArray("visitor_rarities") : new JsonArray();
        for (JsonElement r : rarities) if (!r.getAsJsonArray().get(0).getAsString().equals("SPECIAL")) nonSpecial += r.getAsJsonArray().get(1).getAsLong();
        l.add("§8Unique Served: §a" + fmt(gnum(comm, "unique_npcs_served")) + "§8/§a" + fmt(nonSpecial));
        tips.add(List.of(Component.literal("§7Excluding §cSpecial§7 Visitors.")));
        for (int k = -1; k < rarities.size(); k++) {
            int r = k < 0 ? -1 : VISITOR_RARITY.indexOf(rarities.get(k).getAsJsonArray().get(0).getAsString().charAt(0));
            long[] v = sums[r + 1];
            l.add((r < 0 ? "§8Total" : VISITOR_NAME[r]) + "§8: §a" + fmt(v[0]) + "§8/§a" + fmt(v[1]));
            tips.add(lines(List.of("§7Offers Accepted: §a" + fmt(v[0]), "§7Times Visited: §a" + fmt(v[1]))));
        }
        int afterRarities = l.size() - 1;
        JsonObject vu = t.has("vupgrades") ? t.getAsJsonObject("vupgrades") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : vu.entrySet()) {
            JsonObject d = e.getValue().getAsJsonObject();
            JsonArray ups = d.getAsJsonArray("upgrades");
            int level = (int) gnum(upgrades, e.getKey());
            String name = d.get("name").getAsString();
            l.add("§8" + name.replaceAll("§.", "") + ": §a" + level + "§2/§a" + ups.size());
            List<String> tip = new ArrayList<>(List.of(name, "",
                    d.get("tooltip").getAsString().replace("%reward%", fmt((int) SbpvTreeTips.eval(d.get("formula").getAsString(), Map.of("level", (double) level)))), ""));
            JsonObject cur = level != 0 && level <= ups.size() ? ups.get(level - 1).getAsJsonObject() : new JsonObject();
            for (Map.Entry<String, JsonElement> need : ups.get(ups.size() - 1).getAsJsonObject().entrySet()) {
                long used = gnum(cur, need.getKey()), max = need.getValue().getAsLong();
                tip.add("§7" + (need.getKey().equalsIgnoreCase("copper") ? "§cCopper" : itemName(need.getKey(), true)) + "§7 §e" + fmt(used) + "§6/§e" + fmt(max)
                        + " §7(§3" + pct(used, max) + "%§7)");
            }
            tips.add(lines(tip));
        }
        f.visitors = l.toArray(new String[0]);
        f.visitorTips = tips.toArray(new List[0]);
        f.visitorGaps = new int[] {0, afterRarities};
        // Visitor Queue: 5 wide, one row (two with Max Occupancy), each active visitor's hover
        f.queueRows = Math.max(5, Math.min(6, 5 + (int) gnum(upgrades, "MAX_OCCUPANCY"))) - 4;
        JsonObject active = SbProfile.asObj(garden.get("active_commissions"));
        List<ItemStack> q = new ArrayList<>();
        List<List<Component>> qt = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : active == null ? java.util.Set.<Map.Entry<String, JsonElement>>of() : active.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String nm = names.has(e.getKey()) ? names.get(e.getKey()).getAsString() : null;
            int r = nm == null ? -1 : VISITOR_RARITY.indexOf(nm.charAt(0));
            List<String> tip = new ArrayList<>();
            tip.add((r < 0 ? "§7" : VISITOR_NAME[r].substring(0, 2)) + "§l" + (nm == null ? "Unknown Visitor" : nm.substring(1)));
            tip.add("");
            tip.add("§7Queue Position: §e#" + gnum(o, "position"));
            tip.add("§7Status: " + ("STARTED".equals(optStr(o, "status")) ? "§cStarted" : "§aNot Started"));
            JsonArray req = o.has("requirement") && o.get("requirement").isJsonArray() ? o.getAsJsonArray("requirement") : new JsonArray();
            if (!req.isEmpty()) {
                tip.add("");
                tip.add("§7Items Required:");
                for (JsonElement re : req) {
                    JsonObject ro = re.getAsJsonObject();
                    String item = optStr(ro, "item"), orig = optStr(ro, "original_item");
                    long amount = gnum(ro, "amount"), origAmount = gnum(ro, "original_amount");
                    tip.add(" " + itemName(item == null ? "" : item, true) + "§8 x" + (amount != origAmount && java.util.Objects.equals(orig, item)
                            ? "§a" + fmt(amount) + "§7/§8" : "") + fmt(origAmount));
                }
            }
            JsonArray extra = o.has("extra_rewards") && o.get("extra_rewards").isJsonArray() ? o.getAsJsonArray("extra_rewards") : new JsonArray();
            JsonArray bonus = o.has("bonus_rewards") && o.get("bonus_rewards").isJsonArray() ? o.getAsJsonArray("bonus_rewards") : new JsonArray();
            if (!extra.isEmpty() || !bonus.isEmpty()) {
                tip.add("");
                tip.add("§7Rewards:");
                for (JsonElement re : extra) {
                    String line = reward(re.getAsJsonObject());
                    if (line != null) tip.add(" §7" + line);
                }
                for (JsonElement re : bonus) tip.add(" §7" + itemName(optStr(re.getAsJsonObject(), "item_id"), true));
            }
            q.add(r < 0 ? new ItemStack(Items.BARRIER) : visitorIcon(t.has("visitor_skins") ? t.getAsJsonObject("visitor_skins") : null, e.getKey()));
            qt.add(lines(tip));
        }
        f.queue = q.toArray(new ItemStack[0]);
        f.queueTips = qt.toArray(new List[0]);
    }

    /** One extra visitor reward as text (powder, enchanted book, rune, dyed item, candy, sowdust, essence). */
    private static String reward(JsonObject r) {
        long amount = gnum(r, "amount");
        if (r.has("powder")) return "§2" + fmt(amount) + " " + SbProfile.titleCase(optStr(r, "powder")) + " Powder";
        if (r.has("enchant")) return "§9" + SbProfile.titleCase(optStr(r, "enchant")) + " " + Math.max(1, gnum(r, "level"));
        if (r.has("rune")) return SbProfile.titleCase(optStr(r, "rune")) + " Rune";
        if (r.has("item")) return itemName(optStr(r, "item"), true);
        if (r.has("candy")) return itemName(optStr(r, "candy"), true);
        if (r.has("sowdust")) return "§2" + fmt(gnum(r, "sowdust")) + " Sowdust";
        if (r.has("essence")) return "§d" + fmt(amount) + " " + SbProfile.titleCase(optStr(r, "essence")) + " Essence";
        return null;
    }

    /** Contests: per crop its bracket dots, medal counts, personal best, contests entered and best placing. */
    private static void buildContests(SbProfile p, ProfileExtras.Farming fa, Farm f, JsonObject t) {
        JsonObject jc = p.memberObj("jacobs_contest");
        JsonObject cs = jc == null ? null : SbProfile.asObj(jc.get("contests"));
        JsonObject maxPb = t.has("personal_bests") ? t.getAsJsonObject("personal_bests") : new JsonObject();
        for (int i = 0; i < CROPS.length; i++) {
            String crop = CROPS[i];
            int entered = 0, best = Integer.MAX_VALUE;
            int[] medals = new int[MEDALS.length];
            for (Map.Entry<String, JsonElement> e : cs == null ? java.util.Set.<Map.Entry<String, JsonElement>>of() : cs.entrySet()) {
                String[] parts = e.getKey().split(":", 3);
                if (parts.length < 3 || !parts[2].equals(crop)) continue;
                entered++;
                JsonObject c = SbProfile.asObj(e.getValue());
                String medal = c == null ? null : optStr(c, "claimed_medal");
                int mi = medal == null ? -1 : Arrays.asList(MEDALS).indexOf(medal.toLowerCase(Locale.ROOT));
                if (mi >= 0) medals[mi]++;
                if (c != null && c.has("claimed_position")) best = Math.min(best, (int) gnum(c, "claimed_position") + 1);
            }
            StringBuilder b = new StringBuilder("§7Brackets: ");
            for (int k = 0; k < MEDALS.length; k++) {
                List<String> crops = fa == null ? null : fa.uniqueBrackets().get(MEDALS[k]);
                b.append(MEDAL_COLOR[k]).append(crops != null && crops.contains(crop) ? "●" : "◌");
            }
            StringBuilder counts = new StringBuilder();
            for (int k = 0; k < MEDALS.length; k++) {
                if (medals[k] == 0) continue;
                if (!counts.isEmpty()) counts.append("§8/");
                counts.append(MEDAL_COLOR[k]).append(fmt(medals[k]));
            }
            if (!counts.isEmpty()) b.append("§7 (").append(counts).append("§7)");
            String pb;
            if (fa == null || !fa.personalBestsPerk()) {
                pb = "§7Personal Best: §cNot Unlocked!";
            } else {
                long max = maxPb.has(crop) ? maxPb.get(crop).getAsLong() : 0, have = fa.personalBests().getOrDefault(crop, 0L);
                pb = "§7Personal Best: §e" + fmt(have) + (max >= have ? "§6/§e" + shorten(max, 0) + "§7 (§3" + pct(have, max) + "%§7)" : "");
            }
            String name = crop.equals("MUSHROOM_COLLECTION") ? "Red Mushroom" : NeuItemData.nameFor(crop);
            List<String> tip = new ArrayList<>(List.of("§f§l" + name, "", b.toString(), pb, "§7Contests participated: §e" + fmt(entered)));
            if (best != Integer.MAX_VALUE) tip.add("§7Highest Position: §e#" + fmt(best));
            f.contestIcons[i] = ItemIcons.collectionIcon(crop);
            f.contestTips[i] = lines(tip);
        }
    }

    /** CropScreen: per crop the best tool, the crop upgrade and the crop milestone. */
    private static void buildCrops(Farm f, JsonObject t, JsonObject garden) {
        long[] cost = ExtraTables.tableLongs(t.get("crop_upgrade_cost"));
        long totalCopper = Arrays.stream(cost).sum();
        JsonObject levels = SbProfile.asObj(garden.get("crop_upgrade_levels")), collected = SbProfile.asObj(garden.get("resources_collected"));
        JsonObject milestones = ExtraTables.section("garden").has("crop_milestones") ? ExtraTables.section("garden").getAsJsonObject("crop_milestones") : new JsonObject();
        JsonObject tools = t.has("tools") ? t.getAsJsonObject("tools") : new JsonObject();
        for (int i = 0; i < CROPS.length; i++) {
            String crop = CROPS[i];
            JsonArray tool = tools.has(crop) ? tools.getAsJsonArray(crop) : null;
            f.toolEmpty[i] = tool != null && tool.get(0).getAsString().equals("axe") ? AXE_SLOT : HOE_SLOT;
            int lvl = (int) gnum(levels, crop);
            boolean maxed = lvl == cost.length;
            String lt = (maxed ? "§a" : "§c") + lvl;
            long used = 0;
            for (int k = 0; k < Math.min(lvl, cost.length); k++) used += cost[k];
            f.crop[i][0] = new ItemStack(maxed ? Items.NETHERRACK : Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            f.cropText[i][0] = lt;
            f.cropTips[i][0] = lines(List.of("§lCrop Upgrade", "§7Upgrades: " + lt + "§7/" + cost.length, "§7Copper paid: §c" + fmt(used) + "§6/§c" + fmt(totalCopper)
                    + (totalCopper != used ? "§7 (§e" + pct(used, totalCopper) + "%§7)" : "")));
            long have = gnum(collected, crop);
            long[] b = cumulative(ExtraTables.tableLongs(milestones.get(crop)));
            int ms = Math.max(0, lastAtMost(b, have)), maxLevel = b.length - 1;
            String mt = (ms == maxLevel ? "§a" : "§c") + ms;
            String name = crop.equals("MUSHROOM_COLLECTION") ? "Mushroom" : NeuItemData.nameFor(crop);
            List<String> tip = new ArrayList<>(List.of("§l" + name + " Milestone", "§7Progress: " + mt + "§7/" + maxLevel));
            if (ms != maxLevel) {
                long into = have - b[ms], needed = b[ms + 1] - b[ms];
                tip.add("§7Progress to " + (ms + 1) + ": §e" + fmt(into) + "§6/§e" + shorten(needed, 1) + "§7 (§3" + pct(into, needed) + "%§7)");
                tip.add("§7Total Progress: §e" + fmt(have) + "§6/§e" + shorten(b[maxLevel], 1) + " §7(§3" + pct(have, b[maxLevel]) + "%§7)");
            } else {
                tip.add("§7Total: §e" + fmt(have));
            }
            f.crop[i][1] = ItemIcons.collectionIcon(crop);
            f.cropText[i][1] = mt;
            f.cropTips[i][1] = lines(tip);
        }
    }

    /** BaseFarmingScreen's categories down the right edge (Main, Crop), then the category. False while the garden loads. */
    @SuppressWarnings("unchecked")
    private boolean drawFarming(GuiGraphicsExtractor g, SbProfile p, Model m, int mx, int my) {
        if (m.farm == null) {
            m.farm = s.gardenAux(p.profileId).handle((r, err) -> r == null ? null : r.data())
                    .thenApplyAsync(garden -> buildFarm(p, m.farming, garden == null ? new JsonObject() : garden), ProfileViewerApi.EXECUTOR);
        }
        Farm f = m.farm.isDone() && !m.farm.isCompletedExceptionally() ? m.farm.getNow(null) : null;
        if (f == null) return false;
        if (f.gear == null) {
            f.gear = p.inventories().handle((inv, err) -> inv).thenApplyAsync(inv -> {
                List<List<ItemStack>> sets = new ArrayList<>(m.equipSets.values());
                sets.addAll(m.armorSets.values());
                JsonObject gt = ExtraTables.bundled().getAsJsonObject("sbpv_gear").getAsJsonObject("farming");
                JsonObject tools = gardenTable().has("tools") ? gardenTable().getAsJsonObject("tools") : new JsonObject();
                ItemStack[] best = new ItemStack[CROPS.length];
                for (int i = 0; i < CROPS.length; i++) {
                    best[i] = tools.has(CROPS[i]) ? SbpvGear.best(inv, sets, ExtraTables.strings(tools.getAsJsonArray(CROPS[i]).get(1)), SbpvGear::toolScore)
                            : ItemStack.EMPTY;
                }
                return new Object[] {SbpvGear.build(inv, sets, "farming", "none", null, SbpvGear::equipScore),
                        SbpvGear.best(inv, sets, ExtraTables.strings(gt.get("vacuum")), SbpvGear::rarity),
                        SbpvGear.best(inv, sets, ExtraTables.strings(gt.get("watering_can")), SbpvGear::rarity), best};
            }, ProfileViewerApi.EXECUTOR);
        }
        Object[] gear = f.gear.isDone() && !f.gear.isCompletedExceptionally() ? f.gear.getNow(null) : null;
        if (farmingIcons == null) {
            farmingIcons = new ItemStack[] {new ItemStack(Items.WHEAT), new ItemStack(Items.VILLAGER_SPAWN_EGG), new ItemStack(Items.CARROT),
                    new ItemStack(Items.PAPER), new ItemStack(Items.PAPER)};
        }
        for (int i = 0; i < farmingIcons.length; i++) {
            int tx = s.panelX + s.panelW - 9, ty = s.panelY + 20 + 22 * i;
            final int idx = i;
            PvStyleChrome.sideTab(g, s.pvStyle(), tx, ty, i == s.subView);
            g.item(i == 3 ? f.mutationTab : i == 4 ? f.composterTab : farmingIcons[i], tx + 10, ty + 2);
            s.hotspots.add(new ProfileViewerScreen.Hotspot(tx, ty, 31, 20, () -> s.subView = idx));
            if (hover(mx, my, tx, ty, 31, 20)) s.pendingTooltip = (List<Component>) FARMING_TIPS[i];
        }
        if (s.subView >= farmingIcons.length) s.subView = 0;
        switch (s.subView) {
            case 1 -> drawGrouped(g, f.visitorCells, 0, mx, my);
            case 2 -> drawCrops(g, f, gear, mx, my);
            case 3 -> drawGrouped(g, f.mutationCells, 1, mx, my);
            case 4 -> drawComposter(g, f, mx, my);
            default -> drawFarmMain(g, f, gear, mx, my);
        }
        return true;
    }

    private int queueW() {
        return 5 * 20 + 4 * 2 + 4;
    }

    private int queueH(Farm f) {
        int rows = Math.max(f.queueRows, (f.queue.length + 4) / 5);
        return rows * 20 + (rows - 1) * 2 + 4;
    }

    private int visitorsH(Farm f) {
        return f.visitors.length * 9 + 10;
    }

    /** FarmingScreen: Gear | Contests and Chips over the Visitor Queue | Information over Visitors, centered; when that does not
     *  fit, Gear + Contests, Chips + Information, Visitors, Visitor Queue, one under the other, scrolled. */
    private void drawFarmMain(GuiGraphicsExtractor g, Farm f, Object[] gear, int mx, int my) {
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        int gearW = labelW(FARM_GEAR_W, 0), gearH = labelH(84), conW = labelW(64, 0), chipW = labelW(44, 0), tabH = labelH(TABLE_H);
        int qW = labelW(queueW(), 0), qH = labelH(queueH(f));
        int infoCW = widest(f.info), visCW = widest(f.visitors);
        int infoW = labelW(infoCW, 0), infoH = labelH(f.info.length * 9), visW = labelW(visCW, 0), visH = labelH(visitorsH(f));
        int col2W = Math.max(conW + chipW, qW), col2H = tabH + 10 + qH, col3W = Math.max(infoW, visW), col3H = infoH + 10 + visH;
        int w = gearW + col2W + col3W, h = Math.max(gearH, Math.max(col2H, col3H));
        g.enableScissor(bx, by, bx + bw, by + bh);
        if (w <= bw && h <= bh) {
            int x = bx + (bw - w) / 2, y = by + (bh - h) / 2;
            farmGear(g, f, gear, x, y + (h - gearH) / 2, mx, my);
            x += gearW;
            int y2 = y + (h - col2H) / 2, rx = x + (col2W - conW - chipW) / 2;
            contests(g, f, rx, y2, mx, my);
            chips(g, f, rx + conW, y2, mx, my);
            queue(g, f, x + (col2W - qW) / 2, y2 + tabH + 10, mx, my);
            x += col2W;
            int y3 = y + (h - col3H) / 2;
            farmInfo(g, f, x + (col3W - infoW) / 2, y3, infoCW, mx, my);
            farmVisitors(g, f, x + (col3W - visW) / 2, y3 + infoH + 10, visCW, mx, my);
        } else {
            int y = by + 5 - s.pageScroll * 10, r1W = gearW + 5 + conW, r1H = Math.max(gearH, tabH), r2W = chipW + 5 + infoW, r2H = Math.max(tabH, infoH);
            int x = bx + (bw - r1W) / 2;
            farmGear(g, f, gear, x, y + (r1H - gearH) / 2, mx, my);
            contests(g, f, x + gearW + 5, y + (r1H - tabH) / 2, mx, my);
            y += r1H + 5;
            x = bx + (bw - r2W) / 2;
            chips(g, f, x, y + (r2H - tabH) / 2, mx, my);
            farmInfo(g, f, x + chipW + 5, y + (r2H - infoH) / 2, infoCW, mx, my);
            y += r2H + 5;
            farmVisitors(g, f, bx + (bw - visW) / 2, y, visCW, mx, my);
            queue(g, f, bx + (bw - qW) / 2, y + visH + 5, mx, my);
        }
        g.disableScissor();
    }

    /** Gear: armor + equipment (2 x 4), 5, the four best farming pets, 5, the vacuum over the watering can. */
    private void farmGear(GuiGraphicsExtractor g, Farm f, Object[] gear, int x, int y, int mx, int my) {
        int[] c = label(g, "Gear", null, x, y, FARM_GEAR_W, 84, 0);
        SbpvGear.Gear ge = gear == null ? null : (SbpvGear.Gear) gear[0];
        for (int i = 0; i < 4; i++) {
            int sy = c[1] + 2 + i * 20;
            gearSlot(g, ge == null ? ItemStack.EMPTY : ge.armor().get(i), ARMOR_SLOTS[i], c[0] + 2, sy, mx, my);
            gearSlot(g, ge == null ? ItemStack.EMPTY : ge.equip().get(i), EQUIP_SLOTS[i], c[0] + 22, sy, mx, my);
            if (f.pets[i] != null) slotItem(g, f.pets[i], f.petLevels[i], c[0] + 49 + 2, sy, null, mx, my);
            else gearSlot(g, ItemStack.EMPTY, SLOT_BONE, c[0] + 49 + 2, sy, mx, my);
        }
        int vx = c[0] + 49 + 24 + 5, vy = c[1] + (84 - 53) / 2;
        Identifier[] empty = {MINECART_SLOT, WATERING_CAN_SLOT};
        for (int k = 0; k < 2; k++) {
            ItemStack st = gear == null ? ItemStack.EMPTY : (ItemStack) gear[1 + k];
            int sy = vy + k * 29;
            PvStyleChrome.slot(g, s.pvStyle(), vx, sy, 24);
            if (st.isEmpty()) {
                g.blitSprite(RenderPipelines.GUI_TEXTURED, empty[k], vx + 4, sy + 4, 16, 16);
            } else {
                g.item(st, vx + 4, sy + 4);
                if (hover(mx, my, vx, sy, 24, 24)) s.pendingItemTooltip = st;
            }
        }
    }

    /** Contests: the 13 crops in columns of five (3 x 5). */
    private void contests(GuiGraphicsExtractor g, Farm f, int x, int y, int mx, int my) {
        int[] c = label(g, "Contests", null, x, y, 64, TABLE_H, 0);
        for (int i = 0; i < 15; i++) {
            ItemStack st = i < CROPS.length ? f.contestIcons[i] : ItemStack.EMPTY;
            slotItem(g, st, null, c[0] + 2 + i / 5 * 20, c[1] + 2 + i % 5 * 20, i < CROPS.length ? f.contestTips[i] : null, mx, my);
        }
    }

    /** Chips: the ten chips in columns of five (2 x 5), the level as the stack text. */
    private void chips(GuiGraphicsExtractor g, Farm f, int x, int y, int mx, int my) {
        int[] c = label(g, "Chips", null, x, y, 44, TABLE_H, 0);
        for (int i = 0; i < CHIPS.length; i++) {
            slotItem(g, f.chipIcons[i], f.chipText[i], c[0] + 2 + i / 5 * 20, c[1] + 2 + i % 5 * 20, f.chipTips[i], mx, my);
        }
    }

    /** Visitor Queue: the waiting visitors, 5 wide, padded with empty slots. */
    private void queue(GuiGraphicsExtractor g, Farm f, int x, int y, int mx, int my) {
        int[] c = label(g, "Visitor Queue", null, x, y, queueW(), queueH(f), 0);
        int cells = Math.max(f.queue.length, f.queueRows * 5);
        for (int i = 0; i < cells; i++) {
            boolean has = i < f.queue.length;
            slotItem(g, has ? f.queue[i] : ItemStack.EMPTY, null, c[0] + 2 + i % 5 * 22, c[1] + 2 + i / 5 * 22, has ? f.queueTips[i] : null, mx, my);
        }
    }

    private void farmInfo(GuiGraphicsExtractor g, Farm f, int x, int y, int w, int mx, int my) {
        int[] c = label(g, "Information", CLIPBOARD, x, y, w, f.info.length * 9, 0);
        textLines(g, f.info, f.infoTips, c[0], c[1], 0, mx, my);
    }

    /** Visitors: Unique Served, 5, Total and each rarity (accepted/visited), 5, the visitor upgrades. */
    private void farmVisitors(GuiGraphicsExtractor g, Farm f, int x, int y, int w, int mx, int my) {
        int[] c = label(g, "Visitors", null, x, y, w, visitorsH(f), 0);
        int ly = c[1];
        for (int i = 0; i < f.visitors.length; i++) {
            g.text(font(), f.visitors[i], c[0], ly, text(), false);
            if (f.visitorTips[i] != null && hover(mx, my, c[0], ly, font().width(f.visitors[i]), 9)) s.pendingTooltip = f.visitorTips[i];
            ly += 9 + (i == f.visitorGaps[0] || i == f.visitorGaps[1] ? 5 : 0);
        }
    }

    /** CropScreen: one 1 x 3 column per crop (tool, upgrade, milestone), 2 apart, wrapped into centered rows when too wide. */
    private void drawCrops(GuiGraphicsExtractor g, Farm f, Object[] gear, int mx, int my) {
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        int perRow = CROPS.length;
        if (CROPS.length * 26 > bw - 20) {
            int used = 0;
            perRow = 0;
            while (perRow < CROPS.length && (used += 26) < bw - 20) perRow++;
            perRow = Math.max(1, perRow);
        }
        int rows = (CROPS.length + perRow - 1) / perRow, h = rows * 64 + (rows - 1) * 2;
        int y0 = by + (bh - h) / 2;
        ItemStack[] tools = gear == null ? null : (ItemStack[]) gear[3];
        for (int r = 0; r < rows; r++) {
            int n = Math.min(perRow, CROPS.length - r * perRow), x = bx + (bw - (n * 26 - 2)) / 2, y = y0 + r * 66;
            for (int k = 0; k < n; k++, x += 26) {
                int i = r * perRow + k;
                gearSlot(g, tools == null ? ItemStack.EMPTY : tools[i], f.toolEmpty[i], x + 2, y + 2, mx, my);
                slotItem(g, f.crop[i][0], f.cropText[i][0], x + 2, y + 22, f.cropTips[i][0], mx, my);
                slotItem(g, f.crop[i][1], f.cropText[i][1], x + 2, y + 42, f.cropTips[i][1], mx, my);
            }
        }
    }

    // ---------------------------------------------------------------- mining

    private static final List<?>[] MINING_TIPS = {List.of(Component.literal("Main")), List.of(Component.literal("Mining Gear")),
            List.of(Component.literal("HotM Tree")), List.of(Component.literal("Glacite Tunnels"))};
    private static ItemStack[] miningIcons;
    private static final Identifier PICKAXE_SLOT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_pickaxe");
    /** The chisel column's empty slot (the original's icon/slot/armorstand). */
    private static final Identifier ARMORSTAND_SLOT = Identifier.fromNamespaceAndPath("killer560smod", "pv/sbpv/slot_armorstand");

    private static ItemStack treeSkull(int t) {
        return skull(ExtraTables.bundled().getAsJsonObject("sbpv_tree_skulls"), TREE_KEY[t]);
    }

    /** BaseMiningScreen's categories down the right edge: Main, Mining Gear, HotM Tree (not on Stranded), Glacite Tunnels
     *  (only with glacite data). */
    @SuppressWarnings("unchecked")
    private void drawMining(GuiGraphicsExtractor g, SbProfile p, Model m, int mx, int my) {
        if (miningIcons == null) {
            miningIcons = new ItemStack[] {new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.PRISMARINE_SHARD), treeSkull(0),
                    new ItemStack(Items.BLUE_ICE)};
        }
        int slot = 0;
        for (int i = 0; i < 4; i++) {
            if (i == 2 && m.stranded || i == 3 && (m.stranded || !m.glacite)) continue;
            int tx = s.panelX + s.panelW - 9, ty = s.panelY + 20 + 22 * slot++;
            final int idx = i;
            PvStyleChrome.sideTab(g, s.pvStyle(), tx, ty, i == s.subView);
            g.item(miningIcons[i], tx + 10, ty + 2);
            s.hotspots.add(new ProfileViewerScreen.Hotspot(tx, ty, 31, 20, () -> s.subView = idx));
            if (hover(mx, my, tx, ty, 31, 20)) s.pendingTooltip = (List<Component>) MINING_TIPS[i];
        }
        if (s.subView > 3 || s.subView == 2 && m.stranded || s.subView == 3 && (m.stranded || !m.glacite)) s.subView = 0;
        switch (s.subView) {
            case 1 -> drawMiningGear(g, p, m, mx, my);
            case 2 -> drawTreeCategory(g, m, 0, mx, my);
            case 3 -> drawGlacite(g, m, mx, my);
            default -> drawMiningMain(g, m, mx, my);
        }
    }

    private int powderW(Model m, int col) {
        int w = 0;
        for (String[] row : m.powder) w = Math.max(w, font().width(row[col]));
        return w;
    }

    private static final int CRYSTAL_H = 20;

    private int crystalW() {
        return 2 + 16 + 1 + font().width("§l✔") + 4 + 2;
    }

    private void drawMiningMain(GuiGraphicsExtractor g, Model m, int mx, int my) {
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        int infoW = Math.max(m.stranded ? 0 : 91, widest(m.mineInfo));
        int infoH = (m.stranded ? 0 : 16 + 3) + (m.mineInfo.length - (m.stranded ? 0 : 1)) * 12 - 3;
        int pw0 = powderW(m, 0), pw1 = powderW(m, 1), pw2 = powderW(m, 2), powW = pw0 + pw1 + pw2 + 10, powH = 4 * 9 + 3 * 5;
        int cryContentW = bw / 3, perRow = Math.max(1, cryContentW / (crystalW() + 5));
        int cryRows = m.crystalOk.length == 0 ? 0 : (m.crystalOk.length + perRow - 1) / perRow;
        int cryH = Math.max(0, cryRows * CRYSTAL_H + (cryRows - 1) * 5);
        int forgeW = 0;
        for (int i = 0; i < m.forgeSlot.length; i++) forgeW = Math.max(forgeW, font().width(m.forgeSlot[i]) + 1 + 16 + 1 + font().width(m.forgeTime[i]));
        int forgeH = m.forgeSlot.length * 12 + Math.max(0, m.forgeSlot.length - 1) * 5;
        int infoLW = labelW(infoW, 10), powLW = labelW(powW, 10), cryLW = m.crystalOk.length == 0 ? 0 : labelW(cryContentW, 10),
                forLW = m.forgeSlot.length == 0 ? 0 : labelW(forgeW, 10);
        int left = Math.max(infoLW, powLW), right = Math.max(cryLW, forLW);
        int leftH = labelH(infoH) + 5 + labelH(powH);
        int rightH = (cryLW > 0 ? labelH(cryH) : 0) + (forLW > 0 ? (cryLW > 0 ? 5 : 0) + labelH(forgeH) : 0);
        boolean column = left + right >= bw - 5;
        g.enableScissor(bx, by, bx + bw, by + bh);
        if (column) {
            int y = by - s.pageScroll * 10;
            y = mineInfo(g, m, bx + (bw - infoLW) / 2, y, infoW, infoH, mx, my) + 5;
            y = powderTable(g, m, bx + (bw - powLW) / 2, y, powW, powH, pw0, pw1) + 5;
            if (cryLW > 0) y = crystals(g, m, bx + (bw - cryLW) / 2, y, cryContentW, cryH, perRow, mx, my) + 5;
            if (forLW > 0) forge(g, m, bx + (bw - forLW) / 2, y, forgeW, forgeH, mx, my);
        } else {
            int x0 = bx + (bw - (left + 2 + right)) / 2, x1 = x0 + left + 2;
            int ly = by + Math.max(0, (bh - leftH) / 2), ry = by + Math.max(0, (bh - rightH) / 2);
            ly = mineInfo(g, m, x0 + (left - infoLW) / 2, ly, infoW, infoH, mx, my) + 5;
            powderTable(g, m, x0 + (left - powLW) / 2, ly, powW, powH, pw0, pw1);
            if (cryLW > 0) ry = crystals(g, m, x1 + (right - cryLW) / 2, ry, cryContentW, cryH, perRow, mx, my) + 5;
            if (forLW > 0) forge(g, m, x1 + (right - forLW) / 2, ry, forgeW, forgeH, mx, my);
        }
        g.disableScissor();
    }

    /** Information: the HotM line over its bar (hover: level and progress), then the gray lines, 3 apart. Returns its bottom. */
    private int mineInfo(GuiGraphicsExtractor g, Model m, int x, int y, int w, int h, int mx, int my) {
        int[] c = label(g, "Information", CLIPBOARD, x, y, w, h, 10);
        int ly = c[1];
        for (int i = 0; i < m.mineInfo.length; i++) {
            g.text(font(), m.mineInfo[i], c[0], ly, text(), false);
            boolean bar = i == 0 && !m.stranded;
            if (bar) PvStyleChrome.bar(g, c[0], ly + 11, m.hotmProgress);
            int rowH = bar ? 16 : 9;
            if (m.mineInfoTips[i] != null && hover(mx, my, c[0], ly, bar ? 91 : font().width(m.mineInfo[i]), rowH)) {
                s.pendingTooltip = m.mineInfoTips[i];
            }
            ly += rowH + 3;
        }
        return y + labelH(h);
    }

    /** Powder: a 3-column table ("", Current, Total), 5 apart both ways. */
    private int powderTable(GuiGraphicsExtractor g, Model m, int x, int y, int w, int h, int c0, int c1) {
        int[] c = label(g, "Powder", null, x, y, w, h, 10);
        for (int r = 0; r < m.powder.length; r++) {
            int ry = c[1] + r * 14;
            g.text(font(), m.powder[r][0], c[0], ry, text(), false);
            g.text(font(), m.powder[r][1], c[0] + c0 + 5, ry, text(), false);
            g.text(font(), m.powder[r][2], c[0] + c0 + 5 + c1 + 5, ry, text(), false);
        }
        return y + labelH(h);
    }

    /** Crystals: each crystal on a thin rounded box with a green check or a red cross, rows centered in a third of the width. */
    private int crystals(GuiGraphicsExtractor g, Model m, int x, int y, int w, int h, int perRow, int mx, int my) {
        int[] c = label(g, "Crystals", null, x, y, w, h, 10);
        int cw = crystalW(), n = m.crystalOk.length;
        for (int r = 0; r * perRow < n; r++) {
            int inRow = Math.min(perRow, n - r * perRow), rowW = inRow * cw + (inRow - 1) * 5;
            int cx = c[0] + (w - rowW) / 2, cy = c[1] + r * (CRYSTAL_H + 5);
            for (int i = r * perRow; i < r * perRow + inRow; i++, cx += cw + 5) {
                PvStyleChrome.chip(g, s.pvStyle(), cx, cy, cw, CRYSTAL_H);
                g.item(m.crystalIcons[i], cx + 2, cy + 2);
                g.text(font(), m.crystalOk[i] ? "§2§l✔" : "§4§l❌", cx + 19, cy + 6, -1, true);
                if (hover(mx, my, cx, cy, cw, CRYSTAL_H)) s.pendingTooltip = m.crystalTips[i];
            }
        }
        return y + labelH(h);
    }

    /** Forge: per slot "Slot n", the item, and its time, in a row; 5 apart. */
    private void forge(GuiGraphicsExtractor g, Model m, int x, int y, int w, int h, int mx, int my) {
        int[] c = label(g, "Forge", null, x, y, w, h, 10);
        for (int i = 0; i < m.forgeSlot.length; i++) {
            int ry = c[1] + i * 17, rx = c[0];
            g.text(font(), m.forgeSlot[i], rx, ry + 2, 0xFF555555, false);
            rx += font().width(m.forgeSlot[i]) + 1;
            g.item(m.forgeItems[i], rx, ry - 2);
            rx += 17;
            g.text(font(), m.forgeTime[i], rx, ry + 2, 0xFF555555, false);
            if (m.forgeTips[i] != null && hover(mx, my, c[0], ry, w, 12)) s.pendingTooltip = m.forgeTips[i];
            if (m.forgeEnd[i] > 0) {
                final int k = i;
                s.hotspots.add(new ProfileViewerScreen.Hotspot(c[0], ry, w, 12, () -> forgeClick(m, k)));
            }
        }
    }

    /** MiningGearScreen: armor + equipment, the four best pickaxes, then the best chisel over the Suspicious Scrap count. */
    private void drawMiningGear(GuiGraphicsExtractor g, SbProfile p, Model m, int mx, int my) {
        if (m.mineGear == null) {
            m.mineGear = p.inventories().handle((inv, err) -> inv).thenApplyAsync(inv -> {
                List<List<ItemStack>> sets = new ArrayList<>(m.equipSets.values());
                sets.addAll(m.armorSets.values());
                return new Object[] {SbpvGear.build(inv, sets, "mining", "pickaxes", null), SbpvGear.best(inv, sets, "mining", "chisels", 1),
                        SbpvGear.count(inv, sets, "SUSPICIOUS_SCRAP") + m.scrapSacks};
            }, ProfileViewerApi.EXECUTOR);
        }
        Object[] r = m.mineGear.isDone() && !m.mineGear.isCompletedExceptionally() ? m.mineGear.getNow(null) : null;
        SbpvGear.Gear gear = r == null ? null : (SbpvGear.Gear) r[0];
        int w = 44 + 5 + 24 + 5 + 24, h = 84;
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        int lw = labelW(w, 0);
        int[] c = label(g, "Mining Gear", null, bx + (bw - lw) / 2, by + Math.max(0, (bh - labelH(h)) / 2), w, h, 0);
        for (int i = 0; i < 4; i++) {
            int sy = c[1] + 2 + i * 20;
            gearSlot(g, gear == null ? ItemStack.EMPTY : gear.armor().get(i), ARMOR_SLOTS[i], c[0] + 2, sy, mx, my);
            gearSlot(g, gear == null ? ItemStack.EMPTY : gear.equip().get(i), EQUIP_SLOTS[i], c[0] + 22, sy, mx, my);
            gearSlot(g, gear == null ? ItemStack.EMPTY : gear.tools().get(i), PICKAXE_SLOT, c[0] + 49 + 2, sy, mx, my);
        }
        int cx = c[0] + 49 + 24 + 5, cy = c[1] + (h - 53) / 2;
        @SuppressWarnings("unchecked") List<ItemStack> chisel = r == null ? null : (List<ItemStack>) r[1];
        gearSlot(g, chisel == null ? ItemStack.EMPTY : chisel.get(0), ARMORSTAND_SLOT, cx + 2, cy + 2, mx, my);
        int sy = cy + 24 + 5;
        ItemStack scrap = ItemIcons.forId("SUSPICIOUS_SCRAP");
        PvStyleChrome.slot(g, s.pvStyle(), cx, sy, 24);
        g.item(scrap, cx + 4, sy + 4);
        long count = r == null ? 0 : (Long) r[2];
        g.itemDecorations(font(), scrap, cx + 4, sy + 4, String.valueOf(count));
        if (hover(mx, my, cx, sy, 24, 24)) s.pendingItemTooltip = scrap;
    }

    /** GlaciteScreen: Info and Fossils in one column, Corpses Looted in the next, 5 apart, from the top; stacked when narrow. */
    private void drawGlacite(GuiGraphicsExtractor g, Model m, int mx, int my) {
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        int iw = widest(m.glaInfo), ih = m.glaInfo.length * 12 - 3, cw = widest(m.corpses), ch = m.corpses.length * 12 - 3;
        int fw = 4 * 20 + 4, fh = 2 * 20 + 4;
        int infoLW = labelW(iw, 10), fosLW = labelW(fw, 0), corLW = labelW(cw, 10);
        int col = Math.max(infoLW, fosLW);
        boolean fits = col + 5 + corLW <= bw && 5 + labelH(ih) + 5 + labelH(fh) <= bh;
        g.enableScissor(bx, by, bx + bw, by + bh);
        if (fits) {
            int x0 = bx + (bw - (col + 5 + corLW)) / 2, y = by + 5;
            int[] c = label(g, "Info", CLIPBOARD, x0 + (col - infoLW) / 2, y, iw, ih, 10);
            textLines(g, m.glaInfo, null, c[0], c[1], 3, mx, my);
            fossils(g, m, x0 + (col - fosLW) / 2, y + labelH(ih) + 5, mx, my);
            c = label(g, "Corpses Looted", null, x0 + col + 5, y, cw, ch, 10);
            textLines(g, m.corpses, null, c[0], c[1], 3, mx, my);
        } else {
            int y = by - s.pageScroll * 10;
            int[] c = label(g, "Info", CLIPBOARD, bx + (bw - infoLW) / 2, y, iw, ih, 10);
            textLines(g, m.glaInfo, null, c[0], c[1], 3, mx, my);
            y += labelH(ih) + 5;
            fossils(g, m, bx + (bw - fosLW) / 2, y, mx, my);
            y += labelH(fh) + 5;
            c = label(g, "Corpses Looted", null, bx + (bw - corLW) / 2, y, cw, ch, 10);
            textLines(g, m.corpses, null, c[0], c[1], 3, mx, my);
        }
        g.disableScissor();
    }

    private void fossils(GuiGraphicsExtractor g, Model m, int x, int y, int mx, int my) {
        int[] c = label(g, "Fossils", null, x, y, 4 * 20 + 4, 2 * 20 + 4, 0);
        for (int i = 0; i < m.fossilIcons.length && i < 8; i++) {
            slotItem(g, m.fossilIcons[i], null, c[0] + 2 + (i % 4) * 20, c[1] + 2 + (i / 4) * 20, m.fossilTips[i], mx, my);
        }
    }

    /** LoadoutSkillTreeScreen (HotM Tree / HotF Tree): the five slot buttons (the tree's skull; the shown slot lit), 10 px, then
     *  the tree with its tier column. */
    private final int[] treeView = new int[2];
    private Model treeViewFor;

    private void drawTreeCategory(GuiGraphicsExtractor g, Model m, int t, int mx, int my) {
        if (treeViewFor != m) {
            treeViewFor = m;
            treeView[0] = m.treeSelected[0];
            treeView[1] = m.treeSelected[1];
        }
        Tree tree = m.fullTrees[t][Math.max(1, treeView[t])];
        int bx = s.panelX, by = s.contentY, bw = s.panelW, bh = s.panelY + s.panelH - s.contentY;
        if (tree == null) return;
        int w = Math.max(5 * 20 + 4 * 2, tree.w), h = 20 + 10 + tree.h;
        int x = bx + (bw - w) / 2, y = h > bh ? by - s.pageScroll * 10 : by + (bh - h) / 2;
        g.enableScissor(bx, by, bx + bw, by + bh);
        ItemStack skull = treeSkull(t);
        int btnX = x + (w - (5 * 20 + 4 * 2)) / 2;
        for (int i = 1; i <= 5; i++) {
            int px = btnX + (i - 1) * 22;
            boolean on = i == treeView[t] || i == 1 && treeView[t] <= 1, h2 = hover(mx, my, px, y, 20, 20);
            PvStyleChrome.button(g, s.pvStyle(), px, y, 20, 20, "", h2, on);
            g.item(skull, px + 2, y + 1);
            final int idx = i;
            s.hotspots.add(new ProfileViewerScreen.Hotspot(px, y, 20, 20, () -> treeView[t] = idx));
            if (h2) s.pendingTooltip = List.of(Component.literal("Slot " + i));
        }
        int gx = x + (w - tree.w) / 2, gy = y + 30;
        for (int i = 0; i < tree.icons.length; i++) slotItem(g, tree.icons[i], tree.counts[i], gx + tree.x[i], gy + tree.y[i], tree.tips[i], mx, my);
        g.disableScissor();
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
            drawTreeCategory(g, m, 1, mx, my);
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

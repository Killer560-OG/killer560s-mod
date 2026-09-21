package com.killer560.hub.croesus;

import com.google.gson.JsonParser;
import com.killer560.hub.rngmeter.RngItem;
import com.killer560.hub.rngmeter.RngItemNames;
import com.killer560.hub.rngmeter.RngMeterEngine;
import com.killer560.hub.rngmeter.RngSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Values dungeon reward chests. Every fact about the menus comes from reference mods, not guesses:
 * <ul>
 *   <li>Menu titles - {@code "(1/2) Croesus"}, {@code "[Master ]Catacombs - Floor VII"} (NoammAddons also
 *   accepts the truncated {@code "Master Catacombs - Flo"}), chest screens {@code "Bedrock[ Chest]"}:
 *   NoammAddons ChestProfit.kt, Odin Croesus.kt, quoi AutoCroesus.kt.</li>
 *   <li>Chest lore - {@code Contents} / item lines / blank / {@code Cost} / {@code "1,000,000 Coins"} or
 *   {@code FREE} / optional {@code Dungeon Chest Key} / {@code Already opened!}: quoi AutoCroesusParser.kt,
 *   Odin Croesus.kt; {@code Can't open another chest!}: Odin KuudraTracker.kt.</li>
 *   <li>Reward line formats - {@code "Enchanted Book (Ultimate Wise V)"}, {@code "Wither Essence x20"},
 *   {@code "[Lvl 1] Spirit"}, {@code "... Shard"}, {@code "Shiny "} prefix, and the display-name -> id
 *   replacement table: Odin Croesus.kt + quoi AutoCroesusParser.kt + NoammAddons ChestProfit.kt.</li>
 *   <li>In an open chest screen the claim button is slot 31 (a real chest item whose lore holds "Cost"):
 *   NoammAddons ChestProfit.kt (items[31]) + quoi (clicks 31); rewards live in slots 0-40: Odin.</li>
 * </ul>
 * Prices come ONLY from {@link RngMeterEngine#PRICES} (Bazaar first, AH lowest-BIN fallback).
 */
public final class DungeonChestValuer {

    public enum ChestType {
        WOOD("Wood", 0xFF8B5A2B),
        GOLD("Gold", 0xFFFFD700),
        DIAMOND("Diamond", 0xFF55FFFF),
        EMERALD("Emerald", 0xFF00AA00),
        OBSIDIAN("Obsidian", 0xFFAA00AA),
        BEDROCK("Bedrock", 0xFFAAAAAA);

        public final String display;
        public final int color;

        ChestType(String display, int color) {
            this.display = display;
            this.color = color;
        }

        public static ChestType fromName(String name) {
            if (name == null) {
                return null;
            }
            Matcher m = CHEST_NAME.matcher(name.trim());
            if (!m.matches()) {
                return null;
            }
            for (ChestType t : values()) {
                if (t.display.equals(m.group(1))) {
                    return t;
                }
            }
            return null;
        }
    }

    public record PricedItem(String name, String id, int amount, Long unitPrice, boolean excluded) {
        public long total() {
            return unitPrice == null || excluded ? 0L : unitPrice * amount;
        }

        public boolean unpriced() {
            return unitPrice == null && !excluded;
        }
    }

    /** @param requiresKey the chest's own lore listed "Dungeon Chest Key", i.e. a key is spent opening it and
     *                     that key's Bazaar price is already inside {@link #cost}. */
    public record ChestValue(ChestType type, int slot, long cost, List<PricedItem> items, long value, long profit,
                             boolean opened, int unpricedCount, boolean requiresKey) {
    }

    public static final Pattern CROESUS_MENU_TITLE = Pattern.compile("^(?:\\(\\d+/\\d+\\) )?Croesus$");
    public static final Pattern RUN_VIEW_TITLE = Pattern.compile("^(Master )?Catacombs - Flo(?:or ([IVX]+))?$");
    private static final Pattern CHEST_NAME = Pattern.compile("^(Wood|Gold|Diamond|Emerald|Obsidian|Bedrock)(?: Chest)?$");
    private static final Pattern COST = Pattern.compile("^([\\d,]+) Coins$");
    private static final Pattern ESSENCE = Pattern.compile("^(\\w+) Essence x(\\d+)$");
    private static final Pattern BOOK = Pattern.compile("^Enchanted Book \\((.+) ([IVXL]+|\\d+)\\)$");
    private static final Pattern PET = Pattern.compile("^\\[Lvl \\d+] (.+)$");
    private static final Pattern SHARD = Pattern.compile("^(.+) Shard(?: x(\\d+))?$");
    private static final Pattern AMOUNT_SUFFIX = Pattern.compile("^(.+) x(\\d+)$");
    private static final Pattern FORMATTING = Pattern.compile("§.");

    public static final int CLAIM_BUTTON_SLOT = 31;
    /** The Kismet "Reroll Chest" button in an open chest screen - quoi AutoCroesus.kt reads {@code slots[50]}. */
    public static final int REROLL_BUTTON_SLOT = 50;
    /** The "Go Back" arrow in an open chest screen - quoi clicks 49 after a reroll it can't use. */
    public static final int CHEST_BACK_SLOT = 49;
    /** The Croesus run head lore line that marks a run with nothing claimed yet (quoi AutoCroesus.kt). */
    public static final String RUN_UNOPENED_LORE = "No chests opened yet!";
    /** Where the Croesus menu puts its run heads (quoi AutoCroesus.kt) - shared by Auto Croesus and the
     *  claimed/unclaimed highlight so the two can never disagree about which slots are runs. */
    public static final int[] RUN_HEAD_SLOTS = {
            10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43
    };
    public static final String DUNGEON_CHEST_KEY_ID = "DUNGEON_CHEST_KEY";
    private static final String REROLL_BUTTON_NAME = "Reroll Chest";
    /** Reroll button lore when you are carrying no Kismet Feather (quoi AutoCroesus.kt). */
    private static final String REROLL_NEEDS_KISMET = "Bring a Kismet Feather";
    /** Reroll button lore once this run's one reroll has been spent (quoi AutoCroesus.kt). */
    private static final String REROLL_ALREADY_USED = "You already rerolled a chest";

    /** Display name -> id where the name doesn't map mechanically - merged from Odin Croesus.kt's
     *  itemReplacements and quoi AutoCroesusParser.kt's itemReplacements. */
    private static final Map<String, String> REPLACEMENTS = new HashMap<>();
    /** Unsellable/near-worthless ids valued at 0 - quoi AutoCroesusData.kt defaultAutoCroesusWorthless. */
    private static final Set<String> WORTHLESS_PREFIXES = Set.of(
            "DUNGEON_DISC_", "MAXOR_THE_FISH", "STORM_THE_FISH", "GOLDOR_THE_FISH",
            "ENCHANTMENT_ULTIMATE_NO_PAIN_NO_GAIN_", "ENCHANTMENT_ULTIMATE_COMBO_", "ENCHANTMENT_ULTIMATE_BANK_",
            "ENCHANTMENT_ULTIMATE_JERRY_", "ENCHANTMENT_FEATHER_FALLING_", "ENCHANTMENT_INFINITE_QUIVER_");

    static {
        REPLACEMENTS.put("Wither Chestplate", "WITHER_CHESTPLATE");
        REPLACEMENTS.put("Wither Leggings", "WITHER_LEGGINGS");
        REPLACEMENTS.put("Wither Helmet", "WITHER_HELMET");
        REPLACEMENTS.put("Wither Boots", "WITHER_BOOTS");
        REPLACEMENTS.put("Necron's Handle", "NECRON_HANDLE");
        REPLACEMENTS.put("Wither Shield", "WITHER_SHIELD_SCROLL");
        REPLACEMENTS.put("Implosion", "IMPLOSION_SCROLL");
        REPLACEMENTS.put("Shadow Warp", "SHADOW_WARP_SCROLL");
        REPLACEMENTS.put("Necron Dye", "DYE_NECRON");
        REPLACEMENTS.put("Livid Dye", "DYE_LIVID");
        REPLACEMENTS.put("Giant's Sword", "GIANTS_SWORD");
        REPLACEMENTS.put("Necromancer's Brooch", "NECROMANCER_BROOCH");
        REPLACEMENTS.put("Warped Stone", "AOTE_STONE");
        REPLACEMENTS.put("Spirit Stone", "SPIRIT_DECOY");
        REPLACEMENTS.put("Bonzo Shard", "SHARD_BONZO");
        REPLACEMENTS.put("Wither Shard", "SHARD_WITHER");
        REPLACEMENTS.put("Thorn Shard", "SHARD_THORN");
        REPLACEMENTS.put("Apex Dragon Shard", "SHARD_APEX_DRAGON");
        REPLACEMENTS.put("Power Dragon Shard", "SHARD_POWER_DRAGON");
        REPLACEMENTS.put("Scarf Shard", "SHARD_SCARF");
    }

    private DungeonChestValuer() {
    }

    public static String strip(String s) {
        return s == null ? "" : FORMATTING.matcher(s).replaceAll("");
    }

    public static List<String> cleanLore(ItemStack stack) {
        List<String> out = new ArrayList<>();
        if (stack == null || stack.isEmpty()) {
            return out;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return out;
        }
        for (Component line : lore.lines()) {
            out.add(strip(line.getString()).trim());
        }
        return out;
    }

    public static boolean pricesReady() {
        return RngMeterEngine.PRICES.getBazaarPriceCount() > 0;
    }

    /** Values a chest from its lore alone - the Croesus run view's chest heads, and the open-chest button. */
    public static ChestValue fromLore(ChestType type, int slot, List<String> lore) {
        List<PricedItem> items = new ArrayList<>();
        long cost = 0;
        boolean opened = false;
        boolean requiresKey = false;
        boolean inContents = false;
        for (String line : lore) {
            if (line.equals("Already opened!") || line.equals("Can't open another chest!")) {
                opened = true;
                continue;
            }
            if (line.equals("Contents")) {
                inContents = true;
                continue;
            }
            if (line.isEmpty() || line.equals("Cost")) {
                inContents = false;
                continue;
            }
            if (inContents) {
                items.add(priceLine(line));
                continue;
            }
            if (line.equals("FREE")) {
                cost = 0;
                continue;
            }
            Matcher c = COST.matcher(line);
            if (c.matches()) {
                cost += parseLong(c.group(1));
                continue;
            }
            if (line.equals("Dungeon Chest Key")) {
                // quoi AutoCroesusParser.kt: a key requirement adds the key's own price to the cost.
                requiresKey = true;
                Long key = dungeonChestKeyPrice();
                if (key != null) {
                    cost += key;
                }
            }
        }
        return build(type, slot, cost, items, opened, requiresKey);
    }

    /** Bazaar price of one Dungeon Chest Key, or null while prices haven't loaded. */
    public static Long dungeonChestKeyPrice() {
        return price("Dungeon Chest Key", DUNGEON_CHEST_KEY_ID);
    }

    /**
     * What a chest is worth once a Dungeon Chest Key is paid for it - killer560, 2026-09-20: "highlight the
     * best chest, and the second best if it makes profit assuming i use a dungeon chest key on it".
     * <p>
     * A chest whose own lore already says "Dungeon Chest Key" has the key inside {@link ChestValue#cost()}
     * already (see {@link #fromLore}), so subtracting it again would charge for two keys.
     */
    public static long profitWithKey(ChestValue chest) {
        if (chest == null) {
            return 0L;
        }
        if (chest.requiresKey()) {
            return chest.profit();
        }
        Long key = dungeonChestKeyPrice();
        return key == null ? chest.profit() : chest.profit() - key;
    }

    /** Values an opened chest screen: real reward stacks in slots 0-40 (Odin), cost/opened state from the
     *  slot-31 button's lore (NoammAddons). Falls back to the button's own "Contents" lore when no real
     *  reward stack could be identified. */
    public static ChestValue fromChestScreen(ChestType type, List<ItemStack> containerStacks) {
        ItemStack button = containerStacks.size() > CLAIM_BUTTON_SLOT ? containerStacks.get(CLAIM_BUTTON_SLOT) : ItemStack.EMPTY;
        ChestValue fromButton = fromLore(type, -1, cleanLore(button));
        List<PricedItem> items = new ArrayList<>();
        int limit = Math.min(41, containerStacks.size());
        for (int i = 0; i < limit; i++) {
            if (i == CLAIM_BUTTON_SLOT) {
                continue;
            }
            PricedItem item = priceStack(containerStacks.get(i));
            if (item != null) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            items = fromButton.items();
        }
        return build(type, -1, fromButton.cost(), items, fromButton.opened(), fromButton.requiresKey());
    }

    /** Whether slot 50 of an open chest screen is the real Kismet "Reroll Chest" button. */
    public static boolean isRerollButton(ItemStack button) {
        return button != null && !button.isEmpty()
                && strip(button.getHoverName().getString()).trim().equals(REROLL_BUTTON_NAME);
    }

    /** True when the reroll button says you aren't carrying a Kismet Feather (quoi AutoCroesus.kt). */
    public static boolean rerollNeedsKismet(ItemStack button) {
        return loreContains(button, REROLL_NEEDS_KISMET);
    }

    /** True when this run's single reroll has already been spent (quoi AutoCroesus.kt). */
    public static boolean rerollAlreadyUsed(ItemStack button) {
        return loreContains(button, REROLL_ALREADY_USED);
    }

    private static boolean loreContains(ItemStack stack, String needle) {
        for (String line : cleanLore(stack)) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the slot-31 button currently looks like a real, still-claimable chest ("Cost" in lore,
     *  not already opened) - the precondition every reference checks before treating a click as a claim. */
    public static boolean isClaimableButton(ItemStack button) {
        List<String> lore = cleanLore(button);
        return lore.contains("Cost") && !lore.contains("Already opened!") && !lore.contains("Can't open another chest!");
    }

    private static ChestValue build(ChestType type, int slot, long cost, List<PricedItem> items, boolean opened,
                                    boolean requiresKey) {
        long value = 0;
        int unpriced = 0;
        for (PricedItem item : items) {
            value += item.total();
            if (item.unpriced()) {
                unpriced++;
            }
        }
        return new ChestValue(type, slot, cost, List.copyOf(items), value, value - cost, opened, unpriced, requiresKey);
    }

    private static PricedItem priceStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (itemKey.endsWith("stained_glass_pane")) {
            return null;
        }
        String name = strip(stack.getHoverName().getString()).trim();
        Matcher essence = ESSENCE.matcher(name);
        if (essence.matches()) {
            return priceLine(name);
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        String id = tag.getStringOr("id", null);
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.equals("ENCHANTED_BOOK")) {
            CompoundTag ench = tag.getCompoundOrEmpty("enchantments");
            if (ench.keySet().size() == 1) {
                String key = ench.keySet().iterator().next();
                int level = ench.getIntOr(key, 1);
                id = "ENCHANTMENT_" + key.toUpperCase(Locale.US) + "_" + level;
            } else {
                return priceLine(name);
            }
        } else if (id.equals("PET")) {
            try {
                String type = JsonParser.parseString(tag.getStringOr("petInfo", "{}")).getAsJsonObject().get("type").getAsString();
                id = "PET_" + type;
            } catch (Exception e) {
                return new PricedItem(name, id, 1, null, false);
            }
        }
        if (id.startsWith("STARRED_")) {
            id = id.substring("STARRED_".length());
        }
        return priced(name, id, Math.max(1, stack.getCount()));
    }

    /** One lore reward line -> priced item. */
    static PricedItem priceLine(String rawLine) {
        String line = strip(rawLine).trim();
        Matcher essence = ESSENCE.matcher(line);
        if (essence.matches()) {
            String id = "ESSENCE_" + essence.group(1).toUpperCase(Locale.US);
            int amount = (int) parseLong(essence.group(2));
            if (!CroesusConfig.getInstance().isIncludeEssence()) {
                return new PricedItem(line, id, amount, null, true);
            }
            return new PricedItem(line, id, amount, price(line, id), false);
        }
        Matcher book = BOOK.matcher(line);
        if (book.matches()) {
            String id = resolveEnchantId(book.group(1), book.group(2));
            return id == null ? new PricedItem(line, "", 1, null, false) : priced(line, id, 1);
        }
        String id = RngItemNames.BY_NAME.get(line);
        if (id != null) {
            return priced(line, id, 1);
        }
        Matcher pet = PET.matcher(line);
        if (pet.matches()) {
            return priced(line, "PET_" + snake(pet.group(1)), 1);
        }
        int amount = 1;
        String name = line;
        Matcher amt = AMOUNT_SUFFIX.matcher(line);
        if (amt.matches()) {
            name = amt.group(1);
            amount = (int) parseLong(amt.group(2));
        }
        if (name.startsWith("Shiny ")) {
            name = name.substring("Shiny ".length());
        }
        id = REPLACEMENTS.get(name);
        if (id == null) {
            id = RngItemNames.BY_NAME.get(name);
        }
        if (id == null) {
            Matcher shard = SHARD.matcher(name);
            if (shard.matches()) {
                id = "SHARD_" + snake(shard.group(1));
            }
        }
        if (id == null) {
            // Odin Croesus.kt's last-resort: uppercase, drop apostrophes and " -", spaces -> underscores.
            id = name.toUpperCase(Locale.US).replace("'", "").replace(" -", "").replace(" ", "_");
        }
        return priced(line, id, Math.max(1, amount));
    }

    private static PricedItem priced(String name, String id, int amount) {
        for (String prefix : WORTHLESS_PREFIXES) {
            if (id.startsWith(prefix)) {
                return new PricedItem(name, id, amount, 0L, false);
            }
        }
        return new PricedItem(name, id, amount, price(name, id), false);
    }

    private static Long price(String name, String id) {
        return RngMeterEngine.PRICES.getPrice(new RngItem("", name, id, RngSource.BAZAAR, 1, false));
    }

    /** Same bare-vs-ULTIMATE_ Bazaar existence check RngMeterOverlay.resolveEnchantId uses (Croesus shows
     *  some ultimates without their "Ultimate " word, e.g. "Wisdom", "One For All"). */
    private static String resolveEnchantId(String enchantName, String tierText) {
        Integer tier = roman(tierText);
        if (tier == null) {
            return null;
        }
        String snake = snake(enchantName).replace("-", "_");
        String bare = "ENCHANTMENT_" + snake + "_" + tier;
        String ultimate = ("ENCHANTMENT_ULTIMATE_" + snake + "_" + tier).replace("ULTIMATE_ULTIMATE_", "ULTIMATE_");
        if (!RngMeterEngine.PRICES.hasBazaarProduct(bare) && RngMeterEngine.PRICES.hasBazaarProduct(ultimate)) {
            return ultimate;
        }
        return bare;
    }

    private static String snake(String s) {
        return s.trim().toUpperCase(Locale.US).replace("'", "").replace(" ", "_");
    }

    public static Integer roman(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(s);
        }
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                default -> -1;
            };
            if (v < 0) {
                return null;
            }
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** "1.25M", "350k", "-62k". */
    public static String formatCoins(long coins) {
        long abs = Math.abs(coins);
        String sign = coins < 0 ? "-" : "";
        if (abs >= 1_000_000_000L) {
            return sign + String.format(Locale.US, "%.2fB", abs / 1e9);
        }
        if (abs >= 1_000_000L) {
            return sign + String.format(Locale.US, "%.2fM", abs / 1e6);
        }
        if (abs >= 1_000L) {
            return sign + String.format(Locale.US, "%.1fk", abs / 1e3);
        }
        return sign + abs;
    }
}

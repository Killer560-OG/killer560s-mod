package com.killer560.hub.chestvalue;

import com.killer560.hub.croesus.DungeonChestValuer;
import com.killer560.hub.itembrowser.NeuItemData;
import com.killer560.hub.pricetooltip.ItemKeys;
import com.killer560.hub.pricetooltip.PriceData;
import com.killer560.hub.util.ItemNbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chest Value in a minion's menu (killer560, 2026-11-25: "It needs to factor their hopper the items in the menu and what it
 * generated and its upgrades and all of that" and "include the craft cost of the minion"). The slot layout is the real
 * 54-slot "<Type> Minion <tier>" menu as his logger recorded it on 11-24 (Glowstone XI, Slime XI): minion head 4, skin 10,
 * fuel 19, automated shipping (the hopper) 28, upgrades 37 and 46, storage 21-25 / 30-34 / 39-43. A placeholder pane has no
 * Skyblock id and counts for nothing. The hopper's "Held Coins: N" is read from its lore and Generated from the head's own
 * resources_generated data; nothing the menu does not carry is shown. Built only when ChestValue's key changes (contents,
 * prices, a recipe arriving), never per frame.
 */
public final class MinionValue {

    private static final Pattern TITLE = Pattern.compile("^[A-Z][A-Za-z' ]{1,30} Minion ([IVX]{1,5})$");
    private static final Pattern MINION_ID = Pattern.compile("^([A-Z0-9_]{1,40})_GENERATOR_(\\d{1,2})$");
    private static final Pattern HELD = Pattern.compile("Held Coins:\\s*([\\d,]{1,15}(?:\\.\\d+)?)");
    private static final int HEAD = 4, SKIN = 10, FUEL = 19, SHIPPING = 28, UPGRADE_1 = 37, UPGRADE_2 = 46;
    private static final int[] STORAGE = {21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 39, 40, 41, 42, 43};
    /** Recipe fetches already watched, so each arrival rebuilds the panel once; bounded like the recipe cache. */
    private static final Set<String> WATCHED = new HashSet<>();
    private static final AtomicInteger ARRIVALS = new AtomicInteger();

    /** A fuel, hopper, upgrade or skin item; value -1 = no price. */
    public record Part(String slot, String name, double value) {
    }

    /** Craft cost of tiers I..tier: what could be priced, how many ingredients (or tiers) could not, still loading. */
    public record Craft(String tiers, double priced, int unknown, boolean loading) {
    }

    /** total = storage + hopper coins + priced parts; generated = -1 when the head does not carry it. */
    public record Result(double total, ChestValue.Result storage, double hopperCoins, boolean hopper, long generated,
                         List<Part> parts, Craft craft) {
    }

    private MinionValue() {
    }

    static boolean isTitle(String title) {
        return TITLE.matcher(title).matches();
    }

    /** Moves when a recipe this panel asked for arrives, so the lines rebuild once it is in. */
    static int arrivals() {
        return ARRIVALS.get();
    }

    static Result compute(String title, List<ItemStack> top, ChestValueConfig cfg) {
        List<ItemStack> storage = new ArrayList<>(STORAGE.length);
        for (int i : STORAGE) {
            storage.add(at(top, i));
        }
        ChestValue.Result items = ChestValue.compute(storage, cfg.getSort(), cfg.getMaxLines());
        ItemStack hopperStack = at(top, SHIPPING);
        boolean hopper = tag(hopperStack) != null;
        double coins = hopper ? heldCoins(hopperStack) : 0;
        List<Part> parts = new ArrayList<>(5);
        part(parts, "Fuel", at(top, FUEL));
        part(parts, "Hopper", hopperStack);
        part(parts, "Upgrade", at(top, UPGRADE_1));
        part(parts, "Upgrade", at(top, UPGRADE_2));
        part(parts, "Skin", at(top, SKIN));
        double total = items.total() + coins;
        for (Part p : parts) {
            total += Math.max(0, p.value());
        }
        CompoundTag head = tag(at(top, HEAD));
        Matcher m = MINION_ID.matcher(head == null ? "" : ItemKeys.id(head));
        Matcher t = TITLE.matcher(title);
        Craft craft = m.matches() ? craft(m.group(1), Integer.parseInt(m.group(2)))
                : new Craft(t.matches() ? "I-" + t.group(1) : "?", 0, 1, false);
        long generated = head == null ? -1 : head.getLongOr("resources_generated", -1);
        return new Result(total, items, coins, hopper, generated, List.copyOf(parts), craft);
    }

    private static ItemStack at(List<ItemStack> top, int i) {
        return i < top.size() ? top.get(i) : ItemStack.EMPTY;
    }

    /** The item's Skyblock data, null for an empty slot or a placeholder pane with no Skyblock id. */
    private static CompoundTag tag(ItemStack s) {
        CompoundTag tag = s.isEmpty() ? null : ItemNbt.view(s);
        return tag == null || ItemKeys.id(tag) == null ? null : tag;
    }

    private static void part(List<Part> parts, String slot, ItemStack s) {
        if (tag(s) != null) {
            double v = ChestValue.unitValue(s) * s.getCount();
            parts.add(new Part(slot, s.getHoverName().getString(), v > 0 ? v : -1));
        }
    }

    private static double heldCoins(ItemStack s) {
        for (String line : DungeonChestValuer.cleanLore(s)) {
            Matcher m = HELD.matcher(line);
            if (m.find()) {
                return Double.parseDouble(m.group(1).replace(",", ""));
            }
        }
        return 0;
    }

    private static NeuItemData.Info recipe(String id) {
        NeuItemData.Info info = NeuItemData.get(id);
        if (info == null && !NeuItemData.isSettled(id)) {
            synchronized (WATCHED) {
                if (WATCHED.size() > 512) {
                    WATCHED.clear();
                }
                if (WATCHED.add(id)) {
                    NeuItemData.future(id).whenComplete((i, e) -> ARRIVALS.incrementAndGet());
                }
            }
        }
        return info;
    }

    /** Tier N is crafted from tier N-1 plus materials, so the cost is every tier's materials from I to N. */
    static Craft craft(String base, int tier) {
        double sum = 0;
        int unknown = 0;
        boolean loading = false;
        for (int t = tier; t >= 1; t--) {
            String id = base + "_GENERATOR_" + t;
            NeuItemData.Info info = recipe(id);
            if (info == null || !info.hasCraftingRecipe()) {
                loading |= info == null && !NeuItemData.isSettled(id);
                unknown++;
                continue;
            }
            String prev = base + "_GENERATOR_" + (t - 1);
            for (int i = 0; i < info.grid().size(); i++) {
                String ing = info.grid().get(i);
                if (ing == null || ing.equals(prev)) {
                    continue;
                }
                double p = PriceData.unitPrice(ing);
                if (p > 0) {
                    sum += p * info.gridCounts().get(i);
                } else {
                    unknown++;
                }
            }
        }
        return new Craft(tier == 1 ? "I" : "I-" + roman(tier), sum, unknown, loading);
    }

    private static String roman(int n) {
        String[] r = {"X", "IX", "V", "IV", "I"};
        int[] v = {10, 9, 5, 4, 1};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            for (; n >= v[i]; n -= v[i]) {
                sb.append(r[i]);
            }
        }
        return sb.toString();
    }

    private static String coins(double v) {
        return "§6" + DungeonChestValuer.formatCoins(Math.round(v));
    }

    /** The panel: total, the storage list (scrolled by offset), hopper coins, parts, Generated, then Craft Cost apart. */
    static List<String> format(Result r, int offset) {
        List<String> out = new ArrayList<>();
        out.add("§lMinion Value: §r" + coins(r.total()));
        List<String> items = ChestValue.format(r.storage(), offset);
        if (items.size() > 1) {
            out.addAll(items.subList(1, items.size()));
        } else {
            out.add("§8Storage empty");
        }
        if (r.hopper()) {
            out.add("§eHopper Coins  " + coins(r.hopperCoins()));
        }
        for (Part p : r.parts()) {
            out.add("§7" + p.slot() + ": §f" + ChestValue.clip(p.name()) + "  " + (p.value() > 0 ? coins(p.value()) : "§8?"));
        }
        if (r.generated() >= 0) {
            out.add("§7Generated: §f" + String.format(Locale.ROOT, "%,d", r.generated()));
        }
        Craft c = r.craft();
        String cost = c.loading() ? "§8loading..." : coins(c.priced()) + (c.unknown() > 0 ? " §8+ ?" : "");
        out.add("§7Craft Cost (" + c.tiers() + ")  " + cost);
        return out;
    }
}

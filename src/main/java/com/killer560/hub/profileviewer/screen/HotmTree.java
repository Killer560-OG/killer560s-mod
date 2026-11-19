package com.killer560.hub.profileviewer.screen;

import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.ACCENT;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.DIM;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.GOOD;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.TEXT;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.VALUE;
import static com.killer560.hub.profileviewer.screen.ProfileViewerScreen.tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The Heart of the Mountain perk tree as the /hotm menu lays it out: 7 columns, tier 10 on top down to tier 1, every perk at
 * its real slot (hotmtree-1118). One data table; the drawing is arithmetic over it, nothing is allocated unless a node is
 * hovered. Slots are inventory slots of a 9-wide menu (column = (slot - 1) % 9, row = (slot - 1) / 9, tier = 10 - row).
 */
final class HotmTree {
    private HotmTree() {
    }

    /** One perk. {@code effect} gets the level to describe (a pickaxe ability gets its ability level, 1 to 3). */
    record Perk(String id, String name, int slot, int max, Item icon, boolean ability, IntFunction<String> effect) {
        int col() { return (slot - 1) % 9; }
        int row() { return (slot - 1) / 9; }
        int tier() { return 10 - row(); }
    }

    static final int COLS = 7;
    static final int ROWS = 10;

    private static Perk p(String id, String name, int slot, int max, Item icon, IntFunction<String> effect) {
        return new Perk(id, name, slot, max, icon, false, effect);
    }

    private static Perk ab(String id, String name, int slot, Item icon, IntFunction<String> effect) {
        return new Perk(id, name, slot, 1, icon, true, effect);
    }

    /** A number without a trailing ".0", at most two decimals. */
    static String n(double v) {
        double r = Math.round(v * 100) / 100.0;
        return r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r);
    }

    private static final String[] PEAK = {"+1 Token of the Mountain", "+1 Pickaxe Ability level", "+1 commission slot",
            "+1 Mithril Powder when mining Mithril", "+1 Token of the Mountain", "+2 Gemstone Powder when mining Gemstones",
            "+1 Token of the Mountain", "+3 Glacite Powder when mining Glacite", "+10% chance for a Glacite Mineshaft to spawn",
            "+2 Tokens of the Mountain"};

    static final Perk[] PERKS = {
            ab("gemstone_infusion", "Gemstone Infusion", 1, Items.AMETHYST_SHARD, a -> "Ability: +100% effectiveness of every Gemstone in your pick for " + (15 + 5 * a) + "s. Cooldown 120s."),
            p("crystalline", "Crystalline", 2, 50, Items.DIAMOND, l -> "+" + n(l * 0.5) + "% chance of a Glacite Mineshaft with a Gemstone Crystal."),
            p("gifts_from_the_departed", "Gifts from the Departed", 3, 100, Items.CHEST, l -> n(l * 0.2) + "% chance of an extra item from a Frozen Corpse."),
            p("mining_master", "Mining Master", 4, 10, Items.NETHER_STAR, l -> "+" + n(l * 0.1) + " Pristine."),
            p("hungry_for_more", "Dead Man's Chest", 5, 50, Items.ENDER_CHEST, l -> l + "% chance of one more Frozen Corpse when you enter a Glacite Mineshaft."),
            p("vanguard_seeker", "Vanguard Seeker", 6, 50, Items.IRON_BLOCK, l -> "+" + l + "% chance of a Glacite Mineshaft with a Vanguard Corpse."),
            ab("sheer_force", "Sheer Force", 7, Items.TNT, a -> "Ability: +200 Mining Spread for " + (15 + 5 * a) + "s. Cooldown 120s."),
            p("metal_head", "Metal Head", 11, 20, Items.IRON_INGOT, l -> "+" + 5 * l + " Dwarven Metal Fortune."),
            p("rags_to_riches", "Rags to Riches", 13, 50, Items.GOLD_NUGGET, l -> "+" + 4 * l + " Mining Fortune in a Glacite Mineshaft."),
            p("eager_adventurer", "Eager Adventurer", 15, 100, Items.COMPASS, l -> "+" + 4 * l + " Mining Speed in a Glacite Mineshaft."),
            p("miners_blessing", "Miner's Blessing", 19, 1, Items.GOLDEN_CARROT, l -> "+30 Magic Find on all Mining Islands."),
            p("no_stone_unturned", "No Stone Unturned", 20, 50, Items.SPYGLASS, l -> "+" + n(l * 0.5) + "% Suspicious Scrap chance in a Glacite Mineshaft."),
            p("strong_arm", "Strong Arm", 21, 100, Items.IRON_PICKAXE, l -> "+" + 5 * l + " Mining Speed when mining Dwarven Metals."),
            p("steady_hand", "Steady Hand", 22, 100, Items.AMETHYST_CLUSTER, l -> "+" + n(l * 0.1) + " Gemstone Spread in a Glacite Mineshaft."),
            p("warm_hearted", "Warm Heart", 23, 50, Items.CAMPFIRE, l -> "+" + n(l * 0.4) + " Cold Resistance."),
            p("surveyor", "Surveyor", 24, 20, Items.MAP, l -> "+" + n(l * 0.75) + "% chance of a Glacite Mineshaft in the Glacite Tunnels."),
            p("mineshaft_mayhem", "Mineshaft Mayhem", 25, 1, Items.TNT_MINECART, l -> "Every Glacite Mineshaft entry gives a random buff: +5% Suspicious Scrap chance, +100 Mining Fortune, +200 Mining Speed, +10 Cold Resistance or -25% Pickaxe Ability cooldowns."),
            p("mining_speed_2", "Speedy Mineman", 29, 50, Items.SUGAR, l -> "+" + 40 * l + " Mining Speed."),
            p("powder_buff", "Powder Buff", 31, 50, Items.GLOWSTONE_DUST, l -> "+" + l + "% Powder from any source."),
            p("mining_fortune_2", "Fortunate Mineman", 33, 50, Items.GOLD_INGOT, l -> "+" + 3 * l + " Mining Fortune."),
            ab("anomalous_desire", "Anomalous Desire", 37, Items.ENDER_EYE, a -> "Ability: +" + (20 + 10 * a) + "% chance of rare occurrences for 30s. Cooldown " + (130 - 10 * a) + "s."),
            p("blockhead", "Blockhead", 38, 20, Items.STONE_BRICKS, l -> "+" + 5 * l + " Block Fortune."),
            p("subterranean_fisher", "Subterranean Fisher", 39, 40, Items.FISHING_ROD, l -> "+" + n(1 + l * 0.5) + " Fishing Speed and +" + n(1 + l * 0.1) + " Sea Creature Chance in the Crystal Hollows and Glacite Tunnels."),
            p("keep_it_cool", "Keep It Cool", 40, 50, Items.MAGMA_CREAM, l -> "+" + n(l * 0.4) + " Heat Resistance."),
            p("lonesome_miner", "Lonesome Miner", 41, 45, Items.LEATHER_BOOTS, l -> "+" + n(5 + (l - 1) * 0.5) + "% Strength, Crit Chance, Crit Damage, Defense and Health on Mining Islands."),
            p("great_explorer", "Great Explorer", 42, 20, Items.TRIPWIRE_HOOK, l -> "+" + (20 + 4 * (l - 1)) + "% treasure chest chance in the Crystal Hollows, " + (1 + l / 5) + " fewer locks."),
            ab("maniac_miner", "Maniac Miner", 43, Items.GOLDEN_PICKAXE, a -> "Ability: +1 Breaking Power and +" + 5 * a + " Mining Fortune per block broken (caps at 1,000) for " + (20 + 5 * a) + "s. Cooldown 60s."),
            p("daily_grind", "Daily Grind", 47, 1, Items.WRITABLE_BOOK, l -> "Your first daily commission on each Mining Island gives 500 Powder times your HOTM level."),
            p("special_0", "Peak of the Mountain", 49, 10, Items.BEACON, l -> "Level " + l + ": " + PEAK[l - 1] + "."),
            p("daily_powder", "Daily Powder", 51, 1, Items.REDSTONE, l -> "The first ore you mine each day gives 500 Powder times your HOTM level."),
            p("daily_effect", "Sky Mall", 55, 1, Items.CLOCK, l -> "A random buff every SkyBlock day on any Mining Island."),
            p("old_school", "Old-School", 56, 20, Items.COAL, l -> "+" + 5 * l + " Ore Fortune."),
            p("professional", "Professional", 57, 140, Items.DIAMOND_PICKAXE, l -> "+" + (50 + 5 * l) + " Mining Speed when mining Gemstones."),
            p("mole", "Mole", 58, 200, Items.STONE_SHOVEL, l -> "+" + n(50 + (l - 1) * 350 / 199.0) + " Mining Spread when mining Hard Stone."),
            p("fortunate", "Gem Lover", 59, 20, Items.EMERALD, l -> "+" + (20 + 4 * l) + " Gemstone Fortune."),
            p("mining_experience", "Seasoned Mineman", 60, 100, Items.EXPERIENCE_BOTTLE, l -> "+" + n(5 + l * 0.1) + " Mining Wisdom."),
            p("front_loaded", "Front Loaded", 61, 1, Items.BLAZE_POWDER, l -> "For the first 2,500 Gemstones you mine each day: 3x Gemstone Powder, +150 Gemstone Fortune, +250 Mining Speed."),
            p("random_event", "Luck of the Cave", 65, 45, Items.RABBIT_FOOT, l -> "+" + (5 + l) + "% chance of rare occurrences in the Dwarven Mines."),
            p("efficient_miner", "Efficient Miner", 67, 100, Items.DEEPSLATE, l -> "+" + 3 * l + " Mining Spread."),
            p("forge_time", "Quick Forge", 69, 20, Items.ANVIL, l -> "Forge time -" + n(Math.min(30, 10 + l * 0.5 + (l / 20) * 10)) + "%."),
            ab("mining_speed_boost", "Mining Speed Boost", 74, Items.SUGAR, a -> "Ability: +" + (150 + 50 * a) + "% Mining Speed for " + (5 + 5 * a) + "s. Cooldown 120s."),
            p("precision_mining", "Precision Mining", 75, 1, Items.TARGET, l -> "A particle target on ores and Dwarven Metals gives +30% Mining Speed while you aim at it."),
            p("mining_fortune", "Mining Fortune", 76, 50, Items.GOLD_INGOT, l -> "+" + 2 * l + " Mining Fortune."),
            p("titanium_insanium", "Titanium Insanium", 77, 50, Items.IRON_NUGGET, l -> n(2 + l * 0.1) + "% chance to turn Mithril Ore into Titanium Ore."),
            ab("pickaxe_toss", "Pickobulus", 78, Items.TNT, a -> "Ability: throw your pickaxe to mine every ore in a 3 block radius. Cooldown " + (70 - 10 * a) + "s."),
            p("mining_speed", "Mining Speed", 85, 50, Items.GOLDEN_PICKAXE, l -> "+" + 20 * l + " Mining Speed."),
    };

    /**
     * Draws the tree in a box at x, y, w, h (the cell is the largest that fits both ways) and sets the hover tooltip.
     * {@code hotmLevel} locks the tiers above it; {@code selected} is the chosen pickaxe ability id.
     */
    static void draw(ProfileViewerScreen s, GuiGraphicsExtractor g, Map<String, Integer> nodes, String selected, int hotmLevel,
                     int x, int y, int w, int h, int mx, int my) {
        int cell = Math.max(8, Math.min(w / COLS, h / ROWS));
        int x0 = x + (w - cell * COLS) / 2;
        int box = cell - 2;
        int peak = nodes.getOrDefault("special_0", 0);
        Perk hit = null;
        for (Perk pk : PERKS) {
            int lv = nodes.getOrDefault(pk.id(), 0);
            int px = x0 + pk.col() * cell + 1, py = y + pk.row() * cell + 1;
            boolean open = pk.tier() <= hotmLevel, have = lv > 0, maxed = have && lv >= pk.max();
            boolean sel = pk.ability() && pk.id().equals(selected);
            s.slotBox(g, px, py, box, sel ? 0xFFFFFFFF : maxed ? GOOD : have ? ACCENT : 0);
            ItemStack st = new ItemStack(pk.icon());
            float sc = Math.min(1f, (box - 2) / 16f);
            g.pose().pushMatrix();
            g.pose().translate(px + (box - 16 * sc) / 2f, py + (box - 16 * sc) / 2f);
            g.pose().scale(sc, sc);
            g.item(st, 0, 0);
            g.pose().popMatrix();
            if (!have) {
                g.fill(px + 1, py + 1, px + box - 1, py + box - 1, open ? 0x90000000 : 0xC8000000);
            } else if (pk.max() > 1) {
                String t = String.valueOf(lv);
                tx(g, t, px + box - s.font().width(t) - 1, py + box - 8, maxed ? GOOD : VALUE);
            }
            if (mx >= px && mx < px + box && my >= py && my < py + box) {
                hit = pk;
            }
        }
        if (hit != null && !s.dropdownOpen) {
            s.pendingTooltip = tooltip(hit, nodes.getOrDefault(hit.id(), 0), peak >= 2 ? 2 : 1, hotmLevel, hit.id().equals(selected));
        }
    }

    private static List<Component> tooltip(Perk pk, int lv, int abilityLevel, int hotmLevel, boolean selected) {
        List<Component> out = new ArrayList<>();
        boolean have = lv > 0, maxed = have && lv >= pk.max();
        out.add(line(pk.name(), maxed ? GOOD : have ? VALUE : DIM));
        out.add(line("Tier " + pk.tier() + (pk.ability() ? " - Pickaxe Ability" : ""), DIM));
        if (pk.max() > 1) {
            out.add(line("Level " + lv + "/" + pk.max(), TEXT));
        } else {
            out.add(line(have ? "Unlocked" : "Locked", have ? GOOD : DIM));
        }
        if (selected) {
            out.add(line("Selected ability", ACCENT));
        }
        out.add(line("", TEXT));
        wrap(out, pk.effect().apply(pk.ability() ? abilityLevel : Math.max(1, lv)), TEXT);
        if (have && pk.max() > 1 && !maxed) {
            wrap(out, "Next level: " + pk.effect().apply(lv + 1), DIM);
        }
        if (pk.tier() > hotmLevel) {
            out.add(line("Requires HOTM level " + pk.tier(), 0xFFFF5555));
        }
        return out;
    }

    private static void wrap(List<Component> out, String text, int color) {
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.length() + word.length() > 40 && cur.length() > 0) {
                out.add(line(cur.toString(), color));
                cur.setLength(0);
            }
            cur.append(cur.length() == 0 ? "" : " ").append(word);
        }
        out.add(line(cur.toString(), color));
    }

    private static Component line(String str, int color) {
        return Component.literal(str).withStyle(ProfileViewerScreen.style(color));
    }
}

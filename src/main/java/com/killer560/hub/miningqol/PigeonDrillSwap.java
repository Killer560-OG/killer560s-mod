package com.killer560.hub.miningqol;

import com.killer560.hub.autoroutes.ItemIdentity;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.HotbarSelect;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Swap To Drill On Pigeon (killer560, 2026-11-03: "make a swap to drill on open royal pidgeon"), cheat-only. Using the
 * Royal Pigeon opens the Commissions menu a few ticks later; on the use this changes the held hotbar slot (0-7 only) to a
 * drill, so the claim is made with that drill in hand.
 *
 * <p>What the wiki says (hypixelskyblock.minecraft.wiki, Commissions / Royal Pigeon): the powder a claim gives depends on
 * the commission's area, the Heart of the Mountain level and whether it is a daily; NOT on the held item. The one
 * held-item effect the Royal Pigeon page mentions is Compact: holding a mining tool with Compact when claiming raises the
 * Mining XP gained. So "best drill" is chosen from lore: the highest Compact level first, then the biggest stat line
 * naming the wanted powder (Mithril or Gemstone Powder), then the lowest slot. Auto wants Gemstone Powder in the Crystal
 * Hollows and Mithril Powder anywhere else.
 *
 * <p>drillswap-1114 (killer560: "swap to whatever drill gives the highest bonus ... not just the highest tier drill"): Rank
 * Drills By Powder Bonus (default) ranks by the drill's own unconditional "+N% <Powder> Powder" lore, Compact Level keeps
 * the old order. Auto reads the powder of the DONE commissions about to be claimed ({@link #claimPowder}), falling back to
 * the area. The wiki does not say a held drill changes a claim's powder; the owner's wish is what this follows.
 *
 * <p>Never touches slot 8, never moves an item, never clicks. Takes {@link ActionGate.Actor#PIGEON_DRILL}, and waits out
 * {@link HotbarSelect#swapWouldFlag()} (a held-slot change right after the use packet in one tick is Grim's PacketOrderE).
 */
public final class PigeonDrillSwap {

    private static final int PENDING_TICKS = 12;
    private static final Pattern ROMAN = Pattern.compile("\\bCompact ([IVXLC]{1,6})\\b");
    private static final Pattern PERCENT = Pattern.compile("\\+(\\d{1,3}(?:\\.\\d)?)%");
    private static final Pattern CONDITIONAL = Pattern.compile("\\b(?:when|near|while|during|if)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BONUS_HEADER = Pattern.compile("^[A-Za-z' ]{3,30} Bonus$");
    /** A powder type named on a line ("Mithril, Gemstone and Glacite Powder" names all three). */
    private static final Pattern OTHER_POWDER = Pattern.compile("mithril|gemstone|glacite");
    /** Commission names that give another powder than the area's (inferred from the names; the wiki lists none). */
    private static final Pattern GLACITE_NAME = Pattern.compile("glacite", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEMSTONE_NAME = Pattern.compile("gemstone|amber|amethyst|jade|jasper|opal|ruby|sapphire|topaz",
            Pattern.CASE_INSENSITIVE);

    private static int pendingTicks = 0;
    private static int swaps = 0;
    private static int lastSwapSlot = -1;
    /** After a swap: the slot to confirm is still held on the next ticks (the server may put him back). */
    private static int verifySlot = -1;
    private static int verifyFrom = -1;
    private static int verifyTicks = 0;
    private static boolean retried = false;

    private PigeonDrillSwap() {
    }

    static void register() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                onUse(player.getItemInHand(hand));
            }
            return InteractionResult.PASS;
        });
        // Aimed at a block, vanilla runs the block use first and only a PASS reaches UseItemCallback: the pigeon is
        // ghost-placed (a consumed use), so the item callback never fired and no swap was made (pigeondrill-1110).
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) {
                onUse(player.getItemInHand(hand));
            }
            return InteractionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("PigeonDrillSwap", PigeonDrillSwap::tick));
    }

    /** A use of {@code held} (the client thread). */
    static void onUse(ItemStack held) {
        if (!MiningQolConfig.getInstance().isPigeonDrill()) return;
        if ("ROYAL_PIGEON".equals(ItemIdentity.skyblockId(held))) {
            pendingTicks = PENDING_TICKS;
        }
    }

    static void tick(Minecraft client) {
        if (verifyTicks > 0) {
            if (--verifyTicks == 0) verify(client);
            return;
        }
        if (pendingTicks <= 0) return;
        LocalPlayer player = client.player;
        if (player == null || !MiningQolConfig.getInstance().isPigeonDrill()
                || com.killer560.hub.compat.McCompat.screen(client) != null) {
            pendingTicks = 0; // the menu is already up (a vanilla client cannot change its slot) or the setting went off
            return;
        }
        pendingTicks--;
        if (HotbarSelect.swapWouldFlag() || !ActionGate.tryAct(ActionGate.Actor.PIGEON_DRILL)) {
            return; // next tick
        }
        pendingTicks = 0;
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        int slot = pick(player, cfg.getPigeonDrillMode(), cfg.getPigeonDrillRank(), cfg.getPigeonDrillName(),
                powderFor(cfg.getPigeonDrillMode()));
        if (slot < 0) {
            ModChat.send("Pigeon Drill", Component.literal("No drill in your hotbar to swap to.").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (player.getInventory().getSelectedSlot() == slot) {
            return; // already holding it
        }
        verifyFrom = player.getInventory().getSelectedSlot();
        HotbarSelect.select(client, player, slot);
        swaps++;
        lastSwapSlot = slot;
        verifySlot = slot;
        verifyTicks = 2;
        retried = false;
    }

    /** Announces only a swap that held. When the server put him back on the pigeon slot (the ghost place or a slot
     *  correction) it retries once; a slot he moved himself is left alone. */
    private static void verify(Minecraft client) {
        LocalPlayer player = client.player;
        verifyTicks = 0;
        if (player == null || com.killer560.hub.compat.McCompat.screen(client) != null) return;
        int held = player.getInventory().getSelectedSlot();
        if (held == verifySlot) {
            ModChat.log("Pigeon Drill", Component.literal("Swapped to " + stripped(player.getInventory().getItem(verifySlot)) + ".")
                    .withStyle(ChatFormatting.GRAY));
        } else if (held == verifyFrom && !retried) {
            if (HotbarSelect.swapWouldFlag()) {
                verifyTicks = 1; // same tick as a use: next tick
            } else {
                retried = true;
                HotbarSelect.select(client, player, verifySlot);
                verifyTicks = 2;
            }
        }
    }

    /** "mithril", "gemstone" or "glacite": a fixed Drill Choice, or for Auto the powder of the finished commissions. */
    static String powderFor(MiningQolConfig.PigeonDrillMode mode) {
        return switch (mode) {
            case MITHRIL -> "mithril";
            case GEMSTONE -> "gemstone";
            case GLACITE -> "glacite";
            case AUTO -> claimPowder(MiningState.commissions(), areaPowder());
            case SPECIFIC -> "";
        };
    }

    /** The powder the area gives when nothing finer can be read. */
    public static String areaPowder() {
        return MiningState.crystalHollows() ? "gemstone" : MiningState.glaciteTunnels() || MiningState.mineshaft() ? "glacite" : "mithril";
    }

    /** The powder of one commission by its name (a Glacite or gemstone word), else {@code area}. */
    static String commissionPowder(String name, String area) {
        if (GLACITE_NAME.matcher(name).find()) return "glacite";
        return GEMSTONE_NAME.matcher(name).find() ? "gemstone" : area;
    }

    /** The powder with the most DONE commissions (a tie: the area's); the area's powder when none is done. */
    public static String claimPowder(List<MiningState.Commission> coms, String area) {
        int mithril = 0, gemstone = 0, glacite = 0;
        for (MiningState.Commission c : coms) {
            if (!c.done()) continue;
            switch (commissionPowder(c.name(), area)) {
                case "mithril" -> mithril++;
                case "gemstone" -> gemstone++;
                default -> glacite++;
            }
        }
        int top = Math.max(mithril, Math.max(gemstone, glacite));
        if (top == 0) return area;
        int areaCount = area.equals("mithril") ? mithril : area.equals("gemstone") ? gemstone : glacite;
        if (areaCount == top) return area;
        return mithril == top ? "mithril" : gemstone == top ? "gemstone" : "glacite";
    }

    /** The hotbar slot (0-7) of the drill to hold for a claim, or -1. {@code powder} is "mithril", "gemstone" or "glacite" (ignored for Specific Drill). */
    public static int pick(LocalPlayer player, MiningQolConfig.PigeonDrillMode mode, MiningQolConfig.PigeonDrillRank rank,
                           String name, String powder) {
        String want = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        int best = -1;
        long bestScore = Long.MIN_VALUE;
        for (int i = 0; i < 8; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!isDrill(s)) continue;
            long score;
            if (mode == MiningQolConfig.PigeonDrillMode.SPECIFIC) {
                if (want.isEmpty() || !(stripped(s).toLowerCase(Locale.ROOT).contains(want)
                        || String.valueOf(ItemIdentity.skyblockId(s)).toLowerCase(Locale.ROOT).contains(want))) {
                    continue;
                }
                score = 0;
            } else {
                score = score(loreLines(s), powder, rank);
            }
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Drills and the Gemstone Gauntlet, by Skyblock id. */
    public static boolean isDrill(ItemStack s) {
        String id = ItemIdentity.skyblockId(s);
        return id != null && (id.contains("DRILL") || id.contains("GAUNTLET"));
    }

    /** Powder Bonus: the bonus in tenths of a percent x 1000 plus Compact; Compact Level: Compact x 1,000,000 plus the bonus. */
    static long score(List<String> lore, String powder, MiningQolConfig.PigeonDrillRank rank) {
        long compact = 0;
        for (String line : lore) {
            Matcher r = line.length() <= 80 ? ROMAN.matcher(line) : null;
            if (r != null && r.find()) {
                compact = Math.max(compact, roman(r.group(1)));
            }
        }
        long bonus = bonusTenths(lore, powder);
        return rank == MiningQolConfig.PigeonDrillRank.COMPACT ? compact * 1_000_000L + bonus : bonus * 1000L + Math.min(compact, 999);
    }

    /**
     * The drill's unconditional {@code powder} bonus in tenths of a percent: every "Grants +N% ... Powder" line, plus the
     * "+N% ... Powder" in the description lines of an "Upgrade Module:" entry. A line with when / near / while, and anything
     * under a "... Bonus" header (Stellar Bonus) up to the next blank line, is conditional and left out.
     */
    public static long bonusTenths(List<String> lore, String powder) {
        if (powder.isEmpty()) return 0;
        long sum = 0;
        boolean conditional = false;
        int module = 0;
        for (String line : lore) {
            String t = line.trim();
            if (t.isEmpty()) {
                conditional = false;
                module = 0;
                continue;
            }
            if (BONUS_HEADER.matcher(t).matches()) {
                conditional = true;
                module = 0;
                continue;
            }
            boolean moduleLine = module > 0 && t.indexOf(':') < 0;
            module = t.startsWith("Upgrade Module:") ? 3 : moduleLine ? module - 1 : 0;
            if (conditional || t.length() > 120 || CONDITIONAL.matcher(t).find() || !(moduleLine || t.startsWith("Grants"))) continue;
            Matcher p = PERCENT.matcher(t);
            String l = t.toLowerCase(Locale.ROOT);
            if (p.find() && l.contains("powder") && (l.contains(powder) || !OTHER_POWDER.matcher(l).find())) {
                sum += Math.round(Double.parseDouble(p.group(1)) * 10);
            }
        }
        return sum;
    }

    static int roman(String s) {
        int total = 0;
        int prev = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            int v = switch (s.charAt(i)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                default -> 0;
            };
            total += v < prev ? -v : v;
            prev = Math.max(prev, v);
        }
        return total;
    }

    public static List<String> loreLines(ItemStack s) {
        List<String> out = new ArrayList<>();
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore != null) {
            for (Component c : lore.lines()) {
                out.add(ChatFormatting.stripFormatting(c.getString()));
            }
        }
        return out;
    }

    /** Names of the drills in the hotbar (the settings tab cycles through these). */
    public static List<String> hotbarDrillNames() {
        List<String> out = new ArrayList<>();
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            for (int i = 0; i < 8; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (isDrill(s)) {
                    out.add(stripped(s));
                }
            }
        }
        return out;
    }

    private static String stripped(ItemStack s) {
        String n = ChatFormatting.stripFormatting(s.getHoverName().getString());
        return n == null ? "" : n;
    }


    public static void tickForTest(Minecraft client) { tick(client); }

    public static int swaps() { return swaps; }

    public static int lastSwapSlot() { return lastSwapSlot; }

    public static void resetForTest() {
        pendingTicks = 0;
        verifyTicks = 0;
        swaps = 0;
        lastSwapSlot = -1;
    }
}

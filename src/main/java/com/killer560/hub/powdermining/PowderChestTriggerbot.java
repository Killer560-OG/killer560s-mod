package com.killer560.hub.powdermining;

import com.killer560.hub.miningqol.MiningState;
import com.killer560.hub.miningqol.PigeonDrillSwap;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.HotbarSelect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Powder Chest Triggerbot (cheat build only; killer560, 2026-11-04: "add an auto swap to drill and drill triggerbot for
 * clicking on crystal hollows powder chests. make sure the triggerbot is limited to not click the same chest 1000 times a
 * second"). While the crosshair ({@code mc.hitResult}, so the look the server already has, within vanilla reach) is on a
 * treasure chest {@link TreasureChests} claimed, it right-clicks that exact hit: {@code gameMode.useItemOn} (vanilla's
 * {@code startPrediction}) and a swing, through {@link ActionGate}. Once {@link LockpickHelper} knows the lock's face, only
 * a hit on that face is clicked (a lock is picked on its spot; the wiki: five in a row).
 *
 * <p>Rate limit (killer560, 2026-11-04: "no more than once per tick on a chest. and let me set the cps slider as well"):
 * never two clicks on one chest in one client tick, paced to the CPS setting (1-20; 20 is a click every tick) by a credit
 * of cps/20 a tick, capped at one click. The per-chest map is forgotten on a world change and holds at most
 * {@link #MAX_CHESTS} chests.
 *
 * <p>Auto Swap: before a click the held slot (0-7) goes to the drill with the highest Gemstone Powder bonus. The wiki's
 * Treasure Chest loot modifiers list four drills (Ruby Drill TX-15 +5%, Gemstone Drill LT-522 +10%, Topaz Drill KGR-12
 * +15%, Jasper Drill X +20%); their lore reads "Grants +20% Gemstone Powder" (NEU repo). No such line: any drill. The swap
 * is one tick and the click the next, and it waits out {@link HotbarSelect#swapWouldFlag()} (PacketOrderE).
 */
public final class PowderChestTriggerbot {

    private static final int MAX_CHESTS = 32;
    private static final Pattern GEM_BONUS = Pattern.compile("\\+(\\d{1,3}(?:\\.\\d+)?)% Gemstone Powder");

    /** Chest -> the tick ({@link #ticks}) of its last click. Client thread only. */
    private static final Map<BlockPos, Long> CLICKS = new HashMap<>();
    private static Object level = null;
    private static long ticks = 0L;
    private static double credit = 0.0;
    /** This click's rate (clicks a second), re-rolled in [Min CPS, Max CPS] after every click. */
    private static int rate = -1;
    /** Slot and stack held before the first swap of a chest visit, to hand back on the next dig start (-1: none). */
    private static int backSlot = -1;
    private static ItemStack backStack = ItemStack.EMPTY;
    private static int swapBacks = 0;
    private static int clicks = 0;
    private static int swaps = 0;
    private static int lastSwapSlot = -1;

    private PowderChestTriggerbot() {
    }

    static void tick(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            CLICKS.clear();
            backSlot = -1;
            rate = -1;
        }
        ticks++;
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || !PowderMiningConfig.getInstance().isChestTriggerbot()
                || TreasureChests.chests().isEmpty() || com.killer560.hub.compat.McCompat.screen(client) != null
                || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (!TreasureChests.chests().containsKey(pos) || !MiningState.crystalHollows()) return;
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        if (rate < 0) rate = rollRate(cfg);
        credit = Math.min(1.0, credit + rate / 20.0);
        Direction lock = LockpickHelper.lockFace(pos);
        if (lock != null && lock != hit.getDirection()) return; // aimed off the lock
        Long last = CLICKS.get(pos);
        if (credit < 1.0 - 1e-9 || (last != null && last == ticks)) return;
        if (cfg.isChestTriggerbotSwap()) {
            int slot = bestSlot(player);
            int held = player.getInventory().getSelectedSlot();
            if (slot >= 0 && slot != held) {
                if (cfg.isChestTriggerbotNoSwapMining() && client.options.keyAttack.isDown()) return; // mining: keep the tool
                if (!HotbarSelect.swapWouldFlag() && ActionGate.tryAct(ActionGate.Actor.POWDER_CHEST_SWAP)) {
                    if (backSlot < 0) {
                        backSlot = held;
                        backStack = player.getInventory().getItem(held).copy();
                    }
                    HotbarSelect.select(client, player, slot);
                    swaps++;
                    lastSwapSlot = slot;
                }
                return; // the click goes next tick
            }
        }
        if (!ActionGate.tryAct(ActionGate.Actor.POWDER_CHEST_TRIGGER)) return;
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        com.killer560.hub.compat.McCompat.swingUse(player);
        if (last == null && CLICKS.size() >= MAX_CHESTS) {
            CLICKS.keySet().retainAll(TreasureChests.chests().keySet());
        }
        CLICKS.put(pos.immutable(), ticks);
        credit -= 1.0;
        rate = rollRate(cfg);
        clicks++;
    }

    /** A random whole rate in [Min CPS, Max CPS]. */
    private static int rollRate(PowderMiningConfig cfg) {
        int lo = cfg.getChestTriggerbotMinCps(), hi = cfg.getChestTriggerbotMaxCps();
        return lo >= hi ? lo : java.util.concurrent.ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    /**
     * Swap Back When Mining: called from the attack-block callback, i.e. before the dig packet of the first dig start on
     * a non-chest block. A slot change ahead of the dig is the vanilla order; one that would follow a use / click this
     * server tick (PacketOrderE) waits for the next dig start. Gives up quietly when the old slot no longer holds the
     * item he had.
     */
    public static void onDigStart(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (backSlot < 0 || player == null || TreasureChests.chests().containsKey(pos)
                || !PowderMiningConfig.getInstance().isChestTriggerbotSwapBack()) return;
        if (HotbarSelect.swapWouldFlag() || !ActionGate.tryAct(ActionGate.Actor.POWDER_CHEST_SWAP)) return;
        ItemStack now = player.getInventory().getItem(backSlot);
        if (ItemStack.isSameItem(now, backStack) && now.getHoverName().equals(backStack.getHoverName())) {
            HotbarSelect.select(mc, player, backSlot);
            swapBacks++;
        }
        backSlot = -1;
        backStack = ItemStack.EMPTY;
    }

    /** The hotbar slot (0-7) of the drill with the highest Gemstone Powder bonus (the held one on a tie), or -1. */
    static int bestSlot(LocalPlayer player) {
        int held = player.getInventory().getSelectedSlot();
        int best = -1;
        double bestBonus = -1;
        for (int i = 0; i < 8; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!PigeonDrillSwap.isDrill(s)) continue;
            double b = gemBonus(s);
            if (b > bestBonus || (b == bestBonus && i == held)) {
                bestBonus = b;
                best = i;
            }
        }
        return best;
    }

    /** The "+N% Gemstone Powder" on the item's lore, or 0. */
    static double gemBonus(ItemStack s) {
        double best = 0;
        for (String line : PigeonDrillSwap.loreLines(s)) {
            Matcher m = line.length() <= 120 ? GEM_BONUS.matcher(line) : null;
            if (m != null && m.find()) {
                best = Math.max(best, Double.parseDouble(m.group(1)));
            }
        }
        return best;
    }


    public static int clicks() { return clicks; }

    public static int swaps() { return swaps; }

    public static int swapBacks() { return swapBacks; }

    public static int lastSwapSlot() { return lastSwapSlot; }

    public static void resetForTest() {
        CLICKS.clear();
        credit = 0.0;
        rate = -1;
        backSlot = -1;
        swapBacks = 0;
        clicks = 0;
        swaps = 0;
        lastSwapSlot = -1;
    }
}

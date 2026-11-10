package com.killer560.hub.autosuperboom;

import com.killer560.hub.BuildVariant;
import com.killer560.hub.autoroutes.RouteExecutor;
import com.killer560.hub.compat.McCompat;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.HotbarSelect;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import com.killer560.hub.bosssim.BossGates;
import com.killer560.hub.leveraura.P3SectionTracker;
import com.killer560.hub.secretwaypoints.CryptScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Auto Superboom (cheat build, killer560, 2026-11-08, featsweep item 11): his own left click on a cracked wall (cracked stone
 * bricks) with something else in hand swaps to the Superboom first, and back one tick later. The click itself stays his: the
 * swap goes out at the START of the tick his press is queued in, ahead of that tick's handleKeybinds, so the dig the game
 * sends is the one a vanilla client sends after pressing the Superboom's hotbar key (hotbar keys are read before the attack
 * key). The swap back waits for a later tick and for {@link HotbarSelect#swapWouldFlag()} (GrimAC PacketOrderE).
 */
public final class AutoSuperboomFeature {

    private static int restoreSlot = -1;
    private static int boomSlot = -1;
    private static long swappedTick;

    private AutoSuperboomFeature() {
    }

    public static void register() {
        if (!BuildVariant.CHEAT_FEATURES_ENABLED) {
            return;
        }
        P3SectionTracker.register();
        int bit = FeatureBits.register("Auto Superboom", () -> AutoSuperboomConfig.getInstance().isEnabledRaw());
        ClientTickEvents.START_CLIENT_TICK.register(FeatureGuard.start("AutoSuperboom", bit, AutoSuperboomFeature::tick));
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.level == null || !AutoSuperboomConfig.getInstance().isEnabled()
                || !DungeonState.isInDungeon() || McCompat.screen(client) != null) {
            restoreSlot = -1;
            return;
        }
        P3SectionTracker.poll(client);
        boolean click = crackedClick(client);
        if (restoreSlot >= 0) {
            if (player.getInventory().getSelectedSlot() != boomSlot) {
                restoreSlot = -1; // he changed slot himself
            } else if (click) {
                swappedTick = ActionGate.currentTick(); // a second click on the wall: keep the Superboom out
            } else if (ActionGate.currentTick() > swappedTick && !HotbarSelect.swapWouldFlag()
                    && ActionGate.tryAct(ActionGate.Actor.SUPERBOOM_SWAP)) {
                HotbarSelect.select(client, player, restoreSlot);
                restoreSlot = -1;
            }
            return;
        }
        if (!click || HotbarSelect.swapWouldFlag()) {
            return;
        }
        int slot = RouteExecutor.superboomSlot(player);
        int held = player.getInventory().getSelectedSlot();
        if (slot < 0 || slot == held || !ActionGate.tryAct(ActionGate.Actor.SUPERBOOM_SWAP)) {
            return;
        }
        HotbarSelect.select(client, player, slot);
        boomSlot = slot;
        restoreSlot = held;
        swappedTick = ActionGate.currentTick();
    }

    /** His left click is queued for this tick's handleKeybinds and the crosshair is on something a Superboom opens within reach:
     *  a cracked wall, a P3 gate's block (only while P3 runs), or a crypt's or prince's tomb blocks. */
    private static boolean crackedClick(Minecraft client) {
        if (!ActionGate.hisClickQueued(client.options.keyAttack)
                || !(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState s = client.level.getBlockState(pos);
        return s.is(Blocks.CRACKED_STONE_BRICKS) || s.is(Blocks.INFESTED_CRACKED_STONE_BRICKS)
                || P3SectionTracker.running() && DungeonState.isBossPhaseActive() && BossGates.isGateBlock(pos, s)
                || isTombBlock(client, pos, s);
    }

    /** Crypt and prince tomb blocks around the click: {@link CryptScanner} runs only on a click at a block kind a tomb is made of,
     *  over a 33 x 33 window centered on it, and its answer is kept 5 s (or until a click lands 8 blocks from the center). */
    private static final java.util.Set<BlockPos> TOMB = new java.util.HashSet<>();
    private static BlockPos tombCenter = BlockPos.ZERO;
    private static long tombTick = Long.MIN_VALUE / 2;

    private static boolean isTombBlock(Minecraft client, BlockPos pos, BlockState s) {
        if (!(s.is(Blocks.SMOOTH_STONE_SLAB) || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.GOLD_BLOCK) || s.is(Blocks.SEA_LANTERN)
                || s.is(Blocks.OBSIDIAN) || s.is(Blocks.POLISHED_ANDESITE))) {
            return false;
        }
        long now = ActionGate.currentTick();
        if (now - tombTick > 100 || Math.abs(pos.getX() - tombCenter.getX()) > 8 || Math.abs(pos.getZ() - tombCenter.getZ()) > 8) {
            TOMB.clear();
            for (CryptScanner.Found f : CryptScanner.scan(client.level,
                    new int[]{pos.getX() - 16, pos.getZ() - 16, pos.getX() + 16, pos.getZ() + 16})) {
                TOMB.addAll(f.blocks());
            }
            tombCenter = pos;
            tombTick = now;
        }
        return TOMB.contains(pos);
    }
}

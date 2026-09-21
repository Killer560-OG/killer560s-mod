package com.killer560.hub.secrettrigger;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.cheatutils.SecretAuraFeature;
import com.killer560.hub.livemap.LiveMapFeature;
import com.killer560.hub.roomdatabase.RoomEntry;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.secrets.SecretsFeature;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.AbstractSkullBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Secret Triggerbot - ported from QUOI {@code dungeon/secrets/impl/SecretTriggerbot.kt}: when the crosshair
 * ({@code mc.hitResult}, so already within vanilla reach) is on a secret, wait the delay, optionally swap to a
 * hotbar slot, click that exact hit once ({@code gameMode.useItemOn} + swing), optionally swap back.
 * <ul>
 * <li>Secret = QUOI {@code Dungeon.isSecret}: chest / trapped chest / lever / Wither Essence or Redstone Key skull.
 * <li>Never while a screen is open or in boss (QUOI); Water Board skipped entirely (QUOI). Also skipped, from
 * {@link SecretAuraFeature}'s room rules: Three Weirdos (a wrong chest fails the puzzle) and levers in Tic Tac Toe.
 * <li>Each position is clicked at most once per world (QUOI {@code clickedBlocks}); secrets
 * {@link SecretAuraFeature} already marked done are never clicked, and a confirmed loot (chest opened / chest GUI
 * opened / lever flipped, by anyone) is shared back to it so Secret Aura won't re-click it either.
 * <li>If the crosshair left the block when the delay ends, the click is dropped without swapping (QUOI swaps anyway
 * and only skips the click).
 * </ul>
 */
public final class SecretTriggerbotFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-secrettriggerbot");

    /** Same skin id as {@code dungeonalerts.SecretSound} / QUOI {@code Dungeon.REDSTONE_KEY}. */
    private static final UUID REDSTONE_KEY_ID = UUID.fromString("fed95410-aba1-39df-9b95-1d4f361eb66e");
    private static final Set<String> SKIP_ROOMS = Set.of("Water Board", "Three Weirdos");
    private static final Set<String> NO_LEVER_ROOMS = Set.of("Tic Tac Toe");
    private static final long CHEST_GUI_CONFIRM_MS = 2000;

    private static final Set<Long> clicked = new HashSet<>();
    /** Lever POWERED state when first under the crosshair - a change means it was flipped (by us or a teammate). */
    private static final Map<Long, Boolean> leverSeenState = new HashMap<>();

    private static BlockPos pendingPos = null;
    private static long triggerAtMs = 0;
    private static int originalSlot = -1;
    private static boolean swapBackPending = false;
    private static long lastClickMs = 0;
    private static BlockPos lastClickedChest = null;
    private static Object lastLevel = null;
    private static String lastGateLog = null;

    private SecretTriggerbotFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(SecretTriggerbotFeature::tick);
    }

    private static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            lastLevel = client.level;
            reset("world change");
        }
        LocalPlayer player = client.player;
        if (client.level == null || player == null || client.gameMode == null) {
            return;
        }
        SecretTriggerbotConfig cfg = SecretTriggerbotConfig.getInstance();
        if (!cfg.isEnabled()) {
            pendingPos = null;
            logGate("disabled");
            return;
        }
        long now = System.currentTimeMillis();
        updateDone(client, now);

        String gate = null;
        if (!CheatUtils.isOnDungeonServer(client)) {
            gate = "not on hypixel/p3sim";
        } else if (!DungeonState.isInDungeon()) {
            gate = "not in dungeon";
        } else if (client.screen != null) {
            gate = "screen open";
        } else if (LiveMapFeature.isInBoss()) {
            gate = "in boss";
        }
        RoomEntry room = gate == null ? LiveMapFeature.currentRoomEntry() : null;
        String roomName = room == null ? null : room.name;
        if (gate == null && roomName != null && SKIP_ROOMS.contains(roomName)) {
            gate = "skipped room " + roomName;
        }
        if (gate != null) {
            // QUOI: Water Board drops the pending trigger; screen/boss just pause it. Dropping is the safe superset.
            dropPending(cfg);
            logGate(gate);
            return;
        }
        logGate("active");

        if (swapBackPending) {
            swapBackPending = false;
            if (originalSlot >= 0 && originalSlot <= 8 && player.getInventory().getSelectedSlot() != originalSlot) {
                swapTo(player, originalSlot);
            }
            originalSlot = -1;
            return;
        }

        if (pendingPos == null) {
            if (now - lastClickMs < cfg.getCooldownMs()) {
                return;
            }
            BlockHitResult hit = crosshairBlock(client);
            if (hit == null) {
                return;
            }
            BlockPos pos = hit.getBlockPos();
            if (isDone(pos) || !isSecret(client, pos, client.level.getBlockState(pos), roomName)) {
                return;
            }
            pendingPos = pos.immutable();
            triggerAtMs = now + cfg.getDelayMs();
        }
        if (now < triggerAtMs) {
            return;
        }

        BlockHitResult hit = crosshairBlock(client);
        if (hit == null || !hit.getBlockPos().equals(pendingPos) || isDone(pendingPos)) {
            dropPending(cfg);
            return;
        }
        if (cfg.isSwapEnabled()) {
            int slot = cfg.getSwapSlot() - 1;
            int selected = player.getInventory().getSelectedSlot();
            if (selected != slot) {
                if (originalSlot == -1) {
                    originalSlot = selected;
                }
                swapTo(player, slot);
                return; // click next tick, like QUOI's await-swap step
            }
        }

        // Shared one-interaction-per-tick gate, above every state change below: a refused tick must leave
        // pendingPos/lastClickMs/clicked untouched so the same target is simply clicked a tick or two later.
        if (!com.killer560.hub.util.ActionGate.tryAct(com.killer560.hub.util.ActionGate.Actor.SECRET_TRIGGER)) {
            return;
        }
        BlockPos pos = pendingPos;
        pendingPos = null;
        BlockState state = client.level.getBlockState(pos);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        clicked.add(pos.asLong());
        lastClickMs = now;
        lastClickedChest = isChest(state.getBlock()) ? pos : null;
        if (cfg.isSwapEnabled() && cfg.isSwapBack() && originalSlot != -1) {
            swapBackPending = true;
        } else {
            originalSlot = -1;
        }
        LOGGER.info("[SecretTriggerbot] Clicked {} at {} (room={})",
                state.getBlock().getName().getString(), pos.toShortString(), roomName == null ? "?" : roomName);
    }

    /** Drops a pending trigger; if we already swapped for it, swap back (when enabled) once active again. */
    private static void dropPending(SecretTriggerbotConfig cfg) {
        pendingPos = null;
        if (originalSlot != -1 && !swapBackPending) {
            if (cfg.isSwapBack()) {
                swapBackPending = true;
            } else {
                originalSlot = -1;
            }
        }
    }

    /** QUOI {@code Dungeon.isSecret} plus the room lever rule. */
    private static boolean isSecret(Minecraft client, BlockPos pos, BlockState state, String roomName) {
        Block block = state.getBlock();
        if (isChest(block)) {
            return true;
        }
        if (block == Blocks.LEVER) {
            return roomName == null || !NO_LEVER_ROOMS.contains(roomName);
        }
        if (block instanceof AbstractSkullBlock) {
            return SecretsFeature.isWitherEssence(client.level, pos) || isRedstoneKey(client, pos);
        }
        return false;
    }

    private static boolean isRedstoneKey(Minecraft client, BlockPos pos) {
        BlockEntity be = client.level.getBlockEntity(pos);
        return be instanceof SkullBlockEntity skull && skull.getOwnerProfile() != null
                && skull.getOwnerProfile().partialProfile() != null
                && REDSTONE_KEY_ID.equals(skull.getOwnerProfile().partialProfile().id());
    }

    private static boolean isChest(Block block) {
        return block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST;
    }

    private static boolean isDone(BlockPos pos) {
        return clicked.contains(pos.asLong()) || SecretAuraFeature.isDone(pos);
    }

    /** Runs before the screen gate: a chest GUI opening means the looked-at / just-clicked chest was looted. */
    private static void updateDone(Minecraft client, long now) {
        BlockHitResult hit = crosshairBlock(client);
        if (client.screen instanceof ContainerScreen) {
            if (lastClickedChest != null && now - lastClickMs < CHEST_GUI_CONFIRM_MS) {
                markDone(lastClickedChest, "chest GUI opened after click");
                lastClickedChest = null;
            } else if (hit != null && isChest(client.level.getBlockState(hit.getBlockPos()).getBlock())) {
                markDone(hit.getBlockPos(), "chest GUI opened while looking at it");
            }
        }
        if (hit != null) {
            BlockPos pos = hit.getBlockPos();
            BlockState state = client.level.getBlockState(pos);
            if (state.getBlock() == Blocks.LEVER) {
                leverSeenState.putIfAbsent(pos.asLong(), state.getValue(LeverBlock.POWERED));
            } else if (client.level.getBlockEntity(pos) instanceof ChestBlockEntity chest && chest.getOpenNess(0f) > 0f) {
                markDone(pos, "chest opened");
            }
        }
        if (!leverSeenState.isEmpty()) {
            leverSeenState.entrySet().removeIf(e -> {
                BlockPos pos = BlockPos.of(e.getKey());
                BlockState st = client.level.getBlockState(pos);
                if (st.getBlock() != Blocks.LEVER || !st.getValue(LeverBlock.POWERED).equals(e.getValue())) {
                    markDone(pos, "lever flipped");
                    return true;
                }
                return false;
            });
        }
    }

    private static void markDone(BlockPos pos, String why) {
        clicked.add(pos.asLong());
        if (!SecretAuraFeature.isDone(pos)) {
            SecretAuraFeature.markDone(pos, "triggerbot: " + why);
        }
    }

    private static BlockHitResult crosshairBlock(Minecraft client) {
        return client.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK ? bhr : null;
    }

    private static void swapTo(LocalPlayer player, int slot) {
        player.getInventory().setSelectedSlot(slot);
        player.connection.send(new ServerboundSetCarriedItemPacket(slot));
    }

    private static void reset(String why) {
        if (!clicked.isEmpty()) {
            LOGGER.info("[SecretTriggerbot] Reset ({}): {} clicked/done", why, clicked.size());
        }
        clicked.clear();
        leverSeenState.clear();
        pendingPos = null;
        originalSlot = -1;
        swapBackPending = false;
        lastClickedChest = null;
    }

    private static void logGate(String gate) {
        if (!gate.equals(lastGateLog)) {
            lastGateLog = gate;
            LOGGER.info("[SecretTriggerbot] State: {}", gate);
        }
    }
}

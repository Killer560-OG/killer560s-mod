package com.killer560.hub.dungeonextras;

import com.killer560.hub.dungeonextras.mixin.MultiPlayerGameModeInvoker;
import com.killer560.hub.secrets.DungeonState;
import com.killer560.hub.util.ActionGate;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.WorldRenderUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Breaker Aura (cheat build) - while holding a DUNGEONBREAKER with charges, breaks blocks that obstruct the
 * player's path (the player's 0.6x1.8 hitbox swept forward along movement direction, or look direction when
 * standing still) within reach.
 * <p>
 * Sources: QUOI {@code DungeonBreaker.kt} auto-db tick loop (charges cap per cycle, eye-to-centre reach, skip
 * recently-attempted positions) and {@code AuraManager.breakBlock(immediate = true)} (one
 * {@code START_DESTROY_BLOCK} sent through {@code MultiPlayerGameMode#startPrediction} + main-hand swing, 6-tick
 * cooldown); QUOI {@code VecUtils.getHitResult} for the face; target validity = QUOI
 * {@code Dungeon.isProtectedBlock} (any block entity, or {@code blacklistedDBBlocks}) plus NoammAddons
 * {@code BreakerHelper.kt} (player heads, obsidian excluded from zero-ping). Charges are read from lore exactly like
 * {@code DungeonBreakerFeature} ("Charges: N/M"), with locally-spent charges subtracted until the lore changes.
 */
public final class BreakerAuraFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-dungeonextras");
    private static final String DUNGEON_BREAKER_SKYBLOCK_ID = "DUNGEONBREAKER";
    private static final Pattern CHARGES_PATTERN = Pattern.compile("Charges: (\\d+)/(\\d+)");
    /**
     * How long a block that was just attempted is left alone. FOUR ticks, not thirty.
     * <p>
     * It was 1500 ms. A break that did not take - the server disagreed, or the packet landed a tick early - then
     * sat out thirty ticks before being tried again, while he stood in front of a block that was plainly still
     * there. Neither vanilla nor QUOI has anything like it: QUOI simply re-targets the nearest block that is not
     * air, every tick, so a failed break is retried immediately. This only exists to stop the same block being
     * hammered while the server is still answering, and four ticks is enough for that.
     */
    private static final long RETRY_MS = 200L;
    private static final double FLOOR_CLEARANCE = 0.1;

    private static final Set<Block> BLACKLIST = Set.of(
            Blocks.BARRIER, Blocks.BEDROCK, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
            Blocks.REPEATING_COMMAND_BLOCK, Blocks.SKELETON_SKULL, Blocks.SKELETON_WALL_SKULL,
            Blocks.WITHER_SKELETON_SKULL, Blocks.WITHER_SKELETON_WALL_SKULL, Blocks.TNT,
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.END_PORTAL_FRAME, Blocks.END_PORTAL,
            Blocks.PISTON, Blocks.PISTON_HEAD, Blocks.STICKY_PISTON, Blocks.MOVING_PISTON,
            Blocks.LEVER, Blocks.STONE_BUTTON, Blocks.PLAYER_HEAD, Blocks.PLAYER_WALL_HEAD, Blocks.OBSIDIAN);

    private static final Map<BlockPos, Long> RECENT = new HashMap<>();
    /** Edge state for the pick key, so holding it toggles once rather than every tick. */
    private static boolean selectKeyWasDown;
    private static int cooldownTicks = 0;
    /**
     * How much further than the break reach a picked block may be and still arm the hand swap (blocks).
     * <p>
     * Sized so the settling window is spent before he arrives rather than after: at a sprint he covers a little
     * under a block a tick, so a block and a half is a tick or two of warning at the swap delays this allows
     * (1-20 ticks, his own is 1). It only ever brings the swap FORWARD - nothing outside the real reach is ever
     * broken, and with Auto Swap off it does nothing at all.
     */
    private static final double PRE_SWAP_REACH_MARGIN = 1.5;
    private static int lastLoreCharges = -1;
    private static int spentSinceLore = 0;
    private static String lastSkipReason = null;
    private static boolean wasActive = false;

    // ---- Auto Swap (killer560, 2026-09-20: "an option to make it swap to the breaker or not swap to the breaker
    // automatically ... If it swaps automatically it should whenever the blocks are in range swap to the breaker and
    // then break them"). A slot change and a block break on the same tick is not a thing a hand does, so the swap
    // arms a configurable delay and the first break only goes out once that delay has run down.
    private static int swappedFromSlot = -1;
    private static int swapArmedTicks = 0;
    private static int idleSinceSwapTicks = 0;
    private static boolean warnedNoBreakerInHotbar = false;

    private BreakerAuraFeature() {
    }

    private static void resetState() {
        RECENT.clear();
        lastSkipReason = null;
        spentSinceLore = 0;
        lastLoreCharges = -1;
        swappedFromSlot = -1;
        swapArmedTicks = 0;
        idleSinceSwapTicks = 0;
        warnedNoBreakerInHotbar = false;
    }

    // ---- where the ticks go ------------------------------------------------------------------------------------
    //
    // killer560 chose to keep one break a tick and get the speed back from the ticks that are being wasted
    // (2026-09-23), so the first thing needed is to know which ticks those are. The ceiling is 20 a second (the
    // action gate allows one automated interaction per client tick); his last run managed 6-10. This counts every
    // tick the aura was live and had something it wanted to break, and says where each one went, once every five
    // seconds and only while it is actually working.
    private static long censusFrom;
    private static int cTicks, cSent, cGate, cNoFace, cCooldown, cSwapWait, cOutOfReach;

    /** How far ahead of him a block is, along the way he is moving (negative = behind). */
    private static double ahead(BlockPos pos, Vec3 feet, double dirX, double dirZ) {
        return (pos.getX() + 0.5 - feet.x) * dirX + (pos.getZ() + 0.5 - feet.z) * dirZ;
    }

    /**
     * Is this block in the corridor he is walking down? Half his body (0.3) plus half a block (0.5), rounded up a
     * little, and it has to be in front of him rather than behind.
     */
    private static boolean inTheWay(BlockPos pos, Vec3 feet, double dirX, double dirZ) {
        double dx = pos.getX() + 0.5 - feet.x;
        double dz = pos.getZ() + 0.5 - feet.z;
        if (dx * dirX + dz * dirZ < -0.5) {
            return false; // behind him
        }
        return Math.abs(dx * dirZ - dz * dirX) <= 0.9; // sideways distance from the line he is walking
    }

    private static void census(String bucket) {
        cTicks++;
        switch (bucket) {
            case "sent" -> cSent++;
            case "gate" -> cGate++;
            case "face" -> cNoFace++;
            case "cooldown" -> cCooldown++;
            case "swap" -> cSwapWait++;
            default -> cOutOfReach++;
        }
        long now = System.currentTimeMillis();
        if (censusFrom == 0) {
            censusFrom = now;
            return;
        }
        if (now - censusFrom < 5_000L) {
            return;
        }
        double secs = (now - censusFrom) / 1000.0;
        if (cSent > 0 || cGate > 0) {
            LOGGER.info("[DungeonExtras] Breaker Aura {} tick(s) over {}s: {} sent ({} a second, ceiling 20)"
                            + " | {} gate gave the tick away | {} no face | {} cooldown | {} waiting on the swap"
                            + " | {} nothing in reach",
                    cTicks, String.format("%.1f", secs), cSent, String.format("%.1f", cSent / secs),
                    cGate, cNoFace, cCooldown, cSwapWait, cOutOfReach);
        }
        censusFrom = now;
        cTicks = cSent = cGate = cNoFace = cCooldown = cSwapWait = cOutOfReach = 0;
    }

    private static void skip(String reason) {
        if (!reason.equals(lastSkipReason)) {
            lastSkipReason = reason;
            LOGGER.info("[DungeonExtras] Breaker Aura idle: {}", reason);
        }
    }

    /**
     * The pick key: look at a block and press it to mark it for breaking; press it again on a marked block to
     * unmark it. killer560 (2026-09-23): "I should have a keybind to select blocks. If a block is selected it will
     * be broken... if i press the keybind on a selected block then it unbinds it."
     * <p>
     * Polled before every other gate in {@link #onClientTick} so picking works with the aura switched off - you
     * choose the wall first and turn it on afterwards - and unbound by default like every other key in this mod.
     * It does nothing while a screen is open, so typing cannot mark blocks.
     */
    private static void tickSelectKey(Minecraft client) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        int key = cfg.getBreakerAuraSelectKey();
        if (client.screen != null || client.player == null || client.level == null || client.getWindow() == null) {
            selectKeyWasDown = false;
            return;
        }
        boolean down = com.killer560.hub.util.KeyUtil.isKeyDown(client.getWindow(), key);
        boolean pressed = down && !selectKeyWasDown;
        selectKeyWasDown = down;
        if (!pressed) {
            return;
        }
        if (!(client.hitResult instanceof BlockHitResult hit)) {
            ModChat.send("Breaker Aura", ModChat.text("Look at a block to pick it."));
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (client.level.getBlockState(pos).isAir()) {
            return;
        }
        Set<String> picked = cfg.getBreakerAuraSelected();
        String k = key(pos);
        if (picked.remove(k)) {
            cfg.save();
            ModChat.send("Breaker Aura", ModChat.text("Unpicked "),
                    ModChat.value(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                    ModChat.dim(" (" + picked.size() + " picked)"));
            return;
        }
        if (!isValidTarget(client.level, pos)) {
            ModChat.send("Breaker Aura", ModChat.text("That one cannot be broken - "),
                    ModChat.value(client.level.getBlockState(pos).getBlock().getName().getString()));
            return;
        }
        picked.add(k);
        cfg.save();
        ModChat.send("Breaker Aura", ModChat.text("Picked "),
                ModChat.value(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                ModChat.dim(" (" + picked.size() + " picked)"));
    }

    /**
     * Draw the picked blocks, so choosing them is something he can see rather than something he has to remember.
     * Orange, like the rest of this mod's world markers, and outlined rather than filled so he can still see the
     * block he picked. Only while Breaker Aura is switched on, or while the pick key is bound and he is in a
     * dungeon - there is no point drawing a wall he marked three floors ago.
     */
    static void onWorldRender(net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext context) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (!cfg.isBreakerAuraEnabled() && cfg.getBreakerAuraSelectKey() == com.killer560.hub.util.KeyUtil.NONE) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            return;
        }
        double reachSq = cfg.getBreakerAuraReach() * cfg.getBreakerAuraReach();
        Vec3 eye = client.player.getEyePosition();
        for (BlockPos pos : selectedBlocks()) {
            if (!client.level.isLoaded(pos) || client.level.getBlockState(pos).isAir()) {
                continue;
            }
            // Brighter once it is close enough to actually be broken, so the reach is visible too.
            boolean inReach = eyeToBlockSq(eye, pos) <= reachSq;
            AABB box = new AABB(pos).inflate(0.002);
            WorldRenderUtils.renderOutlineBox(context, box, 1.0f, inReach ? 0.55f : 0.30f, 0.0f,
                    inReach ? 0.95f : 0.55f, 2.0f);
        }
    }

    private static String key(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos parse(String k) {
        String[] a = k.split(",");
        if (a.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim()),
                    Integer.parseInt(a[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Every picked block, for the renderer and the planner. */
    public static List<BlockPos> selectedBlocks() {
        List<BlockPos> out = new ArrayList<>();
        for (String k : DungeonExtrasConfig.getInstance().getBreakerAuraSelected()) {
            BlockPos p = parse(k);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** Forget every pick - the tab's button and {@code /breakeraura clear}. */
    public static int clearSelection() {
        Set<String> picked = DungeonExtrasConfig.getInstance().getBreakerAuraSelected();
        int n = picked.size();
        picked.clear();
        DungeonExtrasConfig.getInstance().save();
        return n;
    }

    /**
     * Put the breaker in his hand and start the settling window. A slot change cannot be followed by a break on
     * the same tick - the server has to see the held item change first - so THIS tick is the first tick of the
     * wait, hence the minus one: a delay of 1 used to mean swap, wait, break, which is two ticks for a setting
     * of one (the same off-by-one already fixed on the cooldown).
     */
    private static void armSwap(LocalPlayer player, DungeonExtrasConfig cfg, int selected, int breakerSlot) {
        if (swappedFromSlot < 0) {
            swappedFromSlot = selected;
        }
        player.getInventory().setSelectedSlot(breakerSlot);
        player.connection.send(new ServerboundSetCarriedItemPacket(breakerSlot));
        swapArmedTicks = Math.max(0, cfg.getBreakerAuraSwapDelayTicks() - 1);
        LOGGER.info("[DungeonExtras] Breaker Aura swapped {} -> {} for the breaker, first break in {} tick(s).",
                selected, breakerSlot, swapArmedTicks);
    }

    /**
     * Squared distance from the eye to the NEAREST POINT OF THE BLOCK, which is what the server measures a break
     * against - not the distance to the block's centre, which is what this used to ask for.
     * <p>
     * killer560 (2026-09-23): "It is still struggling to break blocks as i run into them. I shouldnt be outmoving
     * them but I dont know." He was not outmoving them; they were being called out of range while the server would
     * have allowed them. A centre is up to half a block further away on each axis, so the error grows with every
     * axis he is offset on - and a wall he runs at is offset on all three, because he is beside it, below it and
     * approaching it at once.
     * <p>
     * Measured on his own picks (the ten-block wall at x 53-56, y 132-133, z 142) walking in along -z at x 54.5
     * with his feet at y 131: standing four blocks out, the centre test finds 2 of the 10 in reach and this one
     * finds all 10. That is the whole wall available on the tick he arrives instead of two blocks of it.
     */
    private static double eyeToBlockSq(Vec3 eye, BlockPos pos) {
        double cx = Math.max(pos.getX(), Math.min(eye.x, pos.getX() + 1.0));
        double cy = Math.max(pos.getY(), Math.min(eye.y, pos.getY() + 1.0));
        double cz = Math.max(pos.getZ(), Math.min(eye.z, pos.getZ() + 1.0));
        double dx = eye.x - cx;
        double dy = eye.y - cy;
        double dz = eye.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isPicked(BlockPos pos) {
        return DungeonExtrasConfig.getInstance().getBreakerAuraSelected().contains(key(pos));
    }

    static void onClientTick(Minecraft client) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        // Picking comes first: he chooses the wall with the aura off, then turns it on.
        if (!cfg.isBreakerAuraRespectEditMode() || !isRouteEditModeActive()) {
            tickSelectKey(client);
        }
        boolean active = cfg.isBreakerAuraEnabled() && client.player != null && client.level != null
                && client.gameMode != null && DungeonState.isInDungeon();
        if (active != wasActive) {
            wasActive = active;
            LOGGER.info("[DungeonExtras] Breaker Aura {}.", active ? "active" : "inactive");
            if (!active) {
                restoreSlot(client, "turned off");
            }
            resetState();
        }
        if (!active) {
            return;
        }
        // killer560 (2026-09-20): "It needs an edit mode where when I am in the edit mode, the breaker aura will not
        // work." Placing route nodes means right-clicking blocks you are standing next to, which is exactly what the
        // aura wants to chew through. Auto Routes owns the only real edit mode in the mod; AP3 has none (see notes).
        if (cfg.isBreakerAuraRespectEditMode() && isRouteEditModeActive()) {
            restoreSlot(client, "edit mode");
            skip("Auto Routes edit mode is active");
            return;
        }
        if (com.killer560.hub.util.ActionGate.containerScreenOpen(client)) {
            // Mirrors ActionGate's WORLD rule (container screens only, not this mod's own menu),
            // but cheaper, and it keeps the swap-back from firing mid-menu.
            skip("a container screen is open");
            return;
        }
        if (!(client.gameMode instanceof MultiPlayerGameModeInvoker invoker)) {
            skip("startPrediction invoker not applied");
            return;
        }
        LocalPlayer player = client.player;
        ClientLevel level = client.level;

        int breakerSlot = findBreakerHotbarSlot(player);
        int selected = player.getInventory().getSelectedSlot();
        boolean autoSwap = cfg.isBreakerAuraAutoSwap();
        if (autoSwap && breakerSlot < 0) {
            // "If the breaker is not in the hotbar at all, do nothing and say so once - never rummage the inventory."
            if (!warnedNoBreakerInHotbar) {
                warnedNoBreakerInHotbar = true;
                ModChat.send("Breaker Aura", ModChat.text("Auto Swap is on but there is no "),
                        ModChat.value("Dungeon Breaker"), ModChat.text(" in your hotbar - leaving your hand alone."));
            }
            // The breaker running out of charges mid-run lands here, so this is also where the hand has to go
            // back - otherwise Auto Swap leaves you holding a spent breaker for the rest of the floor.
            restoreSlot(client, "no Dungeon Breaker with charges left in the hotbar");
            skip("auto swap on, no Dungeon Breaker in the hotbar");
            return;
        }
        // Without Auto Swap the breaker has to already be in the main hand, exactly as before.
        ItemStack source = autoSwap && breakerSlot >= 0 ? player.getInventory().getItem(breakerSlot) : player.getMainHandItem();
        int charges = getBreakerCharges(source);
        if (charges != lastLoreCharges) {
            lastLoreCharges = charges;
            spentSinceLore = 0;
        }
        int available = charges - spentSinceLore;
        if (available <= 0) {
            skip(charges <= 0
                    ? (autoSwap ? "no Dungeon Breaker charges in the hotbar" : "no Dungeon Breaker charges in main hand")
                    : "local charges spent, waiting for lore update");
            return;
        }

        long now = System.currentTimeMillis();
        RECENT.values().removeIf(t -> now - t > RETRY_MS);

        double reach = cfg.getBreakerAuraReach();
        List<BlockPos> targets = cfg.isBreakerAuraSelectedOnly()
                ? collectPickedTargets(player, level, reach, now)
                : collectPathTargets(player, level, reach, now);

        // ARMED ON APPROACH, not on arrival. killer560 (2026-09-23): "The breaker aura seems to not be breaking a
        // block on the very first tick it is in range. It almost waits a little bit."
        //
        // The hand swap used to be considered only once a block was already in reach, and a swap cannot be
        // followed by a break on the same tick - the server has to see the held item change first. So the first
        // block of every wall cost the swap tick plus the whole settling window before anything was sent, which
        // is exactly the hesitation he is describing. Deciding the swap against a slightly longer radius spends
        // that window while he is still walking in, so the hand is already holding the breaker on the tick the
        // block actually comes into reach and the break goes out on that tick.
        //
        // Only ever WIDENS what arms the hand. What gets broken is still whatever is inside the real reach.
        // The widened radius decides ONE thing: whether to arm the hand early. It does NOT keep the feature
        // awake, and it does not count as "something in reach".
        //
        // It did both in the first version of this (2026-09-23) and the cost showed up immediately in his log:
        // 1242 ticks sat in "waiting for a picked block to come into reach", in runs of 380, 357 and 198 ticks -
        // ten to nineteen seconds at a stretch. His picks are scattered across three parts of the floor and stay
        // saved between runs, so merely walking within six blocks of any of them held the breaker in his hand and
        // pulled his hotbar slot back off whatever he had selected. Idle is judged on the real reach, exactly as
        // it was before, so Swap Back still fires on its own timer.
        List<BlockPos> approaching = targets.isEmpty() && autoSwap && selected != breakerSlot
                ? (cfg.isBreakerAuraSelectedOnly()
                        ? collectPickedTargets(player, level, reach + PRE_SWAP_REACH_MARGIN, now)
                        : collectPathTargets(player, level, reach + PRE_SWAP_REACH_MARGIN, now))
                : targets;

        if (targets.isEmpty()) {
            skip(approaching.isEmpty()
                    ? (cfg.isBreakerAuraSelectedOnly() ? "no picked blocks in reach" : "no valid blocks in path")
                    : "breaker coming to hand, a picked block is nearly in reach");
            // The cooldown is the gap between two breaks, and a tick with nothing to break is part of that gap.
            // It used to be decremented only past this point, so it FROZE while idle: at a cooldown of 2 or more,
            // walking up to a fresh wall meant sitting out the remains of a gap that had already elapsed.
            if (cooldownTicks > 0) {
                cooldownTicks--;
            }
            census(approaching.isEmpty() ? "reach" : "swap");
            // Arm the hand while he is still walking in, so the settling window is spent before he arrives and
            // the break can go out on the very tick the block comes into reach.
            if (!approaching.isEmpty() && swapArmedTicks <= 0) {
                armSwap(player, cfg, selected, breakerSlot);
            } else if (swapArmedTicks > 0) {
                swapArmedTicks--;
            }
            if (autoSwap && swappedFromSlot >= 0 && cfg.isBreakerAuraSwapBack()
                    && ++idleSinceSwapTicks >= cfg.getBreakerAuraSwapBackIdleTicks()) {
                restoreSlot(client, "nothing left in the path");
            }
            return;
        }
        lastSkipReason = null;
        idleSinceSwapTicks = 0;

        if (autoSwap && selected != breakerSlot) {
            if (swapArmedTicks <= 0) {
                armSwap(player, cfg, selected, breakerSlot);
            }
            return;
        }
        if (swapArmedTicks > 0) {
            // Settling window after a slot change; the break goes out once the server has plausibly seen the swap.
            swapArmedTicks--;
            census("swap");
            return;
        }
        if (cooldownTicks > 0) {
            cooldownTicks--;
            census("cooldown");
            return;
        }

        // How many go out this cycle, and in what order.
        //
        // killer560 (2026-09-23): "Look at how quoi parses multiple blocks and follow it exactly. I trust them."
        // So this follows QUOI, and QUOI does NOT break several on one tick. Its DungeonBreaker holds a whole set
        // of blocks and every tick takes `activeBreakerBlocks.minByOrNull { it.distToCenterSqr(playerPos) }` - the
        // single nearest one - line-of-sight checks it, sends one START_DESTROY_BLOCK and swings once. Its
        // AuraManager is built the same way: a queue, `queuedBlocks.firstOrNull()` per tick, and a cooldown after
        // every packet it sends. Several blocks are how you DESCRIBE the job to it, not how many it does at once.
        //
        // Which is also the rule he gave here first, for levers and chests: "have it only pick one and then the
        // other on the next tick". The setting stays for when he wants otherwise, but it defaults to one, and one
        // is what following QUOI exactly means.
        //
        // WHAT IS IN HIS WAY first, then QUOI's nearest-first for everything else.
        //
        // This is the one honest way left to make it feel faster, and it sends not one extra packet: the rate is
        // still one block a tick. killer560 (2026-09-23): "it can still break if i run into it. Is there some way
        // to break it either faster or do more of them at once?" Measured on his own log, a ten-block wall takes
        // about ten ticks at one a tick, and he covers three blocks in that time - so whether he gets through
        // without slowing is not about how MANY break, it is about whether the two or three blocking his body
        // are among the first. Nearest-first does not care about that: on his wall it worked along x from 56 to
        // 53, so the blocks he was walking into came fifth and sixth.
        //
        // The corridor is his own width plus half a block: anything further off his line he would walk past
        // anyway. Ordered by how far AHEAD they are, so the hole opens at his feet and grows away from him.
        //
        // A departure from "look at how quoi parses multiple blocks and follow it exactly" - deliberately, and
        // only in the order. Which blocks are eligible, and one a tick, are still QUOI's. When he is not moving
        // there is no way to be in, and this falls back to QUOI's rule exactly.
        int allowed = Math.max(1, Math.min(cfg.getBreakerAuraBlocksPerCycle(), available));
        Vec3 feet = player.position();
        Vec3 vel = player.getDeltaMovement();
        double speed = Math.hypot(vel.x, vel.z);
        final double dirX;
        final double dirZ;
        if (speed > 0.05) {
            dirX = vel.x / speed;
            dirZ = vel.z / speed;
        } else {
            dirX = Double.NaN; // standing still: nothing is "ahead", so QUOI's order stands
            dirZ = Double.NaN;
        }
        List<BlockPos> order = new ArrayList<>(targets);
        order.sort((a, b) -> {
            if (!Double.isNaN(dirX)) {
                int ia = inTheWay(a, feet, dirX, dirZ) ? 0 : 1;
                int ib = inTheWay(b, feet, dirX, dirZ) ? 0 : 1;
                if (ia != ib) {
                    return Integer.compare(ia, ib);
                }
                if (ia == 0) {
                    return Double.compare(ahead(a, feet, dirX, dirZ), ahead(b, feet, dirX, dirZ));
                }
            }
            return Double.compare(Vec3.atCenterOf(a).distanceToSqr(feet),
                    Vec3.atCenterOf(b).distanceToSqr(feet));
        });
        int sent = 0;
        boolean gateRefused = false;
        // ONE claim for the whole tick, not one per block.
        //
        // The gate is still asked - Breaker Aura still takes its turn among the other actors, and still stands
        // down for a screen, a teleport, a world swap or a higher-priority actor. What changed is that having
        // won the tick it may now send more than one break on it, up to Blocks Per Cycle.
        //
        // killer560 asked for this directly, twice, after the measurements ruled everything else out
        // (2026-09-24): "If there is 0 other bug then allow it to break multiple at once." It is worth being
        // plain about what it costs, because he built the gate for exactly this reason: several interaction
        // packets inside one client tick is not a pattern a hand can produce, and it is visible as such. There
        // is deliberately nothing here that tries to disguise it - no jitter, no shaped spacing - because
        // disguising it is a different thing from doing it, and he is choosing the risk with the numbers in
        // front of him. The default stays 1, which is the human-shaped rate.
        if (!ActionGate.tryAct(ActionGate.Actor.BREAKER_AURA)) {
            gateRefused = true;
            order = List.of();
        }
        for (BlockPos pos : order) {
            if (sent >= allowed) {
                break;
            }
            BlockHitResult hit = hitResult(player, level, pos);
            if (hit == null) {
                continue;
            }
            Block block = level.getBlockState(pos).getBlock();
            breakBlock(invoker, level, pos, hit.getDirection(), cfg.isBreakerAuraZeroPing());
            RECENT.put(pos, now);
            spentSinceLore++;
            sent++;
            LOGGER.info("[DungeonExtras] Breaker Aura sent START_DESTROY_BLOCK at {} ({}), charges {} -> {} (local).",
                    pos, block, charges, charges - spentSinceLore);
        }
        if (sent == 0) {
            // Two very different reasons, and calling them both the same thing sent the last investigation down
            // the wrong road: the gate handing this tick to a higher-priority actor is throughput, a block with
            // no reachable face is geometry.
            skip(gateRefused ? "the action gate gave this tick to something else"
                    : "no reachable face on any block in reach");
            census(gateRefused ? "gate" : "face");
            return;
        }
        census("sent");
        // One swing however many went out: a hand swings once a tick whatever it is doing.
        player.swing(InteractionHand.MAIN_HAND);
        // MINUS ONE, because the wait is checked on a later tick and that check costs a tick of its own: with the
        // old arithmetic a cooldown of 1 meant break, skip, break - half the rate the setting says, and half
        // QUOI's, which has no cooldown in its break loop at all and simply sends one every tick. killer560
        // (2026-09-23): "right now the breaker aura is just way to slow. It isnt how the other clients feel."
        cooldownTicks = Math.max(0, cfg.getBreakerAuraCooldownTicks() - 1);
    }

    /** True while Auto Routes is in its block-placing edit mode. AP3 has no edit mode to ask about (see notes). */
    private static boolean isRouteEditModeActive() {
        try {
            return com.killer560.hub.autoroutes.AutoRoutesFeature.isEditMode();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Puts the hand back where Auto Swap found it. No-op unless we are the ones who moved it. */
    private static void restoreSlot(Minecraft client, String why) {
        int back = swappedFromSlot;
        swappedFromSlot = -1;
        swapArmedTicks = 0;
        idleSinceSwapTicks = 0;
        LocalPlayer player = client.player;
        if (back < 0 || back > 8 || player == null || player.connection == null) {
            return;
        }
        if (player.getInventory().getSelectedSlot() == back) {
            return;
        }
        player.getInventory().setSelectedSlot(back);
        player.connection.send(new ServerboundSetCarriedItemPacket(back));
        LOGGER.info("[DungeonExtras] Breaker Aura swapped back to slot {} ({}).", back, why);
    }

    /** First hotbar slot holding a DUNGEONBREAKER with charges left, or -1. Hotbar only - never the inventory. */
    private static int findBreakerHotbarSlot(LocalPlayer player) {
        for (int i = 0; i < 9; i++) {
            if (getBreakerCharges(player.getInventory().getItem(i)) > 0) {
                return i;
            }
        }
        return -1;
    }

    private static void breakBlock(MultiPlayerGameModeInvoker invoker, ClientLevel level, BlockPos pos,
                                   Direction face, boolean zeroPing) {
        BlockState state = level.getBlockState(pos);
        invoker.killer560smod$invokeStartPrediction(level, sequence -> {
            if (zeroPing && !state.is(Blocks.OBSIDIAN)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            return new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, sequence);
        });
    }

    /** Blocks intersecting the player's hitbox swept forward up to reach, nearest first. */
    private static List<BlockPos> collectPathTargets(LocalPlayer player, ClientLevel level, double reach, long now) {
        Vec3 motion = player.getDeltaMovement();
        Vec3 dir = new Vec3(motion.x, 0, motion.z);
        if (dir.lengthSqr() < 0.0025) {
            Vec3 look = player.getViewVector(1f);
            dir = new Vec3(look.x, 0, look.z);
        }
        if (dir.lengthSqr() < 1.0E-6) {
            return List.of();
        }
        dir = dir.normalize();
        Vec3 feet = player.position();
        Vec3 eye = player.getEyePosition();
        double reachSq = reach * reach;
        Set<BlockPos> ordered = new LinkedHashSet<>();
        for (double t = 0; t <= reach; t += 0.25) {
            double cx = feet.x + dir.x * t;
            double cz = feet.z + dir.z * t;
            // Review fix (2026-09-15): start the swept box FLOOR_CLEARANCE above the feet. At exactly feet.y a
            // player whose y is a hair under an integer (float error, soul sand/farmland, setbacks) got the
            // block they are STANDING ON counted as "in the path" and broken out from under them.
            AABB box = new AABB(cx - 0.3, feet.y + FLOOR_CLEARANCE, cz - 0.3, cx + 0.3, feet.y + 1.8, cz + 0.3);
            int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX);
            int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY - 1.0E-4);
            int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ);
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (ordered.contains(pos) || RECENT.containsKey(pos)) {
                            continue;
                        }
                        if (eyeToBlockSq(eye, pos) > reachSq) {
                            continue;
                        }
                        if (!isValidTarget(level, pos)) {
                            continue;
                        }
                        VoxelShape collision = level.getBlockState(pos).getCollisionShape(level, pos);
                        if (collision.isEmpty() || !collision.bounds().move(pos).intersects(box)) {
                            continue;
                        }
                        ordered.add(pos);
                    }
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    /**
     * The blocks he has PICKED that are in reach and still there - the whole target list when
     * {@code breakerAuraSelectedOnly} is on. A pick that has already been broken simply stops matching; one out of
     * reach waits until he is closer, which is the point of picking a wall before you get to it.
     */
    private static List<BlockPos> collectPickedTargets(LocalPlayer player, ClientLevel level, double reach,
                                                       long now) {
        List<BlockPos> out = new ArrayList<>();
        Vec3 eye = player.getEyePosition();
        double reachSq = reach * reach;
        for (BlockPos pos : selectedBlocks()) {
            if (level.isLoaded(pos) && level.getBlockState(pos).isAir()) {
                // QUOI: `if (level.getBlockState(targetBlock).isAir) { activeBreakerBlocks.remove(targetBlock) }`.
                // A pick that has been broken stops being a target - but it stays SAVED, because his picks are a
                // route he runs every time and QUOI's own blocks outlive the run that cleared them.
                continue;
            }
            if (RECENT.containsKey(pos)) {
                continue; // tried a moment ago; give the server time to answer
            }
            if (eyeToBlockSq(eye, pos) > reachSq) {
                continue;
            }
            if (!isValidTarget(level, pos)) {
                continue;
            }
            out.add(pos);
        }
        return out;
    }

    /**
     * Whether a route planner should treat this block as air - killer560 (2026-09-22): "If a block is selected for
     * breaker aura treat that block as not being there when you go to run through it". True only while Breaker Aura
     * is actually on and the block is one it would break.
     */
    public static boolean plannerTreatsAsAir(ClientLevel level, BlockPos pos) {
        DungeonExtrasConfig cfg = DungeonExtrasConfig.getInstance();
        if (!cfg.isBreakerAuraEnabled()) {
            return false;
        }
        // Picking changes what this means: only a block he has actually marked is one the route may count on
        // being gone. Treating every breakable block as air would let a route plan straight through a wall the
        // aura has been told to leave alone.
        if (cfg.isBreakerAuraSelectedOnly()) {
            return isPicked(pos) && isValidTarget(level, pos);
        }
        return isValidTarget(level, pos);
    }

    private static boolean isValidTarget(ClientLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.hasBlockEntity() || level.getBlockEntity(pos) != null) {
            return false;
        }
        if (BLACKLIST.contains(state.getBlock())) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0f;
    }

    /** QUOI {@code BlockPos.getHitResult}: clip the block's own outline shape from the eyes through its centre. */
    private static BlockHitResult hitResult(LocalPlayer player, ClientLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 centre = shape.bounds().getCenter().add(pos.getX(), pos.getY(), pos.getZ());
        Vec3 dir = centre.subtract(eye).normalize();
        Vec3 end = eye.add(dir.scale(eye.distanceTo(centre) + 1.5));
        return shape.clip(eye, end, pos);
    }

    /** Same logic as the private {@code DungeonBreakerFeature.getBreakerCharges}. */
    private static int getBreakerCharges(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return 0;
        }
        CompoundTag tag = data.copyTag();
        if (!DUNGEON_BREAKER_SKYBLOCK_ID.equals(tag.getStringOr("id", null))) {
            return 0;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) {
            return 0;
        }
        for (Component line : lore.lines()) {
            Matcher m = CHARGES_PATTERN.matcher(line.getString());
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }
}

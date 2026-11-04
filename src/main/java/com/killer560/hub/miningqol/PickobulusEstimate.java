package com.killer560.hub.miningqol;

import com.killer560.hub.cheatutils.CheatUtils;
import com.killer560.hub.compat.McBlocks;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pickobulus Estimate (legit, both builds; killer560 2026-11-01: "a estimate for where my pickobulus will go").
 *
 * <p>While he holds a tool whose lore names the Pickobulus ability and it is not on cooldown (the tab list's "Pickaxe
 * Ability:" widget says "Pickobulus: Available", or is not shown), every block the ability would break is outlined. The
 * model is Skyblocker's PickobulusHelper (LGPL-3.0, read for behavior only, no code taken): Pickobulus is thrown from
 * 0.53625 blocks above the eye, lands on the first block in a 20-block line of sight, and breaks the blocks with an exposed
 * face (air on at least one side) within 3 blocks of where it lands (a 6x6x6 box round the landing block). Which blocks
 * break depends on the island: in the Dwarven Mines, Gold Mine and Deep Caverns only the ore and mineable blocks, in the
 * Crystal Hollows and the Glacite Mineshafts everything, and in the Glacite Tunnels the tunnel ores. It is an estimate:
 * nothing is used, clicked or sent.
 */
public final class PickobulusEstimate {

    private static final Pattern ABILITY = Pattern.compile("^Ability: Pickobulus\\b.*");

    private static final Set<Block> MINES_BREAKABLE = Set.of(
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.POLISHED_DIORITE, Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS,
            Blocks.DARK_PRISMARINE, McBlocks.CYAN_TERRACOTTA, McBlocks.LIGHT_BLUE_WOOL, McBlocks.GRAY_WOOL,
            Blocks.LAPIS_BLOCK, Blocks.GOLD_BLOCK, Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.REDSTONE_BLOCK, Blocks.COAL_BLOCK, Blocks.QUARTZ_BLOCK, Blocks.GOLD_ORE, Blocks.IRON_ORE,
            Blocks.COAL_ORE, Blocks.LAPIS_ORE, Blocks.REDSTONE_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
            Blocks.NETHER_QUARTZ_ORE, Blocks.NETHERRACK, Blocks.GLOWSTONE, Blocks.OBSIDIAN, Blocks.END_STONE);

    private static final Set<Block> TUNNELS_BREAKABLE = Set.of(
            Blocks.PACKED_ICE, Blocks.POLISHED_DIORITE, Blocks.INFESTED_STONE, McBlocks.LIGHT_GRAY_CARPET,
            Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, McBlocks.LIGHT_BLUE_WOOL,
            McBlocks.GRAY_WOOL, McBlocks.CYAN_TERRACOTTA, McBlocks.BROWN_TERRACOTTA, Blocks.TERRACOTTA,
            Blocks.SMOOTH_RED_SANDSTONE, Blocks.INFESTED_COBBLESTONE, Blocks.CLAY);

    private static volatile List<BlockPos> breaks = List.of();
    private static volatile String status = "";
    private static int frames = 0;
    private static final BoxBatch LINES = new BoxBatch();

    /**
     * Hypixel's own Pickobulus chat lines (killer560's 26.3 log, 2026-11-02), anchored whole lines, so a player's chat
     * ("Bob: You used your ...") can never match. Used: the ability went off; ready: it is back.
     */
    private static final Pattern USED = Pattern.compile(
            "^(?:You used your Pickobulus Pickaxe Ability!|Your Pickobulus destroyed \\d{1,4} blocks?!)$");
    private static final Pattern READY = Pattern.compile("^Pickobulus is now available!$");
    private static final Pattern REFUSED = Pattern.compile("^This ability is on cooldown for \\d{1,4}s\\.$");
    /**
     * How long a chat line outranks the tab list. Hypixel refreshes the "Pickaxe Ability:" widget slowly, so right after
     * a use it still says "Available" and right after "is now available!" it still shows seconds: reading the tab alone
     * kept the outlines up for a while after each use and kept them away for a while after it came back (killer560,
     * 2026-11-03, mining-1103).
     */
    static final long CHAT_WINS_MS = 15_000L;
    /** A use line outranks a stale "Available" in the tab until its ready line comes, at most this long. */
    static final long USED_WINS_MS = 120_000L;
    /** The last chat line's answer (true: on cooldown) and when it came; 0 = none yet. Client thread. */
    private static volatile boolean chatCooldown = false;
    private static volatile long chatAt = 0L;

    private PickobulusEstimate() {
    }

    static void register() {
        com.killer560.hub.util.ChatObserver.subscribe(PickobulusEstimate::onChat);
    }

    static void onChat(net.minecraft.network.chat.Component message) {
        String raw = com.killer560.hub.util.ChatObserver.strip(message);
        if (raw == null || raw.length() > 80) {
            return;
        }
        onChatLine(raw.trim(), System.currentTimeMillis());
    }

    /** One stripped chat line (public for the testkit). */
    public static void onChatLine(String raw, long now) {
        if (USED.matcher(raw).matches()) {
            setChatState(true, now);
        } else if (READY.matcher(raw).matches()) {
            setChatState(false, now);
        } else if (REFUSED.matcher(raw).matches()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && holdsPickobulus(mc.player)) {
                setChatState(true, now);
            }
        }
    }

    private static void setChatState(boolean cooldown, long now) {
        chatCooldown = cooldown;
        chatAt = now;
        if (cooldown) {
            // Gone this frame, not on the next tick.
            breaks = List.of();
            status = "cooldown";
        }
    }

    /** On cooldown: a chat line from the last {@link #CHAT_WINS_MS} decides; with none, the tab widget does. */
    static boolean onCooldown(long now) {
        // A use stands until its ready line (Pickobulus cools down for under two minutes); a ready line for 15 s.
        if (chatAt != 0L && now - chatAt <= (chatCooldown ? USED_WINS_MS : CHAT_WINS_MS)) {
            return chatCooldown;
        }
        for (String line : MiningState.pickaxeAbilityLines()) {
            if (line.startsWith("Pickobulus: ") && !line.equals("Pickobulus: Available")) {
                return true;
            }
        }
        return false;
    }

    /** Forgets the chat answer (world change; testkit). */
    public static void resetChat() {
        chatAt = 0L;
        chatCooldown = false;
    }

    static void tick(Minecraft client) {
        if (!MiningQolConfig.getInstance().isPickobulus() || client.player == null || client.level == null
                || !MiningState.onMiningIsland()) {
            breaks = List.of();
            status = "";
            return;
        }
        if (onCooldown(System.currentTimeMillis())) {
            breaks = List.of();
            status = "cooldown";
            return;
        }
        if (!holdsPickobulus(client.player)) {
            breaks = List.of();
            status = "not holding";
            return;
        }
        BlockPos target = target(client.player, client.level);
        if (target == null) {
            breaks = List.of();
            status = "no block";
            return;
        }
        breaks = estimate(client.level, target);
        status = "ok";
    }

    static boolean holdsPickobulus(Player player) {
        for (String line : CheatUtils.lore(player.getMainHandItem())) {
            if (ABILITY.matcher(line.trim()).matches()) {
                return true;
            }
        }
        return false;
    }

    /** The block Pickobulus would land on: the first block within 20 blocks along the look, from 0.53625 above the eye. */
    static BlockPos target(Player player, ClientLevel level) {
        Vec3 start = player.getEyePosition().add(0, 0.53625, 0);
        Vec3 end = start.add(player.getLookAngle().scale(20));
        BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    /** Every block the ability would break landing on {@code pos}, by the island's rule. Public for the testkit. */
    public static List<BlockPos> estimate(ClientLevel level, BlockPos pos) {
        BlockState[][][] b = new BlockState[8][8][8];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                for (int k = 0; k < 8; k++) {
                    b[i][j][k] = level.getBlockState(pos.offset(i - 4, j - 4, k - 4));
                }
            }
        }
        boolean all = MiningState.crystalHollows() || MiningState.mineshaft();
        boolean tunnels = !all && MiningState.glaciteTunnels();
        List<BlockPos> out = new ArrayList<>();
        for (int i = 1; i < 7; i++) {
            for (int j = 1; j < 7; j++) {
                for (int k = 1; k < 7; k++) {
                    BlockState s = b[i][j][k];
                    if (s.isAir() || s.is(Blocks.BEDROCK)) {
                        continue;
                    }
                    boolean exposed = b[i - 1][j][k].isAir() || b[i + 1][j][k].isAir() || b[i][j - 1][k].isAir()
                            || b[i][j + 1][k].isAir() || b[i][j][k - 1].isAir() || b[i][j][k + 1].isAir();
                    if (!exposed) {
                        continue;
                    }
                    boolean breakable;
                    if (all) {
                        breakable = true;
                    } else if (tunnels) {
                        breakable = TUNNELS_BREAKABLE.contains(s.getBlock()) || glass(s);
                    } else {
                        breakable = MINES_BREAKABLE.contains(s.getBlock()) || glass(s);
                    }
                    if (breakable) {
                        out.add(pos.offset(i - 4, j - 4, k - 4));
                        // Where everything breaks, a broken block exposes the ones behind it (Skyblocker does the same).
                        if (all || (tunnels && (s.is(Blocks.PACKED_ICE) || glass(s)))) {
                            b[i][j][k] = Blocks.AIR.defaultBlockState();
                        }
                    }
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean glass(BlockState s) {
        return s.getBlock() instanceof StainedGlassBlock || s.getBlock() instanceof StainedGlassPaneBlock;
    }

    static void render(LevelRenderContext context) {
        List<BlockPos> snap = breaks;
        if (snap.isEmpty() || !MiningQolConfig.getInstance().isPickobulus()) {
            return;
        }
        float[] k = WorldRenderUtils.argbToFloats(MiningQolConfig.getInstance().getPickobulusColor());
        LINES.clear();
        for (BlockPos p : snap) {
            LINES.add(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, k[0], k[1], k[2], 1f);
        }
        LINES.drawOutlines(context, com.killer560.hub.util.EspTypes.LINES, 2f);
        frames++;
    }


    public static int count() {
        return breaks.size();
    }

    public static String status() {
        return status;
    }

    public static int frames() {
        return frames;
    }
}

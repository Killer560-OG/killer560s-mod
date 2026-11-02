package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McBlocks;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/**
 * Three of the pre-2.0 mining highlights (killer560 2026-11-01), each its own switch:
 * <ul>
 *   <li><b>Carpet Highlighter</b> (legit): the Dwarven Mines' unbreakable carpets inside ore veins - a gray, light blue or
 *       light gray carpet on a Sea Lantern, the shape Skyblocker's CarpetHighlighter keys on (LGPL-3.0, read for behavior
 *       only) - filled in a color of his choice, so he stops swinging at them.</li>
 *   <li><b>Titanium Highlight</b> (legit; through walls in the cheat build only): Titanium is Polished Diorite in the Dwarven
 *       Mines (hypixelskyblock wiki, Titanium Ore). Always, or only while an open commission wants titanium where he is
 *       ("Titanium Miner" anywhere, "Royal Mines Titanium" in the Royal Mines), and always with Universal Area Access on.</li>
 *   <li><b>Raffle Ticket Highlight</b> (legit; through walls in the cheat build only): during the Dwarven Mines Raffle, a box
 *       round each raffle ticket on the ground - a dropped item named "Raffle Ticket", a dropped Name Tag (the wiki's ticket
 *       icon), or a stand named "Raffle Ticket".</li>
 * </ul>
 * Blocks are found by {@link BlockScan} once a second within 48 blocks; the world is never changed and nothing is sent.
 */
public final class MiningHighlights {

    private static final int RADIUS = 48;
    private static final int DY = 40;
    private static final int MAX = 2000;

    private static final Set<Block> VEIN_CARPETS = Set.of(McBlocks.GRAY_CARPET, McBlocks.LIGHT_BLUE_CARPET,
            McBlocks.LIGHT_GRAY_CARPET);

    private static volatile List<BlockPos> carpets = List.of();
    private static volatile List<BlockPos> titanium = List.of();
    private static volatile List<double[]> tickets = List.of();
    private static int ticks = 0;
    private static int carpetFrames = 0;
    private static int titaniumFrames = 0;
    private static int raffleFrames = 0;

    private static final BoxBatch FILLS = new BoxBatch();
    private static final BoxBatch LINES = new BoxBatch();
    private static final BoxBatch WALL_FILLS = new BoxBatch();
    private static final BoxBatch WALL_LINES = new BoxBatch();

    private MiningHighlights() {
    }

    static void tick(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        boolean dm = MiningState.dwarvenMines() && client.level != null && client.player != null;
        if (!dm) {
            carpets = List.of();
            titanium = List.of();
            tickets = List.of();
            return;
        }
        // Tickets move and vanish: every 5 ticks. Blocks: once a second.
        if (ticks % 5 == 0) {
            tickets = cfg.isRaffle() ? findTickets(client) : List.of();
        }
        if (ticks++ % 20 != 0) {
            return;
        }
        BlockPos at = client.player.blockPosition();
        carpets = cfg.isCarpet() ? BlockScan.scan(client.level, at, RADIUS, DY,
                s -> VEIN_CARPETS.contains(s.getBlock()),
                (pos, s) -> client.level.getBlockState(pos.below()).is(Blocks.SEA_LANTERN), MAX) : List.of();
        titanium = titaniumWanted(cfg) ? BlockScan.scan(client.level, at, RADIUS, DY,
                MiningHighlights::isTitanium, (pos, s) -> true, MAX) : List.of();
    }

    static boolean isTitanium(BlockState s) {
        return s.is(Blocks.POLISHED_DIORITE);
    }

    static boolean titaniumWanted(MiningQolConfig cfg) {
        if (!cfg.isTitanium()) {
            return false;
        }
        if (cfg.getTitaniumMode() == MiningQolConfig.TitaniumMode.ALWAYS || cfg.isUniversalAccess()) {
            return true;
        }
        return MiningState.openCommissionHere("Titanium", false);
    }

    private static List<double[]> findTickets(Minecraft client) {
        java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (isTicket(e)) {
                out.add(new double[]{e.getX(), e.getY(), e.getZ(), e.getBbWidth(), e.getBbHeight()});
                if (out.size() >= 200) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    static boolean isTicket(Entity e) {
        if (e instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                return false;
            }
            if (stack.is(Items.NAME_TAG)) {
                return true;
            }
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            return name != null && name.contains("Raffle Ticket");
        }
        if (e instanceof ArmorStand stand && stand.hasCustomName() && stand.getCustomName() != null) {
            String name = ChatFormatting.stripFormatting(stand.getCustomName().getString());
            return name != null && name.contains("Raffle Ticket");
        }
        return false;
    }

    static void render(LevelRenderContext context) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        List<BlockPos> c = carpets;
        List<BlockPos> t = titanium;
        List<double[]> r = tickets;
        if (c.isEmpty() && t.isEmpty() && r.isEmpty()) {
            return;
        }
        FILLS.clear();
        LINES.clear();
        WALL_FILLS.clear();
        WALL_LINES.clear();
        if (!c.isEmpty() && cfg.isCarpet()) {
            float[] k = WorldRenderUtils.argbToFloats(cfg.getCarpetColor());
            for (BlockPos p : c) {
                FILLS.add(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 0.0625, p.getZ() + 1, k[0], k[1], k[2],
                        Math.min(k[3], 0.6f));
            }
            carpetFrames++;
        }
        if (!t.isEmpty() && cfg.isTitanium()) {
            float[] k = WorldRenderUtils.argbToFloats(cfg.getTitaniumColor());
            boolean walls = cfg.isTitaniumEsp();
            BoxBatch fill = walls ? WALL_FILLS : FILLS;
            BoxBatch line = walls ? WALL_LINES : LINES;
            for (BlockPos p : t) {
                double e = 0.002;
                fill.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e, p.getZ() + 1 + e,
                        k[0], k[1], k[2], 0.25f);
                line.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e, p.getZ() + 1 + e,
                        k[0], k[1], k[2], 1f);
            }
            titaniumFrames++;
        }
        if (!r.isEmpty() && cfg.isRaffle()) {
            float[] k = WorldRenderUtils.argbToFloats(cfg.getRaffleColor());
            boolean walls = cfg.isRaffleEsp();
            BoxBatch fill = walls ? WALL_FILLS : FILLS;
            BoxBatch line = walls ? WALL_LINES : LINES;
            for (double[] d : r) {
                double hw = Math.max(0.25, d[3] / 2 + 0.05);
                double h = Math.max(0.5, d[4] + 0.1);
                fill.add(d[0] - hw, d[1], d[2] - hw, d[0] + hw, d[1] + h, d[2] + hw, k[0], k[1], k[2], 0.3f);
                line.add(d[0] - hw, d[1], d[2] - hw, d[0] + hw, d[1] + h, d[2] + hw, k[0], k[1], k[2], 1f);
            }
            raffleFrames++;
        }
        FILLS.drawFilled(context, RenderTypes.debugFilledBox());
        LINES.drawOutlines(context, RenderTypes.LINES_TRANSLUCENT, 2f);
        WALL_FILLS.drawFilled(context, WorldRenderUtils.throughWallsFilled());
        WALL_LINES.drawOutlines(context, WorldRenderUtils.throughWallsLines(), 2f);
    }

    // ---- testkit hooks

    public static int carpetCount() {
        return carpets.size();
    }

    public static int titaniumCount() {
        return titanium.size();
    }

    public static int ticketCount() {
        return tickets.size();
    }

    public static int carpetFrames() {
        return carpetFrames;
    }

    public static int titaniumFrames() {
        return titaniumFrames;
    }

    public static int raffleFrames() {
        return raffleFrames;
    }

    /** Whether the boxes drawn now go through walls (testkit: legit jars must say false). */
    public static boolean titaniumThroughWalls() {
        return MiningQolConfig.getInstance().isTitaniumEsp();
    }

    public static boolean raffleThroughWalls() {
        return MiningQolConfig.getInstance().isRaffleEsp();
    }
}

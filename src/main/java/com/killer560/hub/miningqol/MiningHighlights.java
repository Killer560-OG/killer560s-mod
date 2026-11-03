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
import net.minecraft.world.level.Level;
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
 *       Mines (hypixelskyblock wiki, Titanium Ore). Always, or only the blocks an open commission wants: "Titanium Miner"
 *       anywhere, "Royal Mines Titanium" for a block that is in the Royal Mines ({@link MinesAreaMap}; a block of unknown
 *       area by the area he stands in).</li>
 *   <li><b>Raffle Ticket Highlight</b> (legit; through walls in the cheat build only): during the Dwarven Mines Raffle, a box
 *       round each raffle ticket on the ground - a dropped item named "Raffle Ticket", a dropped Name Tag (the wiki's ticket
 *       icon), or a stand named "Raffle Ticket".</li>
 * </ul>
 * Blocks follow the server's block updates the tick they arrive (and a new chunk's blocks the tick it loads), with a
 * {@link BlockScan} of 48 blocks round him every second as the safety net; the world is never changed and nothing is sent.
 */
public final class MiningHighlights {

    private static final int RADIUS = 48;
    private static final int DY = 40;
    private static final int MAX = 2000;
    /** How far the carpet fill sits outside the carpet, so no face of it is coplanar with a block face. */
    public static final double CARPET_MARGIN = 0.002;

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
            carpetSet.clear();
            titaniumSet.clear();
            carpets = List.of();
            titanium = List.of();
            tickets = List.of();
            lastRuleKey = "";
            return;
        }
        ticks++;
        // Tickets move and vanish: every 5 ticks.
        if (ticks % 5 == 0) {
            tickets = cfg.isRaffle() ? findTickets(client) : List.of();
        }
        // Blocks are event-driven (onBlockChanged / onChunkLoaded; killer560 2026-11-03: "make it update stuff like
        // highlighted blocks faster"). The rule is re-read every tick and a change of it rescans at once; the full scan
        // every 20 ticks is only the safety net (it also drops what is now out of range).
        updateRule(cfg);
        String key = ruleKey(cfg);
        if (!key.equals(lastRuleKey) || ticks % 20 == 0) {
            lastRuleKey = key;
            rescan(client);
        }
    }

    /** What decides which blocks count: a change of it rescans immediately. */
    private static String ruleKey(MiningQolConfig cfg) {
        return cfg.isCarpet() + "|" + cfg.isTitanium() + "|" + cfg.getTitaniumMode() + "|" + titaniumRule + "|"
                + MinesAreaMap.version() + "|" + MiningState.island();
    }

    private static void updateRule(MiningQolConfig cfg) {
        if (!cfg.isTitanium()) {
            titaniumRule = null;
        } else if (cfg.getTitaniumMode() == MiningQolConfig.TitaniumMode.ALWAYS) {
            titaniumRule = ALWAYS;
        } else {
            // Commission Area: each block by the area IT is in (killer560, 2026-11-03), unknown ones by his own area.
            MiningState.Wanted w = MiningState.wanted("Titanium", false);
            titaniumRule = w.any() ? new TitaniumRule(w, MiningState.area()) : null;
        }
    }

    /** Which titanium counts (wanted null: every block). */
    private record TitaniumRule(MiningState.Wanted wanted, String here) {
        boolean test(BlockPos p) {
            return wanted == null || wanted.at(MinesAreaMap.areaAt(p.getX(), p.getY(), p.getZ()), here);
        }
    }

    private static final TitaniumRule ALWAYS = new TitaniumRule(null, null);
    private static volatile TitaniumRule titaniumRule = null;
    private static String lastRuleKey = "";
    private static final java.util.LinkedHashSet<BlockPos> carpetSet = new java.util.LinkedHashSet<>();
    private static final java.util.LinkedHashSet<BlockPos> titaniumSet = new java.util.LinkedHashSet<>();
    private static int scans = 0;
    private static int events = 0;

    private static void rescan(Minecraft client) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        BlockPos at = client.player.blockPosition();
        carpetSet.clear();
        titaniumSet.clear();
        if (cfg.isCarpet()) {
            carpetSet.addAll(BlockScan.scan(client.level, at, RADIUS, DY, s -> VEIN_CARPETS.contains(s.getBlock()),
                    (pos, s) -> isVeinCarpet(client.level, pos, s), MAX));
        }
        TitaniumRule rule = titaniumRule;
        if (rule != null) {
            titaniumSet.addAll(BlockScan.scan(client.level, at, RADIUS, DY, MiningHighlights::isTitanium,
                    (pos, s) -> rule.test(pos), MAX));
        }
        scans++;
        publish();
    }

    private static boolean isVeinCarpet(Level level, BlockPos pos, BlockState s) {
        return VEIN_CARPETS.contains(s.getBlock()) && level.getBlockState(pos.below()).is(Blocks.SEA_LANTERN);
    }

    private static void publish() {
        carpets = List.copyOf(carpetSet);
        titanium = List.copyOf(titaniumSet);
    }

    /**
     * A block the server changed (client thread, after the change is in the level; from {@code MiningBlockUpdateMixin}):
     * that block's highlight follows in the same tick. The carpet above it is re-checked too (its lantern may have gone).
     */
    public static void onBlockChanged(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !MiningState.dwarvenMines()) {
            return;
        }
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        BlockPos p = pos.immutable();
        boolean changed = false;
        if (cfg.isCarpet()) {
            changed |= recheckCarpet(client.level, p);
            changed |= recheckCarpet(client.level, p.above());
        }
        TitaniumRule rule = titaniumRule;
        if (rule != null) {
            boolean want = isTitanium(client.level.getBlockState(p)) && rule.test(p);
            changed |= want ? titaniumSet.add(p) : titaniumSet.remove(p);
        }
        events++;
        if (changed) {
            publish();
        }
    }

    private static boolean recheckCarpet(Level level, BlockPos p) {
        boolean want = isVeinCarpet(level, p, level.getBlockState(p));
        return want ? carpetSet.add(p) : carpetSet.remove(p);
    }

    /** A batch of changes in one section (kept out of the mixin so the mixin holds no lambda). */
    public static void onSectionChanged(net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket packet) {
        packet.runUpdates((pos, state) -> onBlockChanged(pos));
    }

    /** A chunk arrived: its blocks are evaluated now, not on the next safety scan. */
    public static void onChunkLoaded(int chunkX, int chunkZ) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !MiningState.dwarvenMines()) {
            return;
        }
        BlockPos at = client.player.blockPosition();
        if (Math.abs((chunkX << 4) + 8 - at.getX()) > RADIUS + 8 || Math.abs((chunkZ << 4) + 8 - at.getZ()) > RADIUS + 8) {
            return;
        }
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        BlockPos center = new BlockPos((chunkX << 4) + 8, at.getY(), (chunkZ << 4) + 8);
        boolean changed = false;
        if (cfg.isCarpet()) {
            changed |= carpetSet.addAll(BlockScan.scan(client.level, center, 8, DY,
                    s -> VEIN_CARPETS.contains(s.getBlock()), (pos, s) -> isVeinCarpet(client.level, pos, s), MAX));
        }
        TitaniumRule rule = titaniumRule;
        if (rule != null) {
            changed |= titaniumSet.addAll(BlockScan.scan(client.level, center, 8, DY, MiningHighlights::isTitanium,
                    (pos, s) -> rule.test(pos), MAX));
        }
        if (changed) {
            publish();
        }
    }

    static boolean isTitanium(BlockState s) {
        return s.is(Blocks.POLISHED_DIORITE);
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
            // A hair outside the carpet (killer560, 2026-11-03: "if i jump or crouch ... the highlight will like phase
            // through the block"): the fill's faces lay exactly on the carpet's top face and the Sea Lantern's top
            // face, z-fought with them, and which one won flipped as the camera moved. Same margin as the titanium boxes.
            double e = CARPET_MARGIN;
            for (BlockPos p : c) {
                FILLS.add(p.getX() - e, p.getY() + e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 0.0625 + e,
                        p.getZ() + 1 + e, k[0], k[1], k[2], Math.min(k[3], 0.6f));
            }
            BlockPos p0 = c.get(0);
            lastCarpetBox = new double[]{p0.getX() - e, p0.getY() + e, p0.getZ() - e, p0.getX() + 1 + e,
                    p0.getY() + 0.0625 + e, p0.getZ() + 1 + e};
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

    private static volatile double[] lastCarpetBox = null;

    /** The last carpet fill box drawn (testkit: no face lies on the carpet's or the lantern's own face). */
    public static double[] lastCarpetBox() {
        return lastCarpetBox;
    }

    /** Full scans so far, block events handled (testkit: the event path ran). */
    public static int scanCount() {
        return scans;
    }

    public static int blockEvents() {
        return events;
    }

    public static boolean hasTitaniumAt(int x, int y, int z) {
        return titanium.contains(new BlockPos(x, y, z));
    }

    public static boolean hasCarpetAt(int x, int y, int z) {
        return carpets.contains(new BlockPos(x, y, z));
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

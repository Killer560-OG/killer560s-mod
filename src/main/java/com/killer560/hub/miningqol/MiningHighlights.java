package com.killer560.hub.miningqol;

import com.killer560.hub.compat.McBlocks;
import com.killer560.hub.util.BoxBatch;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
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
            publishCarpetKeys(List.of());
            titanium = List.of();
            tickets = List.of();
            lastRuleKey = "";
            return;
        }
        ticks++;
        if (!redirty.isEmpty() && ticks >= redirtyAt) {
            dirtySections(client, new java.util.ArrayList<>(redirty));
            redirty.clear();
        }
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
        boolean test(BlockPos p) { return wanted == null || wanted.at(MinesAreaMap.areaAt(p.getX(), p.getY(), p.getZ()), here); }
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
        publishCarpetKeys(carpetSet);
    }

    // ---- carpet retexture (killer560, 2026-11-03: "make them the red carpet texture not just a highlight. otherwise
    // different colored carpets look weird"). The mesher draws each highlighted carpet with the red carpet's model.
    private static volatile java.util.Set<Long> carpetKeys = java.util.Set.of();
    private static volatile BlockState redCarpet;
    private static final java.util.concurrent.atomic.AtomicLong carpetSwaps = new java.util.concurrent.atomic.AtomicLong();

    private static void publishCarpetKeys(java.util.Collection<BlockPos> now) {
        java.util.Set<Long> next = new java.util.HashSet<>();
        for (BlockPos p : now) {
            next.add(p.asLong());
        }
        java.util.Set<Long> old = carpetKeys;
        if (old.equals(next)) return;
        carpetKeys = java.util.Set.copyOf(next);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        java.util.Set<Long> sections = new java.util.HashSet<>();
        for (Long k : old) {
            if (!next.contains(k)) {
                sections.add(BlockPos.of(k).asLong());
            }
        }
        for (Long k : next) {
            if (!old.contains(k)) {
                sections.add(k);
            }
        }
        java.util.Set<Long> done = new java.util.HashSet<>();
        for (Long k : sections) {
            BlockPos p = BlockPos.of(k);
            done.add(net.minecraft.core.SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4));
        }
        dirtySections(mc, done);
        // A section being compiled right now finishes with the old answer and a mark made meanwhile can be lost: mark again
        // a few ticks on (the carpet-texture case saw the single mark leave the carpets unchanged).
        redirty.addAll(done);
        redirtyAt = ticks + 10;
    }

    private static final java.util.Set<Long> redirty = new java.util.HashSet<>();
    private static int redirtyAt = 0;

    private static void dirtySections(Minecraft mc, java.util.Collection<Long> secs) {
        for (Long sp : secs) {
            mc.level.setSectionDirtyWithNeighbors(net.minecraft.core.SectionPos.x(sp), net.minecraft.core.SectionPos.y(sp),
                    net.minecraft.core.SectionPos.z(sp));
            carpetDirtied++;
        }
    }

    /** From the chunk mesher (any thread): red carpet's state for a highlighted vein carpet, else null. */
    static BlockState carpetReplace(BlockState state, int x, int y, int z) {
        java.util.Set<Long> keys = carpetKeys;
        if (state != null && VEIN_CARPETS.contains(state.getBlock())) {
            carpetAsked.incrementAndGet();
        }
        if (keys.isEmpty() || state == null || !VEIN_CARPETS.contains(state.getBlock())
                || !keys.contains(BlockPos.asLong(x, y, z))) {
            return null;
        }
        BlockState r = redCarpet;
        if (r == null) {
            r = McBlocks.RED_CARPET.defaultBlockState();
            redCarpet = r;
        }
        carpetSwaps.incrementAndGet();
        return r;
    }

    private static final java.util.concurrent.atomic.AtomicLong carpetAsked = new java.util.concurrent.atomic.AtomicLong();
    private static int carpetDirtied = 0;

    public static int carpetDirtied() { return carpetDirtied; }

    /** Mesher draws swapped to red carpet since start (testkit). */
    public static long carpetSwapCount() { return carpetSwaps.get(); }

    /** Whether the block at x, y, z is drawn as red carpet now (testkit). */
    public static boolean carpetRetextured(int x, int y, int z) { return carpetKeys.contains(BlockPos.asLong(x, y, z)); }

    /**
     * A block the server changed (client thread, after the change is in the level; from {@code MiningBlockUpdateMixin}):
     * that block's highlight follows in the same tick. The carpet above it is re-checked too (its lantern may have gone).
     */
    public static void onBlockChanged(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !MiningState.dwarvenMines()) return;
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
        packet.runUpdates((pos, state) -> {
            onBlockChanged(pos);
            CommissionProgress.onBlockChanged(pos);
        });
    }

    /** A chunk arrived: its blocks are evaluated now, not on the next safety scan. */
    public static void onChunkLoaded(int chunkX, int chunkZ) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !MiningState.dwarvenMines()) return;
        BlockPos at = client.player.blockPosition();
        if (Math.abs((chunkX << 4) + 8 - at.getX()) > RADIUS + 8 || Math.abs((chunkZ << 4) + 8 - at.getZ()) > RADIUS + 8) return;
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

    static boolean isTitanium(BlockState s) { return s.is(Blocks.POLISHED_DIORITE); }

    private static List<double[]> findTickets(Minecraft client) {
        java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        for (Entity e : client.level.entitiesForRendering()) {
            if (isTicket(e)) {
                out.add(new double[]{e.getX(), e.getY(), e.getZ(), e.getBbWidth(), e.getBbHeight()});
                if (out.size() >= 200) break;
            }
        }
        return List.copyOf(out);
    }

    static boolean isTicket(Entity e) {
        if (e instanceof ItemEntity item) {
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) return false;
            if (stack.is(Items.NAME_TAG)) return true;
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            return name != null && name.contains("Raffle Ticket");
        }
        if (e instanceof ArmorStand stand && stand.hasCustomName() && stand.getCustomName() != null) {
            String name = ChatFormatting.stripFormatting(stand.getCustomName().getString());
            return name != null && name.contains("Raffle Ticket");
        }
        return false;
    }

    /** A face of {@code p} touches air: the block can be reached and mined (Exposed Only, Through Walls). */
    static boolean exposed(net.minecraft.world.level.Level level, BlockPos p) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (level.getBlockState(p.relative(d)).isAir()) return true;
        }
        return false;
    }

    /** A ray from his eye reaches a face of {@code p} that touches air without another block in between. */
    static boolean inSight(net.minecraft.world.level.Level level, net.minecraft.world.entity.player.Player player, BlockPos p) {
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!level.getBlockState(p.relative(d)).isAir()) continue;
            net.minecraft.world.phys.Vec3 face = net.minecraft.world.phys.Vec3.atCenterOf(p)
                    .add(d.getStepX() * 0.501, d.getStepY() * 0.501, d.getStepZ() * 0.501);
            if (face.subtract(eye).dot(net.minecraft.world.phys.Vec3.atCenterOf(p).subtract(face)) > 0) {
                continue; // the face points away from him
            }
            net.minecraft.world.phys.HitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, face,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE,
                    player));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                    || (hit instanceof net.minecraft.world.phys.BlockHitResult b && b.getBlockPos().equals(p))) {
                return true;
            }
        }
        return false;
    }

    static void render(LevelRenderContext context) {
        MiningQolConfig cfg = MiningQolConfig.getInstance();
        List<BlockPos> c = carpets;
        List<BlockPos> t = titanium;
        List<double[]> r = tickets;
        if (c.isEmpty() && t.isEmpty() && r.isEmpty()) return;
        FILLS.clear();
        LINES.clear();
        WALL_FILLS.clear();
        WALL_LINES.clear();
        if (!c.isEmpty() && cfg.isCarpet()) {
            // Drawn by the chunk mesher as red carpet now ({@link #carpetReplace}); no box. The last-box record stays for tests.
            double e = CARPET_MARGIN;
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
            boolean exposedOnly = walls && cfg.isTitaniumExposedOnly();
            var mc = net.minecraft.client.Minecraft.getInstance();
            var level = mc.level;
            // Not Through Walls (owner 2026-11-03: "should show the full block ... but only if it is in my line of sight"):
            // a block he can see gets its whole box drawn without depth (all edges), one he cannot see gets nothing.
            if (!walls) {
                fill = WALL_FILLS;
                line = WALL_LINES;
            }
            for (BlockPos p : t) {
                if (exposedOnly && level != null && !exposed(level, p)) continue;
                if (!walls && (level == null || mc.player == null || !inSight(level, mc.player, p))) continue;
                drawnNow.add(p);
                double e = 0.002;
                fill.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e, p.getZ() + 1 + e,
                        k[0], k[1], k[2], 0.25f);
                line.add(p.getX() - e, p.getY() - e, p.getZ() - e, p.getX() + 1 + e, p.getY() + 1 + e, p.getZ() + 1 + e,
                        k[0], k[1], k[2], 1f);
            }
            titaniumFrames++;
        }
        drawnTitanium = List.copyOf(drawnNow);
        drawnNow.clear();
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
        FILLS.drawFilled(context, com.killer560.hub.util.EspTypes.FILLED);
        LINES.drawOutlines(context, com.killer560.hub.util.EspTypes.LINES, 2f);
        WALL_FILLS.drawFilled(context, WorldRenderUtils.throughWallsFilled());
        WALL_LINES.drawOutlines(context, WorldRenderUtils.throughWallsLines(), 2f);
    }


    public static int carpetCount() { return carpets.size(); }

    private static final java.util.ArrayList<BlockPos> drawnNow = new java.util.ArrayList<>();
    private static volatile List<BlockPos> drawnTitanium = List.of();

    /** Testkit: whether the last frame drew a titanium box at x,y,z (after line of sight and Exposed Only). */
    public static boolean drewTitaniumAt(int x, int y, int z) { return drawnTitanium.contains(new BlockPos(x, y, z)); }

    public static int titaniumCount() { return titanium.size(); }

    public static int ticketCount() { return tickets.size(); }

    private static volatile double[] lastCarpetBox = null;

    /** The last carpet fill box drawn (testkit: no face lies on the carpet's or the lantern's own face). */
    public static double[] lastCarpetBox() { return lastCarpetBox; }

    public static int blockEvents() { return events; }

    public static boolean hasTitaniumAt(int x, int y, int z) { return titanium.contains(new BlockPos(x, y, z)); }

    public static boolean hasCarpetAt(int x, int y, int z) { return carpets.contains(new BlockPos(x, y, z)); }

    public static int carpetFrames() { return carpetFrames; }

    public static int titaniumFrames() { return titaniumFrames; }

    public static int raffleFrames() { return raffleFrames; }

    /** Whether the boxes drawn now go through walls (testkit: legit jars must say false). */
    public static boolean titaniumThroughWalls() { return MiningQolConfig.getInstance().isTitaniumEsp(); }

    public static boolean raffleThroughWalls() { return MiningQolConfig.getInstance().isRaffleEsp(); }
}

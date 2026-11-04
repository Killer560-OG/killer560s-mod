package com.killer560.hub.powdermining;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.miningqol.MiningState;
import com.killer560.hub.util.PerTickServerClock;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Crystal Hollows treasure chests ("powder chests") the player uncovers by mining hard stone: which chest is his, how
 * long it has left, the Treasure Chest Highlight box (legit: depth-tested; Through Walls is cheat-only), its HUD timer and
 * the Treasure Chest Alert.
 *
 * <p>Facts (killer560s-mod-logs/powder-grind-1103.md: SkyHanni PowderTracker, Skyblocker CrystalsChestHighlighter, the
 * wiki's Treasure Chest page): the server sends "You uncovered a treasure chest!" and puts a {@code minecraft:chest} next to
 * the mined block; the chest lasts one minute; no despawn line exists. A chest is claimed when a chest block appears within
 * 10 blocks of the player within 3 s of the uncovered line (either order: the block change and the line are matched both
 * ways, Skyblocker's rule plus the reverse). Any other chest (a player's chest, a lobby chest) is never highlighted. A
 * claimed chest leaves when its block stops being a chest (opened and gone), after its minute plus 5 s, or on a world change.
 *
 * <p>Draw-only: nothing here sends a packet.
 */
public final class TreasureChests {

    public static final String TIMER_ID = "mining_treasure_chest";
    /** One minute of server time (wiki; SkyHanni's 60 s clock). */
    static final long LIFETIME_TICKS = 1200L;
    private static final long GRACE_TICKS = 100L;
    /** How far apart (server ticks) the uncovered line and the chest block may arrive. */
    private static final long MATCH_TICKS = 60L;
    private static final int CLAIM_RANGE_SQ = 100;

    /** Claimed chests: position -> server tick it appeared. Client thread only. */
    private static final Map<BlockPos, Long> CHESTS = new LinkedHashMap<>();
    /** Chest blocks that appeared near the player with no uncovered line yet: position -> tick. */
    private static final Map<BlockPos, Long> UNCLAIMED = new LinkedHashMap<>();
    private static int pendingLines = 0;
    private static long pendingAt = 0L;
    private static Object level = null;

    private static int claims = 0;
    private static int alerts = 0;
    private static int boxFrames = 0;
    private static boolean throughWallsNow = false;

    private TreasureChests() {
    }

    static boolean tracking() {
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        return (cfg.isTreasureChest() || cfg.isLockpickHelper()) && MiningState.crystalHollows();
    }

    /** A block changed (client thread, after the change is in the level). */
    public static void onBlockChanged(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !tracking()) {
            return;
        }
        boolean chest = client.level.getBlockState(pos).is(Blocks.CHEST);
        if (!chest) {
            BlockPos key = pos.immutable();
            CHESTS.remove(key);
            UNCLAIMED.remove(key);
            return;
        }
        BlockPos key = pos.immutable();
        if (CHESTS.containsKey(key) || key.distSqr(client.player.blockPosition()) > CLAIM_RANGE_SQ) {
            return;
        }
        long now = PerTickServerClock.now();
        if (pendingLines > 0 && now - pendingAt <= MATCH_TICKS) {
            pendingLines--;
            claim(key, now);
        } else {
            UNCLAIMED.put(key, now);
        }
    }

    /** "You uncovered a treasure chest!" (whole line, from {@link PowderChat}). */
    static void onUncovered() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !MiningState.crystalHollows()) {
            return;
        }
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        if (cfg.isChestAlert()) {
            alerts++;
            if (cfg.isChestAlertTitle()) {
                McCompat.setTimes(client, 2, 25, 5);
                McCompat.setTitle(client, Component.literal("§6§lTREASURE CHEST"));
                McCompat.setSubtitle(client, Component.literal(""));
            }
            if (cfg.isChestAlertSound()) {
                client.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.6f, 1.0f));
            }
        }
        PowderTracker.onUncovered();
        if (!tracking()) {
            return;
        }
        long now = PerTickServerClock.now();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Long> e : UNCLAIMED.entrySet()) {
            if (now - e.getValue() > MATCH_TICKS) {
                continue;
            }
            double d = e.getKey().distSqr(client.player.blockPosition());
            if (d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        if (best != null) {
            long at = UNCLAIMED.remove(best);
            claim(best, at);
        } else {
            pendingLines = Math.min(pendingLines + 1, 4);
            pendingAt = now;
        }
    }

    private static void claim(BlockPos pos, long at) {
        CHESTS.put(pos, at);
        claims++;
    }

    /** The loot block came: the nearest claimed chest within 8 blocks is done (its block goes on its own). */
    static void onLooted() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        BlockPos best = null;
        double bestD = 64.0;
        for (BlockPos p : CHESTS.keySet()) {
            double d = p.distToCenterSqr(client.player.getEyePosition());
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best != null) {
            CHESTS.remove(best);
        }
    }

    static void tick(Minecraft client) {
        if (client.level == null || client.player == null || !tracking()) {
            clear();
            return;
        }
        if (level != client.level) {
            level = client.level;
            clear();
        }
        long now = PerTickServerClock.now();
        for (Iterator<Map.Entry<BlockPos, Long>> it = CHESTS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Long> e = it.next();
            if (now - e.getValue() > LIFETIME_TICKS + GRACE_TICKS || !client.level.getBlockState(e.getKey()).is(Blocks.CHEST)) {
                it.remove();
            }
        }
        UNCLAIMED.values().removeIf(t -> now - t > MATCH_TICKS);
        if (pendingLines > 0 && now - pendingAt > MATCH_TICKS) {
            pendingLines = 0;
        }
        throughWallsNow = PowderMiningConfig.getInstance().isTreasureChestEsp();
    }

    static void clear() {
        CHESTS.clear();
        UNCLAIMED.clear();
        pendingLines = 0;
    }

    /** The chest's box (a chest is 14/16 wide and tall, centered). */
    static AABB box(BlockPos p) {
        return new AABB(p.getX() + 0.0625, p.getY(), p.getZ() + 0.0625,
                p.getX() + 0.9375, p.getY() + 0.875, p.getZ() + 0.9375);
    }

    static Map<BlockPos, Long> chests() {
        return CHESTS;
    }

    static void render(LevelRenderContext context) {
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        if (CHESTS.isEmpty() || !cfg.isTreasureChest()) {
            return;
        }
        long now = PerTickServerClock.now();
        int n = CHESTS.size();
        AABB[] boxes = new AABB[n];
        float[] rgba = new float[n * 4];
        float[] base = WorldRenderUtils.argbToFloats(cfg.getTreasureChestColor());
        int i = 0;
        for (Map.Entry<BlockPos, Long> e : CHESTS.entrySet()) {
            boxes[i] = box(e.getKey()).inflate(0.004);
            float r = base[0], g = base[1], b = base[2];
            if (cfg.isTreasureChestFade()) {
                // Toward red as the minute runs out.
                float f = Math.max(0f, Math.min(1f, (now - e.getValue()) / (float) LIFETIME_TICKS));
                r = r + (1f - r) * f;
                g = g * (1f - f);
                b = b * (1f - f);
            }
            rgba[i * 4] = r;
            rgba[i * 4 + 1] = g;
            rgba[i * 4 + 2] = b;
            rgba[i * 4 + 3] = 1f;
            i++;
        }
        boolean walls = throughWallsNow;
        WorldRenderUtils.renderOutlineBoxes(context, boxes, rgba, n, 2f, walls);
        WorldRenderUtils.renderFilledBoxes(context, boxes.clone(), rgba.clone(), n, 0.25f, walls);
        boxFrames++;
    }

    /** Seconds left on the chest that runs out first, or -1 with none. */
    public static long secondsLeft() {
        long now = PerTickServerClock.now();
        long best = Long.MAX_VALUE;
        for (long at : CHESTS.values()) {
            best = Math.min(best, at + LIFETIME_TICKS - now);
        }
        return best == Long.MAX_VALUE ? -1L : Math.max(0L, (best + 19L) / 20L);
    }

    static String hudLine() {
        long s = secondsLeft();
        if (s < 0) {
            return null;
        }
        int n = CHESTS.size();
        String color = s > 30 ? "§a" : s > 10 ? "§e" : "§c";
        return "§6Treasure Chest" + (n > 1 ? " §7(" + n + ")" : "") + "§7: " + color + s + "s";
    }

    // ---- testkit hooks

    public static int claims() {
        return claims;
    }

    public static int alerts() {
        return alerts;
    }

    public static int boxFrames() {
        return boxFrames;
    }

    public static int count() {
        return CHESTS.size();
    }

    public static boolean throughWalls() {
        return throughWallsNow;
    }

    public static boolean tracked(int x, int y, int z) {
        return CHESTS.containsKey(new BlockPos(x, y, z));
    }

    public static String hudLineNow() {
        return hudLine();
    }

    public static List<int[]> positions() {
        List<int[]> out = new ArrayList<>();
        for (BlockPos p : CHESTS.keySet()) {
            out.add(new int[]{p.getX(), p.getY(), p.getZ()});
        }
        return out;
    }

    /** The movable despawn timer. */
    public static final class Timer implements HudElement {

        private static final String SAMPLE = "§6Treasure Chest§7: §a42s";

        private String shown() {
            String s = hudLine();
            if (s == null && HudVisibility.editorOpen()) {
                return SAMPLE;
            }
            return s;
        }

        @Override
        public String id() {
            return TIMER_ID;
        }

        @Override
        public String displayName() {
            return "Treasure Chest Timer";
        }

        @Override
        public int defaultX() {
            return 10;
        }

        @Override
        public int defaultY() {
            return 232;
        }

        @Override
        public int width() {
            String s = shown();
            return Math.max(60, Minecraft.getInstance().font.width(s == null ? SAMPLE : s));
        }

        @Override
        public int height() {
            return 10;
        }

        @Override
        public boolean isEnabledInSettings() {
            PowderMiningConfig cfg = PowderMiningConfig.getInstance();
            return cfg.isTreasureChestRaw() && cfg.isTreasureChestTimer();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            PowderMiningConfig cfg = PowderMiningConfig.getInstance();
            if (!cfg.isTreasureChest() || !cfg.isTreasureChestTimer() || HudVisibility.hidesHud()) {
                return;
            }
            String s = shown();
            if (s == null) {
                return;
            }
            graphics.text(Minecraft.getInstance().font, s, x, y, 0xFFFFFFFF, true);
            if (hudLine() != null) {
                HudSeen.markDrawn(TIMER_ID);
            }
        }
    }
}

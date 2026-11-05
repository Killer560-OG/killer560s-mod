package com.killer560.hub.powdermining;

import com.killer560.hub.compat.McCompat;
import com.killer560.hub.compat.McRender;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudSeen;
import com.killer560.hub.hud.HudVisibility;
import com.killer560.hub.miningqol.MiningState;
import com.killer560.hub.util.PerTickServerClock;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    // The highlighted chest he last right-clicked, and when (ms): the loot block belongs to it, not to the nearest one
    // (killer560, 2026-11-03: opening a farther chest removed the closest one's highlight).
    private static BlockPos lastUsed = null;
    private static long lastUsedAt = 0L;
    static final long USED_MS = 10_000L;
    // Highlighted chests that left on their lid opening, not yet matched by a loot block (capped), and when.
    private static int openedCredits = 0;
    private static long openedAt = 0L;
    private static int opened = 0;

    /**
     * He right-clicked a block: remember it when it is a chest, highlighted or not. The loot block belongs to the chest he
     * clicked last, so a click on an untracked (already looted, or never claimed) chest must not leave an earlier
     * highlighted one as "last used": that chest then lost its highlight while still there (killer560 2026-11-04, "a chest
     * that isnt highlighting", chestfx-1104).
     */
    static void onUseBlock(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (pos == null || client.level == null || !client.level.getBlockState(pos).is(Blocks.CHEST)) {
            return;
        }
        lastUsed = pos.immutable();
        lastUsedAt = System.currentTimeMillis();
        if (CHESTS.containsKey(pos)) {
            if (PowderMiningConfig.getInstance().isTreasureChestHideOpened()) {
                // Client-only: only the chest's model is skipped (TreasureChestHideMixin); the block stays a chest, so no
                // ghost block and the server's state is untouched.
                HIDDEN.put(lastUsed, System.currentTimeMillis() + HIDDEN_MS);
            }
        }
    }

    /**
     * Chests he clicked, whose model is not drawn: position -> wall-clock ms the hide ends. Read on the render thread by the
     * mixin. A position stays hidden for {@link #GONE_MS} after its block stops being a chest, because the chest can come
     * back: a client tick falling between the server's chest -> air and air -> chest updates used to drop the hide, and the
     * re-sent chest then showed (and flickered) until it was really gone (chestfx-1104). Only a fresh claim there, the
     * timeout or a world change ends it.
     */
    private static final Map<BlockPos, Long> HIDDEN = new ConcurrentHashMap<>();
    /** How long a hidden chest stays hidden at most (its minute plus slack). */
    private static final long HIDDEN_MS = 180_000L;
    /** How long a hidden position stays hidden once its block is no chest. */
    static final long GONE_MS = 5_000L;
    private static int hiddenSkips = 0;
    private static int tracerFrames = 0;
    // The tracer's scratch (grown, never shrunk) and its last frame's lines for the testkit: SEG doubles per line,
    // {from crosshair 1/0, from x y z, to x y z, r g b}.
    private static final int SEG = 10;
    private static int[] order = new int[4];
    private static long[] spawn = new long[4];
    private static double[] segments = new double[4 * SEG];
    private static int segmentCount = 0;
    private static int countdownFrames = 0;
    private static int lastCountdownColor = 0;
    private static String lastCountdownText = null;

    /** True when the chest at {@code pos} is one he clicked and Hide Opened Chests is on (the render mixin's question). */
    public static boolean shouldHide(BlockPos pos) {
        if (HIDDEN.isEmpty() || !PowderMiningConfig.getInstance().isTreasureChestHideOpened()) {
            return false;
        }
        boolean hidden = HIDDEN.containsKey(pos);
        if (hidden) {
            hiddenSkips++;
        }
        return hidden;
    }
    private static int boxFrames = 0;
    private static boolean throughWallsNow = false;

    private TreasureChests() {
    }

    static boolean tracking() {
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        return (cfg.isTreasureChest() || cfg.isLockpickHelper() || cfg.isChestTriggerbot()) && MiningState.crystalHollows();
    }

    /** A block changed (client thread, after the change is in the level). */
    public static void onBlockChanged(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null || !tracking()) return;
        boolean chest = client.level.getBlockState(pos).is(Blocks.CHEST);
        if (!chest) {
            BlockPos key = pos.immutable();
            CHESTS.remove(key);
            UNCLAIMED.remove(key);
            return;
        }
        BlockPos key = pos.immutable();
        if (CHESTS.containsKey(key) || key.distSqr(client.player.blockPosition()) > CLAIM_RANGE_SQ) return;
        long now = PerTickServerClock.now();
        if (pendingLines > 0 && now - pendingAt <= MATCH_TICKS) {
            pendingLines--;
            claim(key, now);
        } else {
            UNCLAIMED.put(key, now);
        }
    }

    /**
     * A chunk arrived (client thread, after it is in the level). A chest that comes inside chunk data gets no block update,
     * so with an uncovered line waiting its chests are matched here: a line, then the chunk with the chest in it (a chunk
     * the client had not loaded yet) was never claimed (chestbot-1104, 1 testkit run in 3).
     */
    public static void onChunkLoaded(int chunkX, int chunkZ) {
        Minecraft client = Minecraft.getInstance();
        if (pendingLines == 0 || client.level == null || client.player == null || !tracking()) return;
        for (BlockPos pos : client.level.getChunk(chunkX, chunkZ).getBlockEntities().keySet()) {
            if (pendingLines == 0) return;
            onBlockChanged(pos);
        }
    }

    /** "You uncovered a treasure chest!" (whole line, from {@link PowderChat}). */
    static void onUncovered() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !MiningState.crystalHollows()) return;
        PowderMining.markChest();
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
        if (!tracking()) return;
        long now = PerTickServerClock.now();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Long> e : UNCLAIMED.entrySet()) {
            if (now - e.getValue() > MATCH_TICKS) continue;
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
        HIDDEN.remove(pos); // a new chest at a spot he opened before
        CHESTS.put(pos, at);
        claims++;
    }

    /**
     * A chest's lid opened (block event, client thread): a highlighted chest there is done. Exact, where the loot block only
     * says that SOME chest was looted; the loot block that follows is then paid for by {@link #openedCredits}.
     */
    public static void onChestOpened(BlockPos pos) {
        if (CHESTS.remove(pos) != null) {
            openedCredits = Math.min(openedCredits + 1, 4);
            openedAt = System.currentTimeMillis();
            opened++;
        }
    }

    /**
     * The loot block came: a chest whose lid opened pays for it; else the chest he clicked last (if highlighted); else, with
     * no chest click in {@link #USED_MS}, the nearest claimed chest within 8 blocks (its block goes on its own).
     */
    static void onLooted() {
        PowderMining.markChest();
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        long ms = System.currentTimeMillis();
        if (openedCredits > 0 && ms - openedAt <= USED_MS) {
            openedCredits--; // its chest already left when its lid opened
            return;
        }
        openedCredits = 0;
        if (lastUsed != null && ms - lastUsedAt <= USED_MS) {
            CHESTS.remove(lastUsed); // nothing when the chest he clicked was not a highlighted one
            lastUsed = null;
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
        if (client.level == null || client.player == null) {
            clear();
            return;
        }
        if (level != client.level) {
            level = client.level;
            clear();
        }
        if (!HIDDEN.isEmpty()) {
            // Kept through a lapse in tracking() (an island read) too: only the world going ends a hide early.
            long wall = System.currentTimeMillis();
            for (Iterator<Map.Entry<BlockPos, Long>> it = HIDDEN.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<BlockPos, Long> e = it.next();
                boolean chest = client.level.getBlockState(e.getKey()).is(Blocks.CHEST);
                if (wall > e.getValue()) {
                    it.remove();
                } else if (chest ? e.getValue() < wall + GONE_MS : e.getValue() > wall + GONE_MS) {
                    e.setValue(wall + GONE_MS); // gone: GONE_MS more; back again: hidden while it stays
                }
            }
        }
        if (!tracking()) {
            CHESTS.clear();
            UNCLAIMED.clear();
            pendingLines = 0;
            return;
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
        HIDDEN.clear();
        CHESTS.clear();
        UNCLAIMED.clear();
        pendingLines = 0;
        openedCredits = 0;
        lastUsed = null;
    }

    /** The chest's box (a chest is 14/16 wide and tall, centered). */
    static AABB box(BlockPos p) {
        return new AABB(p.getX() + 0.0625, p.getY(), p.getZ() + 0.0625,
                p.getX() + 0.9375, p.getY() + 0.875, p.getZ() + 0.9375);
    }

    static Map<BlockPos, Long> chests() { return CHESTS; }

    static void render(LevelRenderContext context) {
        PowderMiningConfig cfg = PowderMiningConfig.getInstance();
        if (CHESTS.isEmpty() || !cfg.isTreasureChest()) return;
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
        if (cfg.isTreasureChestTracer()) {
            renderTracers(context, boxes, rgba, n, cfg.getTreasureChestTracerMode() == PowderMiningConfig.TracerMode.CHAIN,
                    walls);
        }
        if (cfg.isTreasureChestCountdown()) {
            for (Map.Entry<BlockPos, Long> e : CHESTS.entrySet()) {
                long left = e.getValue() + LIFETIME_TICKS - now;
                String text = countdownText(left);
                int color = countdownColor(left);
                lastCountdownColor = color;
                lastCountdownText = text;
                BlockPos p = e.getKey();
                renderLabel(context, p.getX() + 0.5, p.getY() + 1.3, p.getZ() + 0.5, text, color, walls);
            }
            countdownFrames++;
        }
    }

    /**
     * Each line takes the color of the chest it goes TO, as its box is drawn (killer560, 2026-11-04). Chain: crosshair ->
     * oldest chest -> next oldest ...; From Crosshair: one line from the crosshair to each chest. {@code boxes} and
     * {@code rgba} are in {@link #CHESTS} order.
     */
    private static void renderTracers(LevelRenderContext context, AABB[] boxes, float[] rgba, int n, boolean chain,
                                      boolean walls) {
        if (order.length < n) {
            order = new int[n * 2];
            spawn = new long[n * 2];
            segments = new double[n * 2 * SEG];
        }
        int i = 0;
        for (long at : CHESTS.values()) {
            spawn[i] = at;
            order[i] = i;
            i++;
        }
        if (chain) {
            for (int a = 1; a < n; a++) { // insertion sort by spawn tick, oldest first: a handful of chests
                int k = order[a];
                int b = a - 1;
                while (b >= 0 && spawn[order[b]] > spawn[k]) {
                    order[b + 1] = order[b];
                    b--;
                }
                order[b + 1] = k;
            }
        }
        Vec3 origin = WorldRenderUtils.tracerOrigin(context);
        Vec3 from = origin;
        for (int s = 0; s < n; s++) {
            int c = order[s];
            Vec3 to = boxes[c].getCenter();
            float r = rgba[c * 4], g = rgba[c * 4 + 1], b = rgba[c * 4 + 2];
            WorldRenderUtils.renderLine(context, from, to, r, g, b, 1f, 2f, walls);
            int o = s * SEG;
            segments[o] = from == origin ? 1 : 0;
            segments[o + 1] = from.x;
            segments[o + 2] = from.y;
            segments[o + 3] = from.z;
            segments[o + 4] = to.x;
            segments[o + 5] = to.y;
            segments[o + 6] = to.z;
            segments[o + 7] = r;
            segments[o + 8] = g;
            segments[o + 9] = b;
            if (chain) {
                from = to;
            }
        }
        segmentCount = n;
        tracerFrames++;
    }

    /** "42s" for the ticks left (rounded up, never negative). */
    public static String countdownText(long leftTicks) {
        return Math.max(0L, (leftTicks + 19L) / 20L) + "s";
    }

    /**
     * The countdown's color for the ticks left of the minute: green with the whole minute left, yellow at half, red at none
     * (a straight blend of the two halves, so it moves a little every second).
     */
    public static int countdownColor(long leftTicks) {
        float f = Math.max(0f, Math.min(1f, leftTicks / (float) LIFETIME_TICKS));
        int r;
        int g;
        if (f >= 0.5f) {
            r = Math.round(255f * (1f - f) * 2f);
            g = 255;
        } else {
            r = 255;
            g = Math.round(255f * f * 2f);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | 0x20;
    }

    /** Camera-facing text over the chest; legit text is depth-tested, Through Walls draws it see-through. */
    private static void renderLabel(LevelRenderContext context, double x, double y, double z, String text, int color,
                                    boolean walls) {
        PoseStack poseStack = context.poseStack();
        if (poseStack == null) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 cam = McRender.cameraPos(context);
        double dist = Math.sqrt(cam.distanceToSqr(x, y, z));
        float s = 0.025f * (float) Math.min(8.0, Math.max(1.0, dist / 12.0));
        poseStack.pushPose();
        try {
            poseStack.translate(x - cam.x, y - cam.y, z - cam.z);
            McRender.rotate(poseStack, McRender.cameraRotation(context));
            poseStack.scale(s, -s, s);
            McRender.drawText(context, font, text, -McRender.textWidth(font, text) / 2f, -font.lineHeight / 2f, color, true,
                    poseStack, walls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, 0xF000F0);
        } finally {
            poseStack.popPose();
        }
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
        if (s < 0) return null;
        int n = CHESTS.size();
        String color = s > 30 ? "§a" : s > 10 ? "§e" : "§c";
        return "§6Treasure Chest" + (n > 1 ? " §7(" + n + ")" : "") + "§7: " + color + s + "s";
    }


    public static int claims() { return claims; }

    public static int opened() {
        return opened;
    }

    public static int alerts() {
        return alerts;
    }

    public static int boxFrames() { return boxFrames; }

    public static int hiddenSkips() {
        return hiddenSkips;
    }

    public static int hiddenCount() {
        return HIDDEN.size();
    }

    public static int tracerFrames() {
        return tracerFrames;
    }

    /** The last frame's tracer lines in drawing order, each {from crosshair 1/0, from x y z, to x y z, r g b}. */
    public static List<double[]> tracerSegments() {
        List<double[]> out = new ArrayList<>();
        for (int s = 0; s < segmentCount; s++) {
            out.add(java.util.Arrays.copyOfRange(segments, s * SEG, s * SEG + SEG));
        }
        return out;
    }

    public static boolean hidden(int x, int y, int z) {
        return HIDDEN.containsKey(new BlockPos(x, y, z));
    }

    public static int countdownFrames() {
        return countdownFrames;
    }

    public static int lastCountdownColor() {
        return lastCountdownColor;
    }

    public static String lastCountdownText() {
        return lastCountdownText;
    }

    /** Pretends he right-clicked the chest at the position (testkit). */
    public static void useForTest(int x, int y, int z) {
        onUseBlock(new BlockPos(x, y, z));
    }

    public static int count() {
        return CHESTS.size();
    }

    public static boolean throughWalls() { return throughWallsNow; }

    public static boolean tracked(int x, int y, int z) { return CHESTS.containsKey(new BlockPos(x, y, z)); }

    public static String hudLineNow() { return hudLine(); }

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
            return s == null && HudVisibility.editorOpen() ? SAMPLE : s;
        }

        @Override
        public String id() { return TIMER_ID; }

        @Override
        public String displayName() { return "Treasure Chest Timer"; }

        @Override
        public int defaultX() { return 10; }

        @Override
        public int defaultY() { return 232; }

        @Override
        public int width() {
            String s = shown();
            return Math.max(60, Minecraft.getInstance().font.width(s == null ? SAMPLE : s));
        }

        @Override
        public int height() { return 10; }

        @Override
        public boolean isEnabledInSettings() {
            PowderMiningConfig cfg = PowderMiningConfig.getInstance();
            return cfg.isTreasureChestRaw() && cfg.isTreasureChestTimer();
        }

        @Override
        public void render(GuiGraphicsExtractor graphics, int x, int y) {
            PowderMiningConfig cfg = PowderMiningConfig.getInstance();
            if (!cfg.isTreasureChest() || !cfg.isTreasureChestTimer() || HudVisibility.hidesHud()) return;
            String s = shown();
            if (s == null) return;
            graphics.text(Minecraft.getInstance().font, s, x, y, 0xFFFFFFFF, true);
            if (hudLine() != null) {
                HudSeen.markDrawn(TIMER_ID);
            }
        }
    }
}

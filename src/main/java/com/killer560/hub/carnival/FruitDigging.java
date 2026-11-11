package com.killer560.hub.carnival;

import com.killer560.hub.islands.IslandHud;
import com.killer560.hub.islands.IslandRender;
import com.killer560.hub.util.ChatObserver;
import com.killer560.hub.util.FeatureBits;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.ModLog;
import com.killer560.hub.util.WorldRenderUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fruit Digging Solver (SkyHanni's Fruit Digging helper and solver): while you play Fruit Digging it keeps the 7x7 sand
 * grid's state - which tiles are dug, what each held, the Mines, Treasure and Anchor clues each dig gave, the fruits a
 * bomb destroyed - and boxes the best tile to dig next with the shovel mode to use and its bomb odds
 * ({@link FruitDiggingSolver}), labels each tile's clue and known content, and shows a HUD with the digs left, the
 * recommendation and the fruit still in the ground. Display only: it never digs for you (Auto Fruit Digging, cheat build,
 * does).
 *
 * <p>The grid is found as the 7x7 patch of sand nearest you when the game starts (SkyHanni's spot, -112 72 19, first).
 * A tile counts as dug when it stops being sand right after you hit it, or turns to sandstone; any other tile that stops
 * being sand was a fruit a bomb destroyed. Unverified in game, from SkyHanni: the clue lines, the reveal tag over a dug
 * tile ("Mango (+300)", "Bomb", "Rum"), sandstone for a dug tile, and the Anchor's marker being an item over the tile.
 */
public final class FruitDigging {

    private static final Logger LOGGER = ModLog.get("killer560smod-carnival");
    static final Pattern TREASURE = Pattern.compile("^TREASURE! There is an? ([A-Za-z ]{3,20}) nearby\\.$");
    static final Pattern NO_FRUIT = Pattern.compile("^(?:TREASURE|ANCHOR)! There are no fruits nearby!$");
    static final Pattern MINES = Pattern.compile("^MINES! There (?:is|are) (\\d{1,2}) bombs? hidden nearby\\.$");
    static final Pattern REVEAL = Pattern.compile("^([A-Za-z ]{3,20}?)(?: \\(\\+\\d{1,5}\\))?$");
    /** SkyHanni's grid corner (row A, column 1). */
    private static final BlockPos KNOWN_ORIGIN = new BlockPos(-112, 72, 19);

    private static BlockPos origin;
    private static FruitDiggingSolver.Cell[] cells = newCells();
    private static int digsUsed;
    private static int lastDug = -1;
    /** The dig before {@link #lastDug} (a Coconut there keeps this dig's bomb from going off). */
    private static int previousDug = -1;
    private static long lastDugTick;
    private static int attacked = -1;
    private static long attackedTick;
    private static final Set<Integer> SEEN_TAGS = new HashSet<>();
    /** A dug bomb that went off, and when: its neighbors still standing hold no fruit once its blast has landed. */
    private static final List<int[]> EXPLOSIONS = new ArrayList<>();
    private static boolean dirty;
    private static long tick;
    private static long solvedTick = -100;
    private static volatile FruitDiggingSolver.Recommendation recommendation;
    private static volatile int solves;

    private FruitDigging() {
    }

    private static FruitDiggingSolver.Cell[] newCells() {
        FruitDiggingSolver.Cell[] c = new FruitDiggingSolver.Cell[FruitDiggingSolver.CELLS];
        for (int i = 0; i < c.length; i++) {
            c[i] = new FruitDiggingSolver.Cell();
        }
        return c;
    }

    /** On while the solver or the auto wants the board read. */
    static boolean wanted() {
        return FruitDiggingConfig.getInstance().isEnabledRaw() || AutoFruitConfig.wantsBoard();
    }

    public static void register() {
        CarnivalState.register();
        CarnivalState.addDemand(FruitDigging::wanted);
        CarnivalState.onGameStart(g -> {
            if (g == CarnivalState.Game.FRUIT_DIGGING) {
                reset();
            }
        });
        CarnivalState.onGameEnd((g, score, ms) -> {
            if (g == CarnivalState.Game.FRUIT_DIGGING) {
                LOGGER.info("[Fruit Digging] game over: {} digs, score {}", digsUsed, score);
            }
        });
        ChatObserver.subscribe(FruitDigging::onChat);
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            try {
                if (active() && level.isClientSide()) {
                    int c = cellAt(pos);
                    if (c >= 0 && guardRefuses(player, c)) {
                        return InteractionResult.FAIL;
                    }
                    if (c >= 0) {
                        attacked = c;
                        attackedTick = tick;
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Fruit Digging] attack hook failed", e);
            }
            return InteractionResult.PASS;
        });
        int bit = FeatureBits.register("Fruit Digging Solver", FruitDigging::wanted);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("FruitDigging.tick", bit, FruitDigging::tick));
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(FeatureGuard.afterFeatures(bit, FruitDigging::render));
    }

    private static String lastRefusal;
    private static long lastRefusalMs;
    private static int refusals;

    /**
     * Prevent Misclicks and Prevent Wrong Shovel (legit, they only cancel his own click): true when a dig on tile
     * {@code c} must not go. Canceling makes the callback fire every tick the button is held (docs/LESSONS-MINECRAFT.md),
     * so the chat line is said once per refusal reason and tile, at most every 2 s.
     */
    private static boolean guardRefuses(net.minecraft.world.entity.player.Player player, int c) {
        FruitDiggingConfig cfg = FruitDiggingConfig.getInstance();
        if (!cfg.isEnabled() || (cfg.isSneakAllows() && player.isShiftKeyDown())) {
            return false;
        }
        FruitDiggingSolver.Recommendation r = recommendation();
        if (r == null || !cell(c).diggable) {
            return false;
        }
        String why = null;
        if (cfg.isPreventMisclicks() && c != r.cell()) {
            why = "Not the best tile: dig " + r.label() + (cfg.isSneakAllows() ? " (sneak to dig anyway)." : ".");
        } else if (cfg.isPreventWrongShovel() && player instanceof net.minecraft.client.player.LocalPlayer lp
                && !AutoShovelSwap.ready(lp)) {
            why = "Wrong shovel mode: right click to switch to " + r.shovel()
                    + (cfg.isSneakAllows() ? " (sneak to dig anyway)." : ".");
        }
        if (why == null) {
            return false;
        }
        refusals++;
        long now = System.currentTimeMillis();
        if (!why.equals(lastRefusal) || now - lastRefusalMs > 2000L) {
            lastRefusal = why;
            lastRefusalMs = now;
            com.killer560.hub.util.ModChat.send("Fruit Digging", com.killer560.hub.util.ModChat.text(why));
        }
        return true;
    }

    /** Harness: digs the two guards canceled (ticks held count each). */
    public static int refusals() {
        return refusals;
    }

    static boolean active() {
        return CarnivalState.playing(CarnivalState.Game.FRUIT_DIGGING) && CarnivalState.inCarnival();
    }

    static void reset() {
        origin = null;
        cells = newCells();
        digsUsed = 0;
        lastDug = -1;
        previousDug = -1;
        attacked = -1;
        SEEN_TAGS.clear();
        EXPLOSIONS.clear();
        recommendation = null;
        solved = null;
        boardGeneration++;
        dirty = true;
    }

    // ---- grid ----------------------------------------------------------------------------------------------------

    static BlockPos posOf(int cell) {
        return origin == null ? null : origin.offset(cell % FruitDiggingSolver.SIZE, 0, cell / FruitDiggingSolver.SIZE);
    }

    static int cellAt(BlockPos pos) {
        if (origin == null || pos.getY() != origin.getY()) {
            return -1;
        }
        int col = pos.getX() - origin.getX();
        int row = pos.getZ() - origin.getZ();
        if (col < 0 || row < 0 || col >= FruitDiggingSolver.SIZE || row >= FruitDiggingSolver.SIZE) {
            return -1;
        }
        return row * FruitDiggingSolver.SIZE + col;
    }

    /** The cell a point above the grid (an entity) stands over, or -1. */
    static int cellUnder(double x, double y, double z) {
        if (origin == null || y < origin.getY() - 0.5 || y > origin.getY() + 4.5) {
            return -1;
        }
        int col = (int) Math.floor(x) - origin.getX();
        int row = (int) Math.floor(z) - origin.getZ();
        if (col < 0 || row < 0 || col >= FruitDiggingSolver.SIZE || row >= FruitDiggingSolver.SIZE) {
            return -1;
        }
        return row * FruitDiggingSolver.SIZE + col;
    }

    private static String blockId(ClientLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    private static boolean gridBlock(String id) {
        return id.equals("sand") || id.startsWith("sandstone");
    }

    /** Whether the 7x7 patch at {@code o} is a Fruit Digging grid: all sand or sandstone, at least 25 still sand. */
    private static boolean isGrid(ClientLevel level, BlockPos o) {
        int sand = 0;
        for (int r = 0; r < FruitDiggingSolver.SIZE; r++) {
            for (int c = 0; c < FruitDiggingSolver.SIZE; c++) {
                String id = blockId(level, o.offset(c, 0, r));
                if (!gridBlock(id)) {
                    return false;
                }
                if (id.equals("sand")) {
                    sand++;
                }
            }
        }
        return sand >= 25;
    }

    /** Looks for the grid: SkyHanni's spot, then any fitting patch around the player. */
    static BlockPos findGrid(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            return null;
        }
        if (isGrid(level, KNOWN_ORIGIN)) {
            return KNOWN_ORIGIN;
        }
        BlockPos p = mc.player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int y = p.getY() - 4; y <= p.getY(); y++) {
            for (int x = p.getX() - 12; x <= p.getX() + 6; x++) {
                for (int z = p.getZ() - 12; z <= p.getZ() + 6; z++) {
                    BlockPos o = new BlockPos(x, y, z);
                    // Corners first: almost every candidate fails there.
                    if (!gridBlock(blockId(level, o)) || !gridBlock(blockId(level, o.offset(6, 0, 6)))
                            || !gridBlock(blockId(level, o.offset(6, 0, 0))) || !gridBlock(blockId(level, o.offset(0, 0, 6)))) {
                        continue;
                    }
                    if (!isGrid(level, o)) {
                        continue;
                    }
                    double d = o.offset(3, 0, 3).distSqr(p);
                    if (d < bestD) {
                        bestD = d;
                        best = o;
                    }
                }
            }
        }
        return best;
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    private static void tick(Minecraft mc) {
        tick++;
        if (!active() || mc.level == null || mc.player == null) {
            return;
        }
        if (origin == null) {
            if (tick % 10 != 0) {
                return;
            }
            origin = findGrid(mc);
            if (origin == null) {
                return;
            }
            LOGGER.info("[Fruit Digging] grid found at {}", origin.toShortString());
            dirty = true;
        }
        scanBlocks(mc.level);
        scanEntities(mc);
        for (int[] ex : EXPLOSIONS) {
            if (ex[1] >= 0 && tick - ex[1] >= 10) {
                for (int nb : FruitDiggingSolver.neighbors(ex[0])) {
                    if (cells[nb].diggable && cells[nb].content == null) {
                        cells[nb].notFruit = true;
                    }
                }
                ex[1] = -1;
                dirty = true;
            }
        }
        if (dirty && tick - solvedTick >= 4) {
            solve();
        }
    }

    /** Notes every tile that stopped being sand since the last look. */
    static void scanBlocks(ClientLevel level) {
        if (origin == null) {
            return;
        }
        for (int i = 0; i < FruitDiggingSolver.CELLS; i++) {
            FruitDiggingSolver.Cell c = cells[i];
            if (!c.diggable) {
                continue;
            }
            String id = blockId(level, posOf(i));
            if (id.equals("sand")) {
                continue;
            }
            c.diggable = false;
            dirty = true;
            boolean hitIt = i == attacked && tick - attackedTick <= 40;
            boolean watermelonReveal = lastDug >= 0 && cells[lastDug].content == Fruit.WATERMELON
                    && tick - lastDugTick <= 30 && isNeighbor(lastDug, i) && !hitIt;
            boolean noAttackKnown = attacked < 0 || tick - attackedTick > 40;
            if (hitIt || (id.equals("sandstone") && !watermelonReveal && noAttackKnown)) {
                digsUsed++;
                previousDug = lastDug;
                lastDug = i;
                lastDugTick = tick;
                attacked = -1;
                LOGGER.info("[Fruit Digging] dug {} ({} of {})", FruitDiggingSolver.label(i), digsUsed,
                        FruitDiggingSolver.MAX_DIGS);
            } else if (!watermelonReveal && c.content == null) {
                // Blown up by a bomb: only fruits are destroyed.
                c.mustFruit = true;
            }
        }
    }

    private static boolean isNeighbor(int a, int b) {
        for (int nb : FruitDiggingSolver.neighbors(a)) {
            if (nb == b) {
                return true;
            }
        }
        return false;
    }

    private static void scanEntities(Minecraft mc) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof ArmorStand stand) {
                if (SEEN_TAGS.contains(stand.getId())) {
                    continue;
                }
                String name = IslandRender.plainName(stand);
                if (name == null || name.isBlank()) {
                    continue;
                }
                int cell = cellUnder(stand.getX(), stand.getY(), stand.getZ());
                if (cell < 0) {
                    continue;
                }
                Matcher m = REVEAL.matcher(name.trim());
                Fruit f = m.matches() ? Fruit.byName(m.group(1)) : null;
                if (f != null) {
                    SEEN_TAGS.add(stand.getId());
                    reveal(cell, f);
                }
            } else if (e instanceof ItemEntity item) {
                int cell = cellUnder(item.getX(), item.getY(), item.getZ());
                if (cell < 0 || !cells[cell].diggable || SEEN_TAGS.contains(item.getId())) {
                    continue;
                }
                SEEN_TAGS.add(item.getId());
                anchorMark(cell, Fruit.byName(ChatObserver.stripCodes(item.getItem().getHoverName().getString())));
            }
        }
    }

    /** A dug or watermelon-shown tile says what it held. */
    static void reveal(int cell, Fruit f) {
        FruitDiggingSolver.Cell c = cells[cell];
        if (c.content == f) {
            return;
        }
        c.content = f;
        c.mustFruit = false;
        dirty = true;
        LOGGER.info("[Fruit Digging] {} held {}", FruitDiggingSolver.label(cell), f.label);
        // A dug bomb goes off unless the dig before it was a Coconut.
        if (f == Fruit.BOMB && cell == lastDug && !(previousDug >= 0 && cells[previousDug].content == Fruit.COCONUT)) {
            EXPLOSIONS.add(new int[]{cell, (int) tick});
        }
    }

    /** An Anchor marker over a standing tile: that tile holds the lowest fruit near the last dig. */
    static void anchorMark(int cell, Fruit f) {
        FruitDiggingSolver.Cell c = cells[cell];
        if (f != null && f.edible) {
            c.content = f;
            if (lastDug >= 0 && cells[lastDug].anchor == null && !cells[lastDug].noFruitNearby) {
                cells[lastDug].anchor = f;
            }
        } else {
            c.mustFruit = true;
        }
        dirty = true;
        LOGGER.info("[Fruit Digging] anchor marks {} ({})", FruitDiggingSolver.label(cell), f == null ? "unnamed" : f.label);
    }

    private static void onChat(Component message) {
        if (!wanted() || !active()) {
            return;
        }
        String plain = ChatObserver.strip(message);
        if (plain == null || plain.length() > 60) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && origin != null) {
            // The dig's block change came in the same batch as its clue: note it first so the clue lands on it.
            scanBlocks(mc.level);
        }
        if (lastDug < 0) {
            return;
        }
        FruitDiggingSolver.Cell c = cells[lastDug];
        Matcher m = MINES.matcher(plain);
        if (m.matches()) {
            c.mines = Integer.parseInt(m.group(1));
            dirty = true;
            return;
        }
        m = TREASURE.matcher(plain);
        if (m.matches()) {
            Fruit f = Fruit.byName(m.group(1));
            if (f != null && f.edible) {
                c.treasure = f;
                dirty = true;
            }
            return;
        }
        if (NO_FRUIT.matcher(plain).matches()) {
            c.noFruitNearby = true;
            dirty = true;
        }
    }

    // ---- solving -------------------------------------------------------------------------------------------------

    /** The last dug tile's fruit, which sets the next dig's multiplier (Pomegranate x1.5, Durian x0.5). */
    static Fruit lastFruit() {
        return lastDug >= 0 ? cells[lastDug].content : null;
    }

    static double nextMultiplier() {
        Fruit f = lastFruit();
        return f == Fruit.POMEGRANATE ? 1.5 : f == Fruit.DURIAN ? 0.5 : 1.0;
    }

    /** Solves off the render thread (a solve takes 10-50 ms); the newest finished one is shown. */
    static void solve() {
        if (solving) {
            return;
        }
        dirty = false;
        solvedTick = tick;
        solving = true;
        FruitDiggingSolver.Cell[] snapshot = new FruitDiggingSolver.Cell[cells.length];
        for (int i = 0; i < cells.length; i++) {
            snapshot[i] = cells[i].copy();
        }
        int digs = digsUsed;
        double mult = nextMultiplier();
        boolean coconut = lastFruit() == Fruit.COCONUT;
        long generation = boardGeneration;
        FruitDiggingSolver.Recommendation last = recommendation;
        int keep = last == null ? -1 : last.cell();
        WORKER.execute(() -> {
            try {
                long t0 = System.nanoTime();
                FruitDiggingSolver solver = new FruitDiggingSolver(new Random());
                FruitDiggingSolver.Recommendation r = solver.solve(snapshot, digs, mult, coconut, keep);
                if (generation == boardGeneration) {
                    solved = solver;
                    recommendation = r;
                    solves++;
                }
                if (r != null) {
                    LOGGER.info("[Fruit Digging] best dig {} with {} (bomb {}%, rum {}%, {} samples, {} ms)", r.label(),
                            r.shovel(), pct(r.pBomb()), pct(r.pRum()), r.samples(), (System.nanoTime() - t0) / 1_000_000);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[Fruit Digging] solve failed", e);
            } finally {
                solving = false;
            }
        });
    }

    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "killer560smod-fruit-digging");
                t.setDaemon(true);
                return t;
            });
    private static volatile boolean solving;
    /** Bumped by every reset, so a solve of the last game's board is thrown away. */
    private static volatile long boardGeneration;
    private static volatile FruitDiggingSolver solved;

    private static String pct(double p) {
        return String.format(Locale.US, "%.0f", p * 100);
    }

    static FruitDiggingSolver.Recommendation recommendation() {
        return recommendation;
    }

    static int digsUsed() {
        return digsUsed;
    }

    static FruitDiggingSolver.Cell cell(int i) {
        return cells[i];
    }

    /** Fruit still in the ground (not dug, shown or destroyed), per kind. */
    static int[] remaining() {
        int[] left = new int[Fruit.values().length];
        for (Fruit f : Fruit.values()) {
            left[f.ordinal()] = f.count;
        }
        for (FruitDiggingSolver.Cell c : cells) {
            if (c.content != null && !c.diggable) {
                left[c.content.ordinal()]--;
            } else if (c.mustFruit && !c.diggable) {
                // A destroyed fruit of an unknown kind: nothing to take away from one kind.
            }
        }
        return left;
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    private static void render(LevelRenderContext context) {
        FruitDiggingConfig cfg = FruitDiggingConfig.getInstance();
        if (!cfg.isEnabled() || !active() || origin == null) {
            return;
        }
        for (int i = 0; i < FruitDiggingSolver.CELLS; i++) {
            FruitDiggingSolver.Cell c = cells[i];
            BlockPos p = posOf(i);
            double x = p.getX() + 0.5;
            double z = p.getZ() + 0.5;
            double y = p.getY() + 1.25;
            List<String> label = new ArrayList<>();
            if (cfg.isFound() && c.content != null) {
                label.add((c.diggable ? "§e" : "§7") + c.content.label);
            }
            if (cfg.isClues() && !c.diggable) {
                if (c.mines >= 0) {
                    label.add("§c" + c.mines + " bomb" + (c.mines == 1 ? "" : "s"));
                }
                if (c.treasure != null) {
                    label.add("§b≤ " + c.treasure.label);
                }
                if (c.anchor != null) {
                    label.add("§b≥ " + c.anchor.label);
                }
                if (c.noFruitNearby) {
                    label.add("§8no fruit near");
                }
            }
            if (cfg.isOdds() && c.diggable && c.content == null) {
                FruitDiggingSolver sv = solved;
                double[] b = sv == null ? null : sv.belief(i);
                if (b != null) {
                    label.add("§7" + pct(b[Fruit.BOMB.ordinal()] + b[Fruit.RUM.ordinal()]) + "% bad");
                }
            }
            for (int k = 0; k < label.size(); k++) {
                IslandRender.label(context, x, y + 0.3 * (label.size() - 1 - k), z, label.get(k), 0xFFFFFF);
            }
        }
        FruitDiggingSolver.Recommendation r = recommendation;
        if (cfg.isBestDig() && r != null && cells[r.cell()].diggable) {
            BlockPos p = posOf(r.cell());
            AABB box = new AABB(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1.02, p.getZ() + 1);
            float[] col = WorldRenderUtils.argbToFloats(cfg.colorFor(r.shovel()));
            WorldRenderUtils.renderFilledBox(context, box, col[0], col[1], col[2], 0.35f);
            WorldRenderUtils.renderOutlineBox(context, box, col[0], col[1], col[2], 1f, 3f);
            IslandRender.label(context, p.getX() + 0.5, p.getY() + 2.1, p.getZ() + 0.5,
                    "§aDig here §7(" + r.shovel() + ", " + pct(r.pBomb()) + "% bomb)", 0xFFFFFF);
        }
    }

    static List<String> lines() {
        FruitDiggingConfig cfg = FruitDiggingConfig.getInstance();
        if (!cfg.isEnabled() || !cfg.isHud() || !active()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("§6§lFruit Digging");
        if (origin == null) {
            out.add("§7Looking for the grid...");
            return out;
        }
        out.add("§7Digs: §f" + digsUsed + "§7/" + FruitDiggingSolver.MAX_DIGS
                + (nextMultiplier() != 1.0 ? " §7next dig §e x" + nextMultiplier() : "")
                + (lastFruit() == Fruit.COCONUT ? " §7bomb-proof" : "")
                + (lastFruit() == Fruit.RUM ? " §cshovel off" : ""));
        FruitDiggingSolver.Recommendation r = recommendation;
        if (r != null) {
            out.add("§aDig: §f" + r.label() + " §7with §f" + r.shovel());
            out.add("§7Bomb §c" + pct(r.pBomb()) + "% §7Rum §6" + pct(r.pRum()) + "% §7(~"
                    + String.format(Locale.US, "%.0f", r.expected()) + " pts)");
        }
        if (cfg.isRemaining()) {
            int[] left = remaining();
            StringBuilder s = new StringBuilder();
            for (Fruit f : Fruit.values()) {
                if (f.edible && left[f.ordinal()] > 0) {
                    if (s.length() > 0) {
                        s.append("§7, ");
                    }
                    s.append("§f").append(f.label).append(" ").append(left[f.ordinal()]);
                }
            }
            if (s.length() > 0) {
                out.add("§7Left: " + s);
            }
        }
        return out;
    }

    // ---- harness -------------------------------------------------------------------------------------------------

    /** Harness: the grid corner, or null. */
    public static BlockPos gridOrigin() {
        return origin;
    }

    /** Harness: the recommended tile's label ("C4"), or null. */
    public static String bestLabel() {
        FruitDiggingSolver.Recommendation r = recommendation;
        return r == null ? null : r.label();
    }

    /** Harness: the recommended tile's block, or null. */
    public static BlockPos bestPos() {
        FruitDiggingSolver.Recommendation r = recommendation;
        return r == null ? null : posOf(r.cell());
    }

    /** Harness: digs counted. */
    public static int digs() {
        return digsUsed;
    }

    /** Harness: solves run. */
    public static int solveCount() {
        return solves;
    }

    /** Harness: a tile's Mines clue ("C4"), or -1. */
    public static int minesAt(String label) {
        int i = (label.charAt(0) - 'A') * FruitDiggingSolver.SIZE + (label.charAt(1) - '1');
        return cells[i].mines;
    }

    /** Harness: a tile's known content name, or null. */
    public static String contentAt(String label) {
        int i = (label.charAt(0) - 'A') * FruitDiggingSolver.SIZE + (label.charAt(1) - '1');
        return cells[i].content == null ? null : cells[i].content.label;
    }

    /** Harness: the HUD's lines now. */
    public static List<String> hudLines() {
        return lines();
    }

    public static final class Hud extends IslandHud {

        private static final List<String> SAMPLE = List.of("§6§lFruit Digging", "§7Digs: §f6§7/15",
                "§aDig: §fC4 §7with §fAnchor", "§7Bomb §c8% §7Rum §64% §7(~410 pts)",
                "§7Left: §fMango 7§7, §fDurian 2");

        @Override
        public String id() {
            return "carnival_fruit_digging";
        }

        @Override
        public String displayName() {
            return "Fruit Digging Solver";
        }

        @Override
        public int defaultY() {
            return 230;
        }

        @Override
        public boolean isEnabledInSettings() {
            FruitDiggingConfig cfg = FruitDiggingConfig.getInstance();
            return cfg.isEnabled() && cfg.isHud();
        }

        @Override
        protected List<String> lines() {
            return FruitDigging.lines();
        }

        @Override
        protected List<String> sample() {
            return SAMPLE;
        }
    }
}

package com.killer560.hub.termism;

import com.killer560.hub.terminals.TerminalSolverConfig;
import com.killer560.hub.terminals.TerminalSolverFeature;
import com.killer560.hub.terminals.TerminalType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/** Generates and hosts a fake, entirely local terminal puzzle to practice on - killer560's explicit
 *  request (2026-09-09): "generate its own terminal for me to practice solve." No real Hypixel menu is
 *  ever involved (this is a plain {@link Screen}, not a container screen at all) - Panes/Rubix/Numbers/
 *  Starts With/Select each reuse the exact same real mechanics already ported from Odin for
 *  {@code TerminalSolverFeature}'s own solvers, just driving a self-generated puzzle instead of a real
 *  one, so practicing here matches the genuine terminal's real click behavior. Deliberately renders the
 *  RAW puzzle with no highlighting at all - unlike Custom GUI mode (which exists to skip reading the
 *  puzzle), the entire point here is practicing reading it yourself. */
public class TermismPracticeScreen extends Screen {

    private static final int CELL_SIZE = 18;
    private static final int PANEL_PADDING = 8;
    // Opaque now (was 0xEE, ~93%) - same round-10 fix as TerminalSolverFeature's own PANEL_BG_COLOR, per
    // killer560's report of real content bleeding through a translucent panel. Used only when Custom GUI
    // is ON - see #customGuiOn.
    private static final int PANEL_BG_COLOR = 0xFF241206;
    private static final int PANEL_BORDER_COLOR = 0xFFFFA500;
    // Per killer560's round-12 request: "make them use my solver if it is on and not use it if it is
    // off... if it is off it should be very similar if not the exact same to the default minecraft gui."
    // A plain neutral panel (no orange theme) used when Custom GUI is OFF instead - the closest this
    // standalone practice screen can get to vanilla's own inventory-screen look without a real container.
    private static final int VANILLA_BG_COLOR = 0xF0373737;
    private static final int VANILLA_BORDER_COLOR = 0xFFC6C6C6;
    // Real per-type grid dimensions (columns x rows), decompiled 2026-09-09 straight from Odin's own
    // TerminalTypes enum - each type builds its real Custom GUI panel via
    // simpleTermGui(rows, cols, startRow, startCol): PANES(3,5,..), RUBIX(3,3,..), NUMBERS(2,7,..),
    // STARTS_WITH(3,7,..), SELECT(4,7,..). Round 10.1's guessed 5-wide/4-wide grids were all wrong (round
    // 11 killer560 confirmed via screenshot: "rubix still isnt a 3x3", "numbers should be a 2x7") - these
    // replace that guesswork with the real, verified shape per type instead of one shared size.

    // Real Hypixel Rubix mechanic (same order TerminalSolverFeature's own solveRubix uses) - each pane
    // cycles forward one step per left-click, backward per right-click, no neighbor coupling.
    private static final List<DyeColor> RUBIX_COLOR_ORDER =
            List.of(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED);

    // Real Melody mechanic, ported from Odin's own MelodySim (decompiled 2026-09-09 as reference) - the
    // real terminal is 9 columns wide, 6 rows tall (0-5). Columns 1-5 are the track, column 7 is the real
    // clickable buttons (0, 6, 8 are always empty). Rows 1-4 are the "active band" - each round, the
    // WHOLE active row is red except one pane (the "moving" marker) that bounces left-right between
    // columns 1 and 5 on a timer; two fixed MAGENTA panes mark the current round's target column, one at
    // row 0 and one at row 5. Clicking the real button (column 7) in the active row only succeeds if the
    // moving marker's column currently matches the magenta target column - miss the timing and nothing
    // happens. A successful click advances to the next row (and picks a new random target column); after
    // row 4's click, the puzzle is done - matches Hypixel's real "Click the button on time!" 4-round
    // structure.
    private static final int MELODY_COLUMNS = 9;
    private static final int MELODY_BUTTON_COLUMN = 7;
    private static final long MELODY_MOVE_INTERVAL_MS = 500;

    private static final Map<Item, DyeColor> PANE_COLOR_LOOKUP = buildPaneColorLookup();

    private record NamedItem(String name, Item item) {
    }

    // How many random candidate items #generateStartsWith draws before keeping whichever one's letter
    // has the most pool matches - see that method's own round 37 doc.
    private static final int CANDIDATE_COUNT = 5;

    // Not Hypixel's exact real item pool (that's not decompiled) - a hand-picked, visually varied set of
    // real vanilla items covering plenty of different starting letters, enough to practice the actual
    // "read the name, click what matches" mechanic.
    private static final List<NamedItem> STARTS_WITH_POOL = List.of(
            new NamedItem("Stick", Items.STICK), new NamedItem("String", Items.STRING),
            new NamedItem("Saddle", Items.SADDLE), new NamedItem("Snowball", Items.SNOWBALL),
            new NamedItem("Sponge", Items.SPONGE), new NamedItem("Slimeball", Items.SLIME_BALL),
            new NamedItem("Feather", Items.FEATHER), new NamedItem("Flint", Items.FLINT),
            new NamedItem("Egg", Items.EGG), new NamedItem("Emerald", Items.EMERALD),
            new NamedItem("Torch", Items.TORCH), new NamedItem("Ladder", Items.LADDER),
            new NamedItem("Lead", Items.LEAD), new NamedItem("Bucket", Items.BUCKET),
            new NamedItem("Bread", Items.BREAD), new NamedItem("Bone", Items.BONE),
            new NamedItem("Coal", Items.COAL), new NamedItem("Compass", Items.COMPASS),
            new NamedItem("Clock", Items.CLOCK), new NamedItem("Cactus", Items.CACTUS),
            new NamedItem("Diamond", Items.DIAMOND), new NamedItem("Redstone", Items.REDSTONE),
            new NamedItem("Quartz", Items.QUARTZ), new NamedItem("Paper", Items.PAPER),
            new NamedItem("Book", Items.BOOK), new NamedItem("Map", Items.MAP),
            new NamedItem("Pumpkin", Items.PUMPKIN), new NamedItem("Melon", Items.MELON),
            new NamedItem("Kelp", Items.KELP), new NamedItem("Ice", Items.ICE),
            new NamedItem("Obsidian", Items.OBSIDIAN), new NamedItem("Apple", Items.APPLE),
            new NamedItem("Arrow", Items.ARROW), new NamedItem("Gunpowder", Items.GUNPOWDER),
            new NamedItem("Vine", Items.VINE), new NamedItem("Web", Items.COBWEB),
            // Round 35 (2026-09-10) additions - per killer560's "starts with is continually loading with
            // near 0 amounts of panes still" report, filled out the 10 letters that only had ONE item
            // each. Round 36 (2026-09-10) additions below - simulating the actual algorithm afterward
            // showed round 35 had accidentally made 11 of the 19 letters converge to exactly THREE items
            // each, so matchCount piled up at 3 (~59% of rolls) no matter how high the ceiling went -
            // matching killer560's fresh "did a ton of sims, all sub 5 clicks" report. These push most
            // letters up further (S/B/C toward 7-9, most others to 5-6) - simulated afterward: mean
            // matchCount rose from 3.4 to 6.3, with ~96% of rolls now landing at 5 or above instead of
            // ~90% landing at 4 or below. Combined with biasing WHICH letter gets picked toward richer
            // ones (see #generateStartsWith's own doc), not just raising the ceiling on its own again.
            new NamedItem("Fishing Rod", Items.FISHING_ROD), new NamedItem("Ender Pearl", Items.ENDER_PEARL),
            new NamedItem("Leather", Items.LEATHER), new NamedItem("Lava Bucket", Items.LAVA_BUCKET),
            new NamedItem("Pufferfish", Items.PUFFERFISH), new NamedItem("Milk Bucket", Items.MILK_BUCKET),
            new NamedItem("Anvil", Items.ANVIL),
            new NamedItem("TNT", Items.TNT), new NamedItem("Trident", Items.TRIDENT),
            new NamedItem("Diamond Sword", Items.DIAMOND_SWORD), new NamedItem("Diamond Pickaxe", Items.DIAMOND_PICKAXE),
            new NamedItem("Rail", Items.RAIL), new NamedItem("Rabbit", Items.RABBIT),
            new NamedItem("Iron Ingot", Items.IRON_INGOT), new NamedItem("Ink Sac", Items.INK_SAC),
            new NamedItem("Orange Dye", Items.ORANGE_DYE),
            new NamedItem("Golden Apple", Items.GOLDEN_APPLE), new NamedItem("Glowstone Dust", Items.GLOWSTONE_DUST),
            new NamedItem("Wheat", Items.WHEAT), new NamedItem("Water Bucket", Items.WATER_BUCKET),
            new NamedItem("Shears", Items.SHEARS), new NamedItem("Sugar", Items.SUGAR), new NamedItem("Shield", Items.SHIELD),
            new NamedItem("Furnace", Items.FURNACE), new NamedItem("Flower Pot", Items.FLOWER_POT),
            new NamedItem("Elytra", Items.ELYTRA), new NamedItem("Enchanted Book", Items.ENCHANTED_BOOK),
            new NamedItem("Turtle Egg", Items.TURTLE_EGG), new NamedItem("Tripwire Hook", Items.TRIPWIRE_HOOK),
            new NamedItem("Lantern", Items.LANTERN), new NamedItem("Lily Pad", Items.LILY_PAD),
            new NamedItem("Bow", Items.BOW), new NamedItem("Brick", Items.BRICK), new NamedItem("Bell", Items.BELL),
            new NamedItem("Carrot", Items.CARROT), new NamedItem("Chest", Items.CHEST), new NamedItem("Charcoal", Items.CHARCOAL),
            new NamedItem("Diamond Axe", Items.DIAMOND_AXE), new NamedItem("Diamond Helmet", Items.DIAMOND_HELMET),
            new NamedItem("Diamond Block", Items.DIAMOND_BLOCK),
            new NamedItem("Rotten Flesh", Items.ROTTEN_FLESH), new NamedItem("Rabbit's Foot", Items.RABBIT_FOOT),
            new NamedItem("Prismarine Shard", Items.PRISMARINE_SHARD), new NamedItem("Popped Chorus Fruit", Items.POPPED_CHORUS_FRUIT),
            new NamedItem("Minecart", Items.MINECART), new NamedItem("Mushroom Stew", Items.MUSHROOM_STEW),
            new NamedItem("Knowledge Book", Items.KNOWLEDGE_BOOK),
            new NamedItem("Iron Sword", Items.IRON_SWORD), new NamedItem("Iron Axe", Items.IRON_AXE),
            new NamedItem("Oak Boat", Items.OAK_BOAT), new NamedItem("Oak Sign", Items.OAK_SIGN),
            new NamedItem("Amethyst Shard", Items.AMETHYST_SHARD), new NamedItem("Armor Stand", Items.ARMOR_STAND),
            new NamedItem("Gold Ingot", Items.GOLD_INGOT), new NamedItem("Glass Bottle", Items.GLASS_BOTTLE),
            new NamedItem("Wooden Sword", Items.WOODEN_SWORD), new NamedItem("Writable Book", Items.WRITABLE_BOOK),
            // Round 37 (2026-09-10) - per killer560's "it is normally a little bit higher but i would
            // still like the average to be about 50% higher" follow-up. Simulated round 36's actual
            // algorithm first (mean matchCount 6.3) before touching anything: scaling most letters up
            // ~1.3x combined with widening #generateStartsWith's own candidate-letter bias from 2 to 5
            // candidates landed almost exactly on target (simulated mean 9.3, +48%). These ~22 items are
            // that 1.3x scale-up, one or two per letter (Q/K/V untouched - genuinely no more safe, real
            // vanilla items starting with those letters worth adding).
            new NamedItem("Spider Eye", Items.SPIDER_EYE), new NamedItem("Salmon", Items.SALMON), new NamedItem("Sugar Cane", Items.SUGAR_CANE),
            new NamedItem("Firework Rocket", Items.FIREWORK_ROCKET),
            new NamedItem("Experience Bottle", Items.EXPERIENCE_BOTTLE),
            new NamedItem("Totem of Undying", Items.TOTEM_OF_UNDYING),
            new NamedItem("Lapis Lazuli", Items.LAPIS_LAZULI), new NamedItem("Lightning Rod", Items.LIGHTNING_ROD),
            new NamedItem("Beetroot", Items.BEETROOT), new NamedItem("Blaze Rod", Items.BLAZE_ROD),
            new NamedItem("Clay Ball", Items.CLAY_BALL), new NamedItem("Cod", Items.COD),
            new NamedItem("Dried Kelp", Items.DRIED_KELP), new NamedItem("Diamond Boots", Items.DIAMOND_BOOTS),
            new NamedItem("Raw Iron", Items.RAW_IRON),
            new NamedItem("Potato", Items.POTATO),
            new NamedItem("Magma Cream", Items.MAGMA_CREAM),
            new NamedItem("Iron Helmet", Items.IRON_HELMET),
            new NamedItem("Oak Door", Items.OAK_DOOR),
            new NamedItem("Ancient Debris", Items.ANCIENT_DEBRIS),
            new NamedItem("Ghast Tear", Items.GHAST_TEAR),
            new NamedItem("Wooden Axe", Items.WOODEN_AXE)
    );

    private record ColorAlias(DyeColor color, String name, Item texture) {
    }

    // Same real color-alias idea TerminalSolverFeature's own SELECT_PREFIXES map is built from (Hypixel's
    // real item names for a color don't all literally start with the dye color's own name) - a smaller,
    // one-name-per-color set here since practice only needs one clean example per color, not every alias.
    private static final List<ColorAlias> SELECT_POOL = List.of(
            new ColorAlias(DyeColor.RED, "Rose", Items.RED_WOOL),
            new ColorAlias(DyeColor.GREEN, "Cactus", Items.GREEN_WOOL),
            new ColorAlias(DyeColor.BLUE, "Lapis", Items.BLUE_WOOL),
            new ColorAlias(DyeColor.BROWN, "Cocoa", Items.BROWN_WOOL),
            new ColorAlias(DyeColor.WHITE, "Bone", Items.WHITE_WOOL),
            new ColorAlias(DyeColor.BLACK, "Ink", Items.BLACK_WOOL),
            new ColorAlias(DyeColor.YELLOW, "Dandelion", Items.YELLOW_WOOL),
            new ColorAlias(DyeColor.LIGHT_GRAY, "Silver", Items.LIGHT_GRAY_WOOL)
    );

    private final Screen parent;
    // Not final - per killer560's "for new puzzle make it completely random from all puzzles besides
    // melody" request (2026-09-09), the New Puzzle button now rerolls the type itself, not just the board.
    private TerminalType type;
    private final Random random = new Random();
    private long startedAtMs;
    private long solvedAtMs = -1;
    private boolean solved;

    private List<ItemStack> cells = new ArrayList<>();
    private int columns;
    private int rows;
    private int remainingMatches;
    private String targetLetter;
    private DyeColor targetColor;

    private int melodyMagentaColumn;
    private int melodyLimeColumn;
    private int melodyLimeDirection;
    private int melodyCurrentRow;
    private long melodyLastMoveAtMs;

    // Auto Terminals in Termism (2026-09-09) - per killer560's explicit "also make it work in termism"
    // request, cheat build only (see TerminalSolverConfig#isAutoTerminalsEnabled). Only ever active
    // while Custom GUI is also on (see #customGuiOn) - Custom GUI OFF is deliberately "practice reading
    // it yourself" mode (see this class's own top doc), so auto-clicking there would defeat the whole
    // point. Reuses TerminalSolverFeature's own #pickAutoClickTarget decision logic against THIS
    // screen's own locally-computed highlights (same #solve call #renderSolverOverlay already makes),
    // exactly the way this screen already reuses #solve itself for that overlay.
    private long nextAutoClickAllowedAtMs;
    private int lastAutoClickedSlot = -1;
    private int lastMelodyAutoClickedRow = -1;
    private long lastMelodyAutoClickAtMs;

    private int gridOriginX;
    private int gridOriginY;

    public TermismPracticeScreen(Screen parent, TerminalType type) {
        super(Component.literal("Termism: " + type.displayName()));
        this.parent = parent;
        this.type = type;
    }

    @Override
    protected void init() {
        generatePuzzle();
        this.addRenderableWidget(Button.builder(Component.literal("New Puzzle"), btn -> {
                    type = TermismMenuScreen.PRACTICE_TYPES.get(random.nextInt(TermismMenuScreen.PRACTICE_TYPES.size()));
                    generatePuzzle();
                })
                .bounds(this.width / 2 - 105, this.height - 30, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 + 5, this.height - 30, 100, 20).build());
    }

    private void generatePuzzle() {
        solved = false;
        solvedAtMs = -1;
        startedAtMs = System.currentTimeMillis();
        cells = new ArrayList<>();
        // The real terminal's own committed-Rubix-target state (round 14) is shared static state, not
        // scoped to any one board - clear it so a fresh practice puzzle never inherits a stale target
        // left over from a real terminal or a previous practice round.
        TerminalSolverFeature.resetRubixTarget();
        // Same reasoning for Auto Terminals' own re-click guards (2026-09-09) - a fresh puzzle must not
        // inherit "already clicked this slot" state from whatever board was showing before it.
        nextAutoClickAllowedAtMs = 0;
        lastAutoClickedSlot = -1;
        lastMelodyAutoClickedRow = -1;
        lastMelodyAutoClickAtMs = 0;
        switch (type) {
            case PANES -> generatePanes();
            case RUBIX -> generateRubix();
            case NUMBERS -> generateNumbers();
            case STARTS_WITH -> generateStartsWith();
            case SELECT -> generateSelect();
            case MELODY -> generateMelody();
        }
        updateLayoutMetrics();
    }

    private void generateMelody() {
        columns = MELODY_COLUMNS;
        melodyCurrentRow = 1;
        melodyMagentaColumn = 1 + random.nextInt(5);
        melodyLimeColumn = 1;
        melodyLimeDirection = 1;
        melodyLastMoveAtMs = System.currentTimeMillis();
        rebuildMelodyCells();
    }

    private void rebuildMelodyCells() {
        List<ItemStack> newCells = new ArrayList<>();
        int total = MELODY_COLUMNS * 6;
        for (int i = 0; i < total; i++) {
            newCells.add(melodyItemFor(i % MELODY_COLUMNS, i / MELODY_COLUMNS));
        }
        cells = newCells;
    }

    private ItemStack melodyItemFor(int col, int row) {
        boolean inBand = row >= 1 && row < 5;
        if (col == melodyMagentaColumn && !inBand) {
            return new ItemStack(Items.MAGENTA_STAINED_GLASS_PANE);
        }
        if (col == melodyLimeColumn && row == melodyCurrentRow) {
            return new ItemStack(Items.LIME_STAINED_GLASS_PANE);
        }
        if (col >= 1 && col < 6 && row == melodyCurrentRow) {
            return new ItemStack(Items.RED_STAINED_GLASS_PANE);
        }
        if (col == MELODY_BUTTON_COLUMN && row == melodyCurrentRow) {
            return new ItemStack(Items.LIME_TERRACOTTA);
        }
        if (col == MELODY_BUTTON_COLUMN && inBand) {
            return new ItemStack(Items.RED_TERRACOTTA);
        }
        if (col >= 1 && col < 6 && inBand) {
            return new ItemStack(Items.WHITE_STAINED_GLASS_PANE);
        }
        return ItemStack.EMPTY;
    }

    /** Advances the moving (lime) marker on a fixed real-time interval, matching Odin's own "every 10
     *  ticks" (~500ms) cadence - called every render frame (see #extractRenderState) but only actually
     *  moves/rebuilds once the interval has actually elapsed. */
    private void updateMelodyAnimation() {
        if (solved) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - melodyLastMoveAtMs < MELODY_MOVE_INTERVAL_MS) {
            return;
        }
        melodyLastMoveAtMs = now;
        melodyLimeColumn += melodyLimeDirection;
        if (melodyLimeColumn == 1 || melodyLimeColumn == 5) {
            melodyLimeDirection *= -1;
        }
        rebuildMelodyCells();
    }

    /** See the doc comment on {@link #nextAutoClickAllowedAtMs} - only ever runs with Custom GUI on. */
    private void tickAutoClick() {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        if (!cfg.isAutoTerminalsEnabled() || !customGuiOn() || solved) {
            return;
        }
        if (type == TerminalType.MELODY) {
            if (cfg.isAutoMelodyEnabled()) {
                tickMelodyAutoClick();
            }
            return;
        }
        if (!TerminalSolverFeature.isAutoTypeEnabled(type, cfg)) {
            return;
        }
        Map<Integer, TerminalSolverFeature.SlotHighlight> highlights =
                TerminalSolverFeature.solve(type, syntheticTitle(), cells);
        if (highlights.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAutoClickAllowedAtMs) {
            return;
        }
        TerminalSolverFeature.AutoClickTarget target = TerminalSolverFeature.pickAutoClickTarget(type, highlights);
        if (target == null) {
            return;
        }
        if (target.slot() == lastAutoClickedSlot && type != TerminalType.RUBIX) {
            return;
        }
        nextAutoClickAllowedAtMs = now + cfg.rollAutoClickDelayMs();
        lastAutoClickedSlot = target.slot();
        handleCellClick(target.slot(), target.button() == 0);
    }

    /** Termism's own Melody simulation already tracks the exact ground-truth state
     *  ({@link #melodyLimeColumn}/{@link #melodyMagentaColumn}/{@link #melodyCurrentRow}) directly - no
     *  need to re-derive it by scanning cell items the way {@code TerminalSolverFeature}'s real-terminal
     *  version has to (this screen isn't a real container, just a plain local simulation). */
    private void tickMelodyAutoClick() {
        if (melodyLimeColumn != melodyMagentaColumn) {
            return;
        }
        long now = System.currentTimeMillis();
        if (melodyCurrentRow == lastMelodyAutoClickedRow && now - lastMelodyAutoClickAtMs < 250) {
            return;
        }
        lastMelodyAutoClickedRow = melodyCurrentRow;
        lastMelodyAutoClickAtMs = now;
        handleCellClick(melodyCurrentRow * columns + MELODY_BUTTON_COLUMN, true);
    }

    private void generatePanes() {
        columns = 5;
        int gridSize = columns * 3;
        // Per killer560's "by default tend to favor higher amounts of panes shown" request (2026-09-10),
        // then "raise the max higher than 7 - it should max out at the max amount of clicks that sim can
        // handle" follow-up: the ceiling is now the full grid itself (gridSize, 15) - solvePanes just
        // highlights every RED pane regardless of how many that is, and randomIndices already caps at
        // the grid's own size, so a board that's entirely wrong panes is a completely valid puzzle, not
        // an edge case to guard against. Picking the max of two rolls (instead of one) keeps the same
        // bias toward the higher end of whatever the range is, not just a raised ceiling.
        int minActive = 4;
        int activeCount = minActive + Math.max(random.nextInt(gridSize - minActive + 1), random.nextInt(gridSize - minActive + 1));
        List<Integer> active = randomIndices(gridSize, activeCount);
        for (int i = 0; i < gridSize; i++) {
            cells.add(new ItemStack(active.contains(i) ? Items.RED_STAINED_GLASS_PANE : Items.LIME_STAINED_GLASS_PANE));
        }
    }

    // Real Rubix is a dense 3x3 - every cell is a colored pane, none empty (killer560's "rubix still
    // isnt a 3x3" report, 2026-09-09, round 11 - confirmed against Odin's own simpleTermGui(3, 3, ..)).
    private void generateRubix() {
        columns = 3;
        int gridSize = columns * 3;
        for (int i = 0; i < gridSize; i++) {
            DyeColor color = RUBIX_COLOR_ORDER.get(random.nextInt(RUBIX_COLOR_ORDER.size()));
            cells.add(new ItemStack(paneItemFor(color)));
        }
    }

    // Real Numbers is a dense 2x7 - all 14 cells filled, numbered 1-14 (killer560's "numbers should be a
    // 2x7" report, round 11, and "numbers is not generating with all the numbers 1-14" follow-up, round
    // 12 - confirmed against Odin's own simpleTermGui(2, 7, ..); was still only randomly filling 4-7 of
    // the 14 cells, same sparse pattern Panes/Rubix legitimately use but Numbers apparently doesn't).
    private void generateNumbers() {
        columns = 7;
        int gridSize = columns * 2;
        List<Integer> order = new ArrayList<>();
        for (int i = 1; i <= gridSize; i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);
        int cursor = 0;
        for (int i = 0; i < gridSize; i++) {
            cells.add(new ItemStack(Items.RED_STAINED_GLASS_PANE, order.get(cursor++)));
        }
    }

    // Real Starts With is a 3x7 (Odin's own simpleTermGui(3, 7, ..)).
    private void generateStartsWith() {
        columns = 7;
        List<NamedItem> pool = new ArrayList<>(STARTS_WITH_POOL);
        Collections.shuffle(pool, random);
        // Round 36 (2026-09-10) - per killer560's "did a ton of sims, all sub 5 clicks" report: picking
        // the seed uniformly by ITEM (not letter) means a letter's own selection odds are already
        // proportional to how many items it has - but with several letters similarly populated (see
        // STARTS_WITH_POOL's own round 36 doc), that alone wasn't tilting things toward the richer ones
        // enough. Same max-of-N trick used for matchCount's own roll, applied here too: draw several
        // candidate items and keep whichever one's letter has the most matches in the pool, so a letter
        // that can actually support a high matchCount gets picked more than its raw item-share would
        // imply. Round 37 (2026-09-10) widened this from 2 candidates to CANDIDATE_COUNT (5) per
        // killer560's "i would still like the average to be about 50% higher" follow-up - simulated
        // together with STARTS_WITH_POOL's own round 37 growth to land on that target (verified mean
        // matchCount 9.3, up from round 36's 6.3).
        char letter = pool.subList(0, CANDIDATE_COUNT).stream()
                .map(item -> Character.toUpperCase(item.name().charAt(0)))
                .max(Comparator.comparingLong(candidateLetter ->
                        STARTS_WITH_POOL.stream().filter(i -> Character.toUpperCase(i.name().charAt(0)) == candidateLetter).count()))
                .orElseThrow();
        targetLetter = String.valueOf(letter);

        List<NamedItem> matches = new ArrayList<>();
        List<NamedItem> distractors = new ArrayList<>();
        for (NamedItem candidate : STARTS_WITH_POOL) {
            if (Character.toUpperCase(candidate.name().charAt(0)) == letter) {
                matches.add(candidate);
            } else {
                distractors.add(candidate);
            }
        }
        Collections.shuffle(matches, random);
        Collections.shuffle(distractors, random);

        int startsWithGridSize = columns * 3;
        // Per killer560's "make sure panes startswith and select all have the max available as an
        // option" request (2026-09-10) - same treatment Panes got in round 33: the ceiling is the whole
        // grid (matchCount could fill every cell if the pool has enough same-letter items, distractors
        // simply fill whatever's left), not an arbitrary small cap. Math.min against matches.size() still
        // protects against asking for more real matches than the pool actually has for this letter.
        // Max of two rolls keeps the "tends toward the higher end" bias from round 32/33.
        int matchCount = Math.min(2 + Math.max(random.nextInt(startsWithGridSize - 1), random.nextInt(startsWithGridSize - 1)), matches.size());
        List<NamedItem> chosenMatches = new ArrayList<>(matches.subList(0, matchCount));
        int distractorCount = Math.min(startsWithGridSize - matchCount, distractors.size());
        List<NamedItem> chosenDistractors = new ArrayList<>(distractors.subList(0, distractorCount));

        List<NamedItem> combined = new ArrayList<>();
        combined.addAll(chosenMatches);
        combined.addAll(chosenDistractors);
        Collections.shuffle(combined, random);

        for (NamedItem entry : combined) {
            cells.add(new ItemStack(entry.item()));
        }
        while (cells.size() < startsWithGridSize) {
            cells.add(ItemStack.EMPTY);
        }
        remainingMatches = chosenMatches.size();
    }

    // Real Select is a 4x7 (Odin's own simpleTermGui(4, 7, ..)).
    private void generateSelect() {
        columns = 7;
        int gridSize = columns * 4;
        ColorAlias target = SELECT_POOL.get(random.nextInt(SELECT_POOL.size()));
        targetColor = target.color();

        List<ColorAlias> distractorPool = new ArrayList<>(SELECT_POOL);
        distractorPool.remove(target);
        Collections.shuffle(distractorPool, random);

        // Per killer560's "make sure panes startswith and select all have the max available as an
        // option" request (2026-09-10) - same treatment as Panes (round 33) and Starts With above: the
        // ceiling is the whole grid, since matchCount just repeats the target color and distractors fill
        // whatever's left (a board that's entirely the target color is a valid puzzle). Explicit
        // Math.min against gridSize keeps distractorCount below from ever going negative. Max of two
        // rolls keeps the "tends toward the higher end" bias from round 32/33.
        int matchCount = Math.min(2 + Math.max(random.nextInt(gridSize - 1), random.nextInt(gridSize - 1)), gridSize);
        List<ItemStack> combined = new ArrayList<>();
        for (int i = 0; i < matchCount; i++) {
            combined.add(namedStack(target.texture(), target.name()));
        }
        int distractorCount = gridSize - matchCount;
        for (int i = 0; i < distractorCount && !distractorPool.isEmpty(); i++) {
            ColorAlias d = distractorPool.get(i % distractorPool.size());
            combined.add(namedStack(d.texture(), d.name()));
        }
        Collections.shuffle(combined, random);
        cells.addAll(combined);
        while (cells.size() < gridSize) {
            cells.add(ItemStack.EMPTY);
        }
        remainingMatches = matchCount;
    }

    private void handleCellClick(int index, boolean leftClick) {
        if (index < 0 || index >= cells.size()) {
            return;
        }
        ItemStack stack = cells.get(index);
        if (stack.isEmpty()) {
            return;
        }
        switch (type) {
            case PANES -> {
                if (stack.getItem() == Items.RED_STAINED_GLASS_PANE) {
                    cells.set(index, new ItemStack(Items.LIME_STAINED_GLASS_PANE));
                    if (cells.stream().noneMatch(s -> s.getItem() == Items.RED_STAINED_GLASS_PANE)) {
                        markSolved();
                    }
                }
            }
            case RUBIX -> {
                DyeColor current = PANE_COLOR_LOOKUP.get(stack.getItem());
                if (current != null) {
                    int idx = RUBIX_COLOR_ORDER.indexOf(current);
                    int nextIdx = Math.floorMod(idx + (leftClick ? 1 : -1), RUBIX_COLOR_ORDER.size());
                    cells.set(index, new ItemStack(paneItemFor(RUBIX_COLOR_ORDER.get(nextIdx))));
                    List<DyeColor> colors = cells.stream().map(s -> PANE_COLOR_LOOKUP.get(s.getItem()))
                            .filter(Objects::nonNull).distinct().toList();
                    if (colors.size() == 1) {
                        markSolved();
                    }
                }
            }
            case NUMBERS -> {
                if (stack.getItem() == Items.RED_STAINED_GLASS_PANE) {
                    int minCount = cells.stream()
                            .filter(s -> s.getItem() == Items.RED_STAINED_GLASS_PANE)
                            .mapToInt(ItemStack::getCount).min().orElse(Integer.MAX_VALUE);
                    if (stack.getCount() == minCount) {
                        cells.set(index, new ItemStack(Items.LIME_STAINED_GLASS_PANE));
                        if (cells.stream().noneMatch(s -> s.getItem() == Items.RED_STAINED_GLASS_PANE)) {
                            markSolved();
                        }
                    }
                }
            }
            case STARTS_WITH -> {
                String name = stack.getHoverName().getString();
                if (!name.isEmpty() && String.valueOf(Character.toUpperCase(name.charAt(0))).equals(targetLetter)) {
                    cells.set(index, ItemStack.EMPTY);
                    remainingMatches--;
                    if (remainingMatches <= 0) {
                        markSolved();
                    }
                }
            }
            case SELECT -> {
                ColorAlias target = selectAliasFor(targetColor);
                if (target != null && stack.getHoverName().getString().equalsIgnoreCase(target.name())) {
                    cells.set(index, ItemStack.EMPTY);
                    remainingMatches--;
                    if (remainingMatches <= 0) {
                        markSolved();
                    }
                }
            }
            case MELODY -> {
                int col = index % columns;
                int row = index / columns;
                // Real Odin mechanic: only the real button (column 7) in the CURRENT active row counts,
                // and only if the moving marker's column currently matches the fixed target column - a
                // mistimed click (or clicking the wrong row's button) is simply a no-op, same as the real
                // terminal.
                if (col == MELODY_BUTTON_COLUMN && row == melodyCurrentRow && melodyLimeColumn == melodyMagentaColumn) {
                    melodyMagentaColumn = 1 + random.nextInt(5);
                    melodyCurrentRow++;
                    if (melodyCurrentRow >= 5) {
                        markSolved();
                    } else {
                        rebuildMelodyCells();
                    }
                }
            }
        }
    }

    private void markSolved() {
        solved = true;
        solvedAtMs = System.currentTimeMillis();
    }

    // Per killer560's round-12 request: Custom GUI OFF -> practice looks close to plain vanilla (fixed
    // 1x scale, neutral panel); Custom GUI ON -> "fully replace it with my solver's scaling and hud and
    // whatnot" (the real Highlight Scale setting and the orange theme, same as round 11).
    private static boolean customGuiOn() {
        return TerminalSolverConfig.getInstance().isCustomGuiEnabled();
    }

    private static float scale() {
        return customGuiOn() ? TerminalSolverConfig.getInstance().getScale() : 1.0f;
    }

    private void updateLayoutMetrics() {
        rows = Math.max(1, (int) Math.ceil(cells.size() / (double) columns));
        float scale = scale();
        int panelWidth = Math.round(columns * CELL_SIZE * scale);
        int panelHeight = Math.round(rows * CELL_SIZE * scale);
        gridOriginX = (this.width - panelWidth) / 2;
        gridOriginY = (this.height - panelHeight) / 2 - 8;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (type == TerminalType.MELODY) {
            updateMelodyAnimation();
        }
        tickAutoClick();
        graphics.fill(0, 0, this.width, this.height, 0xCC000000);

        boolean customGui = customGuiOn();
        int bgColor = customGui ? PANEL_BG_COLOR : VANILLA_BG_COLOR;
        int borderColor = customGui ? PANEL_BORDER_COLOR : VANILLA_BORDER_COLOR;
        float scale = scale();
        int panelWidth = Math.round(columns * CELL_SIZE * scale);
        int panelHeight = Math.round(rows * CELL_SIZE * scale);
        graphics.fill(gridOriginX - PANEL_PADDING, gridOriginY - PANEL_PADDING,
                gridOriginX + panelWidth + PANEL_PADDING, gridOriginY + panelHeight + PANEL_PADDING, bgColor);
        graphics.outline(gridOriginX - PANEL_PADDING, gridOriginY - PANEL_PADDING,
                panelWidth + PANEL_PADDING * 2, panelHeight + PANEL_PADDING * 2, borderColor);

        graphics.pose().pushMatrix();
        graphics.pose().translate(gridOriginX, gridOriginY);
        graphics.pose().scale(scale, scale);
        if (customGui) {
            if (type == TerminalType.MELODY) {
                renderMelodyOverlay(graphics);
            } else {
                renderSolverOverlay(graphics);
            }
        } else {
            renderRawPuzzle(graphics);
        }
        graphics.pose().popMatrix();

        graphics.centeredText(this.font, headerText(), this.width / 2, gridOriginY - PANEL_PADDING - 22, 0xFFFFFFFF);

        if (solved) {
            double seconds = (solvedAtMs - startedAtMs) / 1000.0;
            graphics.centeredText(this.font, String.format("§aSolved in %.1fs! Click New Puzzle to try again.", seconds),
                    this.width / 2, gridOriginY + panelHeight + PANEL_PADDING + 10, 0xFF55FF55);
        } else {
            graphics.centeredText(this.font, hintText(),
                    this.width / 2, gridOriginY + panelHeight + PANEL_PADDING + 10, 0xFFAAAAAA);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    /** Custom GUI OFF: the raw puzzle, real item icons - the original "practice reading it yourself"
     *  mode. */
    private void renderRawPuzzle(GuiGraphicsExtractor graphics) {
        for (int i = 0; i < cells.size(); i++) {
            ItemStack stack = cells.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int col = i % columns;
            int row = i / columns;
            int x0 = col * CELL_SIZE;
            int y0 = row * CELL_SIZE;
            graphics.item(stack, x0, y0);
            // Numbers' real mechanic is the stack COUNT (its sequence position) - #item() alone never
            // draws that, only the raw icon, so every pane looked visually identical and unreadable
            // (killer560's "numbers... broken" report, 2026-09-09). Forces the label to always show via
            // the explicit-string overload, since vanilla's own default count overlay skips count == 1.
            // Only for still-red panes - a solved (lime) pane defaults to stack count 1, which showed a
            // stray "1" on every already-solved cell (killer560's round-13 "after clicking on the right
            // pane the number changes to 1, dont do that" report).
            if (type == TerminalType.NUMBERS && stack.getItem() == Items.RED_STAINED_GLASS_PANE) {
                graphics.itemDecorations(this.font, stack, x0, y0, String.valueOf(stack.getCount()));
            }
        }
    }

    /** Melody's own Custom-GUI-ON overlay - it has no "correct slot" solve() result to reuse (see
     *  {@link TerminalSolverFeature#solve}'s own MELODY case), so this reuses the exact same 2 role
     *  colors {@code TerminalSolverFeature}'s own real Melody panel uses instead: the magenta
     *  endpoints/lime mover/real buttons all read as the bright endpoint color, everything else
     *  (the red active-row cells and the white static band) reads as the dimmer track-base color. */
    private void renderMelodyOverlay(GuiGraphicsExtractor graphics) {
        for (int i = 0; i < cells.size(); i++) {
            ItemStack stack = cells.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            int col = i % columns;
            int row = i / columns;
            int x0 = col * CELL_SIZE;
            int y0 = row * CELL_SIZE;
            graphics.fill(x0, y0, x0 + 16, y0 + 16, melodyOverlayColorFor(stack));
        }
    }

    private static int melodyOverlayColorFor(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.MAGENTA_STAINED_GLASS_PANE || item == Items.LIME_STAINED_GLASS_PANE
                || item == Items.LIME_TERRACOTTA || item == Items.RED_TERRACOTTA) {
            return TerminalSolverFeature.MELODY_ENDPOINT_COLOR;
        }
        return TerminalSolverFeature.MELODY_TRACK_BASE_COLOR;
    }

    /** Custom GUI ON: per killer560's "my solver overlay still isnt happening on it which I want...
     *  fully replace it with my solvers scaling and hud and whatnot" request (2026-09-09, round 13) -
     *  runs this practice board through the exact same real solving logic
     *  ({@link TerminalSolverFeature#solve}) the actual terminal uses, then draws flat colored boxes
     *  (only for slots that logic actually flags, everything else invisible) exactly like the real
     *  Custom GUI's own panel does - no item icons at all in this mode, matching the real thing. */
    private void renderSolverOverlay(GuiGraphicsExtractor graphics) {
        Map<Integer, TerminalSolverFeature.SlotHighlight> highlights =
                TerminalSolverFeature.solve(type, syntheticTitle(), cells);
        for (Map.Entry<Integer, TerminalSolverFeature.SlotHighlight> entry : highlights.entrySet()) {
            int index = entry.getKey();
            if (index < 0 || index >= cells.size()) {
                continue;
            }
            TerminalSolverFeature.SlotHighlight highlight = entry.getValue();
            int col = index % columns;
            int row = index / columns;
            int x0 = col * CELL_SIZE;
            int y0 = row * CELL_SIZE;
            graphics.fill(x0, y0, x0 + 16, y0 + 16, highlight.color());
            if (type == TerminalType.RUBIX && highlight.label() != null) {
                // No shadow, matching TerminalSolverFeature's own round-14 fix - #centeredText has no
                // shadow-off overload, so this centers by hand via the plain #text overload instead.
                // Round 15's small centering nudge, same as that class's own fix.
                int textY = y0 + (16 - this.font.lineHeight) / 2 + 1;
                int textX = x0 + 8 - Math.round(this.font.width(highlight.label()) / 2f) + 1;
                graphics.text(this.font, highlight.label(), textX, textY, 0xFF000000, false);
            }
        }
    }

    /** A fake title matching the real terminal's own {@link TerminalType#titlePattern()}, since Termism
     *  has no real Hypixel container title to read the target letter/color from - only Starts With and
     *  Select actually need this (see {@link TerminalSolverFeature#solve}'s own doc). */
    private String syntheticTitle() {
        return switch (type) {
            case STARTS_WITH -> "What starts with: '" + targetLetter + "'?";
            // Hypixel's real title says "SILVER" for LIGHT_GRAY (same special case
            // TerminalSolverFeature#parseSelectColor itself handles) - every other color's enum name is
            // already a single word matching the real title text.
            case SELECT -> "Select all the " + (targetColor == DyeColor.LIGHT_GRAY ? "SILVER" : targetColor.name()) + " items!";
            default -> "";
        };
    }

    private String headerText() {
        return switch (type) {
            case STARTS_WITH -> type.displayName() + " Practice - starts with '" + targetLetter + "'";
            case SELECT -> type.displayName() + " Practice - select all " + targetColor.getName().replace('_', ' ');
            default -> type.displayName() + " Practice";
        };
    }

    private String hintText() {
        return switch (type) {
            case RUBIX -> "Left-click to cycle forward, right-click to cycle backward.";
            case MELODY -> "Click the button when the moving pane lines up with the magenta markers!";
            default -> "Click the correct slot(s).";
        };
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!solved && (event.button() == 0 || event.button() == 1)) {
            double localX = (event.x() - gridOriginX) / (double) scale();
            double localY = (event.y() - gridOriginY) / (double) scale();
            int col = (int) Math.floor(localX / CELL_SIZE);
            int row = (int) Math.floor(localY / CELL_SIZE);
            if (col >= 0 && col < columns && row >= 0 && row < rows) {
                int index = row * columns + col;
                if (index < cells.size() && !cells.get(index).isEmpty()) {
                    handleCellClick(index, event.button() == 0);
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private List<Integer> randomIndices(int total, int count) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            all.add(i);
        }
        Collections.shuffle(all, random);
        return new ArrayList<>(all.subList(0, Math.min(count, total)));
    }

    private static ItemStack namedStack(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    private static ColorAlias selectAliasFor(DyeColor color) {
        for (ColorAlias alias : SELECT_POOL) {
            if (alias.color() == color) {
                return alias;
            }
        }
        return null;
    }

    private static Item paneItemFor(DyeColor color) {
        return switch (color) {
            case ORANGE -> Items.ORANGE_STAINED_GLASS_PANE;
            case YELLOW -> Items.YELLOW_STAINED_GLASS_PANE;
            case GREEN -> Items.GREEN_STAINED_GLASS_PANE;
            case BLUE -> Items.BLUE_STAINED_GLASS_PANE;
            case RED -> Items.RED_STAINED_GLASS_PANE;
            default -> Items.GRAY_STAINED_GLASS_PANE;
        };
    }

    private static Map<Item, DyeColor> buildPaneColorLookup() {
        Map<Item, DyeColor> map = new HashMap<>();
        for (DyeColor color : RUBIX_COLOR_ORDER) {
            map.put(paneItemFor(color), color);
        }
        return map;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

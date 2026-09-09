package com.killer560.hub.termism;

import com.killer560.hub.terminals.TerminalSolverConfig;
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

    private static final Map<Item, DyeColor> PANE_COLOR_LOOKUP = buildPaneColorLookup();

    private record NamedItem(String name, Item item) {
    }

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
            new NamedItem("Vine", Items.VINE), new NamedItem("Web", Items.COBWEB)
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
        switch (type) {
            case PANES -> generatePanes();
            case RUBIX -> generateRubix();
            case NUMBERS -> generateNumbers();
            case STARTS_WITH -> generateStartsWith();
            case SELECT -> generateSelect();
            case MELODY -> throw new IllegalStateException("Melody has no practice mode - see TermismMenuScreen");
        }
        updateLayoutMetrics();
    }

    private void generatePanes() {
        columns = 5;
        int gridSize = columns * 3;
        int activeCount = 4 + random.nextInt(4);
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
        NamedItem seed = pool.get(0);
        char letter = Character.toUpperCase(seed.name().charAt(0));
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
        int matchCount = Math.min(2 + random.nextInt(3), matches.size());
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

        int matchCount = 2 + random.nextInt(3);
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
            if (type == TerminalType.NUMBERS) {
                graphics.itemDecorations(this.font, stack, x0, y0, String.valueOf(stack.getCount()));
            }
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

package com.killer560.hub.terminals;

import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.storageoverlay.mixin.SlotClickInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StainedGlassPaneBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;

/** Highlights the correct slot(s) to click in Floor 7 dungeon terminals - Solver Only, per killer560's
 *  explicit "Not auto terminals yet just the solver": this never clicks anything, only draws colored
 *  outlines (plus a number/click-count label where relevant) over the slots the player should click
 *  themselves. Modeled on Odin's own Terminal Solver (decompiled for reference, 2026-09-08) - the exact
 *  title regexes, item-matching rules, and the Rubix color-cycle math below are all ported from its real
 *  handler classes, not guessed. Ships disabled by default - see {@link TerminalSolverConfig}. */
public final class TerminalSolverFeature {

    private static final int SLOT_SIZE = 16;

    // Custom GUI mode's own layout constants - a fixed local grid cell size (vanilla's own 16px item
    // plus a 2px gap, same spacing convention as vanilla chest slots) that the shared Scale setting
    // then blows up via a pose transform, same trick StorageOverlayFeature's own grid already uses.
    private static final int CELL_SIZE = 18;
    private static final int GRID_COLUMNS = 9;
    private static final int PANEL_PADDING = 6;
    private static final int PANEL_BG_COLOR = 0xEE1A1A1A;
    private static final int PANEL_BORDER_COLOR = 0xFF663D1A;
    private static final int CELL_BG_COLOR = 0xFF2A2A2A;

    // A normal Hypixel container GUI always appends the player's own 36 inventory+hotbar slots after
    // the GUI's own content - subtracting this out is a simpler, more robust way to isolate "just the
    // terminal grid" than hardcoding each type's own grid slot count.
    private static final int PLAYER_INVENTORY_SIZE = 36;

    // Per killer560's "focus on the orange side of the mod's gui... more orange in a sense for the
    // tiles" request (2026-09-09) - the mod's own established amber/orange accent (see
    // SettingsButtonWidget's own BORDER_HOVER), leaned into here instead of the old green/cyan/gold
    // rainbow mix, everywhere the puzzle itself doesn't force a specific color choice.
    private static final int THEME_ORANGE_LIGHT = 0xFFFFCC66;
    private static final int THEME_ORANGE = 0xFFFF8C00;
    private static final int THEME_ORANGE_DEEP = 0xFFCC5500;

    private static final int PANES_COLOR = THEME_ORANGE;
    private static final int ORDER_COLOR_1 = THEME_ORANGE_LIGHT;
    private static final int ORDER_COLOR_2 = THEME_ORANGE;
    private static final int ORDER_COLOR_3 = THEME_ORANGE_DEEP;
    private static final int STARTS_WITH_COLOR = THEME_ORANGE;
    // Rubix keeps a real functional 2-color split (left-click vs right-click), per killer560's explicit
    // request - orange for the common forward/left-click case, a clearly distinct blue for the reverse/
    // right-click case, rather than 4 shades that don't actually mean anything extra at a glance.
    private static final int RUBIX_LEFT_CLICK_COLOR = THEME_ORANGE;
    private static final int RUBIX_RIGHT_CLICK_COLOR = 0xFF3399FF;

    // Real Hypixel Rubix mechanic (confirmed via Odin's own RubixHandler): each click on a pane
    // advances it ONE step through this 5-color cycle - no adjacency/neighbor coupling despite the name.
    private static final List<DyeColor> RUBIX_COLOR_ORDER =
            List.of(DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.GREEN, DyeColor.BLUE, DyeColor.RED);

    private static final Map<DyeColor, List<String>> SELECT_PREFIXES = Map.ofEntries(
            Map.entry(DyeColor.BLACK, List.of("black", "ink")),
            Map.entry(DyeColor.BLUE, List.of("blue", "lapis")),
            Map.entry(DyeColor.BROWN, List.of("brown", "cocoa")),
            Map.entry(DyeColor.WHITE, List.of("white", "bone", "wool")),
            Map.entry(DyeColor.GREEN, List.of("green", "cactus")),
            Map.entry(DyeColor.RED, List.of("red", "rose")),
            Map.entry(DyeColor.YELLOW, List.of("yellow", "dandelion")),
            Map.entry(DyeColor.LIGHT_GRAY, List.of("silver", "light gray"))
    );

    private static TerminalType currentType;
    private static Map<Integer, SlotHighlight> currentHighlights = Map.of();
    private static int currentTerminalSlotCount;

    // Melody has no solving logic - Custom GUI mode just hides its player-inventory rows and recenters
    // the screen (see #applyMelodyRepositioning). Tracks the real, un-shifted topPos per screen instance
    // so the recenter shift is always computed from a stable baseline instead of drifting frame to frame.
    private static ContainerScreen melodyBaselineScreen;
    private static int melodyBaselineTopPos;
    private static final int MELODY_RECENTER_SHIFT = 45;

    private record SlotHighlight(int color, String label) {
    }

    private TerminalSolverFeature() {
    }

    /** Real bug found and fixed (2026-09-09), per killer560's report of a real, if brief, flash of the
     *  raw unmodified terminal on open before Custom GUI kicks in: state used to only refresh once per
     *  client TICK (~50ms), but rendering happens far more often than that (every frame, up to several
     *  times before the next tick even fires) - the first few frames after a terminal screen opens
     *  could render with stale (null) state, showing the real vanilla screen for a moment before the
     *  next tick corrected it. Called from {@link com.killer560.hub.terminals.mixin.TerminalSolverBackgroundMixin}
     *  at the very HEAD of {@code extractBackground} - the true first thing a screen's whole render pass
     *  does (confirmed via javap: {@code Screen.extractRenderStateWithTooltipAndSubtitles} calls it
     *  before {@code extractRenderState} even begins) - so every hook downstream of it, every single
     *  frame including the very first, already sees correct, freshly-computed state. */
    public static void refreshState() {
        TerminalSolverConfig cfg = TerminalSolverConfig.getInstance();
        if (!cfg.isEnabled() || !(Minecraft.getInstance().screen instanceof ContainerScreen screen)) {
            currentType = null;
            currentHighlights = Map.of();
            melodyBaselineScreen = null;
            return;
        }
        String title = screen.getTitle().getString();
        TerminalType type = matchType(title, cfg);
        if (type == null) {
            currentType = null;
            currentHighlights = Map.of();
            melodyBaselineScreen = null;
            return;
        }
        currentType = type;
        List<ItemStack> items = terminalItems(screen.getMenu());
        currentTerminalSlotCount = items.size();
        if (type == TerminalType.MELODY) {
            currentHighlights = Map.of();
            applyMelodyRepositioning(screen);
        } else {
            melodyBaselineScreen = null;
            currentHighlights = solve(type, title, items);
        }
    }

    /** Melody-only: recenters the real vanilla screen once its inventory rows are hidden, by directly
     *  shifting its own real {@code topPos} - moving the actual anchor point (not just a visual overlay)
     *  keeps rendering AND real click hit-testing in sync automatically, since both already read from
     *  this same field. {@link #MELODY_RECENTER_SHIFT} is an approximation (roughly half the height of
     *  the hidden inventory rows + label gap) rather than an exact computed value. */
    private static void applyMelodyRepositioning(ContainerScreen screen) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        if (melodyBaselineScreen != screen) {
            melodyBaselineScreen = screen;
            melodyBaselineTopPos = accessor.killer560smod$getTopPos();
        }
        int targetTopPos = TerminalSolverConfig.getInstance().isCustomGuiEnabled()
                ? melodyBaselineTopPos + MELODY_RECENTER_SHIFT : melodyBaselineTopPos;
        accessor.killer560smod$setTopPos(targetTopPos);
    }

    /** @return whether the given real slot index should be hidden right now. Every type but Melody
     *  hides its whole grid (Custom GUI draws a full replacement panel); Melody only hides the player's
     *  own inventory rows (there's no replacement panel for it - the real terminal portion stays put),
     *  per killer560's "hide my inventory" request (2026-09-09). */
    public static boolean shouldHideSlot(int slotIndex) {
        if (!isCustomGuiActive()) {
            return false;
        }
        return currentType != TerminalType.MELODY || slotIndex >= currentTerminalSlotCount;
    }

    /** @return whether the vanilla background texture and title/"Inventory" labels should be hidden -
     *  everything but Melody (whose real terminal portion is left fully visible, unlike every other
     *  type's full custom-panel replacement). */
    public static boolean shouldHideBackgroundAndLabels() {
        return isCustomGuiActive() && currentType != TerminalType.MELODY;
    }

    /** @return whether Custom GUI mode should currently be showing (feature + that toggle both on,
     *  and a covered terminal is actually open right now). */
    public static boolean isCustomGuiActive() {
        return currentType != null && TerminalSolverConfig.getInstance().isCustomGuiEnabled();
    }

    /** @return whether a real, clickable custom PANEL should currently be intercepting clicks - true
     *  for every type but Melody, which has no panel to redirect clicks to (its real terminal buttons
     *  need to stay genuinely clickable - only its inventory rows and position are touched). */
    public static boolean isCustomGuiPanelActive() {
        return isCustomGuiActive() && currentType != TerminalType.MELODY;
    }

    public static void renderOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (currentType == null || currentHighlights.isEmpty()) {
            return;
        }
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (isCustomGuiActive()) {
            renderCustomGui(graphics, screen, mouseX, mouseY);
        } else {
            renderVanillaHighlights(graphics, screen);
        }
    }

    private static void renderVanillaHighlights(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        int left = accessor.killer560smod$getLeftPos();
        int top = accessor.killer560smod$getTopPos();
        float scale = TerminalSolverConfig.getInstance().getScale();
        int inflate = Math.max(1, Math.round(scale));

        graphics.nextStratum();
        for (Slot slot : screen.getMenu().slots) {
            SlotHighlight highlight = currentHighlights.get(slot.index);
            if (highlight == null) {
                continue;
            }
            int x0 = left + slot.x;
            int y0 = top + slot.y;
            graphics.outline(x0 - inflate, y0 - inflate, SLOT_SIZE + inflate * 2, SLOT_SIZE + inflate * 2, highlight.color());
            if (highlight.label() != null) {
                float textScale = 0.5f * scale;
                graphics.pose().pushMatrix();
                graphics.pose().translate(x0, y0 + SLOT_SIZE - 7);
                graphics.pose().scale(textScale, textScale);
                graphics.text(Minecraft.getInstance().font, highlight.label(), 1, 1, 0xFFFFFFFF, true);
                graphics.pose().popMatrix();
            }
        }
    }

    /** Custom GUI mode: a big, standalone panel showing ONLY the slots actually in the solution -
     *  "the buttons i have to press are the only things I see in the gui", per killer560's explicit
     *  request (2026-09-09) - laid out at each item's real row/column within the terminal's own 9-wide
     *  grid (every Hypixel chest-style container is 9 columns wide) so the spatial layout still makes
     *  sense, just with every irrelevant slot skipped entirely and everything scaled up via the same
     *  Scale setting the vanilla-overlay mode already uses. The real slots are hidden elsewhere (see
     *  {@code TerminalSolverSlotMixin}) - clicking a cell here redirects to the real underlying slot by
     *  index via {@link SlotClickInvoker}, the same trick Storage Overlay's own custom grid uses. */
    private static void renderCustomGui(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int mouseX, int mouseY) {
        CustomGuiLayout layout = computeCustomGuiLayout(screen);
        double localMouseX = (mouseX - layout.originX) / (double) layout.scale;
        double localMouseY = (mouseY - layout.originY) / (double) layout.scale;

        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(layout.originX, layout.originY);
        graphics.pose().scale(layout.scale, layout.scale);

        graphics.fill(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING, layout.panelHeight + PANEL_PADDING, PANEL_BG_COLOR);
        graphics.outline(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING * 2, layout.panelHeight + PANEL_PADDING * 2, PANEL_BORDER_COLOR);

        List<Slot> slots = screen.getMenu().slots;
        ItemStack hoveredStack = null;
        for (Map.Entry<Integer, SlotHighlight> entry : currentHighlights.entrySet()) {
            int slotIndex = entry.getKey();
            if (slotIndex >= slots.size()) {
                continue;
            }
            SlotHighlight highlight = entry.getValue();
            int x0 = (slotIndex % GRID_COLUMNS) * CELL_SIZE;
            int y0 = (slotIndex / GRID_COLUMNS) * CELL_SIZE;
            ItemStack stack = slots.get(slotIndex).getItem();

            // Per killer560's per-type style requests (2026-09-09):
            switch (currentType) {
                case PANES -> {
                    // "i can only see the red ones and they have no outline" - the bare real item
                    // (already a red pane) with no cell background or outline added around it.
                    if (!stack.isEmpty()) {
                        graphics.item(stack, x0, y0);
                    }
                }
                case SELECT -> {
                    // "the items are instead glass panes/colored boxes... all the same" - a flat box in
                    // the real target color (see solveSelect), no item icon, no outline.
                    graphics.fill(x0, y0, x0 + SLOT_SIZE, y0 + SLOT_SIZE, highlight.color());
                }
                case RUBIX -> {
                    // "remove the outside border... left/right click color... number large in the
                    // middle" - the cell's own fill IS the left/right-click color indicator, no separate
                    // outline, with the signed click count centered on top at full (unshrunk) size.
                    graphics.fill(x0, y0, x0 + SLOT_SIZE, y0 + SLOT_SIZE, highlight.color());
                    if (highlight.label() != null) {
                        int textY = y0 + (SLOT_SIZE - Minecraft.getInstance().font.lineHeight) / 2;
                        graphics.centeredText(Minecraft.getInstance().font, highlight.label(), x0 + SLOT_SIZE / 2, textY, 0xFF000000);
                    }
                }
                default -> {
                    // Numbers, Starts With: unchanged from before - real item + colored outline.
                    graphics.fill(x0, y0, x0 + SLOT_SIZE, y0 + SLOT_SIZE, CELL_BG_COLOR);
                    graphics.outline(x0 - 1, y0 - 1, SLOT_SIZE + 2, SLOT_SIZE + 2, highlight.color());
                    if (!stack.isEmpty()) {
                        graphics.item(stack, x0, y0);
                        graphics.itemDecorations(Minecraft.getInstance().font, stack, x0, y0);
                    }
                }
            }

            if (!stack.isEmpty() && localMouseX >= x0 && localMouseX < x0 + SLOT_SIZE
                    && localMouseY >= y0 && localMouseY < y0 + SLOT_SIZE) {
                hoveredStack = stack;
            }
        }
        graphics.pose().popMatrix();

        // Select/Rubix show a flat color instead of the real item, so a tooltip about "the item" would
        // be meaningless there - only Panes/Numbers/Starts With (which still show the real item) get one.
        if (hoveredStack != null && currentType != TerminalType.SELECT && currentType != TerminalType.RUBIX) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, hoveredStack, mouseX, mouseY);
        }
    }

    /** @return whether the click was consumed - Custom GUI mode swallows every click while it's
     *  showing (redirecting the ones that land on a real cell, discarding the rest), since the real
     *  slots underneath are hidden and shouldn't be reachable by an unrelated click landing on empty
     *  panel background. */
    public static boolean handleCustomGuiClick(AbstractContainerScreen<?> screen, double mouseX, double mouseY, int button) {
        if (!isCustomGuiPanelActive()) {
            return false;
        }
        CustomGuiLayout layout = computeCustomGuiLayout(screen);
        double localX = (mouseX - layout.originX) / layout.scale;
        double localY = (mouseY - layout.originY) / layout.scale;
        int col = (int) Math.floor(localX / CELL_SIZE);
        int row = (int) Math.floor(localY / CELL_SIZE);
        int slotIndex = row * GRID_COLUMNS + col;

        List<Slot> slots = screen.getMenu().slots;
        if (col >= 0 && col < GRID_COLUMNS && row >= 0 && currentHighlights.containsKey(slotIndex) && slotIndex < slots.size()) {
            Slot slot = slots.get(slotIndex);
            ((SlotClickInvoker) (Object) screen).killer560smod$slotClicked(slot, slot.index, button, ContainerInput.PICKUP);
        }
        return true;
    }

    private record CustomGuiLayout(int originX, int originY, float scale, int panelWidth, int panelHeight) {
    }

    private static CustomGuiLayout computeCustomGuiLayout(AbstractContainerScreen<?> screen) {
        int terminalSlotCount = terminalItems(screen.getMenu()).size();
        int rows = Math.max(1, (int) Math.ceil(terminalSlotCount / (double) GRID_COLUMNS));
        float scale = TerminalSolverConfig.getInstance().getScale();
        int panelWidth = GRID_COLUMNS * CELL_SIZE;
        int panelHeight = rows * CELL_SIZE;
        int scaledWidth = Math.round(panelWidth * scale);
        int scaledHeight = Math.round(panelHeight * scale);
        int originX = (Minecraft.getInstance().getWindow().getGuiScaledWidth() - scaledWidth) / 2;
        int originY = (Minecraft.getInstance().getWindow().getGuiScaledHeight() - scaledHeight) / 2;
        return new CustomGuiLayout(originX, originY, scale, panelWidth, panelHeight);
    }

    private static TerminalType matchType(String title, TerminalSolverConfig cfg) {
        for (TerminalType type : TerminalType.values()) {
            if (!isTypeEnabled(type, cfg)) {
                continue;
            }
            if (type.titlePattern().matcher(title).matches()) {
                return type;
            }
        }
        return null;
    }

    private static boolean isTypeEnabled(TerminalType type, TerminalSolverConfig cfg) {
        return switch (type) {
            case PANES -> cfg.isPanesEnabled();
            case RUBIX -> cfg.isRubixEnabled();
            case NUMBERS -> cfg.isNumbersEnabled();
            case STARTS_WITH -> cfg.isStartsWithEnabled();
            case SELECT -> cfg.isSelectEnabled();
            // No solving/toggle of its own - only ever detected so Custom GUI's hide-inventory/recenter
            // treatment (see #shouldHideSlot, #applyMelodyRepositioning) can apply to it.
            case MELODY -> true;
        };
    }

    private static List<ItemStack> terminalItems(AbstractContainerMenu menu) {
        List<ItemStack> all = menu.getItems();
        int terminalSlotCount = Math.max(0, all.size() - PLAYER_INVENTORY_SIZE);
        return terminalSlotCount == 0 ? all : all.subList(0, terminalSlotCount);
    }

    private static Map<Integer, SlotHighlight> solve(TerminalType type, String title, List<ItemStack> items) {
        return switch (type) {
            case PANES -> solvePanes(items);
            case RUBIX -> solveRubix(items);
            case NUMBERS -> solveNumbers(items);
            case STARTS_WITH -> solveStartsWith(type, title, items);
            case SELECT -> solveSelect(type, title, items);
            // refreshState() never calls solve() for Melody - see its own doc.
            case MELODY -> Map.of();
        };
    }

    /** "Correct all the panes!" - click every red pane once (any order, no sequencing). */
    private static Map<Integer, SlotHighlight> solvePanes(List<ItemStack> items) {
        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem() == Items.RED_STAINED_GLASS_PANE) {
                result.put(i, new SlotHighlight(PANES_COLOR, null));
            }
        }
        return result;
    }

    /** "Click in order!" - same red-pane detection as Panes, but the real order is encoded in each
     *  pane's stack COUNT (not its name/lore) - confirmed via Odin's NumbersHandler. No label of our
     *  own on these - per killer560's report (2026-09-09), vanilla already renders that same count as
     *  a real number in the corner of the item, so our own text just doubled up on top of it. */
    private static Map<Integer, SlotHighlight> solveNumbers(List<ItemStack> items) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem() == Items.RED_STAINED_GLASS_PANE) {
                slots.add(i);
            }
        }
        slots.sort(Comparator.comparingInt(i -> items.get(i).getCount()));
        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int order = 0; order < slots.size(); order++) {
            int color = switch (order) {
                case 0 -> ORDER_COLOR_1;
                case 1 -> ORDER_COLOR_2;
                default -> ORDER_COLOR_3;
            };
            result.put(slots.get(order), new SlotHighlight(color, null));
        }
        return result;
    }

    /** "What starts with: '<letter>'?" - select every item whose (color-code-stripped) name starts
     *  with the given letter, ignoring case. Real items already selected/correct glint (are enchanted)
     *  in Hypixel's own UI - excluded here the same way Odin's StartsWithHandler does, with a Golden
     *  Apple allowlist for the one common item that glints but is still a valid answer. */
    private static Map<Integer, SlotHighlight> solveStartsWith(TerminalType type, String title, List<ItemStack> items) {
        Matcher matcher = type.titlePattern().matcher(title);
        if (!matcher.matches()) {
            return Map.of();
        }
        String letter = matcher.group(1);
        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item.isEmpty()) {
                continue;
            }
            if (item.hasFoil() && item.getItem() != Items.GOLDEN_APPLE) {
                continue;
            }
            String name = stripColor(item.getHoverName().getString());
            if (name.isEmpty()) {
                continue;
            }
            if (name.regionMatches(true, 0, letter, 0, letter.length())) {
                result.put(i, new SlotHighlight(STARTS_WITH_COLOR, null));
            }
        }
        return result;
    }

    /** "Select all the '<color>' items!" - select every item whose name starts with one of that
     *  color's known aliases (Hypixel's real item names don't all literally start with the dye color's
     *  own name - e.g. "Rose" for red, "Cactus" for green - see {@link #SELECT_PREFIXES}), excluding
     *  glinted items and the black filler panes. Highlight color is the real target dye's own color
     *  (via {@code DyeColor.getFireworkColor()}, a vivid real RGB per color) rather than a fixed
     *  constant - per killer560's request (2026-09-09) that Custom GUI show these as plain colored
     *  boxes in the actual target color, not the real (visually inconsistent) item icons. */
    private static Map<Integer, SlotHighlight> solveSelect(TerminalType type, String title, List<ItemStack> items) {
        Matcher matcher = type.titlePattern().matcher(title);
        if (!matcher.matches()) {
            return Map.of();
        }
        String colorText = matcher.group(1).trim().toLowerCase(Locale.ROOT);
        DyeColor color = parseSelectColor(colorText);
        List<String> prefixes = color != null ? SELECT_PREFIXES.getOrDefault(color, List.of(colorText)) : List.of(colorText);
        int highlightColor = color != null ? (0xFF000000 | color.getFireworkColor()) : THEME_ORANGE;

        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item.isEmpty() || item.hasFoil() || item.getItem() == Items.BLACK_STAINED_GLASS_PANE) {
                continue;
            }
            String name = stripColor(item.getHoverName().getString()).toLowerCase(Locale.ROOT);
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    result.put(i, new SlotHighlight(highlightColor, null));
                    break;
                }
            }
        }
        return result;
    }

    // Hypixel's terminal title says "SILVER" (Minecraft's old wool-color name) for what DyeColor calls
    // LIGHT_GRAY - same special case Odin's own TerminalTypes.openHandler handles.
    private static DyeColor parseSelectColor(String lowerCaseText) {
        if (lowerCaseText.equals("silver")) {
            return DyeColor.LIGHT_GRAY;
        }
        try {
            return DyeColor.valueOf(lowerCaseText.replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "Change all to same color!" - each pane cycles forward one step through
     *  {@link #RUBIX_COLOR_ORDER} per click (no neighbor coupling). Tries every possible target color
     *  and picks whichever needs the fewest total clicks, counting a "3 or 4 forward" pane as cheaper
     *  clicked backwards instead (2 or 1 clicks respectively) - real Hypixel lets you reverse-cycle a
     *  pane, same mechanic Odin's RubixHandler relies on. Each highlighted slot's label is the signed
     *  click count: positive = click normally that many times, negative = click backwards. */
    private static Map<Integer, SlotHighlight> solveRubix(List<ItemStack> items) {
        List<int[]> panes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DyeColor color = paneDyeColor(items.get(i));
            if (color == null || color == DyeColor.BLACK) {
                continue;
            }
            int colorIndex = RUBIX_COLOR_ORDER.indexOf(color);
            if (colorIndex < 0) {
                continue;
            }
            panes.add(new int[]{i, colorIndex});
        }
        if (panes.isEmpty()) {
            return Map.of();
        }

        Map<Integer, SlotHighlight> best = Map.of();
        int bestCost = Integer.MAX_VALUE;
        int cycleLength = RUBIX_COLOR_ORDER.size();
        for (int target = 0; target < cycleLength; target++) {
            Map<Integer, SlotHighlight> candidate = new LinkedHashMap<>();
            int cost = 0;
            for (int[] pane : panes) {
                int forward = Math.floorMod(target - pane[1], cycleLength);
                if (forward == 0) {
                    continue;
                }
                int clicksRequired = forward <= 2 ? forward : forward - cycleLength;
                cost += Math.abs(clicksRequired);
                candidate.put(pane[0], new SlotHighlight(rubixColorFor(clicksRequired), String.valueOf(clicksRequired)));
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = candidate;
            }
        }
        return best;
    }

    private static int rubixColorFor(int clicksRequired) {
        return clicksRequired > 0 ? RUBIX_LEFT_CLICK_COLOR : RUBIX_RIGHT_CLICK_COLOR;
    }

    private static DyeColor paneDyeColor(ItemStack item) {
        if (!(item.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        if (!(blockItem.getBlock() instanceof StainedGlassPaneBlock pane)) {
            return null;
        }
        return pane.getColor();
    }

    private static String stripColor(String s) {
        return s.replaceAll("§.", "").trim();
    }
}

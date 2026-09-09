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

    // A normal Hypixel container GUI always appends the player's own 36 inventory+hotbar slots after
    // the GUI's own content - subtracting this out is a simpler, more robust way to isolate "just the
    // terminal grid" than hardcoding each type's own grid slot count.
    private static final int PLAYER_INVENTORY_SIZE = 36;

    // Per killer560's "focus on the orange side of the mod's gui... more orange in a sense for the
    // tiles" request (2026-09-09) - the mod's own established amber/orange accent (see
    // SettingsButtonWidget's own BORDER_HOVER), leaned into here instead of the old green/cyan/gold
    // rainbow mix, everywhere the puzzle itself doesn't force a specific color choice.
    private static final int THEME_ORANGE = 0xFFFF8C00;
    // "Having them be bright would be better" (2026-09-09, round 4) - a more vivid/saturated orange
    // than THEME_ORANGE specifically for Panes/Numbers' flat boxes, which are otherwise a big flat
    // area of solid color (unlike an outline or a small Select box) where "bright" reads better.
    private static final int BRIGHT_ORANGE = 0xFFFFA500;
    private static final int MUTED_ORANGE = 0xFFB37744;

    private static final int PANES_COLOR = BRIGHT_ORANGE;
    private static final int STARTS_WITH_COLOR = BRIGHT_ORANGE;
    // Per killer560's "make more of the actual in element gui orange" request (2026-09-09, round 7) -
    // the panel itself (background + border) leans into the same theme now, not just the highlighted
    // cells: a warm dark amber instead of a neutral gray-black, and the same bright orange as every
    // other accent for the border (was a muted brown that barely read as "orange" at a glance). Shared
    // by every type's panel, Melody included - it used to have its own identical-value constant.
    private static final int PANEL_BG_COLOR = 0xEE241206;
    private static final int PANEL_BORDER_COLOR = BRIGHT_ORANGE;
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
            return;
        }
        String title = screen.getTitle().getString();
        TerminalType type = matchType(title, cfg);
        if (type == null) {
            currentType = null;
            currentHighlights = Map.of();
            return;
        }
        currentType = type;
        List<ItemStack> items = terminalItems(screen.getMenu());
        currentTerminalSlotCount = items.size();
        // Melody has no solving logic - nothing to mark correct/incorrect - so currentHighlights always
        // stays empty for it. Its Custom GUI panel (see #renderMelodyCustomGui) instead just redraws
        // every real terminal-grid item as-is, decluttered from the rest of the screen.
        currentHighlights = type == TerminalType.MELODY ? Map.of() : solve(type, title, items);
    }

    /** @return whether the given real slot index should be hidden right now - true for every type,
     *  Melody included, while Custom GUI mode is showing (each type either draws a full replacement
     *  panel of its own, or - for Melody - a plain redraw of the real terminal items, per killer560's
     *  "follow a similar track to the other one... hide the normal screen and redraw it entirely"
     *  request, 2026-09-09 round 6, which folded Melody into the same unified treatment). */
    public static boolean shouldHideSlot(int slotIndex) {
        return isCustomGuiActive();
    }

    /** @return whether the vanilla background texture and title/"Inventory" labels should be hidden -
     *  same as {@link #shouldHideSlot}, unconditionally true for every type while Custom GUI is on. */
    public static boolean shouldHideBackgroundAndLabels() {
        return isCustomGuiActive();
    }

    /** @return whether Custom GUI mode should currently be showing (feature + that toggle both on,
     *  and a covered terminal is actually open right now). */
    public static boolean isCustomGuiActive() {
        return currentType != null && TerminalSolverConfig.getInstance().isCustomGuiEnabled();
    }

    public static void renderOverlay(GuiGraphicsExtractor graphics) {
        if (currentType == null) {
            return;
        }
        if (!(Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        if (!isCustomGuiActive()) {
            // Melody has nothing to show outside Custom GUI mode - no solving logic means no
            // vanilla-overlay outlines to draw either.
            if (currentType != TerminalType.MELODY && !currentHighlights.isEmpty()) {
                renderVanillaHighlights(graphics, screen);
            }
            return;
        }
        if (currentType == TerminalType.MELODY) {
            renderMelodyCustomGui(graphics, screen);
        } else if (!currentHighlights.isEmpty()) {
            renderCustomGui(graphics, screen);
        }
    }

    /** Melody has no solving logic - nothing here is marked correct/incorrect - so this just redraws
     *  every real terminal-grid item, bare (no fill/outline decoration), in the same bigger decluttered
     *  panel style every other type's Custom GUI mode already uses. Per killer560's "follow a similar
     *  track to the other one... hide the normal screen and just redraw it entirely" request
     *  (2026-09-09, round 6) - folds Melody into the same unified hide-and-redraw treatment instead of
     *  the narrower "just hide inventory" special case round 5 had. */
    private static void renderMelodyCustomGui(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        CustomGuiLayout layout = computeCustomGuiLayout(screen);

        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(layout.originX, layout.originY);
        graphics.pose().scale(layout.scale, layout.scale);

        graphics.fill(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING, layout.panelHeight + PANEL_PADDING, PANEL_BG_COLOR);
        graphics.outline(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING * 2, layout.panelHeight + PANEL_PADDING * 2, PANEL_BORDER_COLOR);

        List<Slot> slots = screen.getMenu().slots;
        for (int slotIndex = 0; slotIndex < currentTerminalSlotCount && slotIndex < slots.size(); slotIndex++) {
            ItemStack stack = slots.get(slotIndex).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            int col = slotIndex % GRID_COLUMNS - layout.minCol();
            int row = slotIndex / GRID_COLUMNS - layout.minRow();
            if (col < 0 || row < 0) {
                continue;
            }
            graphics.item(stack, col * CELL_SIZE, row * CELL_SIZE);
        }
        graphics.pose().popMatrix();
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
    private static void renderCustomGui(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen) {
        CustomGuiLayout layout = computeCustomGuiLayout(screen);

        graphics.nextStratum();
        graphics.pose().pushMatrix();
        graphics.pose().translate(layout.originX, layout.originY);
        graphics.pose().scale(layout.scale, layout.scale);

        graphics.fill(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING, layout.panelHeight + PANEL_PADDING, PANEL_BG_COLOR);
        graphics.outline(-PANEL_PADDING, -PANEL_PADDING, layout.panelWidth + PANEL_PADDING * 2, layout.panelHeight + PANEL_PADDING * 2, PANEL_BORDER_COLOR);

        List<Slot> slots = screen.getMenu().slots;
        for (Map.Entry<Integer, SlotHighlight> entry : currentHighlights.entrySet()) {
            int slotIndex = entry.getKey();
            if (slotIndex >= slots.size()) {
                continue;
            }
            SlotHighlight highlight = entry.getValue();
            int x0 = (slotIndex % GRID_COLUMNS - layout.minCol()) * CELL_SIZE;
            int y0 = (slotIndex / GRID_COLUMNS - layout.minRow()) * CELL_SIZE;

            // Per killer560's per-type style requests (2026-09-09) - every type is now a flat colored
            // box (no real item icon, no outline) except Rubix, which also centers its click-count text.
            graphics.fill(x0, y0, x0 + SLOT_SIZE, y0 + SLOT_SIZE, highlight.color());
            if (currentType == TerminalType.RUBIX && highlight.label() != null) {
                int textY = y0 + (SLOT_SIZE - Minecraft.getInstance().font.lineHeight) / 2;
                graphics.centeredText(Minecraft.getInstance().font, highlight.label(), x0 + SLOT_SIZE / 2, textY, 0xFF000000);
            }
        }
        graphics.pose().popMatrix();
    }

    /** @return whether the click was consumed - Custom GUI mode swallows every click while it's
     *  showing (redirecting the ones that land on a real cell, discarding the rest), since the real
     *  slots underneath are hidden and shouldn't be reachable by an unrelated click landing on empty
     *  panel background. */
    public static boolean handleCustomGuiClick(AbstractContainerScreen<?> screen, double mouseX, double mouseY, int button) {
        if (!isCustomGuiActive()) {
            return false;
        }
        CustomGuiLayout layout = computeCustomGuiLayout(screen);
        double localX = (mouseX - layout.originX) / layout.scale;
        double localY = (mouseY - layout.originY) / layout.scale;
        int localCol = (int) Math.floor(localX / CELL_SIZE);
        int localRow = (int) Math.floor(localY / CELL_SIZE);
        int panelColumns = layout.panelWidth / CELL_SIZE;
        int panelRows = layout.panelHeight / CELL_SIZE;
        // Clicks landing outside the (now cropped-to-content) panel grid have nothing to redirect to -
        // still consumed below, same as a click on empty panel padding always was.
        if (localCol < 0 || localCol >= panelColumns || localRow < 0 || localRow >= panelRows) {
            return true;
        }
        int slotIndex = (localRow + layout.minRow()) * GRID_COLUMNS + (localCol + layout.minCol());

        // Melody has no solved/correct set to check against - any real terminal-grid cell is fair game,
        // matching "redraw it entirely" (every real item is shown, so every real item stays clickable).
        boolean validCell = currentType == TerminalType.MELODY
                ? slotIndex < currentTerminalSlotCount
                : currentHighlights.containsKey(slotIndex);
        List<Slot> slots = screen.getMenu().slots;
        if (validCell && slotIndex >= 0 && slotIndex < slots.size()) {
            Slot slot = slots.get(slotIndex);
            ((SlotClickInvoker) (Object) screen).killer560smod$slotClicked(slot, slot.index, button, ContainerInput.PICKUP);
        }
        return true;
    }

    private record CustomGuiLayout(int originX, int originY, float scale, int panelWidth, int panelHeight, int minCol, int minRow) {
    }

    /** Per killer560's "if boxes can only spawn in lets say a 2x7 space, then only show the 2x7 space...
     *  i dont need to see the outer border that will never have anything rendered" request (2026-09-09,
     *  round 7) - the panel used to always span the full 9-wide grid regardless of how much of it a
     *  given terminal type actually uses, wasting most of the panel on dead space for types whose real
     *  pattern only occupies a small sub-rectangle. Now crops to the bounding box of every real,
     *  non-filler item slot (same "not a black filler pane" rule {@link #solveSelect} already uses to
     *  skip Select's own background panes) - applies to every type uniformly, Melody included. */
    private static CustomGuiLayout computeCustomGuiLayout(AbstractContainerScreen<?> screen) {
        GridBounds bounds = computeGridBounds(screen);
        float scale = TerminalSolverConfig.getInstance().getScale();
        int panelWidth = bounds.columns() * CELL_SIZE;
        int panelHeight = bounds.rows() * CELL_SIZE;
        int scaledWidth = Math.round(panelWidth * scale);
        int scaledHeight = Math.round(panelHeight * scale);
        int originX = (Minecraft.getInstance().getWindow().getGuiScaledWidth() - scaledWidth) / 2;
        int originY = (Minecraft.getInstance().getWindow().getGuiScaledHeight() - scaledHeight) / 2;
        return new CustomGuiLayout(originX, originY, scale, panelWidth, panelHeight, bounds.minCol(), bounds.minRow());
    }

    private record GridBounds(int minCol, int minRow, int columns, int rows) {
    }

    private static GridBounds computeGridBounds(AbstractContainerScreen<?> screen) {
        List<ItemStack> items = terminalItems(screen.getMenu());
        int minCol = GRID_COLUMNS;
        int maxCol = -1;
        int minRow = Integer.MAX_VALUE;
        int maxRow = -1;
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() || stack.getItem() == Items.BLACK_STAINED_GLASS_PANE) {
                continue;
            }
            int col = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
        }
        if (maxCol < 0) {
            // No real puzzle items found this frame (shouldn't normally happen while a covered terminal
            // is open) - fall back to the old full-width behavior so nothing renders as a zero-size panel.
            int rows = Math.max(1, (int) Math.ceil(items.size() / (double) GRID_COLUMNS));
            return new GridBounds(0, 0, GRID_COLUMNS, rows);
        }
        return new GridBounds(minCol, minRow, maxCol - minCol + 1, maxRow - minRow + 1);
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
            // No solving/toggle of its own - only ever detected so Custom GUI's hide-inventory
            // treatment (see #shouldHideSlot) can apply to it.
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
     *  a real number in the corner of the item, so our own text just doubled up on top of it.
     *  <p>
     *  Per killer560's follow-up request (2026-09-09, round 4): only ever surfaces the immediate next
     *  click (bright orange) and the one after it (muted orange) - everything else stays completely
     *  unhighlighted, the same "next + following" two-tier reveal Chronomatron/Ultrasequencer's own
     *  Solver Only mode already uses, rather than lighting up every remaining pane's own order at once.
     *  Self-correcting every frame with no extra state: once the real current-lowest pane is clicked,
     *  Hypixel's own server stops it being a red pane, so the freshly re-sorted list naturally advances
     *  on its own. */
    private static Map<Integer, SlotHighlight> solveNumbers(List<ItemStack> items) {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getItem() == Items.RED_STAINED_GLASS_PANE) {
                slots.add(i);
            }
        }
        slots.sort(Comparator.comparingInt(i -> items.get(i).getCount()));
        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        if (!slots.isEmpty()) {
            result.put(slots.get(0), new SlotHighlight(BRIGHT_ORANGE, null));
        }
        if (slots.size() > 1) {
            result.put(slots.get(1), new SlotHighlight(MUTED_ORANGE, null));
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
     *  glinted items and the black filler panes. Highlight color is always the mod's own bright orange
     *  now - a round-4 version briefly used the real target dye's own color instead, but killer560
     *  explicitly asked (round 5, 2026-09-09) for orange regardless of the real target color. */
    private static Map<Integer, SlotHighlight> solveSelect(TerminalType type, String title, List<ItemStack> items) {
        Matcher matcher = type.titlePattern().matcher(title);
        if (!matcher.matches()) {
            return Map.of();
        }
        String colorText = matcher.group(1).trim().toLowerCase(Locale.ROOT);
        DyeColor color = parseSelectColor(colorText);
        List<String> prefixes = color != null ? SELECT_PREFIXES.getOrDefault(color, List.of(colorText)) : List.of(colorText);

        Map<Integer, SlotHighlight> result = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            if (item.isEmpty() || item.hasFoil() || item.getItem() == Items.BLACK_STAINED_GLASS_PANE) {
                continue;
            }
            String name = stripColor(item.getHoverName().getString()).toLowerCase(Locale.ROOT);
            for (String prefix : prefixes) {
                if (name.startsWith(prefix)) {
                    result.put(i, new SlotHighlight(BRIGHT_ORANGE, null));
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

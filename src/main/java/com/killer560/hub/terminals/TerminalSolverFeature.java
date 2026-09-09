package com.killer560.hub.terminals;

import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
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

    // A normal Hypixel container GUI always appends the player's own 36 inventory+hotbar slots after
    // the GUI's own content - subtracting this out is a simpler, more robust way to isolate "just the
    // terminal grid" than hardcoding each type's own grid slot count.
    private static final int PLAYER_INVENTORY_SIZE = 36;

    private static final int PANES_COLOR = 0xFF00FF00;
    private static final int ORDER_COLOR_1 = 0xFF00FF00;
    private static final int ORDER_COLOR_2 = 0xFFFFA500;
    private static final int ORDER_COLOR_3 = 0xFFFF0000;
    private static final int STARTS_WITH_COLOR = 0xFF00FFFF;
    private static final int SELECT_COLOR = 0xFFFFD700;
    private static final int RUBIX_FORWARD_1_COLOR = 0xFF00FF00;
    private static final int RUBIX_FORWARD_2_COLOR = 0xFFFFA500;
    private static final int RUBIX_REVERSE_1_COLOR = 0xFF00BFFF;
    private static final int RUBIX_REVERSE_2_COLOR = 0xFFFF00FF;

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

    private record SlotHighlight(int color, String label) {
    }

    private TerminalSolverFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private static void tick() {
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
        currentHighlights = solve(type, title, items);
    }

    public static void renderHighlights(GuiGraphicsExtractor graphics) {
        if (currentType == null || currentHighlights.isEmpty()) {
            return;
        }
        if (!(Minecraft.getInstance().screen instanceof ContainerScreen screen)) {
            return;
        }
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
        };
    }

    private static List<ItemStack> terminalItems(ChestMenu menu) {
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
     *  pane's stack COUNT (not its name/lore) - confirmed via Odin's NumbersHandler. */
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
            result.put(slots.get(order), new SlotHighlight(color, String.valueOf(order + 1)));
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
     *  glinted items and the black filler panes. */
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
                    result.put(i, new SlotHighlight(SELECT_COLOR, null));
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
        return switch (clicksRequired) {
            case 1 -> RUBIX_FORWARD_1_COLOR;
            case 2 -> RUBIX_FORWARD_2_COLOR;
            case -1 -> RUBIX_REVERSE_1_COLOR;
            default -> RUBIX_REVERSE_2_COLOR;
        };
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

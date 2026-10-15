package com.killer560.hub.itembrowser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The craft/obtain popup killer560 asked for (2026-09-21): "if I left click on it then itll open up the
 * menu showing how to craft/obtain it". Drawn as an overlay on top of the currently open real container
 * screen (from {@code ItemBrowserRenderMixin}, inside the container's own render pass, so its hover tooltips
 * are real tooltips) rather than a real separate {@link net.minecraft.client.gui.screens.Screen} - switching to a
 * real Screen would call the underlying real container screen's {@code removed()}, which for a real Hypixel menu
 * sends a real close-container packet and kicks him out of whatever he actually has open just to look up an item.
 * This never touches the real container at all.
 * <p>
 * Recipes come from the NEU item repo ({@link NeuItemData}) - killer560, 2026-10-11: "Some things are missing
 * recipe data" - with Hypixel's own single {@code recipes[0]} entry as the fallback while NEU's is loading or when
 * it has none. Obtain info (requirements, NPC sell price, minion/museum/salvage) comes from Hypixel's catalog.
 * <p>
 * Also hosts the minion tier picker (killer560, 2026-10-11: "Make it so minions are a sort of stack that if you
 * press on something like blaze minion then it has a popup for all tiers"): {@link #openTiers} shows every tier
 * of one minion, and clicking a tier opens that tier's craft view.
 */
public final class ItemCraftView {

    private static final int PANEL_WIDTH = 230;
    private static final int PADDING = 8;
    private static final int SLOT_SIZE = 18;
    private static final int MAX_OBTAIN_LINES = 6;
    private static final int MAX_OTHER_RECIPE_LINES = 6;
    private static final int TIER_COLUMNS = 6;
    private static final int TIER_CELL_W = 30;
    private static final int TIER_CELL_H = 30;

    private static SkyblockItemEntry openEntry = null;
    private static List<SkyblockItemEntry> openTiers = null;
    private static int[] panelBounds = null; // x, y, w, h in real screen coordinates
    private static int[] closeButtonBounds = null;
    /** Screen bounds of each tier cell in the tier picker, parallel to {@link #openTiers}. */
    private static final List<int[]> tierCells = new ArrayList<>();

    private ItemCraftView() {
    }

    public static boolean isOpen() {
        return openEntry != null || openTiers != null;
    }

    public static boolean isTierPickerOpen() {
        return openTiers != null;
    }

    public static SkyblockItemEntry openEntry() {
        return openEntry;
    }

    public static List<SkyblockItemEntry> openTierList() {
        return openTiers;
    }

    public static void open(SkyblockItemEntry entry) {
        openEntry = entry;
        openTiers = null;
        if (entry != null) {
            NeuItemData.get(entry.id()); // start the fetch now so the recipe is usually there by the first frame
        }
    }

    /** Opens the tier picker for one minion's tiers (sorted lowest first by the caller). */
    public static void openTiers(List<SkyblockItemEntry> tiers) {
        openTiers = List.copyOf(tiers);
        openEntry = null;
        tierCells.clear();
    }

    public static void close() {
        openEntry = null;
        openTiers = null;
        panelBounds = null;
        closeButtonBounds = null;
        tierCells.clear();
    }

    /** @return true if the click landed anywhere relevant to the popup (which is all of them while it's
     *  open - every click is swallowed so it can't fall through onto the real container underneath). */
    public static boolean handleClick(double mouseX, double mouseY, int button) {
        if (!isOpen()) {
            return false;
        }
        if (closeButtonBounds != null && inside(closeButtonBounds, mouseX, mouseY)) {
            close();
            return true;
        }
        if (panelBounds == null || !inside(panelBounds, mouseX, mouseY)) {
            // Clicked outside the popup box entirely - same as pressing Escape.
            close();
            return true;
        }
        if (openTiers != null && button == 0) {
            for (int i = 0; i < tierCells.size() && i < openTiers.size(); i++) {
                if (inside(tierCells.get(i), mouseX, mouseY)) {
                    open(openTiers.get(i));
                    return true;
                }
            }
        }
        return true;
    }

    public static void render(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (openTiers != null) {
            renderTiers(graphics, screenWidth, screenHeight, mouseX, mouseY);
        } else if (openEntry != null) {
            renderEntry(graphics, screenWidth, screenHeight, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ minion tier picker

    private static void renderTiers(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        Font font = Minecraft.getInstance().font;
        List<SkyblockItemEntry> tiers = openTiers;
        int rows = Math.max(1, (tiers.size() + TIER_COLUMNS - 1) / TIER_COLUMNS);
        int panelW = Math.max(PANEL_WIDTH, TIER_COLUMNS * TIER_CELL_W + PADDING * 2);
        int panelH = PADDING * 2 + font.lineHeight + 6 + rows * TIER_CELL_H + font.lineHeight + 2;
        int panelX = (screenWidth - panelW) / 2;
        int panelY = Math.max(10, (screenHeight - panelH) / 2);
        panelBounds = new int[]{panelX, panelY, panelW, panelH};

        graphics.fill(0, 0, screenWidth, screenHeight, 0x99000000);
        com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.ITEM_BROWSER);
        graphics.fill(panelX, panelY, panelX + panelW, panelY + panelH, menu == null ? 0xF00D0D0D : com.killer560.hub.gui.OverlayPalette.alpha(menu.panel(), 0xF0));
        graphics.outline(panelX, panelY, panelW, panelH, menu == null ? 0xFFCC6600 : menu.accent());

        String title = ItemBrowserFeature.minionBaseName(tiers.getFirst()) + " - all tiers";
        graphics.text(font, title, panelX + PADDING, panelY + PADDING, menu == null ? 0xFFFFAA00 : menu.accentHover(), false);

        int gridX = panelX + (panelW - TIER_COLUMNS * TIER_CELL_W) / 2;
        int gridY = panelY + PADDING + font.lineHeight + 6;
        tierCells.clear();
        SkyblockItemEntry hovered = null;
        for (int i = 0; i < tiers.size(); i++) {
            int cx = gridX + (i % TIER_COLUMNS) * TIER_CELL_W;
            int cy = gridY + (i / TIER_COLUMNS) * TIER_CELL_H;
            int[] cell = {cx, cy, TIER_CELL_W, TIER_CELL_H};
            tierCells.add(cell);
            boolean over = inside(cell, mouseX, mouseY);
            graphics.fill(cx + 1, cy + 1, cx + TIER_CELL_W - 1, cy + TIER_CELL_H - 1, over ? (menu == null ? 0x80CC6600 : com.killer560.hub.gui.OverlayPalette.alpha(menu.accent(), 0x80)) : (menu == null ? 0xFF262626 : menu.card()));
            SkyblockItemEntry tier = tiers.get(i);
            graphics.item(SkyblockItemStackFactory.build(tier), cx + (TIER_CELL_W - 16) / 2, cy + 2);
            graphics.centeredText(font, ItemBrowserFeature.minionTierLabel(tier), cx + TIER_CELL_W / 2, cy + 19, 0xFFFFFFFF);
            if (over) {
                hovered = tier;
            }
        }
        graphics.text(font, "§8Click a tier for its recipe", panelX + PADDING, panelY + panelH - PADDING - font.lineHeight + 2,
                0xFF888888, false);
        drawCloseButton(graphics, panelX, panelY, panelW, mouseX, mouseY);
        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, ItemBrowserFeature.buildTooltipLines(hovered), Optional.empty(), mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------------ craft / obtain view

    private static void renderEntry(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        SkyblockItemEntry entry = openEntry;
        Font font = Minecraft.getInstance().font;
        int contentWidth = PANEL_WIDTH - PADDING * 2;

        NeuItemData.Info neu = NeuItemData.get(entry.id());
        boolean neuSettled = NeuItemData.isSettled(entry.id());
        List<String> gridIds;
        List<Integer> gridCounts;
        int outputCount;
        if (neu != null && neu.hasCraftingRecipe()) {
            gridIds = neu.grid();
            gridCounts = neu.gridCounts();
            outputCount = neu.outputCount();
        } else {
            gridIds = entry.recipeIngredients();
            gridCounts = null;
            outputCount = entry.recipeOutputCount();
        }
        List<String> otherLines = new ArrayList<>();
        if (neu != null) {
            for (String line : neu.otherRecipes()) {
                otherLines.addAll(wrap(font, "- " + line, contentWidth));
            }
        }
        boolean otherTruncated = otherLines.size() > MAX_OTHER_RECIPE_LINES;
        if (otherTruncated) {
            otherLines = new ArrayList<>(otherLines.subList(0, MAX_OTHER_RECIPE_LINES));
        }
        String noRecipe = !neuSettled ? "Loading recipe from the NEU item repo..."
                : "No recipe in the NEU item repo or Hypixel's API for this item.";
        List<String> noRecipeLines = gridIds == null && otherLines.isEmpty() ? wrap(font, noRecipe, contentWidth) : List.of();

        List<String> descriptionLines = wrap(font, entry.description(), contentWidth);
        List<String> obtainLines = entry.obtainLines();

        int y = 0;
        y += font.lineHeight + 4; // title
        if (entry.tier() != null || entry.category() != null) {
            y += font.lineHeight + 2;
        }
        y += descriptionLines.size() * font.lineHeight;
        y += 6; // divider gap before Obtain
        y += font.lineHeight; // "Obtain:" header
        int shownObtain = Math.min(obtainLines.size(), MAX_OBTAIN_LINES);
        y += Math.max(1, shownObtain) * font.lineHeight;
        if (obtainLines.size() > MAX_OBTAIN_LINES) {
            y += font.lineHeight;
        }
        y += 6; // divider gap before Recipe
        y += font.lineHeight; // "Recipe:" header
        if (gridIds != null) {
            y += SLOT_SIZE * 3 + 4;
        }
        y += otherLines.size() * font.lineHeight + (otherTruncated ? font.lineHeight : 0);
        y += noRecipeLines.size() * font.lineHeight;
        int panelHeight = y + PADDING * 2;

        int panelX = (screenWidth - PANEL_WIDTH) / 2;
        int panelY = Math.max(10, (screenHeight - panelHeight) / 2);
        panelBounds = new int[]{panelX, panelY, PANEL_WIDTH, panelHeight};

        // Dim the whole screen behind it so the popup reads clearly over whatever real menu is open.
        graphics.fill(0, 0, screenWidth, screenHeight, 0x99000000);
        com.killer560.hub.gui.OverlayPalette menu = com.killer560.hub.gui.OverlayThemes.of(com.killer560.hub.gui.OverlayThemes.ITEM_BROWSER);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, menu == null ? 0xF00D0D0D : com.killer560.hub.gui.OverlayPalette.alpha(menu.panel(), 0xF0));
        graphics.outline(panelX, panelY, PANEL_WIDTH, panelHeight, menu == null ? 0xFFCC6600 : menu.accent());

        int lx = panelX + PADDING;
        int ly = panelY + PADDING;

        ItemStack displayStack = SkyblockItemStackFactory.build(entry);
        graphics.text(font, SkyblockItemStackFactory.tierColorCode(entry.tier()) + entry.name(), lx, ly, 0xFFFFFFFF, false);
        ly += font.lineHeight + 4;

        if (entry.tier() != null || entry.category() != null) {
            String line = "§7" + (entry.tier() != null ? entry.tier() : "")
                    + (entry.tier() != null && entry.category() != null ? " - " : "")
                    + (entry.category() != null ? SkyblockItemStackFactory.niceCategory(entry.category()) : "");
            graphics.text(font, line, lx, ly, 0xFFAAAAAA, false);
            ly += font.lineHeight + 2;
        }

        for (String line : descriptionLines) {
            graphics.text(font, "§7" + line, lx, ly, 0xFFAAAAAA, false);
            ly += font.lineHeight;
        }

        ly += 6;
        graphics.text(font, "§6Obtain:", lx, ly, menu == null ? 0xFFFFAA00 : menu.accentHover(), false);
        ly += font.lineHeight;
        if (obtainLines.isEmpty()) {
            graphics.text(font, "§8No obtain information available from Hypixel's API.", lx, ly, 0xFF888888, false);
            ly += font.lineHeight;
        } else {
            for (int i = 0; i < shownObtain; i++) {
                for (String wrapped : wrap(font, "- " + obtainLines.get(i), contentWidth)) {
                    graphics.text(font, "§7" + wrapped, lx, ly, 0xFFAAAAAA, false);
                    ly += font.lineHeight;
                }
            }
            if (obtainLines.size() > MAX_OBTAIN_LINES) {
                graphics.text(font, "§8+ " + (obtainLines.size() - MAX_OBTAIN_LINES) + " more (see in-game)", lx, ly, 0xFF888888, false);
                ly += font.lineHeight;
            }
        }

        ly += 6;
        graphics.text(font, "§6Recipe:", lx, ly, 0xFFFFAA00, false);
        ly += font.lineHeight;

        Object hoveredTooltip = null; // an ItemStack, or a List of lines for an ingredient with NEU lore
        if (gridIds != null) {
            int gridX = lx;
            int gridY = ly;
            for (int i = 0; i < 9 && i < gridIds.size(); i++) {
                int col = i % 3;
                int row = i / 3;
                int cellX = gridX + col * SLOT_SIZE;
                int cellY = gridY + row * SLOT_SIZE;
                graphics.fill(cellX, cellY, cellX + SLOT_SIZE, cellY + SLOT_SIZE, 0xFF262626);
                graphics.outline(cellX, cellY, SLOT_SIZE, SLOT_SIZE, 0xFF553311);
                String ingredientId = gridIds.get(i);
                if (ingredientId == null) {
                    continue;
                }
                boolean over = mouseX >= cellX && mouseX < cellX + SLOT_SIZE && mouseY >= cellY && mouseY < cellY + SLOT_SIZE;
                SkyblockItemEntry ingredientEntry = SkyblockItemRepository.findById(ingredientId);
                if (ingredientEntry != null) {
                    graphics.item(SkyblockItemStackFactory.build(ingredientEntry), cellX + 1, cellY + 1);
                    if (over) {
                        hoveredTooltip = ItemBrowserFeature.buildTooltipLines(ingredientEntry);
                    }
                } else {
                    ItemStack vanilla = vanillaIcon(ingredientId);
                    if (vanilla.isEmpty()) {
                        graphics.text(font, "?", cellX + 6, cellY + 5, 0xFF888888, false);
                    } else {
                        graphics.item(vanilla, cellX + 1, cellY + 1);
                    }
                    if (over) {
                        hoveredTooltip = List.of(NeuItemData.nameFor(ingredientId));
                    }
                }
                int count = gridCounts == null || i >= gridCounts.size() ? 1 : gridCounts.get(i);
                if (count > 1) {
                    String c = String.valueOf(count);
                    graphics.text(font, c, cellX + SLOT_SIZE - 1 - font.width(c), cellY + SLOT_SIZE - 8, 0xFFFFFFFF, true);
                }
            }
            int arrowX = gridX + 3 * SLOT_SIZE + 6;
            int arrowY = gridY + SLOT_SIZE - 4;
            graphics.text(font, "§f->", arrowX, arrowY, 0xFFFFFFFF, false);
            int outX = arrowX + font.width("-> ") + 4;
            int outY = gridY + SLOT_SIZE;
            graphics.item(displayStack, outX, outY - 8);
            graphics.text(font, "x" + outputCount, outX, outY + 10, 0xFFFFFFFF, false);
            ly = gridY + SLOT_SIZE * 3 + 4;
        }
        for (String line : otherLines) {
            graphics.text(font, "§7" + line, lx, ly, 0xFFAAAAAA, false);
            ly += font.lineHeight;
        }
        if (otherTruncated) {
            graphics.text(font, "§8+ more ways to get it", lx, ly, 0xFF888888, false);
            ly += font.lineHeight;
        }
        for (String line : noRecipeLines) {
            graphics.text(font, "§8" + line, lx, ly, 0xFF888888, false);
            ly += font.lineHeight;
        }

        drawCloseButton(graphics, panelX, panelY, PANEL_WIDTH, mouseX, mouseY);

        // Hover over the title shows the item's own tooltip (NEU lore when it has arrived).
        if (hoveredTooltip == null && mouseX >= panelX && mouseX < panelX + PANEL_WIDTH - 16
                && mouseY >= panelY && mouseY < panelY + font.lineHeight + PADDING) {
            hoveredTooltip = ItemBrowserFeature.buildTooltipLines(entry);
        }
        if (hoveredTooltip instanceof List<?> lines) {
            List<net.minecraft.network.chat.Component> components = new ArrayList<>();
            for (Object o : lines) {
                components.add(o instanceof net.minecraft.network.chat.Component c ? c
                        : net.minecraft.network.chat.Component.literal(String.valueOf(o)));
            }
            graphics.setTooltipForNextFrame(font, components, Optional.empty(), mouseX, mouseY);
        }
    }

    /** NEU names vanilla items by their Bukkit material names; the ones that differ from the 1.8 registry name. */
    private static final java.util.Map<String, String> BUKKIT_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("ink_sack", "dye"), java.util.Map.entry("sulphur", "gunpowder"),
            java.util.Map.entry("wood", "planks"), java.util.Map.entry("log_2", "log2"),
            java.util.Map.entry("leaves_2", "leaves2"), java.util.Map.entry("seeds", "wheat_seeds"),
            java.util.Map.entry("snow_ball", "snowball"), java.util.Map.entry("raw_fish", "fish"),
            java.util.Map.entry("exp_bottle", "experience_bottle"), java.util.Map.entry("watch", "clock"),
            java.util.Map.entry("nether_stalk", "nether_wart"), java.util.Map.entry("sugar_cane", "reeds"),
            java.util.Map.entry("red_rose", "red_flower"), java.util.Map.entry("book_and_quill", "writable_book"),
            java.util.Map.entry("enchantment_table", "enchanting_table"), java.util.Map.entry("workbench", "crafting_table"),
            java.util.Map.entry("pork", "porkchop"), java.util.Map.entry("raw_beef", "beef"),
            java.util.Map.entry("raw_chicken", "chicken"), java.util.Map.entry("leash", "lead"),
            java.util.Map.entry("iron_fence", "iron_bars"), java.util.Map.entry("thin_glass", "glass_pane"),
            java.util.Map.entry("smooth_brick", "stonebrick"), java.util.Map.entry("nether_brick_item", "netherbrick"),
            java.util.Map.entry("ender_stone", "end_stone"), java.util.Map.entry("eye_of_ender", "ender_eye"),
            java.util.Map.entry("fireball", "fire_charge"), java.util.Map.entry("carrot_item", "carrot"),
            java.util.Map.entry("potato_item", "potato"), java.util.Map.entry("skull_item", "skull"),
            java.util.Map.entry("mushroom_soup", "mushroom_stew"), java.util.Map.entry("quartz_ore", "quartz_ore"));

    /** A plain vanilla ingredient the Skyblock catalog doesn't list (NEU's "STICK", "INK_SACK-3" = name-damage), as its
     *  modern item through the 1.8 flattening table; EMPTY when there is none. */
    static ItemStack vanillaIcon(String neuId) {
        if (neuId == null || neuId.isBlank()) {
            return ItemStack.EMPTY;
        }
        String name = neuId;
        int damage = 0;
        int dash = neuId.lastIndexOf('-');
        if (dash > 0 && dash < neuId.length() - 1 && neuId.substring(dash + 1).chars().allMatch(Character::isDigit)
                && neuId.length() - dash <= 3) {
            name = neuId.substring(0, dash);
            damage = Integer.parseInt(neuId.substring(dash + 1));
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        lower = BUKKIT_NAMES.getOrDefault(lower, lower);
        if (lower.startsWith("gold_") && !lower.equals("gold_ingot") && !lower.equals("gold_nugget")
                && !lower.equals("gold_block") && !lower.equals("gold_ore") && !lower.equals("gold_record")) {
            lower = "golden_" + lower.substring(5);
        } else if (lower.startsWith("wood_")) {
            lower = "wooden_" + lower.substring(5);
        }
        try {
            net.minecraft.world.item.Item item = com.killer560.hub.profileviewer.item.LegacyItems.legacyItem(lower, damage);
            return item == null || item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static void drawCloseButton(GuiGraphicsExtractor graphics, int panelX, int panelY, int panelW, int mouseX, int mouseY) {
        int closeSize = 12;
        int closeX = panelX + panelW - closeSize - 4;
        int closeY = panelY + 4;
        closeButtonBounds = new int[]{closeX, closeY, closeSize, closeSize};
        boolean closeHovered = mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize;
        graphics.fill(closeX, closeY, closeX + closeSize, closeY + closeSize, closeHovered ? 0xFFCC3333 : 0xFF553311);
        graphics.text(Minecraft.getInstance().font, "x", closeX + 3, closeY + 2, 0xFFFFFFFF, false);
    }

    private static List<String> wrap(Font font, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return lines;
        }
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String candidate = cur.isEmpty() ? w : cur + " " + w;
            if (font.width(candidate) > maxWidth && !cur.isEmpty()) {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur = new StringBuilder(candidate);
            }
        }
        if (!cur.isEmpty()) {
            lines.add(cur.toString());
        }
        return lines;
    }

    private static boolean inside(int[] rect, double x, double y) {
        return x >= rect[0] && x < rect[0] + rect[2] && y >= rect[1] && y < rect[1] + rect[3];
    }
}

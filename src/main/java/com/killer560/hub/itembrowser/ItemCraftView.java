package com.killer560.hub.itembrowser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The craft/obtain popup killer560 asked for (2026-09-21): "if I left click on it then itll open up the
 * menu showing how to craft/obtain it". Drawn as an overlay on top of the currently open real container
 * screen (same {@code ScreenEvents.afterExtract} render pass {@link ItemBrowserFeature}'s own panel
 * uses) rather than a real separate {@link net.minecraft.client.gui.screens.Screen} - switching to a
 * real Screen would call the underlying real container screen's {@code removed()}, which for a real
 * Hypixel menu sends a real close-container packet and kicks him out of whatever he actually has open
 * just to look up an item. This never touches the real container at all.
 * <p>
 * Built entirely from what {@link SkyblockItemRepository} actually has for the item (see its class doc
 * and {@link SkyblockItemEntry}) - recipe data is real but only exists for 1 of 5,655 real items on the
 * live catalog any more, so most items honestly show "No recipe data available" instead of an empty
 * grid. Obtain info (requirements, NPC sell price, minion/museum/salvage) is shown whenever the real
 * catalog has it.
 */
public final class ItemCraftView {

    private static final int PANEL_WIDTH = 210;
    private static final int PADDING = 8;
    private static final int SLOT_SIZE = 18;
    private static final int MAX_OBTAIN_LINES = 6;

    private static SkyblockItemEntry openEntry = null;
    private static int[] panelBounds = null; // x, y, w, h in real screen coordinates
    private static int[] closeButtonBounds = null;

    private ItemCraftView() {
    }

    public static boolean isOpen() {
        return openEntry != null;
    }

    public static void open(SkyblockItemEntry entry) {
        openEntry = entry;
    }

    public static void close() {
        openEntry = null;
        panelBounds = null;
        closeButtonBounds = null;
    }

    /** @return true if the click landed anywhere relevant to the popup (which is all of them while it's
     *  open - every click is swallowed so it can't fall through onto the real container underneath). */
    public static boolean handleClick(double mouseX, double mouseY, int button) {
        if (openEntry == null) {
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
        return true;
    }

    public static void render(GuiGraphicsExtractor graphics, int screenWidth, int screenHeight, int mouseX, int mouseY) {
        if (openEntry == null) {
            return;
        }
        SkyblockItemEntry entry = openEntry;
        Font font = Minecraft.getInstance().font;

        List<String> descriptionLines = wrap(font, entry.description(), PANEL_WIDTH - PADDING * 2);
        List<String> obtainLines = entry.obtainLines();
        boolean hasRecipe = entry.recipeIngredients() != null;

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
        y += hasRecipe ? (SLOT_SIZE * 3 + 4) : wrap(font, "No recipe data is available from Hypixel's API for this item.",
                PANEL_WIDTH - PADDING * 2).size() * font.lineHeight;
        int panelHeight = y + PADDING * 2;

        int panelX = (screenWidth - PANEL_WIDTH) / 2;
        int panelY = Math.max(10, (screenHeight - panelHeight) / 2);
        panelBounds = new int[]{panelX, panelY, PANEL_WIDTH, panelHeight};

        // Dim the whole screen behind it so the popup reads clearly over whatever real menu is open.
        graphics.fill(0, 0, screenWidth, screenHeight, 0x99000000);
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, 0xF00D0D0D);
        graphics.outline(panelX, panelY, PANEL_WIDTH, panelHeight, 0xFFCC6600);

        int lx = panelX + PADDING;
        int ly = panelY + PADDING;
        int contentWidth = PANEL_WIDTH - PADDING * 2;

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
        graphics.text(font, "§6Obtain:", lx, ly, 0xFFFFAA00, false);
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

        if (!hasRecipe) {
            for (String line : wrap(font, "No recipe data is available from Hypixel's API for this item.", contentWidth)) {
                graphics.text(font, "§8" + line, lx, ly, 0xFF888888, false);
                ly += font.lineHeight;
            }
        } else {
            int gridX = lx;
            int gridY = ly;
            List<String> ingredients = entry.recipeIngredients();
            ItemStack hoveredIngredient = null;
            for (int i = 0; i < 9; i++) {
                int col = i % 3;
                int row = i / 3;
                int cellX = gridX + col * SLOT_SIZE;
                int cellY = gridY + row * SLOT_SIZE;
                graphics.fill(cellX, cellY, cellX + SLOT_SIZE, cellY + SLOT_SIZE, 0xFF262626);
                graphics.outline(cellX, cellY, SLOT_SIZE, SLOT_SIZE, 0xFF553311);
                String ingredientId = ingredients.get(i);
                if (ingredientId == null) {
                    continue;
                }
                SkyblockItemEntry ingredientEntry = SkyblockItemRepository.findById(ingredientId);
                if (ingredientEntry != null) {
                    ItemStack ingredientStack = SkyblockItemStackFactory.build(ingredientEntry);
                    graphics.item(ingredientStack, cellX + 1, cellY + 1);
                    if (mouseX >= cellX && mouseX < cellX + SLOT_SIZE && mouseY >= cellY && mouseY < cellY + SLOT_SIZE) {
                        hoveredIngredient = ingredientStack;
                    }
                } else {
                    graphics.text(font, "?", cellX + 6, cellY + 5, 0xFF888888, false);
                }
            }
            int arrowX = gridX + 3 * SLOT_SIZE + 6;
            int arrowY = gridY + SLOT_SIZE - 4;
            graphics.text(font, "§f->", arrowX, arrowY, 0xFFFFFFFF, false);
            int outX = arrowX + font.width("-> ") + 4;
            int outY = gridY + SLOT_SIZE;
            graphics.item(displayStack, outX, outY - 8);
            String countText = "x" + entry.recipeOutputCount();
            graphics.text(font, countText, outX, outY + 10, 0xFFFFFFFF, false);
            ly = gridY + SLOT_SIZE * 3 + 4;

            if (hoveredIngredient != null) {
                graphics.setTooltipForNextFrame(font, hoveredIngredient, mouseX, mouseY);
            }
        }

        int closeSize = 12;
        int closeX = panelX + PANEL_WIDTH - closeSize - 4;
        int closeY = panelY + 4;
        closeButtonBounds = new int[]{closeX, closeY, closeSize, closeSize};
        boolean closeHovered = mouseX >= closeX && mouseX < closeX + closeSize && mouseY >= closeY && mouseY < closeY + closeSize;
        graphics.fill(closeX, closeY, closeX + closeSize, closeY + closeSize, closeHovered ? 0xFFCC3333 : 0xFF553311);
        graphics.text(font, "x", closeX + 3, closeY + 2, 0xFFFFFFFF, false);

        // Hover tooltip for the item itself, only over the header area (not the recipe grid, which has
        // its own per-ingredient tooltip above).
        if (mouseX >= panelX && mouseX < panelX + PANEL_WIDTH && mouseY >= panelY && mouseY < panelY + font.lineHeight + PADDING) {
            graphics.setTooltipForNextFrame(font, displayStack, mouseX, mouseY);
        }
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

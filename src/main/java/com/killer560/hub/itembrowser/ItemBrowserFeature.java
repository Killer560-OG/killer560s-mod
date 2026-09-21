package com.killer560.hub.itembrowser;

import com.killer560.hub.inventorysearch.InventorySearchFeature;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Real Skyblock item browser panel, referencing the classic Not Enough Updates (NEU) 1.8.9 mod's own
 * real item panel per killer560's own description ("I essentially want neu's search feature... best
 * reference for you"). Draws a persistent, searchable grid of every real Skyblock item on the side of
 * any real {@link AbstractContainerScreen}, sharing the exact same search query as
 * {@link InventorySearchFeature} - typing filters this panel's real item grid live, and the same real
 * Ctrl+F toggle both features already share starts/stops typing.
 * <p>
 * Item data and icons are real, not placeholders - see {@link SkyblockItemRepository} (Hypixel's own
 * public, keyless {@code /v2/resources/skyblock/items} resource) and {@link SkyblockItemStackFactory}.
 * <p>
 * killer560 (2026-09-21): "make it take up the full screen height wise, allow me to adjust the item
 * width up to 20, and the overall scale of it. Make it so if i hover an item it tells me its lore and if
 * i left click on it then itll open up the menu showing how to craft/obtain it. Make the adjusters
 * slider bars. Make it so I can adjust if it is horizontal or vertical and how it is centered as well."
 * <ul>
 *   <li>Full height - {@link #layout} always sizes the grid to fill the available screen height (a
 *   fixed top/bottom margin either side), instead of a separate fixed row count.</li>
 *   <li>Item width up to 20 - {@link ItemBrowserConfig#MAX_COLUMNS}.</li>
 *   <li>Overall scale - the whole panel is drawn through one pose transform (same approach as
 *   {@code StorageOverlayFeature}'s own scale slider), so every fill/outline/item icon below is in
 *   LOCAL (pre-scale) pixel coordinates.</li>
 *   <li>Hover lore / left-click craft-obtain - {@link #buildTooltipLines} and {@link ItemCraftView}.</li>
 *   <li>Slider adjusters / horizontal-vertical / centering - {@link ItemBrowserConfig}; see its own
 *   field docs for exactly what "horizontal/vertical" and "centered" were read as.</li>
 * </ul>
 * Watches its own per-frame cost per the brief: {@link #filteredItemsCached} only re-filters the ~5,655
 * item catalog when the query (or the catalog reference itself, after a background refresh) actually
 * changes, instead of every single frame regardless of whether anything moved.
 */
public final class ItemBrowserFeature {

    private static final int CELL_SIZE = 18;
    private static final int HEADER_HEIGHT = 20;
    private static final int PADDING = 4;
    /** Real screen-space margins the panel's full-height box is measured between. */
    private static final int TOP_MARGIN = 20;
    private static final int BOTTOM_MARGIN = 10;
    private static final int SIDE_MARGIN = 10;

    private static int scrollOffset = 0;

    /** Cache for {@link #filteredItemsCached} - see the class doc's per-frame-cost note. */
    private static String cachedQuery = null;
    private static List<SkyblockItemEntry> cachedSourceRef = null;
    private static List<SkyblockItemEntry> cachedFiltered = List.of();

    /** Real screen bounds of the shared search header from the most recent render, used by the click
     *  handler to focus it - see {@link InventorySearchFeature#setListening}. */
    private static int[] lastHeaderBounds = null;

    private ItemBrowserFeature() {
    }

    private record PanelLayout(int screenX, int screenY, int screenW, int screenH, int rows, int columns, float scale) {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(ItemBrowserFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        scrollOffset = 0;
        lastHeaderBounds = null;
        ItemCraftView.close();

        ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, scrollX, scrollY) -> {
            if (ItemCraftView.isOpen()) {
                // Never let a scroll pass through the craft/obtain popup onto the grid behind it.
                return false;
            }
            ItemBrowserConfig cfg = ItemBrowserConfig.getInstance();
            if (!cfg.isEnabled()) {
                return true;
            }
            PanelLayout layout = layout(containerScreen, cfg);
            if (!inside(layout.screenX(), layout.screenY(), layout.screenW(), layout.screenH(), mouseX, mouseY)) {
                return true;
            }
            int perPage = layout.columns() * layout.rows();
            int maxOffset = Math.max(0, filteredItemsCached().size() - perPage);
            int step = cfg.isHorizontal() ? layout.columns() : layout.rows();
            int direction = scrollY > 0 ? -1 : 1;
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + direction * step));
            return false;
        });

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            if (ItemCraftView.isOpen()) {
                ItemCraftView.handleClick(event.x(), event.y(), event.button());
                return false;
            }
            ItemBrowserConfig cfg = ItemBrowserConfig.getInstance();
            if (!cfg.isEnabled() || event.button() != 0) {
                return true;
            }
            PanelLayout layout = layout(containerScreen, cfg);
            if (!inside(layout.screenX(), layout.screenY(), layout.screenW(), layout.screenH(), event.x(), event.y())) {
                return true;
            }
            double localY = (event.y() - layout.screenY()) / layout.scale();
            if (localY < HEADER_HEIGHT) {
                InventorySearchFeature.setListening(true);
                return false;
            }
            SkyblockItemEntry clicked = entryAt(layout, cfg, event.x(), event.y());
            if (clicked != null) {
                ItemCraftView.open(clicked);
            }
            // Either way the click landed inside the panel - never let it fall through onto a real slot
            // underneath.
            return false;
        });

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            if (ItemCraftView.isOpen() && event.key() == InputConstants.KEY_ESCAPE) {
                ItemCraftView.close();
                return false;
            }
            return true;
        });

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
                render(containerScreen, graphics, mouseX, mouseY));
    }

    /** Re-filters the real item catalog only when the query text or the catalog reference itself
     *  changed (a background {@link SkyblockItemRepository#refreshAsync} replaces that reference) -
     *  per the brief's "watch per-frame cost" note, this used to re-run a full stream filter over the
     *  ~5,655-item catalog on every single frame regardless of whether the query had moved at all. */
    private static List<SkyblockItemEntry> filteredItemsCached() {
        String query = InventorySearchFeature.getQuery();
        List<SkyblockItemEntry> all = SkyblockItemRepository.getItems();
        if (query.equals(cachedQuery) && all == cachedSourceRef) {
            return cachedFiltered;
        }
        cachedQuery = query;
        cachedSourceRef = all;
        if (query.isBlank()) {
            cachedFiltered = all;
        } else {
            String needle = query.toLowerCase(Locale.ROOT);
            cachedFiltered = all.stream()
                    .filter(item -> item.name().toLowerCase(Locale.ROOT).contains(needle))
                    .collect(Collectors.toList());
        }
        return cachedFiltered;
    }

    /** Pure function of the screen size and current settings (no dependency on the previous frame), so
     *  the scroll/click handlers can call it directly instead of reading state cached from render. */
    private static PanelLayout layout(AbstractContainerScreen<?> screen, ItemBrowserConfig cfg) {
        float scale = cfg.getScale();
        int columns = cfg.getColumns();
        int availableScreenHeight = Math.max(CELL_SIZE, screen.height - TOP_MARGIN - BOTTOM_MARGIN);
        int localAvailableHeight = (int) (availableScreenHeight / scale);
        int rows = Math.max(1, (localAvailableHeight - HEADER_HEIGHT - PADDING * 2) / CELL_SIZE);
        int localWidth = columns * CELL_SIZE + PADDING * 2;
        int localHeight = HEADER_HEIGHT + rows * CELL_SIZE + PADDING * 2;
        int screenW = Math.round(localWidth * scale);
        int screenH = Math.round(localHeight * scale);
        int screenX = switch (cfg.getAlign()) {
            case LEFT -> SIDE_MARGIN;
            case CENTER -> (screen.width - screenW) / 2;
            case RIGHT -> screen.width - screenW - SIDE_MARGIN;
        };
        return new PanelLayout(screenX, TOP_MARGIN, screenW, screenH, rows, columns, scale);
    }

    /** Maps a real cell (row, col) to the item index within the current page, honoring
     *  {@link ItemBrowserConfig#isHorizontal()} - see its own field doc for what row-major vs
     *  column-major actually looks like on screen. */
    private static int cellToLocalIndex(PanelLayout layout, ItemBrowserConfig cfg, int col, int row) {
        return cfg.isHorizontal() ? row * layout.columns() + col : col * layout.rows() + row;
    }

    private static SkyblockItemEntry entryAt(PanelLayout layout, ItemBrowserConfig cfg, double mouseX, double mouseY) {
        double localX = (mouseX - layout.screenX()) / layout.scale();
        double localY = (mouseY - layout.screenY()) / layout.scale();
        double gx = localX - PADDING;
        double gy = localY - HEADER_HEIGHT;
        if (gx < 0 || gy < 0) {
            return null;
        }
        int col = (int) (gx / CELL_SIZE);
        int row = (int) (gy / CELL_SIZE);
        if (col >= layout.columns() || row >= layout.rows()) {
            return null;
        }
        int itemIndex = scrollOffset + cellToLocalIndex(layout, cfg, col, row);
        List<SkyblockItemEntry> filtered = filteredItemsCached();
        return itemIndex >= 0 && itemIndex < filtered.size() ? filtered.get(itemIndex) : null;
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ItemBrowserConfig cfg = ItemBrowserConfig.getInstance();
        if (!cfg.isEnabled()) {
            if (ItemCraftView.isOpen()) {
                ItemCraftView.close();
            }
            return;
        }

        PanelLayout layout = layout(screen, cfg);
        List<SkyblockItemEntry> filtered = filteredItemsCached();
        int perPage = layout.columns() * layout.rows();
        int maxOffset = Math.max(0, filtered.size() - perPage);
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }

        double localMouseX = (mouseX - layout.screenX()) / layout.scale();
        double localMouseY = (mouseY - layout.screenY()) / layout.scale();

        int localWidth = layout.columns() * CELL_SIZE + PADDING * 2;
        int localHeight = HEADER_HEIGHT + layout.rows() * CELL_SIZE + PADDING * 2;

        graphics.pose().pushMatrix();
        SkyblockItemEntry hoveredEntry = null;
        try {
            graphics.pose().translate(layout.screenX(), layout.screenY());
            graphics.pose().scale(layout.scale(), layout.scale());

            graphics.fill(0, 0, localWidth, localHeight, 0xCC0D0D0D);
            graphics.outline(0, 0, localWidth, localHeight, 0xFF553311);
            graphics.fill(1, 1, localWidth - 1, HEADER_HEIGHT - 1,
                    InventorySearchFeature.isListening() ? 0x33CC6600 : 0x33000000);
            String query = InventorySearchFeature.getQuery();
            String display = query.isEmpty() ? "§8Ctrl+F or click to search..." : query;
            graphics.text(Minecraft.getInstance().font, display, PADDING, 6, 0xFFFFFFFF, false);

            int gridX = PADDING;
            int gridY = HEADER_HEIGHT;

            for (int i = 0; i < perPage; i++) {
                int itemIndex = scrollOffset + i;
                if (itemIndex >= filtered.size()) {
                    continue;
                }
                int col = cfg.isHorizontal() ? i % layout.columns() : i / layout.rows();
                int row = cfg.isHorizontal() ? i / layout.columns() : i % layout.rows();
                if (col >= layout.columns() || row >= layout.rows()) {
                    continue;
                }
                int cellX = gridX + col * CELL_SIZE;
                int cellY = gridY + row * CELL_SIZE;

                boolean hovered = localMouseX >= cellX && localMouseX < cellX + CELL_SIZE
                        && localMouseY >= cellY && localMouseY < cellY + CELL_SIZE;
                if (hovered) {
                    graphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, 0x80CC6600);
                }

                SkyblockItemEntry entry = filtered.get(itemIndex);
                ItemStack stack = SkyblockItemStackFactory.build(entry);
                graphics.item(stack, cellX + 1, cellY + 1);
                if (hovered) {
                    hoveredEntry = entry;
                }
            }

            if (filtered.isEmpty()) {
                graphics.text(Minecraft.getInstance().font, "§7No matches", gridX, gridY + 4, 0xFFAAAAAA, false);
            }
        } finally {
            graphics.pose().popMatrix();
        }

        lastHeaderBounds = new int[]{layout.screenX(), layout.screenY(), layout.screenW(),
                Math.round(HEADER_HEIGHT * layout.scale())};

        // killer560 (2026-09-21): "if i hover an item it tells me its lore" - real tooltip lines are
        // drawn in real screen space (setTooltipForNextFrame ignores the pose transform above, same as
        // every other overlay panel in this mod that does this), so this runs after popMatrix using the
        // real, untransformed mouseX/mouseY.
        if (hoveredEntry != null) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, buildTooltipLines(hoveredEntry),
                    Optional.empty(), mouseX, mouseY);
        }

        if (ItemCraftView.isOpen()) {
            ItemCraftView.render(graphics, screen.width, screen.height, mouseX, mouseY);
        }
    }

    /** Real hover lore - killer560 (2026-09-21): "if i hover an item it tells me its lore". Built only
     *  from real fields {@link SkyblockItemRepository} actually has (name/tier/category/description/NPC
     *  sell price) - see {@link SkyblockItemEntry}'s class doc for why this isn't full vanilla-style
     *  stat lore (Hypixel's public resource doesn't expose that). */
    private static List<Component> buildTooltipLines(SkyblockItemEntry entry) {
        Font font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(SkyblockItemStackFactory.tierColorCode(entry.tier()) + entry.name()));
        if (entry.tier() != null || entry.category() != null) {
            String line = "§7" + (entry.tier() != null ? entry.tier() : "")
                    + (entry.tier() != null && entry.category() != null ? " - " : "")
                    + (entry.category() != null ? SkyblockItemStackFactory.niceCategory(entry.category()) : "");
            lines.add(Component.literal(line));
        }
        if (entry.description() != null) {
            for (String wrapped : wrap(font, entry.description(), 200)) {
                lines.add(Component.literal("§7" + wrapped));
            }
        }
        if (entry.npcSellPrice() != null) {
            lines.add(Component.literal("§7NPC Sell: §6" + Math.round(entry.npcSellPrice()) + " coins"));
        }
        lines.add(Component.literal("§8Left-click for crafting/obtain info"));
        return lines;
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

    private static boolean inside(int x, int y, int w, int h, double px, double py) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }
}

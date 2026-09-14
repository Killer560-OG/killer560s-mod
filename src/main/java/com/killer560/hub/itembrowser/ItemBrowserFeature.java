package com.killer560.hub.itembrowser;

import com.killer560.hub.inventorysearch.InventorySearchFeature;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Real Skyblock item browser panel, referencing the classic Not Enough Updates (NEU) 1.8.9 mod's own
 * real item panel per killer560's own description ("I essentially want neu's search feature... best
 * reference for you"). Draws a persistent, searchable grid of every real Skyblock item on the right side
 * of any real {@link AbstractContainerScreen}, sharing the exact same search query as
 * {@link InventorySearchFeature} - typing filters this panel's real item grid live, and the same real
 * Ctrl+F toggle both features already share starts/stops typing (see
 * {@link InventorySearchFeature#getQuery()}/{@code #isListening()} - one shared search box, two
 * consumers, matching killer560's own "have searching in that bar effectively search through the not
 * enough items... then if I type something and do ctrl f it'll highlight items in my inventory").
 * <p>
 * Item data and icons are real, not placeholders - see {@link SkyblockItemRepository} (Hypixel's own
 * public, keyless {@code /v2/resources/skyblock/items} resource) and
 * {@link SkyblockItemStackFactory} (renders each item's own real Hypixel visual via the real
 * {@code minecraft:item_model}/{@code minecraft:profile} components, not a generic vanilla icon).
 * Deliberately does NOT reproduce NEU's own recipe-tree/price-lookup screens (a real, much larger
 * separate feature) - this first version is the real search-and-browse-by-icon half only, matching what
 * killer560 actually described.
 */
public final class ItemBrowserFeature {

    private static final int CELL_SIZE = 18;
    private static final int HEADER_HEIGHT = 20;
    private static final int PADDING = 4;

    private static int scrollOffset = 0;
    private static ItemStack hoveredStack = null;

    private ItemBrowserFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(ItemBrowserFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        scrollOffset = 0;

        ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, scrollX, scrollY) -> {
            ItemBrowserConfig cfg = ItemBrowserConfig.getInstance();
            if (!cfg.isEnabled() || !isOverPanel(containerScreen, cfg, mouseX, mouseY)) {
                return true;
            }
            int maxOffset = Math.max(0, filteredCount(cfg) - cfg.getColumns() * cfg.getRows());
            int rowsToScroll = scrollY > 0 ? -1 : 1;
            scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + rowsToScroll * cfg.getColumns()));
            return false;
        });

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
                render(containerScreen, graphics, mouseX, mouseY));
    }

    private static int filteredCount(ItemBrowserConfig cfg) {
        return filteredItems().size();
    }

    private static List<SkyblockItemEntry> filteredItems() {
        String query = InventorySearchFeature.getQuery();
        List<SkyblockItemEntry> all = SkyblockItemRepository.getItems();
        if (query.isBlank()) {
            return all;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(item -> item.name().toLowerCase(Locale.ROOT).contains(needle))
                .collect(Collectors.toList());
    }

    private static int panelX(AbstractContainerScreen<?> screen, ItemBrowserConfig cfg) {
        int panelWidth = cfg.getColumns() * CELL_SIZE + PADDING * 2;
        return screen.width - panelWidth - 10;
    }

    private static boolean isOverPanel(AbstractContainerScreen<?> screen, ItemBrowserConfig cfg, double mouseX, double mouseY) {
        int panelWidth = cfg.getColumns() * CELL_SIZE + PADDING * 2;
        int panelHeight = HEADER_HEIGHT + cfg.getRows() * CELL_SIZE + PADDING * 2;
        int x = panelX(screen, cfg);
        int y = 20;
        return mouseX >= x && mouseX < x + panelWidth && mouseY >= y && mouseY < y + panelHeight;
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        ItemBrowserConfig cfg = ItemBrowserConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }

        int columns = cfg.getColumns();
        int rows = cfg.getRows();
        int panelWidth = columns * CELL_SIZE + PADDING * 2;
        int gridHeight = rows * CELL_SIZE;
        int panelHeight = HEADER_HEIGHT + gridHeight + PADDING * 2;
        int x = panelX(screen, cfg);
        int y = 20;

        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xCC0D0D0D);
        graphics.outline(x, y, panelWidth, panelHeight, 0xFF553311);
        graphics.fill(x + 1, y + 1, x + panelWidth - 1, y + HEADER_HEIGHT - 1,
                InventorySearchFeature.isListening() ? 0x33CC6600 : 0x33000000);
        String query = InventorySearchFeature.getQuery();
        String display = query.isEmpty() ? "§8Ctrl+F to search..." : query;
        graphics.text(Minecraft.getInstance().font, display, x + PADDING, y + 6, 0xFFFFFFFF, false);

        List<SkyblockItemEntry> filtered = filteredItems();
        int perPage = columns * rows;
        int maxOffset = Math.max(0, filtered.size() - perPage);
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }

        hoveredStack = null;
        int gridX = x + PADDING;
        int gridY = y + HEADER_HEIGHT;

        for (int i = 0; i < perPage; i++) {
            int itemIndex = scrollOffset + i;
            if (itemIndex >= filtered.size()) {
                break;
            }
            int col = i % columns;
            int row = i / columns;
            int cellX = gridX + col * CELL_SIZE;
            int cellY = gridY + row * CELL_SIZE;

            boolean hovered = mouseX >= cellX && mouseX < cellX + CELL_SIZE
                    && mouseY >= cellY && mouseY < cellY + CELL_SIZE;
            if (hovered) {
                graphics.fill(cellX, cellY, cellX + CELL_SIZE, cellY + CELL_SIZE, 0x80CC6600);
            }

            ItemStack stack = SkyblockItemStackFactory.build(filtered.get(itemIndex));
            graphics.item(stack, cellX + 1, cellY + 1);
            if (hovered) {
                hoveredStack = stack;
            }
        }

        if (filtered.isEmpty()) {
            graphics.text(Minecraft.getInstance().font, "§7No matches", gridX, gridY + 4, 0xFFAAAAAA, false);
        }

        if (hoveredStack != null) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, hoveredStack, mouseX, mouseY);
        }
    }
}

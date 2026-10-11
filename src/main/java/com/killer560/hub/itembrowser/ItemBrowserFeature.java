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
 * <p>
 * 2026-10-11 (killer560): the panel has no search box of its own any more - "Do not move my search bar above the
 * menu the search bar should stay in the bottom middle of my screen like normal" - it filters by Inventory Search's
 * bar at the bottom middle. Hovering an item shows its real tooltip (NEU repo lore, {@link NeuItemData}); the panel is
 * drawn inside the container's own render pass ({@code ItemBrowserRenderMixin}, {@code ItemBrowserRecipeBookRenderMixin})
 * so its tooltip is set before the frame's deferred tooltip pass, the way the Storage Overlay does it. Every minion's
 * tiers collapse into one cell that opens a tier picker ({@link ItemCraftView#openTiers}). Sorting defaults to value
 * high to low; the old "Default" (catalog order) sort is gone.
 * <p>
 * Watches its own per-frame cost per the brief: {@link #filteredItemsCached} only re-filters the ~5,655
 * item catalog when the query (or the catalog reference itself, after a background refresh) actually
 * changes, instead of every single frame regardless of whether anything moved.
 */
public final class ItemBrowserFeature {

    private static final int CELL_SIZE = 18;
    /** Space above the grid. The panel's own search header was removed 2026-10-11 (see the class doc). */
    private static final int HEADER_HEIGHT = 4;
    /** killer560 (2026-09-27): "make a button at the bottom of it in my inventory to sort by things like
     *  value, a-z, ect." - the sort button's own row, reserved below the item grid the same way
     *  HEADER_HEIGHT is reserved above it. */
    private static final int FOOTER_HEIGHT = 20;
    private static final int PADDING = 4;
    /** Real screen-space margins the panel's full-height box is measured between. */
    private static final int TOP_MARGIN = 20;
    private static final int BOTTOM_MARGIN = 10;
    private static final int SIDE_MARGIN = 10;

    private static int scrollOffset = 0;

    /** Cache for {@link #filteredItemsCached} - see the class doc's per-frame-cost note. */
    private static String cachedQuery = null;
    private static List<SkyblockItemEntry> cachedSourceRef = null;
    private static ItemBrowserConfig.SortMode cachedSortMode = null;
    private static List<SkyblockItemEntry> cachedFiltered = List.of();

    /** Minion tier id: {@code BLAZE_GENERATOR_7} -> base {@code BLAZE}, tier 7. */
    private static final java.util.regex.Pattern MINION_ID = java.util.regex.Pattern.compile("^([A-Z0-9_]{1,40})_GENERATOR_(\\d{1,2})$");
    /** Every catalog minion's tiers, lowest first, keyed by base id - rebuilt with the catalog reference. */
    private static java.util.Map<String, List<SkyblockItemEntry>> minionGroups = java.util.Map.of();
    private static List<SkyblockItemEntry> minionGroupsSource = null;

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
            // The sort button (right at the panel's bottom) responds to both buttons - left cycles the
            // mode forward, right goes back one, same repo-wide rule every other cycling button follows
            // (see SettingsButtonWidget). Everything else in the panel stays left-click only, unchanged.
            if (!cfg.isEnabled() || (event.button() != 0 && event.button() != 1)) {
                return true;
            }
            PanelLayout layout = layout(containerScreen, cfg);
            if (!inside(layout.screenX(), layout.screenY(), layout.screenW(), layout.screenH(), event.x(), event.y())) {
                return true;
            }
            double localY = (event.y() - layout.screenY()) / layout.scale();
            if (localY >= footerLocalY(layout)) {
                cfg.setSortMode(event.button() == 1 ? cfg.getSortMode().previous() : cfg.getSortMode().next());
                cfg.save();
                return false;
            }
            if (event.button() != 0) {
                return true; // right-click anywhere else in the panel is a no-op, same as before
            }
            SkyblockItemEntry clicked = entryAt(layout, cfg, event.x(), event.y());
            if (clicked != null) {
                List<SkyblockItemEntry> tiers = minionTiers(clicked);
                if (tiers.size() > 1) {
                    ItemCraftView.openTiers(tiers);
                } else {
                    ItemCraftView.open(clicked);
                }
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

    }

    /** Called from {@code ItemBrowserRenderMixin} at the TAIL of the container's {@code extractRenderState}, so the
     *  panel's {@code setTooltipForNextFrame} lands in this frame's tooltip pass. */
    public static void renderFromMixin(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        render(screen, graphics, mouseX, mouseY);
    }

    /** @return true when the vanilla slot tooltip should be skipped this frame (the craft popup covers the menu). */
    public static boolean suppressVanillaTooltip() {
        return ItemCraftView.isOpen() && ItemBrowserConfig.getInstance().isEnabled();
    }

    /** Re-filters (and re-sorts) the real item catalog only when the query text, the catalog reference
     *  itself (a background {@link SkyblockItemRepository#refreshAsync} replaces that reference), or the
     *  sort mode actually changed - per the brief's "watch per-frame cost" note, this used to re-run a
     *  full stream filter over the ~5,655-item catalog on every single frame regardless of whether
     *  anything had moved at all. */
    private static List<SkyblockItemEntry> filteredItemsCached() {
        String query = InventorySearchFeature.getQuery();
        List<SkyblockItemEntry> all = SkyblockItemRepository.getItems();
        ItemBrowserConfig.SortMode sortMode = ItemBrowserConfig.getInstance().getSortMode();
        if (query.equals(cachedQuery) && all == cachedSourceRef && sortMode == cachedSortMode) {
            return cachedFiltered;
        }
        cachedQuery = query;
        cachedSourceRef = all;
        cachedSortMode = sortMode;
        List<SkyblockItemEntry> filtered;
        if (query.isBlank() || com.killer560.hub.inventorysearch.SearchCalculator.resultFor(query) != null) {
            filtered = new ArrayList<>(all);
        } else {
            String needle = query.toLowerCase(Locale.ROOT);
            filtered = all.stream()
                    .filter(item -> item.name().toLowerCase(Locale.ROOT).contains(needle))
                    .collect(Collectors.toList());
        }
        filtered = collapseMinions(filtered, all);
        sort(filtered, sortMode);
        cachedFiltered = filtered;
        return cachedFiltered;
    }

    /** One cell per minion: every tier that passed the filter is replaced by the minion's lowest tier, once.
     *  Public for the testkit. */
    public static List<SkyblockItemEntry> collapseMinions(List<SkyblockItemEntry> filtered, List<SkyblockItemEntry> catalog) {
        ensureMinionGroups(catalog);
        List<SkyblockItemEntry> out = new ArrayList<>(filtered.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SkyblockItemEntry e : filtered) {
            java.util.regex.Matcher m = MINION_ID.matcher(e.id());
            if (!m.matches()) {
                out.add(e);
                continue;
            }
            List<SkyblockItemEntry> group = minionGroups.get(m.group(1));
            if (group == null || group.size() < 2) {
                out.add(e);
            } else if (seen.add(m.group(1))) {
                out.add(group.getFirst());
            }
        }
        return out;
    }

    private static void ensureMinionGroups(List<SkyblockItemEntry> catalog) {
        if (catalog == minionGroupsSource) {
            return;
        }
        java.util.Map<String, List<SkyblockItemEntry>> groups = new java.util.HashMap<>();
        for (SkyblockItemEntry e : catalog) {
            java.util.regex.Matcher m = MINION_ID.matcher(e.id());
            if (m.matches()) {
                groups.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(e);
            }
        }
        for (List<SkyblockItemEntry> g : groups.values()) {
            g.sort(java.util.Comparator.comparingInt(ItemBrowserFeature::minionTier));
        }
        minionGroups = groups;
        minionGroupsSource = catalog;
    }

    /** @return every tier of {@code entry}'s minion, lowest first, or just {@code entry} when it is not a minion. */
    public static List<SkyblockItemEntry> minionTiers(SkyblockItemEntry entry) {
        java.util.regex.Matcher m = MINION_ID.matcher(entry.id());
        if (!m.matches()) {
            return List.of(entry);
        }
        ensureMinionGroups(SkyblockItemRepository.getItems());
        List<SkyblockItemEntry> group = minionGroups.get(m.group(1));
        return group == null || group.isEmpty() ? List.of(entry) : group;
    }

    static int minionTier(SkyblockItemEntry entry) {
        java.util.regex.Matcher m = MINION_ID.matcher(entry.id());
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }

    /** "Blaze Minion VII" -> "Blaze Minion". */
    public static String minionBaseName(SkyblockItemEntry entry) {
        return entry.name().replaceFirst("\\s+[IVXLC]{1,8}$", "");
    }

    /** "Blaze Minion VII" -> "VII"; falls back to the id's tier number. */
    public static String minionTierLabel(SkyblockItemEntry entry) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s([IVXLC]{1,8})$").matcher(entry.name());
        return m.find() ? m.group(1) : String.valueOf(minionTier(entry));
    }

    /** killer560 (2026-09-27): "make a button... to sort by things like value, a-z, ect." Value comes
     *  from {@link SkyblockItemValue} (same source the tooltip setting below uses) - an item with no
     *  known price always sorts to the end of a value sort, in either direction, rather than landing at
     *  an arbitrary spot among the priced ones. */
    private static void sort(List<SkyblockItemEntry> filtered, ItemBrowserConfig.SortMode sortMode) {
        java.util.Comparator<SkyblockItemEntry> byNameAz =
                java.util.Comparator.comparing((SkyblockItemEntry e) -> e.name().toLowerCase(Locale.ROOT));
        // Built separately (not one comparator plus .reversed()) so an unpriced item stays LAST in BOTH
        // directions - reversing a nullsLast comparator whole would flip it to sort first instead.
        java.util.Comparator<SkyblockItemEntry> byValueHighLow = java.util.Comparator.comparing(
                (SkyblockItemEntry e) -> SkyblockItemValue.getValue(e.id()),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()));
        java.util.Comparator<SkyblockItemEntry> byValueLowHigh = java.util.Comparator.comparing(
                (SkyblockItemEntry e) -> SkyblockItemValue.getValue(e.id()),
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        switch (sortMode) {
            case AZ -> filtered.sort(byNameAz);
            case ZA -> filtered.sort(byNameAz.reversed());
            case VALUE_HIGH_LOW -> filtered.sort(byValueHighLow);
            case VALUE_LOW_HIGH -> filtered.sort(byValueLowHigh);
        }
    }

    /** Pure function of the screen size and current settings (no dependency on the previous frame), so
     *  the scroll/click handlers can call it directly instead of reading state cached from render. */
    private static PanelLayout layout(AbstractContainerScreen<?> screen, ItemBrowserConfig cfg) {
        float scale = cfg.getScale();
        int columns = cfg.getColumns();
        int availableScreenHeight = Math.max(CELL_SIZE, screen.height - TOP_MARGIN - BOTTOM_MARGIN);
        int localAvailableHeight = (int) (availableScreenHeight / scale);
        int rows = Math.max(1, (localAvailableHeight - HEADER_HEIGHT - FOOTER_HEIGHT - PADDING * 2) / CELL_SIZE);
        int localWidth = columns * CELL_SIZE + PADDING * 2;
        int localHeight = HEADER_HEIGHT + rows * CELL_SIZE + PADDING * 2 + FOOTER_HEIGHT;
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

    /** Local Y where the sort button's footer strip starts - right after the item grid, mirroring how
     *  {@link #HEADER_HEIGHT} reserves the search header above it. */
    private static int footerLocalY(PanelLayout layout) {
        return HEADER_HEIGHT + layout.rows() * CELL_SIZE + PADDING * 2;
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
        if (com.killer560.hub.bazaar.BazaarReskin.isHiding(screen)
                || com.killer560.hub.auction.ah.AhReskin.isHiding(screen)) {
            return; // a reskinned Bazaar or Auction House menu covers the chest; nothing of this belongs on top of it
        }
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
        int localHeight = HEADER_HEIGHT + layout.rows() * CELL_SIZE + PADDING * 2 + FOOTER_HEIGHT;

        graphics.pose().pushMatrix();
        SkyblockItemEntry hoveredEntry = null;
        try {
            graphics.pose().translate(layout.screenX(), layout.screenY());
            graphics.pose().scale(layout.scale(), layout.scale());

            graphics.fill(0, 0, localWidth, localHeight, 0xCC0D0D0D);
            graphics.outline(0, 0, localWidth, localHeight, 0xFF553311);

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
                if (minionTiers(entry).size() > 1) {
                    // A stack of every tier: a small orange corner mark, like a bundle.
                    graphics.fill(cellX + CELL_SIZE - 5, cellY + 1, cellX + CELL_SIZE - 1, cellY + 5, 0xFFCC6600);
                }
                if (hovered) {
                    hoveredEntry = entry;
                }
            }

            if (filtered.isEmpty()) {
                graphics.text(Minecraft.getInstance().font, "§7No matches", gridX, gridY + 4, 0xFFAAAAAA, false);
            }

            // killer560 (2026-09-27): "make a button at the bottom of it... to sort by things like value,
            // a-z, ect." Same dark/amber theme as every other control - left-click cycles the mode
            // forward, right-click back (see the click handler above).
            int footerY = footerLocalY(layout);
            boolean footerHovered = localMouseY >= footerY && localMouseY < footerY + FOOTER_HEIGHT
                    && localMouseX >= 0 && localMouseX < localWidth;
            graphics.fill(1, footerY, localWidth - 1, footerY + FOOTER_HEIGHT - 1, footerHovered ? 0x33CC6600 : 0x33000000);
            graphics.centeredText(Minecraft.getInstance().font, "Sort: " + cfg.getSortMode().label(),
                    localWidth / 2, footerY + (FOOTER_HEIGHT - 8) / 2, 0xFFFFFFFF);
        } finally {
            graphics.pose().popMatrix();
        }

        // killer560 (2026-09-21): "if i hover an item it tells me its lore" - real tooltip lines are
        // drawn in real screen space (setTooltipForNextFrame ignores the pose transform above, same as
        // every other overlay panel in this mod that does this), so this runs after popMatrix using the
        // real, untransformed mouseX/mouseY.
        if (hoveredEntry != null && !ItemCraftView.isOpen()) {
            graphics.setTooltipForNextFrame(Minecraft.getInstance().font, buildTooltipLines(hoveredEntry),
                    Optional.empty(), mouseX, mouseY);
        }

        if (ItemCraftView.isOpen()) {
            ItemCraftView.render(graphics, screen.width, screen.height, mouseX, mouseY);
        }
    }

    /** The item's tooltip: its real lore from the NEU item repo once that has arrived (killer560, 2026-10-11:
     *  "make it so hovering something there shows its tooltip"), else what Hypixel's catalog has (name, tier,
     *  category, description, NPC sell price). Ends with the value and a hint line. Public for the craft view. */
    public static List<Component> buildTooltipLines(SkyblockItemEntry entry) {
        Font font = Minecraft.getInstance().font;
        List<Component> lines = new ArrayList<>();
        List<SkyblockItemEntry> tiers = minionTiers(entry);
        boolean stack = tiers.size() > 1 && ItemCraftView.openTierList() == null;
        NeuItemData.Info neu = NeuItemData.get(entry.id());
        if (stack) {
            lines.add(Component.literal(SkyblockItemStackFactory.tierColorCode(entry.tier()) + minionBaseName(entry)));
            lines.add(Component.literal("\u00a77Tiers " + minionTierLabel(tiers.getFirst()) + "-" + minionTierLabel(tiers.getLast())));
        } else if (neu != null && (neu.displayName() != null || !neu.lore().isEmpty())) {
            lines.add(Component.literal(neu.displayName() != null ? neu.displayName()
                    : SkyblockItemStackFactory.tierColorCode(entry.tier()) + entry.name()));
            for (String l : neu.lore()) {
                lines.add(Component.literal(l));
            }
        } else {
            lines.add(Component.literal(SkyblockItemStackFactory.tierColorCode(entry.tier()) + entry.name()));
            if (entry.tier() != null || entry.category() != null) {
                String line = "\u00a77" + (entry.tier() != null ? entry.tier() : "")
                        + (entry.tier() != null && entry.category() != null ? " - " : "")
                        + (entry.category() != null ? SkyblockItemStackFactory.niceCategory(entry.category()) : "");
                lines.add(Component.literal(line));
            }
            if (entry.description() != null) {
                for (String wrapped : wrap(font, entry.description(), 200)) {
                    lines.add(Component.literal("\u00a77" + wrapped));
                }
            }
            if (entry.npcSellPrice() != null) {
                lines.add(Component.literal("\u00a77NPC Sell: \u00a76" + Math.round(entry.npcSellPrice()) + " coins"));
            }
        }
        Long value = SkyblockItemValue.getValue(entry.id());
        if (value != null && !stack) {
            lines.add(Component.literal("\u00a77Value: \u00a76" + SkyblockItemValue.formatCoins(value)));
        }
        lines.add(Component.literal(stack ? "\u00a78Left-click to see every tier" : "\u00a78Left-click for crafting/obtain info"));
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

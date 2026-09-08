package com.killer560.hub.storageoverlay;

import com.killer560.hub.experiments.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** NoammAddons-style Storage Overlay, per killer560's explicit "really similar to noamm's" request
 *  (2026-09-08): whenever an Ender Chest page or a Backpack is actually opened, its contents are
 *  logged (captured + persisted), and a 3-column grid of every known storage for the current
 *  account/SkyBlock profile is drawn alongside the real menu, updating live as you browse. Ender
 *  Chest and Backpack title patterns are ported directly from NoammAddons' own {@code StorageMenu.kt}
 *  (a real, already-working 26.1.2 mod local to this machine) rather than guessed. */
public final class StorageOverlayFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-storageoverlay");
    private static final String ELEMENT_ID = "storage_overlay";
    private static final int SLOT_SIZE = 18;
    private static final int PANEL_WIDTH = SLOT_SIZE * 9 + 4;
    private static final int PADDING = 6;

    /** Real Hypixel titles, ported from NoammAddons' {@code StorageMenu.kt} (confirmed working
     *  against a live 26.1.2 session, not guessed): "Ender Chest (3/9)" or "Ender Chest ✦ (3/9)",
     *  and "<Backpack Name> (Slot #5)" or "... ✦ (Slot #5)". */
    private static final Pattern ENDER_CHEST_TITLE = Pattern.compile("^Ender Chest (?:✦ )?\\(([1-9])/[1-9]\\)$");
    private static final Pattern BACKPACK_TITLE = Pattern.compile("^.+Backpack (?:✦ )?\\(Slot #([0-9]+)\\)$");

    /** The overview menu's exact real title, ported from {@code StorageMenu.kt} - a chest-style menu
     *  whose slots 9-17 hold one icon per Ender Chest page and 27-44 one icon per Backpack, letting
     *  every storage be discovered before it's ever actually opened. */
    private static final String OVERVIEW_TITLE = "Storage";

    /** Best-effort SkyBlock profile name, read off the sidebar scoreboard the same way
     *  {@link com.killer560.hub.rngmeter.LocationTracker} reads location - Hypixel's exact wording
     *  for the profile line hasn't been confirmed against a live session. Falls back to a fixed
     *  placeholder (never a wrong/guessed real name) so storages are still isolated together under
     *  one bucket per account rather than accidentally shared across profiles based on a bad parse. */
    private static final Pattern PROFILE_LINE = Pattern.compile("(?i)profile:\\s*(\\S+)");

    private StorageOverlayFeature() {
    }

    public static void register() {
        HudElementRegistry.register(new HudElement() {
            @Override
            public String id() {
                return ELEMENT_ID;
            }

            @Override
            public String displayName() {
                return "Storage Overlay";
            }

            // Per killer560's report (2026-09-08): the old default (pinned near the right edge)
            // looked "way off center." Centers the grid on screen instead, same spot the real
            // container GUI is already centered at by vanilla.
            @Override
            public int defaultX() {
                return (Minecraft.getInstance().getWindow().getGuiScaledWidth() - width()) / 2;
            }

            @Override
            public int defaultY() {
                return (Minecraft.getInstance().getWindow().getGuiScaledHeight() - height()) / 2;
            }

            @Override
            public int width() {
                return PANEL_WIDTH * 3 + PADDING * 2;
            }

            @Override
            public int height() {
                return 160;
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, int x, int y) {
                // HUD position editor preview - a real grid isn't necessarily open right now, so show
                // a label only. The real overlay only draws while a storage screen is actually open.
                graphics.text(Minecraft.getInstance().font, "§bStorage Overlay (shown when a storage is open)", x, y, 0xFFFFFFFF);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> onScreenOpen(screen));
    }

    /** Called from {@link ScreenEvents#AFTER_INIT} for every screen that opens. Real bug found and
     *  fixed (2026-09-08), per killer560's screenshot of an opened page showing a completely blank
     *  panel: capturing only once here, right when the screen is first constructed, hit the exact
     *  same timing problem {@code RngMeterOverlay} already had to work around - Hypixel's own item
     *  data hasn't synced from the server yet at this exact moment, so this could capture (and
     *  persist) an all-empty page. Now just does the FIRST attempt; {@link #onContainerScreenRender}
     *  re-attempts every frame and only actually re-persists when the scan changes, so it self-heals
     *  the moment real data arrives without hammering disk once it's stable. */
    private static void onScreenOpen(Screen screen) {
        try {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
                return;
            }
            if (!(containerScreen.getMenu() instanceof ChestMenu menu)) {
                return;
            }
            String title = containerScreen.getTitle().getString();
            if (title.equals(OVERVIEW_TITLE)) {
                scanOverview(menu);
                return;
            }
            String key = storageKeyForTitle(title);
            if (key == null) {
                return;
            }
            captureIfChanged(menu, key);
        } catch (Exception e) {
            LOGGER.error("Failed to log storage screen", e);
        }
    }

    /** Scans {@code menu}'s top (non-player) slots and persists them under {@code key} only if they
     *  actually differ from what's already cached (by item id + count) - cheap to call every frame,
     *  and self-heals a too-early capture (see {@link #onScreenOpen}) the moment real data arrives
     *  without writing to disk on every single frame once the scan has stabilized. */
    private static void captureIfChanged(ChestMenu menu, String key) {
        List<ItemStack> contents = new ArrayList<>();
        int containerSlotCount = Math.max(0, menu.slots.size() - 36);
        for (Slot slot : menu.slots) {
            if (slot.index >= containerSlotCount) {
                break;
            }
            ItemStack stack = slot.getItem();
            contents.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        StorageOverlayCache cache = StorageOverlayCache.getInstance();
        List<ItemStack> cached = cache.get(key);
        if (sameContents(cached, contents)) {
            return;
        }
        cache.put(key, contents);
        LOGGER.info("Logged storage \"{}\" ({} slots)", key, contents.size());
    }

    private static boolean sameContents(List<ItemStack> a, List<ItemStack> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            ItemStack sa = a.get(i);
            ItemStack sb = b.get(i);
            boolean emptyA = sa == null || sa.isEmpty();
            boolean emptyB = sb == null || sb.isEmpty();
            if (emptyA != emptyB) {
                return false;
            }
            if (emptyA) {
                continue;
            }
            if (sa.getCount() != sb.getCount() || !sa.getItem().equals(sb.getItem())) {
                return false;
            }
        }
        return true;
    }

    /** Reads the "Storage" overview menu's own per-page icons (slots 9-17 = Ender Chest pages 1-9,
     *  27-44 = Backpacks 1-18 - ported from {@code StoragePage.overview}) and marks/unmarks each as
     *  known-to-exist, per NoammAddons' own {@code saveOverview} and killer560's screenshots
     *  (2026-09-08) of the real tooltips: an owned-but-unopened page shows as a plain numbered purple
     *  pane (name doesn't matter, just non-empty), while a genuinely not-yet-purchased one shows a
     *  real "Locked Page" tooltip ("Unlock more Ender Chest pages in the community shop!"). Matching
     *  by name instead of NoammAddons' own item-id marker list, since the purple "owned" panes here
     *  didn't match any of their known ids - only the confirmed "Locked Page" text is excluded, so
     *  killer560 gets a dummy/clickable entry for everything he actually owns. Never overwrites a
     *  page that already has real logged contents. */
    private static void scanOverview(ChestMenu menu) {
        StorageOverlayCache cache = StorageOverlayCache.getInstance();
        for (Slot slot : menu.slots) {
            String key = overviewSlotToKey(slot.index);
            if (key == null) {
                continue;
            }
            ItemStack stack = slot.getItem();
            boolean locked = stack != null && !stack.isEmpty()
                    && stack.getHoverName().getString().toLowerCase(java.util.Locale.US).contains("locked");
            boolean owned = stack != null && !stack.isEmpty() && !locked;
            if (owned) {
                cache.markKnown(key);
            } else {
                cache.unmarkKnown(key);
            }
        }
    }

    private static String overviewSlotToKey(int slotIndex) {
        if (slotIndex >= 9 && slotIndex < 18) {
            return storageKey("enderchest_" + (slotIndex - 8));
        }
        if (slotIndex >= 27 && slotIndex < 45) {
            return storageKey("backpack_" + (slotIndex - 26));
        }
        return null;
    }

    /** @return the composite cache key for this screen title if it's a real Ender Chest page or
     *  Backpack, otherwise null. Public so the mixin (a different package) can use the same check
     *  for both "should I draw?" and "should a click here even be considered for opening a page?". */
    public static String storageKeyForTitle(String title) {
        Matcher chest = ENDER_CHEST_TITLE.matcher(title);
        if (chest.matches()) {
            return storageKey("enderchest_" + chest.group(1));
        }
        Matcher backpack = BACKPACK_TITLE.matcher(title);
        if (backpack.matches()) {
            return storageKey("backpack_" + backpack.group(1));
        }
        return null;
    }

    /** Whether the vanilla background/top-slot rendering should be hidden for this screen title - a
     *  tracked numbered page, OR (per killer560's "clean up that starter menu" request, 2026-09-08)
     *  the "Storage" overview screen itself, since the grid now shows there too and having both up
     *  looked cluttered. Public so the hide-mixins (a different package) can share this exact check. */
    public static boolean shouldHideVanilla(String title) {
        return storageKeyForTitle(title) != null || title.equals(OVERVIEW_TITLE);
    }

    /** Called from {@link com.killer560.hub.storageoverlay.mixin.StorageOverlayContainerMixin} on
     *  every container screen's own render pass - draws the 3-column grid of every known storage for
     *  the current account/profile if the currently open screen is itself a tracked storage. */
    public static void onContainerScreenRender(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        try {
            if (!StorageOverlayConfig.getInstance().isEnabled()) {
                return;
            }
            String title = screen.getTitle().getString();
            String activeKey = storageKeyForTitle(title);
            // Per killer560's report (2026-09-08): the grid should already show while browsing the
            // "Storage" overview screen (so a known-but-unopened page's "Click to load" placeholder is
            // actually visible there), not only once a specific numbered page is open. activeKey stays
            // null on the overview itself - nothing is "the active page" while just browsing it.
            boolean isOverview = title.equals(OVERVIEW_TITLE);
            if (activeKey == null && !isOverview) {
                return;
            }
            // Per killer560's request (2026-09-08): now that the vanilla background/top slots are
            // hidden here, outline the player's own inventory too so it doesn't look like it's just
            // floating with nothing to visually anchor it.
            drawPlayerInventoryOutline(screen, graphics);
            if (activeKey != null && screen.getMenu() instanceof ChestMenu activeMenu) {
                // Re-attempts the capture every frame (cheap - see captureIfChanged) so a too-early
                // scan self-heals the moment Hypixel's real item data actually syncs in, instead of
                // being stuck showing whatever onScreenOpen captured first.
                captureIfChanged(activeMenu, activeKey);
            }
            String prefix = accountProfilePrefix();
            List<String> keys = StorageOverlayCache.getInstance().knownKeysFor(prefix);
            if (keys.isEmpty()) {
                return;
            }
            // Ender Chest pages before Backpacks, then numerically within each - matches the natural
            // reading order NoammAddons' own StoragePage.compareTo gives. A null value here means
            // "known to exist (from the overview screen) but not opened/logged yet" - see scanOverview.
            Map<String, List<ItemStack>> ordered = new TreeMap<>(StorageOverlayFeature::compareStorageKeys);
            for (String key : keys) {
                ordered.put(key, StorageOverlayCache.getInstance().get(key));
            }

            HudElement element = HudElementRegistry.all().stream()
                    .filter(e -> e.id().equals(ELEMENT_ID)).findFirst().orElse(null);
            if (element == null) {
                return;
            }
            lastPos = HudElementRegistry.resolvePosition(element);
            lastScale = HudElementRegistry.resolveScale(element);
            graphics.pose().pushMatrix();
            graphics.pose().translate(lastPos[0], lastPos[1]);
            graphics.pose().scale(lastScale, lastScale);
            renderGrid(graphics, ordered, prefix, activeKey);
            graphics.pose().popMatrix();
        } catch (Exception e) {
            LOGGER.error("Failed to render Storage Overlay", e);
        }
    }

    private static int compareStorageKeys(String a, String b) {
        return a.compareTo(b);
    }

    /** Draws a border around the player's own 36-slot inventory area (unaffected by the vanilla-hide
     *  mixins - this only outlines it, doesn't touch its rendering), computed from the real on-screen
     *  bounds of those slots so it lines up exactly regardless of screen size/scale. */
    private static void drawPlayerInventoryOutline(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        if (!(screen.getMenu() instanceof ChestMenu menu)) {
            return;
        }
        int containerSlotCount = Math.max(0, menu.slots.size() - 36);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (Slot slot : menu.slots) {
            if (slot.index < containerSlotCount) {
                continue;
            }
            minX = Math.min(minX, slot.x);
            minY = Math.min(minY, slot.y);
            maxX = Math.max(maxX, slot.x);
            maxY = Math.max(maxY, slot.y);
        }
        if (minX == Integer.MAX_VALUE) {
            return;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        int left = accessor.killer560smod$getLeftPos();
        int top = accessor.killer560smod$getTopPos();
        int x0 = left + minX - 4;
        int y0 = top + minY - 4;
        int x1 = left + maxX + 16 + 4;
        int y1 = top + maxY + 16 + 4;
        int border = StorageOverlayConfig.getInstance().isDarkMode() ? 0xFF553311 : 0xFFAAAAAA;
        graphics.outline(x0, y0, x1 - x0, y1 - y0, border);
    }

    /** Screen-space position/scale from the most recent render, and each panel's LOCAL (pre
     *  translate/scale) bounds from that same render - both needed to hit-test a real mouse click
     *  against the grid in {@link #handleClick}. */
    private static int[] lastPos = null;
    private static float lastScale = 1.0f;
    private static final Map<String, int[]> lastPanelBounds = new LinkedHashMap<>();
    private static final int PLACEHOLDER_HEIGHT = 18;

    private static void renderGrid(GuiGraphicsExtractor graphics, Map<String, List<ItemStack>> storages,
                                    String prefix, String activeKey) {
        StorageOverlayConfig cfg = StorageOverlayConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int textColor = cfg.isDarkMode() ? 0xFFFFFFFF : 0xFF101010;
        int bg = cfg.isDarkMode() ? 0xCC101010 : 0xCCE8E8E8;
        int border = cfg.isDarkMode() ? 0xFF553311 : 0xFFAAAAAA;
        int activeBorder = 0xFFCC6600;

        lastPanelBounds.clear();
        int columns = 3;
        int col = 0;
        int rowX = 0;
        int rowY = 0;
        int rowTallest = 0;

        for (Map.Entry<String, List<ItemStack>> entry : storages.entrySet()) {
            String key = entry.getKey();
            List<ItemStack> contents = entry.getValue();
            boolean active = key.equals(activeKey);
            int panelX = rowX;
            int panelY = rowY;

            // Known-to-exist-but-never-opened (from the overview screen) - matches NoammAddons' own
            // "Name - Click to load" placeholder for a page with no data yet.
            if (contents == null) {
                int panelHeight = PLACEHOLDER_HEIGHT;
                graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, bg);
                graphics.outline(panelX, panelY, PANEL_WIDTH, panelHeight, border);
                graphics.text(font, displayLabel(key, prefix) + " §7- Click to load", panelX + 3, panelY + 5, textColor);
                lastPanelBounds.put(key, new int[]{panelX, panelY, PANEL_WIDTH, panelHeight});
                rowTallest = Math.max(rowTallest, panelHeight);
                col++;
                if (col >= columns) {
                    col = 0;
                    rowX = 0;
                    rowY += rowTallest + PADDING;
                    rowTallest = 0;
                } else {
                    rowX += PANEL_WIDTH + PADDING;
                }
                continue;
            }

            int rows = Math.max(1, (int) Math.ceil(contents.size() / 9.0));
            int panelHeight = rows * SLOT_SIZE + font.lineHeight + 6;

            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, bg);
            graphics.outline(panelX, panelY, PANEL_WIDTH, panelHeight, active ? activeBorder : border);
            String label = displayLabel(key, prefix);
            graphics.text(font, active ? "§6" + label : label, panelX + 3, panelY + 3, textColor);
            lastPanelBounds.put(key, new int[]{panelX, panelY, PANEL_WIDTH, panelHeight});

            int gridY = panelY + font.lineHeight + 4;
            // Per killer560's report (2026-09-08): draw the actual slot cells (matching NoammAddons'
            // own drawSlotGrid) so the item area reads as a real grid even for slots with nothing in
            // them right now, instead of just blank background.
            drawSlotCells(graphics, panelX + 2, gridY, rows, cfg.isDarkMode());
            for (int i = 0; i < contents.size(); i++) {
                ItemStack stack = contents.get(i);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                int slotX = panelX + (i % 9) * SLOT_SIZE + 2;
                int slotY = gridY + (i / 9) * SLOT_SIZE;
                graphics.item(stack, slotX, slotY);
                if (stack.getCount() > 1) {
                    graphics.pose().pushMatrix();
                    graphics.pose().translate(slotX, slotY);
                    graphics.pose().scale(0.6f, 0.6f);
                    graphics.text(font, String.valueOf(stack.getCount()), 10, 10, textColor);
                    graphics.pose().popMatrix();
                }
            }

            rowTallest = Math.max(rowTallest, panelHeight);
            col++;
            if (col >= columns) {
                col = 0;
                rowX = 0;
                rowY += rowTallest + PADDING;
                rowTallest = 0;
            } else {
                rowX += PANEL_WIDTH + PADDING;
            }
        }
    }

    /** Draws the 9-wide item-cell grid itself (dark cell background + thin dividing lines), ported
     *  from NoammAddons' own {@code drawSlotGrid} - makes an empty/still-loading page read as a real
     *  item grid instead of blank background. */
    private static void drawSlotCells(GuiGraphicsExtractor graphics, int x, int y, int rows, boolean darkMode) {
        int cellBg = darkMode ? 0xFF1E1E22 : 0xFFD8D8D8;
        int cellLine = darkMode ? 0xFF37373C : 0xFF999999;
        int w = 9 * SLOT_SIZE;
        int h = rows * SLOT_SIZE;
        graphics.fill(x, y, x + w, y + h, cellBg);
        for (int col = 0; col <= 9; col++) {
            int lx = x + col * SLOT_SIZE;
            graphics.fill(lx, y, lx + 1, y + h, cellLine);
        }
        for (int row = 0; row <= rows; row++) {
            int ly = y + row * SLOT_SIZE;
            graphics.fill(x, ly, x + w, ly + 1, cellLine);
        }
    }

    /** Called from {@link com.killer560.hub.storageoverlay.mixin.StorageOverlayContainerMixin}'s
     *  mouse-click hook - if the click landed on a non-active page's panel from the most recent
     *  render, sends the real client command to open it (ported from NoammAddons' own
     *  {@code StoragePage.open}) and reports the click as handled so the mixin can cancel it before
     *  it reaches the real (unrelated) menu underneath. */
    public static boolean handleClick(double mouseX, double mouseY, String activeKey) {
        if (lastPos == null || lastPanelBounds.isEmpty()) {
            return false;
        }
        double localX = (mouseX - lastPos[0]) / lastScale;
        double localY = (mouseY - lastPos[1]) / lastScale;
        for (Map.Entry<String, int[]> entry : lastPanelBounds.entrySet()) {
            String key = entry.getKey();
            if (key.equals(activeKey)) {
                continue;
            }
            int[] bounds = entry.getValue();
            if (localX < bounds[0] || localX > bounds[0] + bounds[2] || localY < bounds[1] || localY > bounds[1] + bounds[3]) {
                continue;
            }
            sendOpenCommand(key);
            return true;
        }
        return false;
    }

    private static void sendOpenCommand(String key) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.player.connection == null) {
            return;
        }
        String prefix = accountProfilePrefix();
        String local = key.substring(prefix.length() + 1);
        if (local.startsWith("enderchest_")) {
            client.player.connection.sendCommand("enderchest " + local.substring("enderchest_".length()));
        } else if (local.startsWith("backpack_")) {
            client.player.connection.sendCommand("backpack " + local.substring("backpack_".length()));
        }
    }

    /** killer560's custom name for this storage if he set one, otherwise {@link #defaultLabelFor}. */
    private static String displayLabel(String key, String prefix) {
        String custom = StorageOverlayConfig.getInstance().getCustomName(key);
        return custom != null ? custom : defaultLabelFor(key, prefix);
    }

    /** Readable default built from the key ("enderchest_3" -> "Ender Chest #3", "backpack_5" ->
     *  "Backpack #5") - public so the settings tab can pre-fill the same default into a rename field. */
    public static String defaultLabelFor(String key, String prefix) {
        String local = key.substring(prefix.length() + 1);
        if (local.startsWith("enderchest_")) {
            return "Ender Chest #" + local.substring("enderchest_".length());
        }
        if (local.startsWith("backpack_")) {
            return "Backpack #" + local.substring("backpack_".length());
        }
        return local;
    }

    /** Composite cache key: account UUID + SkyBlock profile + this storage's own local id - see the
     *  class doc on {@link StorageOverlayCache} for why embedding both directly in the key is what
     *  actually guarantees no cross-account/cross-profile leakage. */
    private static String storageKey(String localId) {
        return accountProfilePrefix() + "|" + localId;
    }

    /** The account+profile portion of {@link #storageKey} alone, for listing/renaming just the
     *  current account/profile's own storages in the settings tab. */
    public static String accountProfilePrefix() {
        Minecraft client = Minecraft.getInstance();
        String account = client.getUser() != null && client.getUser().getProfileId() != null
                ? client.getUser().getProfileId().toString() : "unknown-account";
        return account + "|" + currentProfileName();
    }

    private static String currentProfileName() {
        String sidebar = readSidebarText();
        Matcher m = PROFILE_LINE.matcher(sidebar);
        if (m.find()) {
            return m.group(1);
        }
        return "unknown-profile";
    }

    private static String readSidebarText() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return "";
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (sidebar == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sidebar.getDisplayName().getString()).append('\n');
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
            sb.append(entry.display() != null ? entry.display().getString() : entry.owner()).append('\n');
        }
        return sb.toString();
    }
}

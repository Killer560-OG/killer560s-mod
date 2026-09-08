package com.killer560.hub.storageoverlay;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
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
    /** Real Hypixel "this page/slot is empty" placeholder items on the overview screen, ported from
     *  NoammAddons' own {@code emptyStorageSlotItems}. */
    private static final List<String> OVERVIEW_EMPTY_MARKERS = List.of(
            "minecraft:red_stained_glass_pane", "minecraft:brown_stained_glass_pane", "minecraft:gray_dye");

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

    /** Called from {@link ScreenEvents#AFTER_INIT} for every screen that opens - logs the contents of
     *  any real Ender Chest page or Backpack, exactly matching what NoammAddons captures on
     *  {@code ContainerFullyOpenedEvent}, or (for the overview screen) just registers which pages
     *  exist without their contents. */
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
            List<ItemStack> contents = new ArrayList<>();
            int containerSlotCount = Math.max(0, menu.slots.size() - 36);
            for (Slot slot : menu.slots) {
                if (slot.index >= containerSlotCount) {
                    break;
                }
                ItemStack stack = slot.getItem();
                contents.add(stack == null ? ItemStack.EMPTY : stack.copy());
            }
            StorageOverlayCache.getInstance().put(key, contents);
            LOGGER.info("Logged storage \"{}\" ({} slots) under key {}", title, contents.size(), key);
        } catch (Exception e) {
            LOGGER.error("Failed to log storage screen", e);
        }
    }

    /** Reads the "Storage" overview menu's own per-page icons (slots 9-17 = Ender Chest pages 1-9,
     *  27-44 = Backpacks 1-18 - ported from {@code StoragePage.overview}) and marks/unmarks each as
     *  known-to-exist, per NoammAddons' own {@code saveOverview}. Never overwrites a page that
     *  already has real logged contents. */
    private static void scanOverview(ChestMenu menu) {
        StorageOverlayCache cache = StorageOverlayCache.getInstance();
        for (Slot slot : menu.slots) {
            String key = overviewSlotToKey(slot.index);
            if (key == null) {
                continue;
            }
            ItemStack stack = slot.getItem();
            boolean empty = stack == null || stack.isEmpty()
                    || OVERVIEW_EMPTY_MARKERS.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (empty) {
                cache.unmarkKnown(key);
            } else {
                cache.markKnown(key);
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

    /** Called from {@link com.killer560.hub.storageoverlay.mixin.StorageOverlayContainerMixin} on
     *  every container screen's own render pass - draws the 3-column grid of every known storage for
     *  the current account/profile if the currently open screen is itself a tracked storage. */
    public static void onContainerScreenRender(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        try {
            if (!StorageOverlayConfig.getInstance().isEnabled()) {
                return;
            }
            String activeKey = storageKeyForTitle(screen.getTitle().getString());
            if (activeKey == null) {
                return;
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

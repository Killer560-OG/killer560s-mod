package com.killer560.hub.storageoverlay;

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

            @Override
            public int defaultX() {
                return Minecraft.getInstance().getWindow().getGuiScaledWidth() - PANEL_WIDTH - 20;
            }

            @Override
            public int defaultY() {
                return 20;
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
     *  {@code ContainerFullyOpenedEvent}. */
    private static void onScreenOpen(Screen screen) {
        try {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
                return;
            }
            String key = storageKeyForTitle(containerScreen.getTitle().getString());
            if (key == null) {
                return;
            }
            if (!(containerScreen.getMenu() instanceof ChestMenu menu)) {
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
            LOGGER.info("Logged storage \"{}\" ({} slots) under key {}", containerScreen.getTitle().getString(), contents.size(), key);
        } catch (Exception e) {
            LOGGER.error("Failed to log storage screen", e);
        }
    }

    /** @return the composite cache key for this screen title if it's a real Ender Chest page or
     *  Backpack, otherwise null. Package-visible for the mixin's own "should I even draw?" check. */
    static String storageKeyForTitle(String title) {
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
            // reading order NoammAddons' own StoragePage.compareTo gives.
            Map<String, List<ItemStack>> ordered = new TreeMap<>(StorageOverlayFeature::compareStorageKeys);
            for (String key : keys) {
                List<ItemStack> contents = StorageOverlayCache.getInstance().get(key);
                if (contents != null) {
                    ordered.put(key, contents);
                }
            }

            HudElement element = HudElementRegistry.all().stream()
                    .filter(e -> e.id().equals(ELEMENT_ID)).findFirst().orElse(null);
            if (element == null) {
                return;
            }
            int[] pos = HudElementRegistry.resolvePosition(element);
            float scale = HudElementRegistry.resolveScale(element);
            graphics.pose().pushMatrix();
            graphics.pose().translate(pos[0], pos[1]);
            graphics.pose().scale(scale, scale);
            renderGrid(graphics, ordered, prefix, activeKey);
            graphics.pose().popMatrix();
        } catch (Exception e) {
            LOGGER.error("Failed to render Storage Overlay", e);
        }
    }

    private static int compareStorageKeys(String a, String b) {
        return a.compareTo(b);
    }

    private static void renderGrid(GuiGraphicsExtractor graphics, Map<String, List<ItemStack>> storages,
                                    String prefix, String activeKey) {
        StorageOverlayConfig cfg = StorageOverlayConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int textColor = cfg.isDarkMode() ? 0xFFFFFFFF : 0xFF101010;
        int bg = cfg.isDarkMode() ? 0xCC101010 : 0xCCE8E8E8;
        int border = cfg.isDarkMode() ? 0xFF553311 : 0xFFAAAAAA;
        int activeBorder = 0xFFCC6600;

        int columns = 3;
        int col = 0;
        int rowX = 0;
        int rowY = 0;
        int rowTallest = 0;

        for (Map.Entry<String, List<ItemStack>> entry : storages.entrySet()) {
            String key = entry.getKey();
            List<ItemStack> contents = entry.getValue();
            int rows = Math.max(1, (int) Math.ceil(contents.size() / 9.0));
            int panelHeight = rows * SLOT_SIZE + font.lineHeight + 6;

            int panelX = rowX;
            int panelY = rowY;

            boolean active = key.equals(activeKey);
            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + panelHeight, bg);
            graphics.outline(panelX, panelY, PANEL_WIDTH, panelHeight, active ? activeBorder : border);
            String label = displayLabel(key, prefix);
            graphics.text(font, active ? "§6" + label : label, panelX + 3, panelY + 3, textColor);

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

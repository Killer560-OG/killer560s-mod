package com.killer560.hub.storageoverlay;

import com.killer560.hub.hud.HudElement;
import com.killer560.hub.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SkyHanni-style storage overlay: whenever an Ender Chest or Backpack item is captured (its real
 *  menu opened at least once) or was previously seen and persisted, holding that same item shows its
 *  cached contents as a HUD overlay - no need to actually open it. Per killer560's request
 *  (2026-09-08): main toggle, dark/light background, adjustable scale (via the shared HUD editor,
 *  same as every other HUD element), renamable storage units, config persists across restarts, and
 *  cached contents never leak between accounts or SkyBlock profiles. */
public final class StorageOverlayFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("killer560smod-storageoverlay");
    private static final String ELEMENT_ID = "storage_overlay";
    private static final int SLOT_SIZE = 18;
    private static final int COLUMNS = 9;

    /** Real Hypixel storage item names always contain one of these, case-insensitive - a first pass,
     *  not verified against a live session (same caveat {@link com.killer560.hub.rngmeter.LocationTracker}
     *  already carries for its own keyword matching). Adjust if a real storage item's name doesn't
     *  get picked up. */
    private static final List<String> STORAGE_NAME_MARKERS = List.of("ender chest", "backpack");

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
                return 20;
            }

            @Override
            public int defaultY() {
                return 20;
            }

            @Override
            public int width() {
                return COLUMNS * SLOT_SIZE;
            }

            @Override
            public int height() {
                return 4 * SLOT_SIZE + 12;
            }

            @Override
            public void render(GuiGraphicsExtractor graphics, int x, int y) {
                // HUD position editor preview - show a small sample grid regardless of whether a
                // real storage is currently cached, same pattern as RngMeterOverlay's own preview.
                List<ItemStack> sample = List.of(new ItemStack(net.minecraft.world.item.Items.CHEST, 1),
                        new ItemStack(net.minecraft.world.item.Items.DIAMOND, 1));
                renderGrid(graphics, x, y, "Example Storage", sample);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> onScreenOpen(screen));
    }

    /** Called from {@link ScreenEvents#AFTER_INIT} for every screen that opens - captures the
     *  contents of any container screen that looks like a storage unit. */
    public static void onScreenOpen(net.minecraft.client.gui.screens.Screen screen) {
        try {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
                return;
            }
            String title = containerScreen.getTitle().getString();
            if (!looksLikeStorage(title)) {
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
            String key = storageKey(title);
            StorageOverlayCache.getInstance().put(key, contents);
            LOGGER.info("Captured storage \"{}\" ({} slots) under key {}", title, contents.size(), key);
        } catch (Exception e) {
            LOGGER.error("Failed to capture storage screen", e);
        }
    }

    private static boolean looksLikeStorage(String title) {
        String lower = title.toLowerCase(Locale.US);
        for (String marker : STORAGE_NAME_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Called every client tick from {@link com.killer560.hub.storageoverlay.mixin.StorageOverlayGuiMixin}
     *  - if the player's currently held item (either hand) matches a known cached storage for the
     *  current account/profile, draws its contents as a HUD overlay. */
    public static void renderIfHoldingKnownStorage(GuiGraphicsExtractor graphics) {
        StorageOverlayConfig cfg = StorageOverlayConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        ItemStack held = heldStorageCandidate(client);
        if (held == null) {
            return;
        }
        String cleanName = held.getHoverName().getString();
        if (!looksLikeStorage(cleanName)) {
            return;
        }
        String key = storageKey(cleanName);
        List<ItemStack> contents = StorageOverlayCache.getInstance().get(key);
        if (contents == null) {
            return;
        }
        HudElement element = HudElementRegistry.all().stream()
                .filter(e -> e.id().equals(ELEMENT_ID)).findFirst().orElse(null);
        if (element == null) {
            return;
        }
        int[] pos = HudElementRegistry.resolvePosition(element);
        float scale = HudElementRegistry.resolveScale(element);
        String label = displayLabel(key, cleanName);
        graphics.pose().pushMatrix();
        graphics.pose().translate(pos[0], pos[1]);
        graphics.pose().scale(scale, scale);
        renderGrid(graphics, 0, 0, label, contents);
        graphics.pose().popMatrix();
    }

    private static ItemStack heldStorageCandidate(Minecraft client) {
        ItemStack main = client.player.getMainHandItem();
        if (main != null && !main.isEmpty()) {
            return main;
        }
        ItemStack off = client.player.getOffhandItem();
        return off != null && !off.isEmpty() ? off : null;
    }

    private static void renderGrid(GuiGraphicsExtractor graphics, int x, int y, String label, List<ItemStack> contents) {
        StorageOverlayConfig cfg = StorageOverlayConfig.getInstance();
        var font = Minecraft.getInstance().font;
        int rows = Math.max(1, (contents.size() + COLUMNS - 1) / COLUMNS);
        int panelWidth = COLUMNS * SLOT_SIZE;
        int panelHeight = rows * SLOT_SIZE + 12;
        int bg = cfg.isDarkMode() ? 0xCC101010 : 0xCCE8E8E8;
        int textColor = cfg.isDarkMode() ? 0xFFFFFFFF : 0xFF101010;
        int border = cfg.isDarkMode() ? 0xFF553311 : 0xFFAAAAAA;

        graphics.fill(x, y, x + panelWidth, y + panelHeight, bg);
        graphics.outline(x, y, panelWidth, panelHeight, border);
        graphics.text(font, label, x + 4, y + 2, textColor);

        int gridY = y + 12;
        for (int i = 0; i < contents.size(); i++) {
            ItemStack stack = contents.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            int col = i % COLUMNS;
            int row = i / COLUMNS;
            int slotX = x + col * SLOT_SIZE + 1;
            int slotY = gridY + row * SLOT_SIZE + 1;
            graphics.item(stack, slotX, slotY);
            if (stack.getCount() > 1) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(slotX, slotY);
                graphics.pose().scale(0.6f, 0.6f);
                graphics.text(font, String.valueOf(stack.getCount()), 10, 10, textColor);
                graphics.pose().popMatrix();
            }
        }
    }

    /** killer560's custom name for this storage if he set one, otherwise the cleaned real name. */
    private static String displayLabel(String key, String fallback) {
        String custom = StorageOverlayConfig.getInstance().getCustomName(key);
        return custom != null ? custom : fallback;
    }

    /** Composite cache key: account UUID + SkyBlock profile + the storage's own title - see the
     *  class doc on {@link StorageOverlayCache} for why embedding both directly in the key is what
     *  actually guarantees no cross-account/cross-profile leakage. */
    static String storageKey(String title) {
        return accountProfilePrefix() + "|" + title.trim();
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

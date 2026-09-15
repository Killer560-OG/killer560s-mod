package com.killer560.hub.storagesearch;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.storageoverlay.StorageOverlayCache;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.Slot;

/**
 * Storage Item Search - a GLOBAL search across everything this mod has cached, as opposed to
 * {@link com.killer560.hub.inventorysearch.InventorySearchFeature} (Ctrl+F highlight inside the one
 * container that's open right now). Reads Storage Overlay's per-account/profile cache read-only (Ender
 * Chest pages + Backpacks) plus the live inventory/armor/offhand, and shows every match with where it is
 * and how old that storage's cached copy is.
 * <p>
 * Ships disabled. Opened by its own keybind (unset by default, see StorageSearchTab) or
 * {@code /k560search [text]}. Clicking a storage result sends the same real Hypixel command Storage
 * Overlay / NoammAddons' {@code StoragePage.open} use ({@code /enderchest N}, {@code /backpack N}) on the
 * player's own click, then outlines the item's slot once that page opens. Purely visual otherwise - never
 * clicks or moves an item. No mixins of its own; slot positions come from Inventory Search's existing
 * read-only {@code leftPos/topPos} accessor.
 */
public final class StorageSearchFeature {

    public static final String CHAT_PREFIX = "Storage Search";
    private static final int HIGHLIGHT_COLOR = 0xFFFF8C1A;
    private static final long PENDING_TIMEOUT_MS = 15_000L;
    private static final long HIGHLIGHT_DURATION_MS = 20_000L;

    private static boolean keyWasDown = false;

    /** Target to outline once a matching screen opens: a storage key + content index, or (key == null)
     *  a player-inventory slot. */
    private static String pendingKey = null;
    private static int pendingContentIndex = -1;
    private static int pendingInventorySlot = -1;
    private static long pendingSetAt = 0L;

    private StorageSearchFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(StorageSearchFeature::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> onScreenInit(screen));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommands.literal("k560search")
                        .executes(ctx -> {
                            openDeferred("");
                            return 1;
                        })
                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    openDeferred(StringArgumentType.getString(ctx, "text"));
                                    return 1;
                                }))));
    }

    private static void openDeferred(String query) {
        Minecraft client = Minecraft.getInstance();
        if (!com.killer560.hub.util.SkyblockGate.allows()) {
            ModChat.send(CHAT_PREFIX, ModChat.bad("Paused"), ModChat.dim(" - Skyblock Only is on and you're not on Skyblock."));
            return;
        }
        if (!StorageSearchConfig.getInstance().isEnabled()) {
            ModChat.send(CHAT_PREFIX, ModChat.bad("Disabled"), ModChat.dim(" - turn it on in the mod menu."));
            return;
        }
        // Deferred like /killer560 and /termism - the chat screen closing after the command would
        // otherwise replace this screen on the same tick.
        client.execute(() -> client.setScreenAndShow(new StorageSearchScreen(null, query)));
    }

    private static void tick(Minecraft client) {
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        int code = cfg.getKeyCode();
        if (!cfg.isEnabled() || code < 0 || client.player == null || client.getWindow() == null) {
            keyWasDown = false;
            return;
        }
        boolean down = InputConstants.isKeyDown(client.getWindow(), code);
        // Only from in-game (no screen open) so typing the key into a chat box / sign never opens it.
        if (down && !keyWasDown && client.screen == null) {
            client.setScreenAndShow(new StorageSearchScreen(null, ""));
        }
        keyWasDown = down;
    }

    // ------------------------------------------------------------------ clicks from the screen

    /** Handles a result click: storage -> optionally send the open command and arm a slot highlight;
     *  inventory -> optionally open the vanilla inventory with the slot outlined. Always echoes the
     *  location to chat so it's still useful with "Open on Click" off. */
    static void onResultClicked(StorageSearchIndex.Entry entry) {
        Minecraft client = Minecraft.getInstance();
        boolean open = StorageSearchConfig.getInstance().isOpenOnClick();
        ModChat.send(CHAT_PREFIX, ModChat.text(entry.name()), ModChat.dim(" → "), ModChat.value(entry.location()));
        if (!open || client.player == null) {
            return;
        }
        if (entry.type() == StorageSearchIndex.SourceType.INVENTORY) {
            setPending(null, -1, entry.inventorySlot());
            client.setScreenAndShow(new InventoryScreen(client.player));
            return;
        }
        if (client.player.connection == null) {
            return;
        }
        setPending(entry.storageKey(), entry.contentIndex(), -1);
        client.setScreen(null);
        String command = entry.type() == StorageSearchIndex.SourceType.ENDER_CHEST
                ? "enderchest " + entry.storageNumber()
                : "backpack " + entry.storageNumber();
        client.player.connection.sendCommand(command);
    }

    private static void setPending(String key, int contentIndex, int inventorySlot) {
        pendingKey = key;
        pendingContentIndex = contentIndex;
        pendingInventorySlot = inventorySlot;
        pendingSetAt = System.currentTimeMillis();
    }

    private static void clearPending() {
        pendingKey = null;
        pendingContentIndex = -1;
        pendingInventorySlot = -1;
    }

    // ------------------------------------------------------------------ screen hooks

    private static void onScreenInit(Screen screen) {
        if (!StorageSearchConfig.getInstance().isEnabled()) {
            return;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        String title = container.getTitle().getString();
        String storageKey = StorageOverlayFeature.storageKeyForTitle(title);

        // "Cache last updated": Storage Overlay (re)captures a tracked page while it's open, so the
        // moment it closes is the freshest its cached copy can be.
        if (storageKey != null) {
            ScreenEvents.remove(screen).register(s -> {
                String closingKey = StorageOverlayFeature.storageKeyForTitle(title);
                if (closingKey != null && StorageOverlayCache.getInstance().hasContents(closingKey)) {
                    StorageSearchTimestamps.mark(closingKey, System.currentTimeMillis());
                }
            });
        }

        if (pendingContentIndex < 0 && pendingInventorySlot < 0) {
            return;
        }
        if (System.currentTimeMillis() - pendingSetAt > PENDING_TIMEOUT_MS) {
            clearPending();
            return;
        }
        final Slot target;
        if (pendingKey != null) {
            if (!pendingKey.equals(storageKey)) {
                return;
            }
            // Storage Overlay hides the vanilla slots and redraws the page in its own grid, so a
            // vanilla-position outline would point at nothing - the chat line already gave the slot.
            if (StorageOverlayConfig.getInstance().isEnabled()) {
                ModChat.send(CHAT_PREFIX, ModChat.dim("Storage Overlay is on - look for slot "),
                        ModChat.value(String.valueOf(pendingContentIndex + 1)), ModChat.dim(" in the highlighted page."));
                clearPending();
                return;
            }
            target = findStorageSlot(container, pendingContentIndex);
        } else {
            if (!(screen instanceof InventoryScreen)) {
                return;
            }
            target = findInventorySlot(container, pendingInventorySlot);
        }
        clearPending();
        if (target == null) {
            return;
        }
        long shownAt = System.currentTimeMillis();
        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) -> {
            if (System.currentTimeMillis() - shownAt < HIGHLIGHT_DURATION_MS) {
                drawSlotHighlight(container, graphics, target);
            }
        });
    }

    /** Real container slot for cached content index {@code i}: Storage Overlay captures from real slot 9
     *  onward (row 1 is Hypixel's menu chrome), so content index i is container slot 9 + i. */
    private static Slot findStorageSlot(AbstractContainerScreen<?> screen, int contentIndex) {
        Minecraft client = Minecraft.getInstance();
        for (Slot slot : screen.getMenu().slots) {
            if (client.player != null && slot.container == client.player.getInventory()) {
                continue;
            }
            if (slot.getContainerSlot() == 9 + contentIndex) {
                return slot;
            }
        }
        return null;
    }

    private static Slot findInventorySlot(AbstractContainerScreen<?> screen, int inventorySlot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        for (Slot slot : screen.getMenu().slots) {
            if (slot.container == client.player.getInventory() && slot.getContainerSlot() == inventorySlot) {
                return slot;
            }
        }
        return null;
    }

    private static void drawSlotHighlight(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, Slot slot) {
        try {
            ContainerScreenPositionAccessor accessor = (ContainerScreenPositionAccessor) screen;
            int x = accessor.killer560smod$getLeftPos() + slot.x;
            int y = accessor.killer560smod$getTopPos() + slot.y;
            // Gentle pulse so it's findable at a glance without being an eyesore.
            int alpha = 0x40 + (int) (0x40 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 180.0)));
            graphics.fill(x, y, x + 16, y + 16, (alpha << 24) | 0xFF8C1A);
            graphics.outline(x - 1, y - 1, 18, 18, HIGHLIGHT_COLOR);
            graphics.outline(x - 2, y - 2, 20, 20, HIGHLIGHT_COLOR);
        } catch (Exception ignored) {
        }
    }
}

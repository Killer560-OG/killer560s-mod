package com.killer560.hub.storagesearch;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.storageoverlay.StorageOverlayCache;
import com.killer560.hub.storageoverlay.StorageOverlayConfig;
import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import com.killer560.hub.util.ModChat;
import com.mojang.blaze3d.platform.Window;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage Item Search - a GLOBAL search across everything this mod has cached, as opposed to
 * {@link com.killer560.hub.inventorysearch.InventorySearchFeature} (Ctrl+F highlight inside the one
 * container that's open right now). Reads Storage Overlay's per-account/profile cache read-only (Ender
 * Chest pages + Backpacks), this feature's own island-chest and wardrobe/equipment/pets caches, plus the live
 * inventory/armor/offhand, and shows every match with where it is and how old that copy is.
 * <p>
 * Ships disabled. Opened by {@code /search}, {@code /k560search [text]}, any of its open-binds (Ctrl+F by
 * default, see {@link StorageSearchBind}) or the Search button in the Storage Overlay grid. Clicking a storage
 * result sends the same real Hypixel command Storage Overlay / NoammAddons' {@code StoragePage.open} use
 * ({@code /enderchest N}, {@code /backpack N}) on the player's own click, then outlines the item's slot once that
 * page opens - in the Storage Overlay's own grid when that is on (scrolling the grid to it), or over the real
 * vanilla slot when it is not. Clicking an island-chest result draws a box round the chest in the world instead,
 * since a chest can only be opened by walking to it. Purely visual otherwise - never clicks or moves an item.
 * No mixins of its own; slot positions come from Inventory Search's existing read-only {@code leftPos/topPos}
 * accessor.
 */
public final class StorageSearchFeature {

    public static final String CHAT_PREFIX = "Storage Search";
    private static final int HIGHLIGHT_COLOR = 0xFFFF8C1A;
    private static final long PENDING_TIMEOUT_MS = 15_000L;
    private static final long HIGHLIGHT_DURATION_MS = 20_000L;
    /** How long after looking at a chest a container screen still counts as "that chest I just opened". */
    private static final long CHEST_LOOK_WINDOW_MS = 2_000L;

    /** Hypixel menu titles for the three extra places killer560 asked to have searched (2026-09-21). Hypixel's
     *  exact wording for these has NOT been confirmed against a live session - see the staging notes. The page
     *  suffix is optional so a single-page menu still matches. */
    private static final Pattern WARDROBE_TITLE = Pattern.compile("^Wardrobe(?: ✦)?(?: \\((\\d+)/(\\d+)\\))?$");
    private static final Pattern PETS_TITLE = Pattern.compile("^Pets(?: ✦)?(?: \\((\\d+)/(\\d+)\\))?$");
    private static final Pattern EQUIPMENT_TITLE = Pattern.compile("^(?:Your Equipment and Stats|Equipment)$");
    private static final String OVERVIEW_TITLE = "Storage";

    private static boolean bindWasDown = false;

    /** What to outline once a matching screen opens. */
    private enum PendingKind { NONE, STORAGE, INVENTORY, CHEST, EXTRA }

    private static PendingKind pendingKind = PendingKind.NONE;
    private static String pendingKey = null;
    private static int pendingContentIndex = -1;
    private static int pendingInventorySlot = -1;
    private static BlockPos pendingChestPos = null;
    private static long pendingSetAt = 0L;

    /** The chest block killer560 was looking at most recently, and when - how an opened chest screen is tied back
     *  to a real world position (the server never tells the client which block a container belongs to). */
    private static BlockPos lastLookedChest = null;
    private static long lastLookedChestAt = 0L;

    private StorageSearchFeature() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(StorageSearchFeature::tick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> onScreenInit(screen));
        StorageSearchEsp.register();
        // The Storage Overlay grid's own Search button (killer560, 2026-09-21: "have a gui button for it in the
        // storage overlay") - handed over as a callback so the overlay never has to know this feature exists.
        StorageOverlayFeature.setSearchOpener(() -> {
            Minecraft client = Minecraft.getInstance();
            client.setScreen(new StorageSearchScreen(client.screen, ""));
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(searchCommand("k560search"));
            // killer560 (2026-09-21): "allow me to do /search to open it."
            dispatcher.register(searchCommand("search"));
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> searchCommand(String name) {
        return ClientCommands.literal(name)
                .executes(ctx -> {
                    openDeferred("");
                    return 1;
                })
                .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            openDeferred(StringArgumentType.getString(ctx, "text"));
                            return 1;
                        }));
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
        if (!cfg.isEnabled() || client.player == null) {
            bindWasDown = false;
            return;
        }
        Window window = client.getWindow();
        boolean down = false;
        if (window != null) {
            // killer560 (2026-09-21) asked for several binds, "in the sense of ctrl + f" - any of them opens it.
            List<StorageSearchBind> binds = cfg.getBinds();
            for (int i = 0; i < binds.size(); i++) {
                if (binds.get(i).isDown(window)) {
                    down = true;
                    break;
                }
            }
        }
        // Only from in-game (no screen open) so typing the bind into a chat box / sign never opens it.
        if (down && !bindWasDown && client.screen == null) {
            client.setScreenAndShow(new StorageSearchScreen(null, ""));
        }
        bindWasDown = down;

        if (cfg.isSearchChests() && client.level != null) {
            trackLookedAtChest(client);
        }
    }

    /** One hit-result read per tick while island-chest search is on: the server never says which block a container
     *  screen belongs to, so the block killer560 was looking at when it opened is the only honest answer. */
    private static void trackLookedAtChest(Minecraft client) {
        HitResult hit = client.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        Block block = client.level.getBlockState(pos).getBlock();
        if (block instanceof ChestBlock || block instanceof BarrelBlock) {
            lastLookedChest = pos.immutable();
            lastLookedChestAt = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------------ clicks from the screen

    /** Handles a result click. Always echoes the location to chat so it's still useful with "Open on Click" off.
     *  {@code allResults} is the list currently on screen, used only for killer560's "if i search for an item and
     *  it is in one backpack then only show that backpack" rule. */
    static void onResultClicked(StorageSearchIndex.Entry entry, List<StorageSearchIndex.Entry> allResults) {
        Minecraft client = Minecraft.getInstance();
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        boolean open = cfg.isOpenOnClick();
        ModChat.send(CHAT_PREFIX, ModChat.text(entry.name()), ModChat.dim(" → "), ModChat.value(entry.location()));
        if (!open || client.player == null) {
            return;
        }
        switch (entry.type()) {
            case INVENTORY -> {
                setPending(PendingKind.INVENTORY, null, -1, entry.inventorySlot(), null);
                client.setScreenAndShow(new InventoryScreen(client.player));
            }
            case ISLAND_CHEST -> {
                openChestResult(client, cfg, entry);
                markDuplicateChests(cfg, entry, allResults);
            }
            case WARDROBE, PETS, EQUIPMENT -> openExtraResult(client, entry);
            default -> openStorageResult(client, cfg, entry, allResults);
        }
    }

    /** A chest can't be opened remotely, so the "open" here is a world box round it plus an armed slot outline for
     *  when he actually walks over and opens it. */
    private static void openChestResult(Minecraft client, StorageSearchConfig cfg, StorageSearchIndex.Entry entry) {
        BlockPos pos = entry.chestPos();
        if (pos == null) {
            return;
        }
        setPending(PendingKind.CHEST, null, entry.contentIndex(), -1, pos);
        if (cfg.isChestEsp()) {
            StorageSearchEsp.mark(pos, cfg.getEspSeconds() * 1000L);
        }
        double distance = client.player.blockPosition().distSqr(pos);
        ModChat.send(CHAT_PREFIX, ModChat.dim("Chest marked at "),
                ModChat.value(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()),
                ModChat.dim(" (" + (int) Math.sqrt(distance) + " blocks away)"));
        client.setScreen(null);
    }

    /** "If something exists on my island as the exact same item in multiple areas, then highlight all chests it may
     *  be in" (killer560, 2026-09-21): every other island-chest result for the same item (same Skyblock id, or the
     *  same name when it has none) gets the same world box. */
    private static void markDuplicateChests(StorageSearchConfig cfg, StorageSearchIndex.Entry entry,
                                            List<StorageSearchIndex.Entry> allResults) {
        if (!cfg.isChestEsp() || allResults == null) {
            return;
        }
        int extra = 0;
        for (StorageSearchIndex.Entry other : allResults) {
            if (other == entry || other.type() != StorageSearchIndex.SourceType.ISLAND_CHEST || other.chestPos() == null
                    || other.chestPos().equals(entry.chestPos())) {
                continue;
            }
            boolean same = !entry.idLower().isEmpty() ? entry.idLower().equals(other.idLower())
                    : entry.nameLower().equals(other.nameLower());
            if (same) {
                StorageSearchEsp.mark(other.chestPos(), cfg.getEspSeconds() * 1000L);
                extra++;
            }
        }
        if (extra > 0) {
            ModChat.send(CHAT_PREFIX, ModChat.dim("Also in "), ModChat.value(extra + " more chest" + (extra == 1 ? "" : "s")),
                    ModChat.dim(" - all marked."));
        }
    }

    /** Wardrobe and Pets both have a real Hypixel command; the equipment menu doesn't, so that one only arms the
     *  outline and says so. */
    private static void openExtraResult(Minecraft client, StorageSearchIndex.Entry entry) {
        setPending(PendingKind.EXTRA, entry.storageKey(), entry.contentIndex(), -1, null);
        String command = switch (entry.type()) {
            case WARDROBE -> "wardrobe";
            case PETS -> "pets";
            default -> null;
        };
        client.setScreen(null);
        if (command == null || client.player.connection == null) {
            ModChat.send(CHAT_PREFIX, ModChat.dim("Open your equipment menu - the slot will be outlined."));
            return;
        }
        client.player.connection.sendCommand(command);
    }

    private static void openStorageResult(Minecraft client, StorageSearchConfig cfg, StorageSearchIndex.Entry entry,
                                          List<StorageSearchIndex.Entry> allResults) {
        if (client.player.connection == null) {
            return;
        }
        setPending(PendingKind.STORAGE, entry.storageKey(), entry.contentIndex(), -1, null);
        // Real bug found and fixed (2026-09-21), per killer560's "when it opens my storage overlay, have it
        // properly scroll to the right height of the item for the custom gui, and still have it highlight the
        // item like it does in my inventory. It doesn't right now": with the Storage Overlay on, every real slot
        // on that screen is hidden and redrawn in the overlay's own grid, so the vanilla-position outline below
        // pointed at nothing - this used to just print "look for slot N" to chat and give up. The overlay now
        // does the highlight (and the scroll to it) itself.
        if (StorageOverlayConfig.getInstance().isEnabled()) {
            StorageOverlayFeature.highlightItem(entry.storageKey(), entry.contentIndex(), HIGHLIGHT_DURATION_MS);
            if (cfg.isFocusSingleStorage() && isOnlyStorageHit(entry.storageKey(), allResults)) {
                // killer560 (2026-09-21): "if i search for an item and it is in one backpack then only show that
                // backpack on the menu. Add a back button to the left..."
                StorageOverlayFeature.setFocus(entry.storageKey());
            }
        }
        client.setScreen(null);
        String command = entry.type() == StorageSearchIndex.SourceType.ENDER_CHEST
                ? "enderchest " + entry.storageNumber()
                : "backpack " + entry.storageNumber();
        client.player.connection.sendCommand(command);
    }

    /** @return true when every storage hit in the current result list lives in this one storage. */
    private static boolean isOnlyStorageHit(String key, List<StorageSearchIndex.Entry> allResults) {
        if (key == null || allResults == null) {
            return false;
        }
        for (StorageSearchIndex.Entry other : allResults) {
            if (other.type().isStorage() && !key.equals(other.storageKey())) {
                return false;
            }
        }
        return true;
    }

    private static void setPending(PendingKind kind, String key, int contentIndex, int inventorySlot, BlockPos chestPos) {
        pendingKind = kind;
        pendingKey = key;
        pendingContentIndex = contentIndex;
        pendingInventorySlot = inventorySlot;
        pendingChestPos = chestPos;
        pendingSetAt = System.currentTimeMillis();
    }

    private static void clearPending() {
        pendingKind = PendingKind.NONE;
        pendingKey = null;
        pendingContentIndex = -1;
        pendingInventorySlot = -1;
        pendingChestPos = null;
    }

    // ------------------------------------------------------------------ screen hooks

    private static void onScreenInit(Screen screen) {
        StorageSearchConfig cfg = StorageSearchConfig.getInstance();
        if (!cfg.isEnabled()) {
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

        String extraKey = storageKey == null ? extraKeyForTitle(title) : null;
        if (extraKey != null && cfg.isSearchExtras()) {
            registerExtraCapture(screen, container, extraKey);
        }
        if (extraKey == null && storageKey == null && cfg.isSearchChests()) {
            registerChestCapture(screen, container, title);
        }

        armHighlight(screen, container, title, storageKey, extraKey);
    }

    /** Captures a wardrobe / equipment / pets page when its screen closes. Deliberately on close rather than on a
     *  timer: by then Hypixel's contents packet has certainly arrived, and it writes the cache file exactly once
     *  per visit instead of once per tick. */
    private static void registerExtraCapture(Screen screen, AbstractContainerScreen<?> container, String extraKey) {
        if (!(container.getMenu() instanceof ChestMenu)) {
            return;
        }
        ScreenEvents.remove(screen).register(s -> {
            try {
                List<ItemStack> contents = captureTopSlots(container);
                if (!contents.isEmpty()) {
                    StorageSearchExtraCache.getInstance().put(extraKey, contents);
                }
            } catch (Exception ignored) {
                // A cache write must never take a screen close down with it.
            }
        });
    }

    /** Captures a real island chest on close, keyed to the block killer560 was looking at when it opened. */
    private static void registerChestCapture(Screen screen, AbstractContainerScreen<?> container, String title) {
        if (!(container.getMenu() instanceof ChestMenu) || title.equals(OVERVIEW_TITLE)) {
            return;
        }
        if (lastLookedChest == null || System.currentTimeMillis() - lastLookedChestAt > CHEST_LOOK_WINDOW_MS) {
            return;
        }
        final BlockPos pos = lastLookedChest;
        final String world = Minecraft.getInstance().level == null ? "unknown"
                : Minecraft.getInstance().level.dimension().identifier().getPath();
        final String prefix = StorageOverlayFeature.accountProfilePrefix();
        ScreenEvents.remove(screen).register(s -> {
            try {
                List<ItemStack> contents = captureTopSlots(container);
                if (contents.isEmpty()) {
                    return;
                }
                IslandChestCache.getInstance().put(IslandChestCache.keyFor(prefix, world, pos), world, pos, title, contents);
            } catch (Exception ignored) {
            }
        });
    }

    /** Every slot of the top (non-player) container, in order, so a cached index still points at the same slot
     *  when the screen is reopened. */
    private static List<ItemStack> captureTopSlots(AbstractContainerScreen<?> container) {
        List<Slot> slots = container.getMenu().slots;
        int containerSlotCount = Math.max(0, slots.size() - 36);
        List<ItemStack> contents = new ArrayList<>(containerSlotCount);
        for (int i = 0; i < containerSlotCount; i++) {
            ItemStack stack = slots.get(i).getItem();
            contents.add(stack == null ? ItemStack.EMPTY : stack.copy());
        }
        return contents;
    }

    /** Arms the pulsing slot outline if this is the screen the last clicked result was pointing at. */
    private static void armHighlight(Screen screen, AbstractContainerScreen<?> container, String title,
                                     String storageKey, String extraKey) {
        if (pendingKind == PendingKind.NONE) {
            return;
        }
        if (System.currentTimeMillis() - pendingSetAt > PENDING_TIMEOUT_MS) {
            clearPending();
            return;
        }
        final Slot target;
        switch (pendingKind) {
            case STORAGE -> {
                if (!java.util.Objects.equals(pendingKey, storageKey)) {
                    return;
                }
                // With the overlay on, the outline is drawn (and scrolled to) inside its own grid instead - see
                // openStorageResult. Nothing to do over the hidden vanilla slots.
                if (StorageOverlayConfig.getInstance().isEnabled()) {
                    clearPending();
                    return;
                }
                // Storage Overlay captures from real slot 9 onward (row 1 is Hypixel's menu chrome), so content
                // index i is container slot 9 + i.
                target = findContainerSlot(container, 9 + pendingContentIndex);
            }
            case EXTRA -> {
                if (extraKey == null || !extraKey.equals(pendingKey)) {
                    return;
                }
                target = findContainerSlot(container, pendingContentIndex);
            }
            case CHEST -> {
                if (pendingChestPos == null || lastLookedChest == null || !lastLookedChest.equals(pendingChestPos)
                        || storageKey != null || title.equals(OVERVIEW_TITLE)) {
                    return;
                }
                target = findContainerSlot(container, pendingContentIndex);
            }
            case INVENTORY -> {
                if (!(screen instanceof InventoryScreen)) {
                    return;
                }
                target = findInventorySlot(container, pendingInventorySlot);
            }
            default -> {
                return;
            }
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

    /** @return the real top-container slot with this container index, ignoring the player's own inventory slots. */
    private static Slot findContainerSlot(AbstractContainerScreen<?> screen, int containerIndex) {
        Minecraft client = Minecraft.getInstance();
        if (containerIndex < 0) {
            return null;
        }
        for (Slot slot : screen.getMenu().slots) {
            if (client.player != null && slot.container == client.player.getInventory()) {
                continue;
            }
            if (slot.getContainerSlot() == containerIndex) {
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

    /** @return this feature's own cache key for a wardrobe / equipment / pets menu title, otherwise null. */
    static String extraKeyForTitle(String title) {
        Matcher wardrobe = WARDROBE_TITLE.matcher(title);
        if (wardrobe.matches()) {
            return extraKey("wardrobe_" + (wardrobe.group(1) == null ? "1" : wardrobe.group(1)));
        }
        Matcher pets = PETS_TITLE.matcher(title);
        if (pets.matches()) {
            return extraKey("pets_" + (pets.group(1) == null ? "1" : pets.group(1)));
        }
        if (EQUIPMENT_TITLE.matcher(title).matches()) {
            return extraKey("equipment");
        }
        return null;
    }

    private static String extraKey(String localId) {
        return StorageOverlayFeature.accountProfilePrefix() + "|" + localId;
    }
}

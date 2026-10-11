package com.killer560.hub.itemprotect;

import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.FeatureGuard;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import com.killer560.hub.util.ModLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.List;

/**
 * Item Protection - client-side guards that stop you throwing your own gear away, all OFF by default. Ported from
 * Devonian's {@code misc/inventory/{ProtectItem, ProtectStarredItems, PreventDroppingHotbar}.kt} (its shared
 * {@code PreventItem.kt} cancel funnel is folded into {@link ItemProtect}), cross-checked against NoammAddons
 * {@code general/ProtectItem.kt}, Skytils' {@code ItemFeatures} (branch {@code 1.x}) and SkyHanni's
 * {@code ItemProtection}.
 *
 * <ol>
 *   <li><b>Protect Item</b> - a per-item list (Skyblock {@code ExtraAttributes.uuid}, or the item id with the
 *       fallback on) plus a typed name list in the settings tab. A protected item is blocked from being dropped,
 *       thrown out of a GUI, or clicked inside a sell / salvage / trade / anvil / auction menu. With
 *       <b>Lock In Place</b> it can't be clicked at all - the job Slot Lock did per slot until killer560 removed it
 *       (2026-10-08). Every protected item shows a small star in its slot's top-right corner.</li>
 *   <li><b>Auto-Protect Starred</b> - anything with {@code upgrade_level}/{@code dungeon_item_level} (or a visible
 *       ✪ / master-star pip) counts as protected without being listed - and gets NO marker ("Do not have a symbol
 *       for starred items being locked even though they are").</li>
 *   <li><b>Prevent Hotbar Drops</b> - the real drop key is swallowed while you're holding a protected item, with an
 *       optional confirm-to-force: press drop 2-4 times (Drops To Force), each within 3s of the last.</li>
 * </ol>
 *
 * <p>Two mixins, both at client input funnels so nothing the SERVER does is ever blocked or silently dropped:
 * {@code AbstractContainerScreen#slotClicked} and {@code LocalPlayer#drop(boolean)}. Every block says so in chat and
 * plays a note (see {@link ItemProtect#announceBlock}). The star is drawn at the end of each slot's own draw
 * ({@code ItemProtectStarSlotMixin}), on top of the item and under any tooltip.
 */
public final class ItemProtectFeature {

    private static final Logger LOGGER = ModLog.get("killer560smod-itemprotect");

    /** Key-repeat guard: Fabric's allowKeyPress fires again while a key is held down. */
    private static final long TOGGLE_DEBOUNCE_MS = 250L;

    private static long lastToggleAt = 0L;
    private static int lastToggleKey = KeyUtil.NONE;

    /** The star, 5x5 GUI units: one string per row, '#' = filled. killer560, 2026-10-11: "make the protect item icon
     *  small and in the top right. it should be like a tiny little 5 point star." */
    private static final String[] STAR = {
            "..#..",
            "#####",
            ".###.",
            ".#.#.",
            "#...#",
    };
    public static final int STAR_SIZE = STAR.length;

    private ItemProtectFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(ItemProtectFeature::onScreenInit);
        ClientTickEvents.END_CLIENT_TICK.register(FeatureGuard.end("ItemProtectFeature.migrate",
                ItemProtectFeature::tickMigration));
    }

    // ------------------------------------------------------------------ Slot Lock migration

    /**
     * Carries the removed Slot Lock's locked slots over to protected UUIDs, once, the first time the inventory has
     * something in it that carries a Skyblock UUID (a lobby or a still-loading join reads as empty, and judging then
     * would migrate nothing). A locked slot whose item has no UUID cannot be carried over by UUID - it is named in
     * chat so nothing is lost silently.
     */
    private static void tickMigration(Minecraft client) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        List<Integer> pending = cfg.getPendingSlotMigration();
        if (pending.isEmpty() || client.player == null) {
            return;
        }
        Inventory inv = client.player.getInventory();
        boolean anyUuid = false;
        for (int i = 0; i < ItemProtectConfig.INVENTORY_SLOTS && !anyUuid; i++) {
            anyUuid = ItemProtect.itemUuid(inv.getItem(i)) != null;
        }
        if (!anyUuid) {
            return;
        }
        migrateNow(inv);
    }

    /** Does the migration against {@code inv} now. @return how many slots became protected items. Public for tests. */
    public static int migrateNow(Inventory inv) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        List<Integer> pending = cfg.getPendingSlotMigration();
        int carried = 0;
        StringBuilder skipped = new StringBuilder();
        for (int idx : pending) {
            ItemStack stack = idx >= 0 && idx < ItemProtectConfig.INVENTORY_SLOTS ? inv.getItem(idx) : ItemStack.EMPTY;
            String uuid = ItemProtect.itemUuid(stack);
            if (uuid != null && !uuid.isBlank()) {
                cfg.addProtectedKey(uuid);
                carried++;
            } else {
                if (!skipped.isEmpty()) {
                    skipped.append(", ");
                }
                skipped.append(idx).append(stack.isEmpty() ? " (empty)" : " (" + ItemProtect.displayName(stack) + ", no UUID)");
            }
        }
        cfg.clearPendingSlotMigration();
        cfg.save();
        LOGGER.info("[ItemProtect] Slot Lock migration: {} of {} locked slot(s) carried over as protected items{}",
                carried, pending.size(), skipped.isEmpty() ? "" : "; not carried: " + skipped);
        ModChat.send("Item Protect", ModChat.text("Slot Lock is now part of Protect Item: "),
                ModChat.value(carried + " locked slot" + (carried == 1 ? "" : "s")),
                ModChat.text(" became protected items (Lock In Place on)."),
                skipped.isEmpty() ? ModChat.text("") : ModChat.dim(" Not carried over: slot " + skipped + "."));
        return carried;
    }

    // ------------------------------------------------------------------ screens

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> handleKey(containerScreen, event.key()));
        // A protect bind can be a mouse button (killer560, 2026-09-20: "make all of the keybind things compatible
        // with mouse buttons and middle mouse buttons"), and a mouse press in a container screen never reaches
        // allowKeyPress, so it needs its own funnel.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                handleKey(containerScreen, ItemProtectConfig.codeForMouseButton(event.button())));
    }

    /** @return false to swallow the key (a protect toggle), true to let the screen handle it. */
    private static boolean handleKey(AbstractContainerScreen<?> screen, int key) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (!cfg.isProtectItemEnabled() || !ItemProtectConfig.isBoundCode(key) || key != cfg.getProtectKey()) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (key == lastToggleKey && now - lastToggleAt < TOGGLE_DEBOUNCE_MS) {
            // Still swallow the key so a held-down protect key doesn't leak into vanilla handling mid-repeat.
            return false;
        }
        lastToggleAt = now;
        lastToggleKey = key;

        Slot hovered = ((AbstractContainerScreenAccessor) screen).killer560smod$getHoveredSlot();
        ItemStack stack = hovered == null ? ItemStack.EMPTY : hovered.getItem();
        if (stack.isEmpty()) {
            ModChat.send("Item Protect", ModChat.text("Hover an item to protect it."));
            return false;
        }
        String protectKey = ItemProtect.protectKeyFor(stack, cfg);
        if (protectKey == null) {
            ModChat.send("Item Protect", ModChat.bad("Can't protect "),
                    ModChat.value(ItemProtect.displayName(stack)),
                    ModChat.text(" - it has no Skyblock UUID. Turn on Item ID Fallback to protect it by item id."));
            return false;
        }
        boolean added = cfg.toggleProtectedKey(protectKey);
        cfg.save();
        ItemProtect.playToggleSound(added);
        ModChat.send("Item Protect",
                added ? ModChat.good("Protected ") : ModChat.bad("Unprotected "),
                ModChat.value(ItemProtect.displayName(stack)));
        return false;
    }

    /** Called from {@code ItemProtectStarSlotMixin} at the end of every slot's own draw, so the star is drawn under
     *  the tooltip and stays on a hovered slot (killer560, 2026-10-11). Slot-local coordinates. */
    public static void drawSlotStar(GuiGraphicsExtractor graphics, Slot slot) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (!cfg.isProtectItemEnabled() || !ItemProtect.isExplicitlyProtected(slot.getItem())) {
            return;
        }
        drawStar(graphics, slot.x + 16 - STAR_SIZE, slot.y, cfg.getProtectedColor());
    }

    /**
     * killer560, 2026-10-08: "Make locked items just have a little star in the top right to show they are
     * protected"; 2026-10-11: "tiny little 5 point star". A 5x5 star in the slot's top-right corner (opposite
     * vanilla's stack count), with a one-unit dark drop shadow below-right so it reads on a light item. The shadow
     * stays inside the 16x16 slot. Pure fills, no texture.
     */
    public static void drawStar(GuiGraphicsExtractor graphics, int x, int y, int color) {
        starRows(graphics, x + 1, y + 1, 0xFF000000);
        starRows(graphics, x, y, color);
    }

    private static void starRows(GuiGraphicsExtractor graphics, int x, int y, int color) {
        for (int row = 0; row < STAR.length; row++) {
            String line = STAR[row];
            int col = 0;
            while (col < line.length()) {
                if (line.charAt(col) != '#') {
                    col++;
                    continue;
                }
                int start = col;
                while (col < line.length() && line.charAt(col) == '#') {
                    col++;
                }
                // One fill per run of filled cells, not per cell (docs/LESSONS-GUI.md: every fill costs).
                graphics.fill(x + start, y + row, x + col, y + row + 1, color);
            }
        }
    }
}

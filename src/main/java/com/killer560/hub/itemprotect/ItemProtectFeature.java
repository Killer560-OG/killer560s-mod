package com.killer560.hub.itemprotect;

import com.killer560.hub.inventorysearch.mixin.ContainerScreenPositionAccessor;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Item Protection - four independent client-side guards that stop you throwing your own gear away, all OFF
 * by default. Ported from Devonian's {@code misc/inventory/{SlotLocking, ProtectItem, ProtectStarredItems,
 * PreventDroppingHotbar}.kt} (its shared {@code PreventItem.kt} cancel funnel is folded into
 * {@link ItemProtect}), cross-checked against NoammAddons {@code general/ProtectItem.kt}, Skytils'
 * {@code SlotLocking}/{@code ItemFeatures} (branch {@code 1.x}) and SkyHanni's {@code ItemProtection}.
 *
 * <ol>
 *   <li><b>Slot Lock</b> - hover a slot in any inventory screen and press the lock key; that player-inventory
 *       slot's contents then can't be picked up, shift-moved, number-key swapped, quick-crafted or thrown.
 *       Locked slots are marked with an outline and/or a small padlock.</li>
 *   <li><b>Protect Item</b> - a per-item list (Skyblock {@code ExtraAttributes.uuid}, or the item id with the
 *       fallback on) plus a typed name list in the settings tab. A protected item is blocked from being
 *       dropped, thrown out of a GUI, or clicked inside a sell / salvage / trade / anvil / auction menu. It
 *       is deliberately NOT blocked from being moved around your own inventory.</li>
 *   <li><b>Auto-Protect Starred</b> - anything with {@code upgrade_level}/{@code dungeon_item_level} (or a
 *       visible ✪ / master-star pip) counts as protected without being listed.</li>
 *   <li><b>Prevent Hotbar Drops</b> - the real drop key is swallowed while you're holding a protected or
 *       locked item, with an optional "press it again within 3s" confirm-to-force.</li>
 * </ol>
 *
 * <p>Two mixins, both at client input funnels so nothing the SERVER does is ever blocked or silently
 * dropped: {@code AbstractContainerScreen#slotClicked} and {@code LocalPlayer#drop(boolean)}. Every block
 * says so in chat and plays a note (see {@link ItemProtect#announceBlock}).
 *
 * <p>Reuses two accessor mixins this mod already ships rather than adding duplicates -
 * {@link AbstractContainerScreenAccessor} (Slot Binds) for {@code hoveredSlot} and
 * {@link ContainerScreenPositionAccessor} (Inventory Search) for {@code leftPos}/{@code topPos} - and draws
 * its markers from {@code ScreenEvents.afterExtract}, the same after-everything hook Inventory Search's own
 * highlight uses, so the padlock lands on top of the item instead of under it.
 */
public final class ItemProtectFeature {

    /** Key-repeat guard: Fabric's allowKeyPress fires again while a key is held down. */
    private static final long TOGGLE_DEBOUNCE_MS = 250L;

    private static long lastToggleAt = 0L;
    private static int lastToggleKey = KeyUtil.NONE;

    private ItemProtectFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(ItemProtectFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> handleKey(containerScreen, event.key()));
        // A lock/protect bind can be a mouse button now (killer560, 2026-09-20: "make all of the keybind
        // things compatible with mouse buttons and middle mouse buttons"), and a mouse press in a container
        // screen never reaches allowKeyPress, so it needs its own funnel.
        ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
                handleKey(containerScreen, ItemProtectConfig.codeForMouseButton(event.button())));

        ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, partialTick) ->
                render(containerScreen, graphics));
    }

    /** @return false to swallow the key (a lock/protect toggle), true to let the screen handle it. */
    private static boolean handleKey(AbstractContainerScreen<?> screen, int key) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (!cfg.isEnabled() || !ItemProtectConfig.isBoundCode(key)) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (key == lastToggleKey && now - lastToggleAt < TOGGLE_DEBOUNCE_MS) {
            // Still swallow the key so a held-down lock key doesn't leak into vanilla handling mid-repeat.
            // Keyed on the same key so pressing lock then protect quickly doesn't eat the second press.
            return !isBoundToggleKey(cfg, key);
        }

        Slot hovered = ((AbstractContainerScreenAccessor) screen).killer560smod$getHoveredSlot();

        if (cfg.isSlotLockEnabled() && key == cfg.getSlotLockKey()) {
            lastToggleAt = now;
            lastToggleKey = key;
            if (hovered == null || !ItemProtect.isPlayerInventorySlot(hovered)) {
                ModChat.send("Item Protect", ModChat.text("Hover a slot in "),
                        ModChat.value("your own inventory"), ModChat.text(" to lock it."));
                return false;
            }
            int idx = hovered.getContainerSlot();
            boolean locked = cfg.toggleSlotLock(idx);
            cfg.save();
            ItemProtect.playToggleSound(locked);
            ModChat.send("Item Protect",
                    locked ? ModChat.good("Locked ") : ModChat.bad("Unlocked "),
                    ModChat.text("inventory slot "), ModChat.value(String.valueOf(idx)));
            return false;
        }

        if (cfg.isProtectItemEnabled() && key == cfg.getProtectKey()) {
            lastToggleAt = now;
            lastToggleKey = key;
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

        return true;
    }

    private static boolean isBoundToggleKey(ItemProtectConfig cfg, int key) {
        return (cfg.isSlotLockEnabled() && key == cfg.getSlotLockKey())
                || (cfg.isProtectItemEnabled() && key == cfg.getProtectKey());
    }

    /** True while the "peek" key is held - polled with {@link KeyUtil} instead of a key-release event so a
     *  hand-edited bad key code never spams GLFW_INVALID_ENUM (the reason KeyUtil exists). */
    private static boolean peeking() {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        Minecraft client = Minecraft.getInstance();
        return cfg.isProtectItemEnabled() && client != null && isBindDown(client, cfg.getPeekKey());
    }

    /** Polls a keyboard code through {@link KeyUtil} or a mouse code through {@code glfwGetMouseButton}.
     *  Local helper until {@code KeyUtil} itself learns about mouse binds - patch in this wave's notes. */
    private static boolean isBindDown(Minecraft client, int code) {
        if (code == KeyUtil.NONE || client.getWindow() == null) {
            return false;
        }
        if (ItemProtectConfig.isMouseCode(code)) {
            return org.lwjgl.glfw.GLFW.glfwGetMouseButton(client.getWindow().handle(),
                    ItemProtectConfig.mouseButton(code)) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }
        return KeyUtil.isKeyDown(client.getWindow(), code);
    }

    private static void render(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (!cfg.isEnabled()) {
            return;
        }
        boolean drawLocks = cfg.isSlotLockEnabled();
        boolean drawProtected = peeking();
        // killer560 (2026-09-20): "put a little lock next to them in a corner so I know they are safe" - the
        // protected-item marker used to only exist while the peek key was held.
        boolean drawProtectedIcon = cfg.isProtectItemEnabled() && cfg.isProtectedIconEnabled();
        if (!drawLocks && !drawProtected && !drawProtectedIcon) {
            return;
        }

        ContainerScreenPositionAccessor accessor = (ContainerScreenPositionAccessor) screen;
        int leftPos = accessor.killer560smod$getLeftPos();
        int topPos = accessor.killer560smod$getTopPos();

        for (Slot slot : screen.getMenu().slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;

            if (drawLocks && ItemProtect.isPlayerInventorySlot(slot) && cfg.isSlotLocked(slot.getContainerSlot())) {
                drawLockMarker(graphics, x, y, cfg.getLockColor(), cfg.getLockStyle());
                continue;
            }
            if ((drawProtected || drawProtectedIcon) && ItemProtect.isProtectedItem(slot.getItem())) {
                if (drawProtected) {
                    graphics.outline(x - 1, y - 1, 18, 18, cfg.getProtectedColor());
                }
                if (drawProtectedIcon) {
                    drawSmallLock(graphics, x, y, cfg.getProtectedColor());
                }
            }
        }
    }

    /** A 6x7 padlock in the slot's TOP-LEFT corner - the opposite corner to the slot-lock marker and to
     *  vanilla's stack count, so a protected stack still shows its number. */
    private static void drawSmallLock(GuiGraphicsExtractor graphics, int x, int y, int color) {
        int shadow = 0xFF000000;
        graphics.fill(x, y, x + 7, y + 8, shadow);
        // shackle: two posts + a cap
        graphics.fill(x + 2, y + 1, x + 3, y + 3, color);
        graphics.fill(x + 4, y + 1, x + 5, y + 3, color);
        graphics.fill(x + 2, y + 1, x + 5, y + 2, color);
        // body
        graphics.fill(x + 1, y + 3, x + 6, y + 7, color);
    }

    private static void drawLockMarker(GuiGraphicsExtractor graphics, int x, int y, int color,
                                       ItemProtectConfig.LockStyle style) {
        if (style != ItemProtectConfig.LockStyle.ICON) {
            graphics.outline(x - 1, y - 1, 18, 18, color);
        }
        if (style == ItemProtectConfig.LockStyle.OUTLINE) {
            return;
        }
        // A tiny padlock drawn from fills in the slot's bottom-right corner - no texture asset needed, so
        // this whole feature stays code-only. Black backing first so it reads on a light item.
        int shadow = 0xFF000000;
        graphics.fill(x + 9, y + 8, x + 17, y + 17, shadow);
        // shackle: two posts + a cap
        graphics.fill(x + 11, y + 9, x + 12, y + 12, color);
        graphics.fill(x + 14, y + 9, x + 15, y + 12, color);
        graphics.fill(x + 11, y + 9, x + 15, y + 10, color);
        // body
        graphics.fill(x + 10, y + 12, x + 16, y + 16, color);
    }
}

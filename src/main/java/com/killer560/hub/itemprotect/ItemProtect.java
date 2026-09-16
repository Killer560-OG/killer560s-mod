package com.killer560.hub.itemprotect;

import com.killer560.hub.util.ModChat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

/**
 * The actual "can this item move?" decisions behind Item Protection - kept out of
 * {@link ItemProtectFeature} (screens/keybinds/rendering) and out of the two mixins so the mixins stay
 * three-line delegates.
 * <p>
 * Ported from Devonian's {@code misc/inventory/PreventItem.kt} (the shared cancel funnel + its
 * {@code BadScreen} sell/salvage/trade detection), {@code SlotLocking.kt}, {@code ProtectItem.kt} and
 * {@code ProtectStarredItems.kt}, cross-checked against NoammAddons {@code general/ProtectItem.kt},
 * Skytils {@code features/impl/handlers/{SlotLocking,ItemFeatures}.kt} (branch {@code 1.x}) and SkyHanni
 * {@code features/inventory/{ItemProtection,SlotLocking}.kt}. Devonian's own model is the one followed:
 * a locked SLOT can't move at all, while a protected ITEM only gets stopped by an action that would
 * actually lose it.
 * <p>
 * <b>Only client-initiated actions are ever blocked.</b> Both call sites are vanilla client input paths -
 * {@code AbstractContainerScreen#slotClicked} (the single funnel every mouse click, shift-click, number-key
 * swap, Q-throw and quick-craft drag in a container screen goes through before the packet is built) and
 * {@code LocalPlayer#drop(boolean)} (only ever called from {@code Minecraft#handleKeybinds} for the real
 * drop key, verified by javap on 26.1.2). Nothing here touches an incoming packet, so the server can still
 * take, move or consume any item it wants and the client never silently disagrees with it.
 * <p>
 * <b>Every block is visible.</b> {@link #announceBlock} always sends a themed chat line and (unless the
 * sound is turned off) plays a low bass note, so a blocked click reads as "the mod stopped that" instead of
 * a dead inventory.
 */
public final class ItemProtect {

    /** Repeat-suppression for the chat line: a quick-craft drag calls {@code slotClicked} once per slot. */
    private static final long MESSAGE_COOLDOWN_MS = 400L;
    /** How long the "press it again to drop anyway" window stays open. */
    public static final long FORCE_WINDOW_MS = 3000L;

    private static String lastMessage = "";
    private static long lastMessageAt = 0L;

    private static int forceArmedSlot = -1;
    private static long forceArmedAt = 0L;

    private ItemProtect() {
    }

    // ------------------------------------------------------------------ item identity

    /** Hypixel Skyblock {@code ExtraAttributes.uuid} (per-item, survives renaming/reforging), or null. */
    public static String itemUuid(ItemStack stack) {
        CompoundTag tag = extraAttributes(stack);
        return tag == null ? null : tag.getStringOr("uuid", null);
    }

    /** Hypixel Skyblock {@code ExtraAttributes.id} - same technique {@code CheatUtils.skyblockId} uses. */
    public static String skyblockId(ItemStack stack) {
        CompoundTag tag = extraAttributes(stack);
        return tag == null ? null : tag.getStringOr("id", null);
    }

    private static CompoundTag extraAttributes(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    /** The key this item is remembered by in the protected list: its UUID, or (with the fallback setting on)
     *  its Skyblock item id. Null when the item has neither - an item that can't be identified is never
     *  added, matching Devonian's own behaviour rather than silently protecting the wrong stack. */
    public static String protectKeyFor(ItemStack stack, ItemProtectConfig cfg) {
        String uuid = itemUuid(stack);
        if (uuid != null && !uuid.isBlank()) {
            return uuid;
        }
        if (cfg.isUseItemIdFallback()) {
            String id = skyblockId(stack);
            if (id != null && !id.isBlank()) {
                return id;
            }
        }
        return null;
    }

    /** Dungeon-starred item test. NBT first ({@code upgrade_level} / {@code dungeon_item_level}, exactly the
     *  two keys Devonian's {@code ProtectStarredItems} reads), then the visible star characters as a fallback
     *  for stacks whose extra attributes the client never received. */
    public static boolean isStarred(ItemStack stack) {
        CompoundTag tag = extraAttributes(stack);
        if (tag != null && (tag.getInt("upgrade_level").isPresent() || tag.getInt("dungeon_item_level").isPresent())) {
            return true;
        }
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        // '✪' is the normal dungeon star; '➊'-'➎' are the master-star pips (see RevertMasterStarsFeature).
        return name != null && (name.indexOf('✪') >= 0
                || name.indexOf('➀') >= 0 || name.indexOf('➁') >= 0 || name.indexOf('➂') >= 0
                || name.indexOf('➃') >= 0 || name.indexOf('➄') >= 0);
    }

    /** Whether this exact stack is on the protected list (by UUID/id or by a typed name fragment), or is a
     *  starred item while Auto-Protect Starred is on. */
    public static boolean isProtectedItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (cfg.isProtectItemEnabled()) {
            String uuid = itemUuid(stack);
            if (uuid != null && cfg.hasProtectedKey(uuid)) {
                return true;
            }
            if (cfg.isUseItemIdFallback()) {
                String id = skyblockId(stack);
                if (id != null && cfg.hasProtectedKey(id)) {
                    return true;
                }
            }
            String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
            if (cfg.nameMatchesProtected(name)) {
                return true;
            }
        }
        return cfg.isProtectStarredEnabled() && isStarred(stack);
    }

    /** Short reason used in the chat line, or null when the stack isn't protected at all. */
    private static String protectReason(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (cfg.isProtectItemEnabled()) {
            String uuid = itemUuid(stack);
            if (uuid != null && cfg.hasProtectedKey(uuid)) {
                return "Protected Item";
            }
            if (cfg.isUseItemIdFallback()) {
                String id = skyblockId(stack);
                if (id != null && cfg.hasProtectedKey(id)) {
                    return "Protected Item";
                }
            }
            if (cfg.nameMatchesProtected(ChatFormatting.stripFormatting(stack.getHoverName().getString()))) {
                return "Protected Name";
            }
        }
        if (cfg.isProtectStarredEnabled() && isStarred(stack)) {
            return "Starred Item";
        }
        return null;
    }

    // ------------------------------------------------------------------ slots

    public static Inventory playerInventory() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? null : player.getInventory();
    }

    /** True only for a slot backed by the real player {@code Inventory} - a locked slot index means nothing
     *  in someone else's chest, and every reference mod scopes slot locking the same way. */
    public static boolean isPlayerInventorySlot(Slot slot) {
        Inventory inv = playerInventory();
        return slot != null && inv != null && slot.container == inv;
    }

    // ------------------------------------------------------------------ screens that lose items

    /** Screens where clicking one of your own items hands it away. Ported from Devonian's {@code BadScreen}
     *  (Salvage / Trade / Sell) plus the anvil and auction-creation menus named in this feature's brief. */
    public enum LossScreen {
        SALVAGE("salvaging"),
        TRADE("trading"),
        SELL("selling"),
        ANVIL("using in the anvil"),
        AUCTION("auctioning");

        public final String action;

        LossScreen(String action) {
            this.action = action;
        }
    }

    public static LossScreen lossScreenOf(AbstractContainerScreen<?> screen) {
        if (screen == null) {
            return null;
        }
        String title;
        try {
            title = ChatFormatting.stripFormatting(screen.getTitle().getString());
        } catch (Exception e) {
            return null;
        }
        if (title == null) {
            return null;
        }
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.contains("salvage")) {
            return LossScreen.SALVAGE;
        }
        if (lower.equals("anvil")) {
            return LossScreen.ANVIL;
        }
        if (lower.contains("auction") && (lower.contains("create") || lower.contains("bin"))) {
            return LossScreen.AUCTION;
        }
        // Hypixel's trade window is titled "You            <partner>".
        if (title.startsWith("You") && title.trim().matches("You\\s+\\w+")) {
            return LossScreen.TRADE;
        }
        // Devonian's own sell-menu test: the hopper in slot 49 says what the menu does.
        try {
            List<ItemStack> items = screen.getMenu().getItems();
            if (items.size() > 49) {
                ItemLore lore = items.get(49).get(DataComponents.LORE);
                if (lore != null && !lore.lines().isEmpty()) {
                    String first = ChatFormatting.stripFormatting(lore.lines().get(0).getString());
                    String last = ChatFormatting.stripFormatting(
                            lore.lines().get(lore.lines().size() - 1).getString());
                    if ((first != null && first.startsWith("Click items in your inventory to sell"))
                            || (last != null && last.startsWith("Click to buyback"))) {
                        return LossScreen.SELL;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ the decisions

    /**
     * Single decision for every container interaction. Returns true to cancel the click before any packet is
     * built.
     *
     * @param slot   the hovered slot, or null for an outside/drop-the-cursor-stack click
     * @param slotId vanilla's slot id ({@code -999} = outside the window)
     * @param button for {@link ContainerInput#SWAP} this is the destination hotbar index (0-8)
     */
    public static boolean shouldBlockSlotClick(AbstractContainerScreen<?> screen, Slot slot, int slotId,
                                               int button, ContainerInput type) {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (!cfg.isEnabled()) {
            return false;
        }
        Inventory inv = playerInventory();
        if (inv == null) {
            return false;
        }
        LossScreen loss = lossScreenOf(screen);
        boolean losesItem = type == ContainerInput.THROW || loss != null;
        String action = type == ContainerInput.THROW ? "dropping" : (loss != null ? loss.action : "moving");

        // Clicking outside the window while holding a stack drops it - the carried stack is the one at risk.
        if (slot == null || slotId < 0) {
            ItemStack carried = carriedStack();
            String reason = protectReason(carried);
            if (reason != null) {
                announceBlock("dropping", carried, reason);
                return true;
            }
            return false;
        }

        // A locked slot can't move at all, whatever the click is (Devonian's SlotLocking semantics).
        if (cfg.isSlotLockEnabled() && isPlayerInventorySlot(slot) && cfg.isSlotLocked(slot.getContainerSlot())) {
            announceBlock(action, slot.getItem(), "Slot Lock");
            return true;
        }

        // A number-key SWAP also moves whatever is in the destination hotbar slot.
        if (type == ContainerInput.SWAP && cfg.isSlotLockEnabled() && button >= 0 && button < 9
                && cfg.isSlotLocked(button)) {
            announceBlock(action, inv.getItem(button), "Slot Lock");
            return true;
        }

        if (!losesItem) {
            // Rearranging your own inventory is never blocked by Protect Item / Starred - only Slot Lock is.
            return false;
        }

        String reason = protectReason(slot.getItem());
        if (reason != null) {
            announceBlock(action, slot.getItem(), reason);
            return true;
        }
        if (type == ContainerInput.SWAP && button >= 0 && button < 9) {
            ItemStack dest = inv.getItem(button);
            String destReason = protectReason(dest);
            if (destReason != null) {
                announceBlock(action, dest, destReason);
                return true;
            }
        }
        return false;
    }

    /**
     * Decision for the real drop key. Returns true to swallow the key press.
     * <p>
     * With Confirm To Force on, the first press is blocked and arms a {@link #FORCE_WINDOW_MS} window for that
     * exact hotbar slot; pressing the key again inside the window drops the item for real, so the feature is
     * never a hard wall on your own gear.
     */
    public static boolean shouldBlockHotbarDrop() {
        ItemProtectConfig cfg = ItemProtectConfig.getInstance();
        if (!cfg.isPreventHotbarDropEnabled()) {
            return false;
        }
        Inventory inv = playerInventory();
        if (inv == null) {
            return false;
        }
        int selected = inv.getSelectedSlot();
        ItemStack held = inv.getSelectedItem();
        if (held == null || held.isEmpty()) {
            return false;
        }

        String reason;
        if (cfg.isSlotLockEnabled() && cfg.isSlotLocked(selected)) {
            reason = "Slot Lock";
        } else {
            reason = protectReason(held);
            if (reason == null && cfg.isBlockEveryHotbarDrop()) {
                reason = "Hotbar Drop Block";
            }
        }
        if (reason == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (cfg.isConfirmToForce()) {
            if (forceArmedSlot == selected && now - forceArmedAt <= FORCE_WINDOW_MS) {
                forceArmedSlot = -1;
                forceArmedAt = 0L;
                ModChat.send("Item Protect", ModChat.text("Dropping "),
                        ModChat.value(displayName(held)), ModChat.text(" anyway (confirmed)."));
                return false;
            }
            forceArmedSlot = selected;
            forceArmedAt = now;
            announceBlock("dropping", held, reason,
                    " Press drop again within " + (FORCE_WINDOW_MS / 1000) + "s to drop it anyway.");
            return true;
        }
        announceBlock("dropping", held, reason);
        return true;
    }

    private static ItemStack carriedStack() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.containerMenu == null) {
            return ItemStack.EMPTY;
        }
        return player.containerMenu.getCarried();
    }

    // ------------------------------------------------------------------ feedback

    public static String displayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "that item";
        }
        String name = ChatFormatting.stripFormatting(stack.getHoverName().getString());
        return name == null || name.isBlank() ? "that item" : name;
    }

    public static void announceBlock(String action, ItemStack stack, String reason) {
        announceBlock(action, stack, reason, "");
    }

    /** Orange-themed local chat line + a short low note, so a blocked action is never silent. */
    public static void announceBlock(String action, ItemStack stack, String reason, String extra) {
        String name = displayName(stack);
        String dedupe = action + "|" + name + "|" + reason;
        long now = System.currentTimeMillis();
        if (dedupe.equals(lastMessage) && now - lastMessageAt < MESSAGE_COOLDOWN_MS) {
            return;
        }
        lastMessage = dedupe;
        lastMessageAt = now;

        Component[] parts = extra.isEmpty()
                ? new Component[]{
                        ModChat.bad("Blocked "), ModChat.text(action + " "),
                        ModChat.value(name), ModChat.dim("  (" + reason + ")")}
                : new Component[]{
                        ModChat.bad("Blocked "), ModChat.text(action + " "),
                        ModChat.value(name), ModChat.dim("  (" + reason + ")"),
                        ModChat.text(extra)};
        ModChat.send("Item Protect", parts);
        playBlockedSound();
    }

    private static void playBlockedSound() {
        if (!ItemProtectConfig.getInstance().isBlockSound()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), 0.5f, 1.0f)));
    }

    /** Local UI confirmation sound for toggling a lock / protection on or off. */
    public static void playToggleSound(boolean on) {
        if (!ItemProtectConfig.getInstance().isBlockSound()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> client.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, on ? 1.4f : 0.7f, 0.6f)));
    }
}

package com.killer560.hub.armourdye;

import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import com.killer560.hub.util.KeyUtil;
import com.killer560.hub.util.ModChat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Wiring for Armour Recolour (see {@link ArmourDye} for what the feature does and what it borrows from Skyblocker).
 * Two jobs only:
 * <ol>
 *   <li><b>Capture key</b> - hover an armour piece in any inventory screen and press it to add that piece to the
 *       list without typing an item id, the same hover-then-press flow Item Protect already uses. The settings tab
 *       has "Add Worn" buttons for the four pieces you have on, which covers the other half of killer560's ask.</li>
 *   <li><b>Trim cache invalidation</b> - trim materials and patterns are datapack registries that only exist once a
 *       world is loaded, so the cache is dropped whenever the level changes.</li>
 * </ol>
 * Nothing here talks to the server. The capture key is swallowed only when it actually does something, so it can
 * share a key with a vanilla inventory binding without eating it while the feature is off.
 */
public final class ArmourDyeFeature {

    /** Key-repeat guard: Fabric's allowKeyPress fires again while a key is held down (same as Item Protect). */
    private static final long CAPTURE_DEBOUNCE_MS = 250L;

    private static long lastCaptureAt = 0L;
    private static Object lastLevel = null;

    private ArmourDyeFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(ArmourDyeFeature::onScreenInit);
        ClientTickEvents.END_CLIENT_TICK.register(ArmourDyeFeature::onTick);
    }

    private static void onTick(Minecraft client) {
        Object level = client == null ? null : client.level;
        if (level != lastLevel) {
            lastLevel = level;
            // A new world means a new datapack registry set; the next lookup rebuilds from it.
            ArmourTrims.invalidate();
        }
    }

    private static void onScreenInit(Minecraft client, Screen screen, int width, int height) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }
        ScreenKeyboardEvents.allowKeyPress(screen)
                .register((s, event) -> handleKey(containerScreen, event.key()));
    }

    /** @return false to swallow the key (a capture), true to let the screen handle it. */
    private static boolean handleKey(AbstractContainerScreen<?> screen, int key) {
        try {
            ArmourDyeConfig cfg = ArmourDyeConfig.getInstance();
            if (!cfg.isEnabled() || !KeyUtil.isValidKey(key) || key != cfg.getCaptureKey()) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastCaptureAt < CAPTURE_DEBOUNCE_MS) {
                // Still swallowed, so a held-down capture key doesn't leak into vanilla handling mid-repeat.
                return false;
            }
            lastCaptureAt = now;

            Slot hovered = ((AbstractContainerScreenAccessor) screen).killer560smod$getHoveredSlot();
            ItemStack stack = hovered == null ? ItemStack.EMPTY : hovered.getItem();
            capture(stack);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Adds (or reports) an entry for this piece. Shared by the capture key and the settings tab's "Add Worn"
     * buttons.
     *
     * @return the entry, or null when the stack isn't an armour piece we can key.
     */
    public static ArmourDyeEntry capture(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            ModChat.send("Armour Recolour", ModChat.text("Hover an armour piece to add it."));
            return null;
        }
        if (!ArmourDye.isArmour(stack)) {
            ModChat.send("Armour Recolour", ModChat.bad("Not armour: "),
                    ModChat.value(stack.getHoverName().getString()));
            return null;
        }
        String id = ArmourDye.identityOf(stack);
        if (id == null || id.isBlank()) {
            ModChat.send("Armour Recolour", ModChat.bad("Can't identify that piece - it has no Skyblock id."));
            return null;
        }
        ArmourDyeConfig cfg = ArmourDyeConfig.getInstance();
        boolean existed = cfg.get(id) != null;
        ArmourDyeEntry entry = cfg.getOrCreate(id, stack.getHoverName().getString());
        if (entry == null) {
            return null;
        }
        // Every GUI/keybind mutation saves - the mod's standing "every setting must persist" rule.
        cfg.save();
        ModChat.send("Armour Recolour",
                existed ? ModChat.text("Already listed: ") : ModChat.good("Added "),
                ModChat.value(entry.label),
                ModChat.dim(" (" + id + ")"),
                ModChat.text(existed ? "" : " - set its colour in the settings menu."));
        return entry;
    }
}

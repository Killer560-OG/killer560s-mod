package com.killer560.hub.slotbinds;

import com.killer560.hub.notify.ModOverlayMessage;
import com.killer560.hub.slotbinds.mixin.AbstractContainerScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;

/**
 * Real inventory "Slot Binds" feature, ported from Odin's own {@code SlotBinds.kt} (simplified to a
 * single global bind list rather than Odin's 6 profiles). Links two slots in your real vanilla
 * Inventory screen (E menu) together; shift-clicking either one swaps it with its bound partner using
 * the exact same real vanilla mechanic pressing a number key over a slot already uses
 * ({@code MultiPlayerGameMode#handleContainerInput} with the real {@code ContainerInput.SWAP} click
 * type - this mod isn't inventing a new kind of click, just triggering the real vanilla one
 * programmatically). One of the two bound slots must be a real hotbar slot (36-44), matching that same
 * real vanilla SWAP click's own requirement that its "button" parameter is a hotbar index.
 * <p>
 * Needs one real accessor mixin ({@link AbstractContainerScreenAccessor}) since vanilla's own
 * {@code AbstractContainerScreen#hoveredSlot} field is {@code protected} with no public getter - a
 * read-only accessor that injects no behavior, the lowest-risk real category of mixin. Everything else
 * is built on the same real Fabric Screen API this session already verified via {@code javap} for the
 * Custom Leap Menu overlay.
 */
public final class SlotBindsFeature {

    private SlotBindsFeature() {
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(SlotBindsFeature::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int scaledWidth, int scaledHeight) {
        if (!SlotBindsConfig.getInstance().isEnabled() || !(screen instanceof InventoryScreen)) {
            return;
        }
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
        // Tracks the first slot picked while setting up a new bind (-1 = not currently setting one up) -
        // a fresh holder per real screen open, matching a fresh Inventory screen instance every time.
        int[] pendingSlot = {-1};

        ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
            SlotBindsConfig cfg = SlotBindsConfig.getInstance();
            Slot hovered = accessor.killer560smod$getHoveredSlot();
            if (hovered == null || !event.hasShiftDown() || hovered.index < 5 || hovered.index >= 45) {
                return true;
            }
            Integer bound = cfg.getBinds().get(hovered.index);
            if (bound == null) {
                return true;
            }
            int from;
            int to;
            if (hovered.index >= 36 && hovered.index < 45) {
                from = bound;
                to = hovered.index;
            } else if (bound >= 36 && bound < 45) {
                from = hovered.index;
                to = bound;
            } else {
                return true;
            }
            client.gameMode.handleContainerInput(
                    ((net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) screen)
                            .getMenu().containerId,
                    from, to % 36, ContainerInput.SWAP, client.player);
            return false;
        });

        ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
            SlotBindsConfig cfg = SlotBindsConfig.getInstance();
            if (cfg.getBindKey() == -1 || event.key() != cfg.getBindKey()) {
                return true;
            }
            Slot hovered = accessor.killer560smod$getHoveredSlot();
            if (hovered == null || hovered.index < 5 || hovered.index >= 45) {
                return true;
            }
            int index = hovered.index;
            if (pendingSlot[0] == -1) {
                pendingSlot[0] = index;
                ModOverlayMessage.show("§b[Slot Binds] Selected slot " + index + " - hover the slot to bind it to, then press the key again.", 3500);
            } else if (pendingSlot[0] == index) {
                ModOverlayMessage.show("§cYou can't bind a slot to itself.", 2500);
                pendingSlot[0] = -1;
            } else if ((pendingSlot[0] < 36 || pendingSlot[0] >= 45) && (index < 36 || index >= 45)) {
                ModOverlayMessage.show("§cOne of the two slots must be in the hotbar.", 3000);
                pendingSlot[0] = -1;
            } else {
                cfg.addBind(pendingSlot[0], index);
                cfg.save();
                ModOverlayMessage.show("§a[Slot Binds] Bound slot " + pendingSlot[0] + " to " + index + ".", 3000);
                pendingSlot[0] = -1;
            }
            return false;
        });
    }
}

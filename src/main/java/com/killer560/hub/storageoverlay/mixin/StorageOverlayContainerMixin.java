package com.killer560.hub.storageoverlay.mixin;

import com.killer560.hub.storageoverlay.StorageOverlayFeature;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Draws the Storage Overlay directly inside every container screen's own render pass, same
 *  injection point (and same reasoning - {@code ScreenEvents.afterExtract} produced no visible
 *  output here) as {@code RngMeterOverlay}'s own {@code AbstractContainerScreenMixin}. Also
 *  intercepts clicks on a non-active grid page to open it, per killer560's "add ... click non
 *  active [page] to open it" request (2026-09-08). */
@Mixin(AbstractContainerScreen.class)
public abstract class StorageOverlayContainerMixin extends Screen {

    protected StorageOverlayContainerMixin(Component title) {
        super(title);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void killer560smod$renderStorageOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                                      float partialTick, CallbackInfo ci) {
        StorageOverlayFeature.onContainerScreenRender((AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void killer560smod$clickStorageOverlay(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        // Real bug found and fixed (2026-09-08), per killer560's report that he couldn't click the
        // grid at all on the "Storage" overview screen: this used to gate on activeKey != null, but
        // storageKeyForTitle("Storage") is always null (only numbered pages match it) - meaning
        // handleClick was NEVER even called while browsing the overview, the one screen where every
        // panel in the grid is clickable (there's no "active" page to skip). Gate on shouldHideVanilla
        // instead, which covers both cases the same way onContainerScreenRender already does.
        String title = this.getTitle().getString();
        if (!StorageOverlayFeature.shouldHideVanilla(title)) {
            return;
        }
        // Per killer560's "double click the actual text and edit it there" request (2026-09-08): a
        // click landing inside the active rename box is left completely alone so vanilla's own EditBox
        // click handling (cursor placement, text selection) runs normally further down this same
        // method - only a click OUTSIDE it commits the rename first (and then still gets to act, e.g.
        // opening a different page, in the same click).
        if (StorageOverlayFeature.isRenameClickInsideBox(event.x(), event.y())) {
            return;
        }
        StorageOverlayFeature.commitRename();
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (doubleClick && StorageOverlayFeature.handleDoubleClick(self, event.x(), event.y())) {
            cir.setReturnValue(true);
            return;
        }
        // Per killer560's "move the inventory portion all the way to the bottom" request (2026-09-08):
        // a click on the relocated Inventory panel is redirected to the real underlying slot by index.
        if (StorageOverlayFeature.handleInventoryClick(self, event.x(), event.y(), event.button(), event.hasShiftDown())) {
            cir.setReturnValue(true);
            return;
        }
        String activeKey = StorageOverlayFeature.storageKeyForTitle(title);
        if (StorageOverlayFeature.handleClick(self, event.x(), event.y(), activeKey, event.button(), event.hasShiftDown())) {
            cir.setReturnValue(true);
            return;
        }
        // Real bug found and fixed (2026-09-08), per killer560's report that moving items between his
        // inventory and an open storage "sometimes tries to drop the item": every real slot on this
        // screen is hidden, so a click that none of the redirects above claimed lands on a mouse
        // position vanilla's own hit-testing finds NO slot under at all - which is exactly the real
        // "clicked outside the inventory" case vanilla throws the carried item for. Rather than track
        // down every exact geometric edge case that can slip through the redirects, this is a safety
        // net: if killer560 is actually holding an item right now, an unclaimed click is blocked
        // outright instead of being allowed to fall through into a real, silent item-loss drop.
        if (StorageOverlayFeature.isHoldingCarriedItem(self)) {
            cir.setReturnValue(true);
        }
    }

    /** Per killer560's "double click the actual text and edit it there" request (2026-09-08) - Enter
     *  commits an in-progress rename immediately, without needing to click away from the box first.
     *  Real bug found and fixed (2026-09-08), per killer560's report that typing a movement key (e.g.
     *  "w") while renaming also made him walk: every OTHER key while renaming is now manually forwarded
     *  to the box and always treated as consumed here, at the very head of this method, so a plain
     *  letter key (which the box itself doesn't need to specially claim - see
     *  {@link StorageOverlayFeature#forwardKeyToRenameBox}) never reaches Minecraft's own global keybind
     *  handling just because nothing downstream claimed it. */
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void killer560smod$renameKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (!StorageOverlayFeature.isRenamePending()) {
            return;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            StorageOverlayFeature.commitRename();
        } else {
            StorageOverlayFeature.forwardKeyToRenameBox(event);
        }
        cir.setReturnValue(true);
    }

    /** Per killer560's "I need to be able to scroll on this page" request (2026-09-08) - lets the
     *  mouse wheel scroll the grid while the cursor is over it. */
    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void killer560smod$scrollStorageOverlay(double mouseX, double mouseY, double scrollX, double scrollY,
                                                       CallbackInfoReturnable<Boolean> cir) {
        String title = this.getTitle().getString();
        if (!StorageOverlayFeature.shouldHideVanilla(title)) {
            return;
        }
        if (StorageOverlayFeature.handleScroll(mouseX, mouseY, scrollY)) {
            cir.setReturnValue(true);
        }
    }

    /** Real bug found and fixed (2026-09-08), per killer560's report that he could pick an item up
     *  fine but it "still really wants to drop it": every interaction this mod supports is a single
     *  discrete click (see killer560smod$clickStorageOverlay) - a real press-hold-drag-release gesture
     *  only ever gets that first press through us; the CONTINUATION (mouseDragged while still held,
     *  then mouseReleased) was never intercepted at all and ran vanilla's own unmodified logic, which
     *  tries to track real slot positions that don't exist any more (every real slot on this screen is
     *  hidden) - exactly the same "no real slot found" situation that can end in a drop. Blocking both
     *  outright is the safe trade-off: a genuine hold-drag-release from the relocated Inventory/grid
     *  panels won't complete the move in one motion any more, but two separate clicks (pick up, then
     *  place - already fully supported) still will, with no risk of an unwanted drop either way. Left
     *  alone while renaming (real text-selection drag inside the EditBox still needs both events). */
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockDragOnHiddenSlots(MouseButtonEvent event, double dragX, double dragY,
                                                        CallbackInfoReturnable<Boolean> cir) {
        if (StorageOverlayFeature.isRenamePending()) {
            return;
        }
        if (StorageOverlayFeature.shouldHideVanilla(this.getTitle().getString())) {
            cir.setReturnValue(true);
        }
    }

    /** See {@link #killer560smod$blockDragOnHiddenSlots} - same reasoning, the other half of a
     *  press-hold-drag-release gesture. */
    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void killer560smod$blockReleaseOnHiddenSlots(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (StorageOverlayFeature.isRenamePending()) {
            return;
        }
        if (StorageOverlayFeature.shouldHideVanilla(this.getTitle().getString())) {
            cir.setReturnValue(true);
        }
    }
}

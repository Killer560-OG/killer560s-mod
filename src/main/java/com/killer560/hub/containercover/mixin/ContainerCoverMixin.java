package com.killer560.hub.containercover.mixin;

import com.killer560.hub.containercover.ContainerCover;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies {@link ContainerCover}: no vanilla slot under a GUI this mod draws hovers or takes input (killer560,
 * 2026-11-01). Targets, javap on 26.3 and 26.2 (identical in both): {@code private Slot getHoveredSlot(double, double)}
 * - the only source of {@code hoveredSlot}, assigned every frame in {@code extractContents} and asked again by
 * {@code mouseClicked}, {@code mouseReleased} and {@code mouseDragged} with the event's position; each of those three calls
 * it exactly once. {@code mouseClicked} calls it after {@code Screen.mouseClicked} (child widgets) has already declined
 * the click, so stopping there leaves widgets alone; {@code mouseReleased}/{@code mouseDragged} call it first and hand
 * the event to {@code Screen.mouseReleased}/{@code mouseDragged} only at their end, so under a cover they go there
 * directly. Keys need no hook: every slot key (number keys, Q, F, middle-click pick) reads {@code hoveredSlot}.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerCoverMixin extends Screen {

    protected ContainerCoverMixin(Component title) {
        super(title);
    }

    @ModifyReturnValue(method = "getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;", at = @At("RETURN"))
    private Slot killer560smod$noSlotUnderCover(Slot original, double mouseX, double mouseY) {
        return original != null && ContainerCover.covers(this, mouseX, mouseY) ? null : original;
    }

    @Inject(method = "mouseClicked", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;"))
    private void killer560smod$noClickUnderCover(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        // Covered: no slot click, no "clicked outside" throw (-999), no hotbar mouse button, no double-click arming.
        if (ContainerCover.covers(this, event.x(), event.y())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;"))
    private void killer560smod$noReleaseUnderCover(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        // Covered: no double-click collect, no quick-craft end, no carried-item drop; widgets still get the release.
        if (ContainerCover.covers(this, event.x(), event.y())) {
            cir.setReturnValue(super.mouseReleased(event));
        }
    }

    @Inject(method = "mouseDragged", cancellable = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;getHoveredSlot(DD)Lnet/minecraft/world/inventory/Slot;"))
    private void killer560smod$noDragUnderCover(MouseButtonEvent event, double dragX, double dragY,
                                               CallbackInfoReturnable<Boolean> cir) {
        if (ContainerCover.covers(this, event.x(), event.y())) {
            cir.setReturnValue(super.mouseDragged(event, dragX, dragY));
        }
    }
}

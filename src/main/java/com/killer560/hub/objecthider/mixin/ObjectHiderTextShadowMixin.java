package com.killer560.hub.objecthider.mixin;

import com.killer560.hub.objecthider.ObjectHiderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * QUOI {@code RenderOptimiser.kt} "Disable text shadow" / "Container text shadow" (both OFF by default,
 * {@link ObjectHiderConfig}) - QUOI redirects {@code new GuiTextRenderState} inside
 * {@code GuiGraphics.drawString} (mixins/GuiGraphicsMixin.java:21-44). javap-verified against the 26.1.2
 * merged jar: {@code GuiGraphics} / {@code drawString} no longer exist - text goes through
 * {@link GuiGraphicsExtractor#text(net.minecraft.client.gui.Font, net.minecraft.util.FormattedCharSequence,
 * int, int, int, boolean)}, the single funnel every other {@code text(...)} overload (String/Component,
 * with/without shadow) calls into (only two {@code new GuiTextRenderState} sites exist in the whole class;
 * the other is unrelated map-label text). Forcing the constructor's {@code dropShadow} argument here is the
 * exact same effect QUOI's redirect has, just against this version's render-state-extraction split. Purely a
 * draw-call flag - never touches what's sent to the server, and broad by design (every string on screen).
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class ObjectHiderTextShadowMixin {

    // CRASHED THE GAME ON STARTUP (2026-09-20, killer560's boot log): this was an @ModifyArg at
    // @At("NEW"), and a NEW insn is not a method call - Mixin rejects that outright with
    // InvalidInjectionException, which is an apply-time error that require = 0 does NOT soften, so the
    // whole mod failed to initialise. Modifying the method's own dropShadow PARAMETER at HEAD reaches the
    // same constructor argument (the funnel passes it straight through) without depending on any
    // instruction inside the method, so a future remap can only make it a no-op, never a crash.
    @ModifyVariable(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private boolean killer560smod$objectHider$shadow(boolean shadow) {
        ObjectHiderConfig cfg = ObjectHiderConfig.getInstance();
        boolean disable = cfg.isDisableTextShadow();
        boolean container = cfg.isContainerTextShadow();
        if (!disable && !container) {
            return shadow;
        }
        // QUOI's "Container text shadow" is a force-ON parity option, not a hider - it only exists so text
        // stays readable over an inventory background while "Disable text shadow" is on everywhere else.
        if (container && Minecraft.getInstance().screen instanceof AbstractContainerScreen<?>) {
            return true;
        }
        return !disable && shadow;
    }
}

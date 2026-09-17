package com.killer560.hub.tooltipscroll.mixin;

import com.killer560.hub.tooltipscroll.TooltipScrollFeature;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** Swaps the component list for the scrolled window right before the tooltip is measured and drawn.
 *  <p>
 *  Verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar: {@code public void
 *  tooltip(Font, List&lt;ClientTooltipComponent&gt;, int, int, ClientTooltipPositioner, Identifier)}. Every
 *  {@code setTooltipForNextFrame} overload ends up deferring a call to exactly this method, so hooking it
 *  once covers item lore, widget tooltips and anything else - and because the list is replaced BEFORE
 *  vanilla walks it for the max width / total height, the tooltip background is sized to the visible window
 *  automatically. {@code argsOnly = true} with no {@code index} is unambiguous here because {@code List} is
 *  the target's only list-typed parameter; an explicit index was deliberately left off since Mixin's
 *  {@code index} is an LVT slot (where {@code this} is 0), not an argument ordinal, and is easy to get
 *  silently wrong.
 *  <p>
 *  {@code require = 0} so a signature change after a Minecraft update disables the feature instead of
 *  crashing, and the whole body is wrapped so a render exception can never escape into the frame - on
 *  failure the original, unmodified list is drawn. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class TooltipScrollGraphicsMixin {

    @ModifyVariable(
            method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0
    )
    private List<ClientTooltipComponent> killer560smod$scrollTooltip(List<ClientTooltipComponent> components,
                                                                    Font font, List<ClientTooltipComponent> unused,
                                                                    int x, int y, ClientTooltipPositioner positioner,
                                                                    Identifier style) {
        try {
            return TooltipScrollFeature.windowFor((GuiGraphicsExtractor) (Object) this, font, components);
        } catch (Throwable ignored) {
            // Visual-only: a bad measurement must never take down the frame, so fall back to vanilla cropping.
            return components;
        }
    }
}

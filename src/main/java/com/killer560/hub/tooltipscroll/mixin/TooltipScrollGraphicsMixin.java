package com.killer560.hub.tooltipscroll.mixin;

import com.killer560.hub.tooltipscroll.TooltipScrollFeature;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/** Translates the ENTIRE tooltip render up by the current scroll offset - killer560, 2026-09-20, after the
 *  first version sliced the component list instead: "It should just move not actually scroll a really long
 *  one."
 *  <p>
 *  Verified with javap against minecraft-merged-043a8b3edf-26.1.2.jar: {@code public void
 *  tooltip(Font, List&lt;ClientTooltipComponent&gt;, int, int, ClientTooltipPositioner, Identifier)} is the
 *  one place every tooltip is actually drawn (every {@code setTooltipForNextFrame} overload funnels into a
 *  deferred call to it), and its own body (javap -c) pushes/pops {@code GuiGraphicsExtractor#pose()} - a
 *  {@code Matrix3x2fStack} - itself around all of its drawing, so translating that same stack from OUTSIDE
 *  the call moves the background box and every line together as one rigid block.
 *  <p>
 *  Uses MixinExtras' {@code @WrapMethod} (bundled by Fabric Loader since 0.14.22, no extra Gradle dependency
 *  needed) rather than a HEAD injector paired with a RETURN injector, specifically so the push/translate and
 *  the matching pop can sit in one real Java {@code try/finally} around the ORIGINAL vanilla call. A HEAD+
 *  RETURN pair would leave the pose stack permanently unbalanced - corrupting every later draw call in the
 *  frame - if vanilla's own rendering threw between the two injection points; a related crash from exactly
 *  that class of mistake (a push with no guaranteed matching pop) hit {@code SecretWaypointsRenderer} the
 *  same day this was written, so this is not a theoretical concern. {@code require = 0} so a signature
 *  change after a Minecraft update disables the feature instead of crashing, and the whole body is wrapped
 *  so a bad measurement can never take down the frame - on failure the original, unmodified, untranslated
 *  tooltip is drawn. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class TooltipScrollGraphicsMixin {

    @WrapMethod(
            method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
            require = 0
    )
    private void killer560smod$translateTooltip(Font font, List<ClientTooltipComponent> components, int x, int y,
                                                ClientTooltipPositioner positioner, Identifier style,
                                                Operation<Void> original) {
        GuiGraphicsExtractor self = (GuiGraphicsExtractor) (Object) this;
        int pixelOffset;
        try {
            pixelOffset = TooltipScrollFeature.pixelOffsetFor(self, font, components);
        } catch (Throwable ignored) {
            // Measurement only; a failure here just means this frame's tooltip isn't translated.
            pixelOffset = 0;
        }
        if (pixelOffset == 0) {
            // The common case (feature off, nothing hovered, tooltip already fits) must cost nothing beyond
            // the measurement above - no push/pop, tooltip renders exactly as vanilla always has.
            original.call(font, components, x, y, positioner, style);
            return;
        }
        Matrix3x2fStack pose = self.pose();
        pose.pushMatrix();
        try {
            // Negative Y moves the whole rendered picture UP on screen, sliding earlier lines out of view at
            // the top and revealing later ones at the bottom - exactly what "translate by pixelOffset" means.
            pose.translate(0, -pixelOffset);
            original.call(font, components, x, y, positioner, style);
        } catch (Throwable ignored) {
            // A render exception must never take down the frame; the pop below still runs regardless.
        } finally {
            pose.popMatrix();
        }
    }
}

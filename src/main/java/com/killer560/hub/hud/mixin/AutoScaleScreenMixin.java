package com.killer560.hub.hud.mixin;

import com.killer560.hub.hud.AutoScale;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Auto Scale for the mod's own screens (see {@link AutoScale}), done once here instead of in thirty screens.
 * <p>
 * A screen scaled by {@code F} is laid out on a virtual canvas {@code F} times smaller than the real GUI area
 * ({@code init}/{@code resize} get {@code width / F, height / F}), drawn with the pose scaled by {@code F}, and sees
 * the mouse divided by {@code F} ({@link AutoScaleMouseMixin}). All three use the factor stored when the screen was
 * laid out, so a widget is clicked exactly where it is drawn. Scissor rectangles go through the pose
 * ({@code GuiGraphicsExtractor.enableScissor} transforms by it, javap 26.1.2 and 26.2), so clipped lists still clip
 * in the right place. Every target below has the same descriptor on 26.1.2 and 26.2 (javap, 2026-10-05).
 */
@Mixin(Screen.class)
public abstract class AutoScaleScreenMixin {

    @Unique
    private boolean killer560smod$autoScalePushed;

    @ModifyVariable(method = "init(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int killer560smod$autoScaleInitWidth(int width) {
        return AutoScale.layoutSize(width, AutoScale.layoutFactor((Screen) (Object) this));
    }

    @ModifyVariable(method = "init(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int killer560smod$autoScaleInitHeight(int height) {
        return AutoScale.layoutSize(height, AutoScale.layoutFactor((Screen) (Object) this));
    }

    @ModifyVariable(method = "resize(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int killer560smod$autoScaleResizeWidth(int width) {
        return AutoScale.layoutSize(width, AutoScale.layoutFactor((Screen) (Object) this));
    }

    @ModifyVariable(method = "resize(II)V", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private int killer560smod$autoScaleResizeHeight(int height) {
        return AutoScale.layoutSize(height, AutoScale.layoutFactor((Screen) (Object) this));
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("HEAD"))
    private void killer560smod$autoScaleBegin(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                             CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        killer560smod$autoScalePushed = false;
        if (AutoScale.needsRelayout(self)) {
            // Auto Scale was toggled (or its factor moved) while this screen was open - usually from the very menu
            // holding the toggle. Lay it out again before drawing so drawing, layout and mouse agree.
            Window window = Minecraft.getInstance().getWindow();
            self.resize(window.getGuiScaledWidth(), window.getGuiScaledHeight());
        }
        float f = AutoScale.appliedFactor(self);
        if (f != 1.0f) {
            graphics.pose().pushMatrix();
            graphics.pose().scale(f, f);
            killer560smod$autoScalePushed = true;
        }
    }

    @Inject(method = "extractRenderStateWithTooltipAndSubtitles", at = @At("RETURN"))
    private void killer560smod$autoScaleEnd(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
                                           CallbackInfo ci) {
        if (killer560smod$autoScalePushed) {
            killer560smod$autoScalePushed = false;
            graphics.pose().popMatrix();
        }
    }
}

package com.killer560.hub.fonts.mixin;

import com.killer560.hub.fonts.FontScope;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mod Menu Only: a GUI text render state is prepared after the screen that made it has finished drawing, so it remembers
 * whether it was made inside one of the mod's screens and replays that around its own preparation (see
 * {@link FontScope}). Same class and methods in 26.1.2 and 26.2 (javap).
 */
@Mixin(GuiTextRenderState.class)
public abstract class GuiTextRenderStateFontMixin {

    @Unique
    private boolean killer560smod$menuFont;
    @Unique
    private int killer560smod$prevOverride = -1;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void killer560smod$rememberScope(CallbackInfo ci) {
        killer560smod$menuFont = FontScope.menuNow();
    }

    @Inject(method = "ensurePrepared", at = @At("HEAD"), require = 0)
    private void killer560smod$enterScope(CallbackInfoReturnable<?> cir) {
        killer560smod$prevOverride = FontScope.override(killer560smod$menuFont);
    }

    @Inject(method = "ensurePrepared", at = @At("RETURN"), require = 0)
    private void killer560smod$exitScope(CallbackInfoReturnable<?> cir) {
        FontScope.restore(killer560smod$prevOverride);
    }
}

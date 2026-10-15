package com.killer560.hub.namechanger.mixin;

import com.killer560.hub.namechanger.NameReplacer;
import net.minecraft.client.gui.components.CommandSuggestions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Leaves the chat box's command suggestions and usage hint exactly as the game wrote them (killer560, 2026-10-15: typing
 * "/killer560" showed the custom name colouring, partly black). The suggestion list is not an edit box, so the edit-box
 * suppression never covered it; a name inside a command is a command, not a name to restyle. Matched by method name with a
 * callback-only handler, which Mixin accepts for any descriptor, so one mixin serves 26.1.2 and 26.2.
 */
@Mixin(CommandSuggestions.class)
public abstract class NameChangerCommandSuggestionsMixin {

    @Inject(method = {"extractRenderState", "extractSuggestions", "extractUsage"}, at = @At("HEAD"), require = 0)
    private void killer560smod$commandSuggestionsStart(CallbackInfo ci) {
        NameReplacer.pushSuppress();
    }

    @Inject(method = {"extractRenderState", "extractSuggestions", "extractUsage"}, at = @At("RETURN"), require = 0)
    private void killer560smod$commandSuggestionsEnd(CallbackInfo ci) {
        NameReplacer.popSuppress();
    }
}

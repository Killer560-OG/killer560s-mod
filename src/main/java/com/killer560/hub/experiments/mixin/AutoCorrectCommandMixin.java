package com.killer560.hub.experiments.mixin;

import com.killer560.hub.autocorrect.AutoCorrectFeature;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Auto Correct's "Correct Commands" hook (2026-09-15 roadmap). Lives in the experiments mixin package
 * only so it can register through the existing experiments mixins json without a new fabric.mod.json
 * entry - it has nothing to do with the Experimentation Table itself.
 * <p>
 * Verified via javap against the 26.1.2 merged jar: {@code ChatScreen.handleChatInput(String, boolean)}
 * normalizes the text, adds it to chat history, then for a leading "/" calls
 * {@code ClientPacketListener.sendCommand(message.substring(1))} (otherwise {@code sendChat}). This
 * rewrites only that one call's argument, so only commands the player actually typed are affected
 * (never commands mods send directly), and it runs after Chat Translate's own HEAD inject in the same
 * method has already declined to handle the line. {@code require = 0}: if the target ever moves, the
 * feature silently no-ops instead of crashing the game.
 */
@Mixin(ChatScreen.class)
public abstract class AutoCorrectCommandMixin {

    @ModifyArg(method = "handleChatInput",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;sendCommand(Ljava/lang/String;)V"),
            index = 0, require = 0)
    private String killer560smod$correctTypedCommand(String command) {
        return AutoCorrectFeature.correctOutgoingCommand(command);
    }
}

package com.killer560.hub.bazaarflip.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The sign editor's four lines as it holds them (what is drawn, what {@code removed()} sends in the
 * {@code ServerboundSignUpdatePacket}). Bazaar Flip reads Hypixel's "Enter amount" prompt off lines 3-4 and checks the
 * amount it typed landed on line 1 before it presses Done. Verified with javap on 26.1.2 and 26.2 (2026-10-16): both
 * have {@code private final java.lang.String[] messages} on {@code AbstractSignEditScreen}.
 */
@Mixin(AbstractSignEditScreen.class)
public interface SignEditScreenAccessor {

    @Accessor("messages")
    String[] killer560smod$getMessages();
}

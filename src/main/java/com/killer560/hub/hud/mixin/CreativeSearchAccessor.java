package com.killer560.hub.hud.mixin;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The creative inventory's search box (private {@code searchBox}, javap 26.1.2 and 26.2), for util/TypingDetector. */
@Mixin(CreativeModeInventoryScreen.class)
public interface CreativeSearchAccessor {
    @Accessor("searchBox")
    EditBox killer560smod$getSearchBox();
}
